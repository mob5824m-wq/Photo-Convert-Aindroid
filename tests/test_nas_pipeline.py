from __future__ import annotations

import ftplib
import io
import os
import time
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image

from server.converter import compute_target_dimensions, inspect_media_file
from server.demo_media import generate_sample_batch
from server.main import create_app
from server.versioning import bump_rollover_version, resolve_next_from_tags
from server.watcher import enforce_output_retention, iter_incoming_media, iter_output_media


def test_auto_merge_rollover_versioning():
    # First merge starts cleanly at v1.0.0 (versionCode = 1)
    assert bump_rollover_version(None) == ("1.0.0", 1)
    assert resolve_next_from_tags([]) == ("1.0.0", 1, None)

    # Normal +0.0.1 increments
    assert bump_rollover_version("1.0.0") == ("1.0.1", 2)
    assert bump_rollover_version("1.0.8") == ("1.0.9", 10)

    # Patch rollover: once it reaches 1.0.9, next is 1.1.0!
    assert bump_rollover_version("1.0.9") == ("1.1.0", 11)
    assert bump_rollover_version("1.1.9") == ("1.2.0", 21)

    # Middle column rollover: once it reaches 1.9.9, next is 2.0.0!
    assert bump_rollover_version("1.8.9") == ("1.9.0", 91)
    assert bump_rollover_version("1.9.8") == ("1.9.9", 100)
    assert bump_rollover_version("1.9.9") == ("2.0.0", 101)
    assert bump_rollover_version("2.0.9") == ("2.1.0", 111)
    assert bump_rollover_version("2.9.9") == ("3.0.0", 201)

    # Full continuous 220-merge sequence verification
    tags: list[str] = []
    for expected_code in range(1, 221):
        ver, code, _ = resolve_next_from_tags(tags)
        assert code == expected_code
        tags.append(f"v{ver}")
    assert tags[0] == "v1.0.0"
    assert tags[9] == "v1.0.9"
    assert tags[10] == "v1.1.0"
    assert tags[99] == "v1.9.9"
    assert tags[100] == "v2.0.0"


def _wait_until(predicate, timeout: float = 20.0, interval: float = 0.2) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def test_storage_saver_dimension_math():
    w, h, resized = compute_target_dimensions(8160, 6120, 16.0)
    assert resized is True
    assert w * h <= 16_000_000
    assert (w, h) == (4618, 3464)

    w2, h2, resized2 = compute_target_dimensions(4032, 3024, 16.0)
    assert resized2 is False
    assert (w2, h2) == (4032, 3024)


