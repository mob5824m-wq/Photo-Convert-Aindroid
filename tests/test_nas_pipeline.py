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
from server.watcher import enforce_output_retention


def _wait_until(predicate, timeout: float = 20.0, interval: float = 0.2) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def test_storage_saver_dimension_math():
    w, h, resized = compute_target_dimensions(8000, 6000, 16.0)
    assert resized is True
    assert w * h <= 16_000_000
    assert (w, h) == (4618, 3464)

    w2, h2, resized2 = compute_target_dimensions(4032, 3024, 16.0)
    assert resized2 is False
    assert (w2, h2) == (4032, 3024)


def test_end_to_end_camera_whatsapp_takeout_video_and_cleanup(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_data")
    with TestClient(app) as client:
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])
        assert (inc_dir / ".nomedia").exists()
        assert not (out_dir / ".nomedia").exists()

        client.post("/api/settings", json={"auto_watch_enabled": False, "ftp_port": 0})
        seeded = generate_sample_batch(inc_dir, include_video=True)
        assert len(seeded) == 5  # 48MP, 24MP Portrait, WhatsApp, Takeout+JSON, 4K Video

        client.post("/api/settings", json={"auto_watch_enabled": True})

        assert _wait_until(
            lambda: len([p for p in inc_dir.iterdir() if not p.name.startswith(".")]) == 0
            and len([p for p in out_dir.iterdir() if not p.name.startswith(".")]) == 5,
            timeout=35.0,
        )

        # Ensure high-res originals AND Takeout .json sidecars in Incoming were deleted
        assert [p.name for p in inc_dir.iterdir()] == [".nomedia"]

        # 1. Verify 48MP iPhone sample shrunk <= 16MP + Apple EXIF & GPS intact
        lake_meta = inspect_media_file(out_dir / "IMG_48MP_iPhone16Pro_LakeLouise.jpg")
        assert lake_meta["width"] * lake_meta["height"] <= 16_000_000
        assert "iPhone 16 Pro" in lake_meta["camera_model"]
        assert lake_meta["date_taken"] == "2026:08:14 17:42:19"
        assert "51.4167" in lake_meta["gps_coords"]

        # 2. Verify 24MP Portrait sample with Orientation=6 normalized upright + Sony EXIF intact
        sony_meta = inspect_media_file(out_dir / "DSC_24MP_SonyA7IV_Portrait_Shibuya.jpg")
        assert sony_meta["width"] < sony_meta["height"]
        assert "ILCE-7M4" in sony_meta["camera_model"]

        # 3. Verify WhatsApp stripped-EXIF image had its true 2023 EXIF DateTimeOriginal restored!
        wa_meta = inspect_media_file(out_dir / "WhatsApp Image 2023-07-19 at 18.45.12.jpg")
        assert wa_meta["date_taken"] == "2023:07:19 18:45:12"

        # 4. Verify Google Takeout .json sidecar merged GPS + timestamp directly into EXIF!
        takeout_meta = inspect_media_file(out_dir / "Takeout_KyotoFushimiInari_50MP.jpg")
        assert takeout_meta["width"] * takeout_meta["height"] <= 16_000_000
        assert "34.9671" in takeout_meta["gps_coords"]
        assert "135.7727" in takeout_meta["gps_coords"]
        assert takeout_meta["date_taken"].startswith("2024:04:")

        # 5. Verify 4K video downscaled cleanly to 1920x1080 MP4
        vid_meta = inspect_media_file(out_dir / "VID_4K_UHD_2160p_SampleClip.mp4")
        assert (vid_meta["width"], vid_meta["height"]) == (1920, 1080)


def test_sha256_dedup_recursive_subfolders_and_retention(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_smart")
    with TestClient(app) as client:
        client.post("/api/settings", json={"ftp_enabled": False})
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])

        # Drop high-res photo inside a nested album subfolder
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
        # Empty subfolder in Incoming auto-cleaned!
        assert _wait_until(lambda: not album_dir.exists(), timeout=6.0)

        # Drop exact same bytes again under a different filename -> SHA-256 dedup skips re-encode!
        dup_path = inc_dir / "reupload_copy.jpg"
        dup_path.write_bytes(payload)
        assert _wait_until(lambda: not dup_path.exists(), timeout=10.0)
        assert not (out_dir / "reupload_copy.jpg").exists()
        summary = client.get("/api/history").json()["summary"]
        assert summary["duplicates_skipped_count"] == 1

        # Test auto-purge retention cleanup for files older than N days
        target_out = out_dir / "Iceland2025_IMG_20250615_143022.jpg"
        old_ts = time.time() - (20 * 86400)
        os.utime(target_out, (old_ts, old_ts))
        # Set ctime fallback via retention=0 vs 14 days if ctime is recent -> test min_free_gb FIFO eviction
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

        # Turn Storage Saver back ON and test WebDAV + FTP
        client.post("/api/settings", json={"storage_saver_enabled": True})

        dav_buf = io.BytesIO()
        Image.new("RGB", (5200, 3900), (180, 70, 90)).save(dav_buf, format="PNG")
        assert client.put("/webdav/Incoming/webdav_20mp.png", content=dav_buf.getvalue()).status_code in (201, 204)
        assert _wait_until(lambda: (out_dir / "webdav_20mp.jpg").is_file(), timeout=12.0)

        ftp_buf = io.BytesIO()
        Image.new("RGB", (5000, 4000), (20, 160, 110)).save(ftp_buf, format="JPEG", quality=94)
        ftp_buf.seek(0)
        with ftplib.FTP() as ftp:
            ftp.connect("127.0.0.1", ftp_port, timeout=5)
            ftp.login("pixel", "pixel")
            ftp.cwd("Incoming")
            ftp.storbinary("STOR ftp_20mp_camera.jpg", ftp_buf)

        assert _wait_until(lambda: (out_dir / "ftp_20mp_camera.jpg").is_file(), timeout=12.0)