def test_per_folder_profiles_whatsapp_takeout_video_and_cleanup(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_data")
    with TestClient(app) as client:
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])
        quar_dir = Path(st["folders"]["quarantine"]["path"])
        assert (inc_dir / ".nomedia").exists()
        assert (quar_dir / ".nomedia").exists()
        assert not (out_dir / ".nomedia").exists()

        client.post("/api/settings", json={"auto_watch_enabled": False, "ftp_port": 0})
        seeded = generate_sample_batch(inc_dir, include_video=True)
        assert len(seeded) == 5

        client.post("/api/settings", json={"auto_watch_enabled": True})

        assert _wait_until(
            lambda: len(list(iter_incoming_media(inc_dir, True))) == 0
            and len(list(iter_output_media(out_dir))) == 5,
            timeout=35.0,
        )

        # Profile folders themselves stay ready for future uploads while empty ad-hoc subfolders get pruned
        assert (inc_dir / "StorageSaver").is_dir()
        assert (inc_dir / "Original").is_dir()
        assert (inc_dir / "Family").is_dir()

        # 1. StorageSaver/ profile shrunk 50MP Pixel 9 Pro photo <= 16MP + Google EXIF & GPS intact
        pixel_meta = inspect_media_file(
            out_dir / "PXL_50MP_Pixel9Pro_LakeLouise.jpg", root_dir=out_dir
        )
        assert pixel_meta["width"] * pixel_meta["height"] <= 16_000_000
        assert "Pixel 9 Pro" in pixel_meta["camera_model"]
        assert pixel_meta["date_taken"] == "2026:08:14 17:42:19"
        assert "51.4167" in pixel_meta["gps_coords"]

        # 2. Original/ profile preserved full 24MP Samsung Galaxy S24 Ultra resolution untouched!
        sam_meta = inspect_media_file(
            out_dir / "SAM_24MP_GalaxyS24Ultra_Portrait.jpg", root_dir=out_dir
        )
        assert sam_meta["width"] * sam_meta["height"] == 24_000_000
        assert "SM-S928B" in sam_meta["camera_model"]

        # 3. Family/ profile shrunk photo <= 16MP, restored WhatsApp EXIF date, AND routed to Output/Family/!
        wa_meta = inspect_media_file(
            out_dir / "Family" / "WhatsApp Image 2023-07-19 at 18.45.12.jpg",
            root_dir=out_dir,
        )
        assert wa_meta["width"] * wa_meta["height"] <= 16_000_000
        assert wa_meta["date_taken"] == "2023:07:19 18:45:12"

        # 4. Google Takeout .json sidecar merged GPS + timestamp directly into EXIF!
        takeout_meta = inspect_media_file(
            out_dir / "Takeout_KyotoFushimiInari_50MP.jpg", root_dir=out_dir
        )
        assert takeout_meta["width"] * takeout_meta["height"] <= 16_000_000
        assert "34.9671" in takeout_meta["gps_coords"]
        assert "135.7727" in takeout_meta["gps_coords"]
        assert takeout_meta["date_taken"].startswith("2024:04:")

        # 5. 4K video downscaled cleanly to 1920x1080 MP4
        vid_meta = inspect_media_file(
            out_dir / "VID_4K_UHD_2160p_SampleClip.mp4", root_dir=out_dir
        )
        assert (vid_meta["width"], vid_meta["height"]) == (1920, 1080)


def test_quarantine_corrupted_transfers_and_widget_profile_upload(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_quarantine")
    with TestClient(app) as client:
        client.post("/api/settings", json={"ftp_enabled": False})
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])
        quar_dir = Path(st["folders"]["quarantine"]["path"])

        # Simulate a half-transferred/corrupted JPEG landing in Incoming/StorageSaver
        res = client.post("/api/demo/corrupt")
        assert res.status_code == 200

        # Watcher retries twice and isolates the broken file into Quarantine/ automatically!
        assert _wait_until(
            lambda: client.get("/api/quarantine").json()["count"] == 1
            and len(list(iter_incoming_media(inc_dir, True))) == 0,
            timeout=15.0,
        )
        q_item = client.get("/api/quarantine").json()["items"][0]
        assert q_item["attempts"] >= 2
        assert q_item["original_rel_path"].startswith("StorageSaver/")
        assert (quar_dir / q_item["name"]).is_file()

        # Verify subsequent healthy upload via Android Home Screen Widget API (?profile=Family) never stalls!
        buf = io.BytesIO()
        Image.new("RGB", (5000, 4000), (45, 130, 195)).save(buf, format="JPEG", quality=92)
        up = client.post(
            "/api/upload?profile=Family",
            files=[("files", ("PXL_20261021_120000.jpg", buf.getvalue(), "image/jpeg"))],
        )
        assert up.status_code == 200
        assert up.json()["profile"] == "Family"
        assert _wait_until(
            lambda: (out_dir / "Family" / "PXL_20261021_120000.jpg").is_file(),
            timeout=12.0,
        )

        # Repair the quarantined file in-place & trigger Retry -> converts cleanly to Output!
        repaired_buf = io.BytesIO()
        Image.new("RGB", (4800, 3600), (20, 150, 100)).save(
            repaired_buf, format="JPEG", quality=90
        )
        (quar_dir / q_item["name"]).write_bytes(repaired_buf.getvalue())
        retry_res = client.post(
            "/api/quarantine/retry", json={"filename": q_item["name"]}
        ).json()
        assert retry_res["retried"] == 1
        assert _wait_until(
            lambda: client.get("/api/quarantine").json()["count"] == 0
            and (out_dir / Path(q_item["name"]).stem).with_suffix(".jpg").is_file(),
            timeout=12.0,
        )


def test_sha256_dedup_recursive_subfolders_and_retention(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_smart")
    with TestClient(app) as client:
        client.post("/api/settings", json={"ftp_enabled": False})
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])

        album_dir = inc_dir / "Iceland2025"
        album_dir.mkdir(parents=True)
        buf = io.BytesIO()
        Image.new("RGB", (5000, 4000), (60, 90, 170)).save(buf, format="JPEG", quality=92)
        payload = buf.getvalue()
        (album_dir / "IMG_20250615_143022.jpg").write_bytes(payload)

        assert _wait_until(
            lambda: (out_dir / "Iceland2025_IMG_20250615_143022.jpg").is_file(),
            timeout=12.0,
        )
        assert _wait_until(lambda: not album_dir.exists(), timeout=6.0)

        dup_path = inc_dir / "reupload_copy.jpg"
        dup_path.write_bytes(payload)
        assert _wait_until(lambda: not dup_path.exists(), timeout=10.0)
        assert not (out_dir / "reupload_copy.jpg").exists()
        summary = client.get("/api/history").json()["summary"]
        assert summary["duplicates_skipped_count"] == 1

        target_out = out_dir / "Iceland2025_IMG_20250615_143022.jpg"
        old_ts = time.time() - (20 * 86400)
        os.utime(target_out, (old_ts, old_ts))
        removed = enforce_output_retention(out_dir, retention_days=0, min_free_gb=999999.0)
        assert removed == 1
        assert not target_out.exists()


def test_passthrough_and_webdav_ftp_protocols(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_protocols")
    with TestClient(app) as client:
        client.post(
            "/api/settings",
            json={
                "device_profile": "pixel_1",
                "storage_saver_enabled": False,
                "ftp_enabled": True,
                "ftp_port": 0,
            },
        )
        buf = io.BytesIO()
        Image.new("RGB", (5000, 5000), (40, 120, 200)).save(buf, format="JPEG", quality=92)
        orig_payload = buf.getvalue()
        client.post(
            "/api/upload",
            files=[("files", ("pixel1_fullres.jpg", orig_payload, "image/jpeg"))],
        )

        st = client.get("/api/status").json()
        out_dir = Path(st["folders"]["output"]["path"])
        ftp_port = st["network"]["ftp_port"]

        assert _wait_until(lambda: (out_dir / "pixel1_fullres.jpg").is_file(), timeout=10.0)
        assert (out_dir / "pixel1_fullres.jpg").stat().st_size == len(orig_payload)

        client.post("/api/settings", json={"storage_saver_enabled": True})

        dav_buf = io.BytesIO()
        Image.new("RGB", (5200, 3900), (180, 70, 90)).save(dav_buf, format="PNG")
        assert client.put("/webdav/Incoming/StorageSaver/webdav_20mp.png", content=dav_buf.getvalue()).status_code in (201, 204)
        assert _wait_until(lambda: (out_dir / "webdav_20mp.jpg").is_file(), timeout=12.0)

        ftp_buf = io.BytesIO()
        Image.new("RGB", (5000, 4000), (20, 160, 110)).save(ftp_buf, format="JPEG", quality=94)
        ftp_buf.seek(0)
        with ftplib.FTP() as ftp:
            ftp.connect("127.0.0.1", ftp_port, timeout=5)
            ftp.login("pixel", "pixel")
            ftp.cwd("Incoming/StorageSaver")
            ftp.storbinary("STOR ftp_20mp_camera.jpg", ftp_buf)

        assert _wait_until(lambda: (out_dir / "ftp_20mp_camera.jpg").is_file(), timeout=12.0)
