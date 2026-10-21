from __future__ import annotations

import ftplib
import io
import time
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image

from server.converter import compute_target_dimensions, inspect_media_file
from server.demo_media import generate_sample_batch
from server.main import create_app


def _wait_until(predicate, timeout: float = 20.0, interval: float = 0.2) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def test_storage_saver_dimension_math():
    # 48MP 4:3 -> <= 16.0 MP
    w, h, resized = compute_target_dimensions(8000, 6000, 16.0)
    assert resized is True
    assert w * h <= 16_000_000
    assert (w, h) == (4618, 3464)

    # 12MP 4:3 -> unchanged
    w2, h2, resized2 = compute_target_dimensions(4032, 3024, 16.0)
    assert resized2 is False
    assert (w2, h2) == (4032, 3024)


def test_end_to_end_48mp_photos_4k_video_exif_and_cleanup(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_data")
    with TestClient(app) as client:
        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])
        assert (inc_dir / ".nomedia").exists()
        assert not (out_dir / ".nomedia").exists()

        # Pause auto-watch briefly so we can inspect high-res files in Incoming first
        client.post("/api/settings", json={"auto_watch_enabled": False, "ftp_port": 21210})
        seeded = generate_sample_batch(inc_dir, include_video=True)
        assert len(seeded) == 4  # 3 high-res photos + 1 4K MP4 clip

        inc_list = client.get("/api/files?folder=incoming").json()["files"]
        assert len(inc_list) == 4

        # Now trigger conversion & auto-cleanup
        client.post("/api/settings", json={"auto_watch_enabled": True})

        assert _wait_until(
            lambda: len([p for p in inc_dir.iterdir() if not p.name.startswith(".")]) == 0
            and len([p for p in out_dir.iterdir() if not p.name.startswith(".")]) == 4,
            timeout=30.0,
        )

        # Verify all high-res originals in Incoming were deleted (except .nomedia)
        remaining_incoming = [p.name for p in inc_dir.iterdir()]
        assert remaining_incoming == [".nomedia"]

        # Verify 48MP iPhone sample shrunk <= 16MP and preserved Apple EXIF + Date + GPS
        lake_out = out_dir / "IMG_48MP_iPhone16Pro_LakeLouise.jpg"
        assert lake_out.is_file()
        lake_meta = inspect_media_file(lake_out)
        assert lake_meta["width"] * lake_meta["height"] <= 16_000_000
        assert "iPhone 16 Pro" in lake_meta["camera_model"]
        assert lake_meta["date_taken"] == "2026:08:14 17:42:19"
        assert "51.4167" in lake_meta["gps_coords"]

        # Verify 24MP Portrait sample with Orientation=6 was normalized upright (width < height) + Sony EXIF kept
        sony_out = out_dir / "DSC_24MP_SonyA7IV_Portrait_Shibuya.jpg"
        sony_meta = inspect_media_file(sony_out)
        assert sony_meta["width"] < sony_meta["height"]
        assert sony_meta["width"] * sony_meta["height"] <= 16_000_000
        assert "ILCE-7M4" in sony_meta["camera_model"]

        # Verify 4K video was downscaled to 1080p MP4
        vid_out = out_dir / "VID_4K_UHD_2160p_SampleClip.mp4"
        vid_meta = inspect_media_file(vid_out)
        assert vid_meta["height"] == 1080
        assert vid_meta["width"] == 1920

        hist = client.get("/api/history").json()
        assert hist["summary"]["total_processed"] == 4
        assert hist["summary"]["originals_deleted_count"] == 4
        assert hist["summary"]["total_saved_bytes"] > 0


def test_passthrough_mode_when_storage_saver_disabled(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_passthrough")
    with TestClient(app) as client:
        client.post(
            "/api/settings",
            json={
                "device_profile": "pixel_1",
                "storage_saver_enabled": False,
                "delete_originals": True,
                "ftp_enabled": False,
            },
        )
        # Upload a 25MP image (5000 x 5000) via multipart /api/upload
        buf = io.BytesIO()
        Image.new("RGB", (5000, 5000), (40, 120, 200)).save(buf, format="JPEG", quality=92)
        orig_payload = buf.getvalue()

        res = client.post(
            "/api/upload",
            files=[("files", ("pixel1_fullres.jpg", orig_payload, "image/jpeg"))],
        )
        assert res.status_code == 200

        st = client.get("/api/status").json()
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])

        assert _wait_until(lambda: (out_dir / "pixel1_fullres.jpg").is_file(), timeout=10.0)
        assert not (inc_dir / "pixel1_fullres.jpg").exists()
        # Original resolution & exact bytes kept intact!
        out_file = out_dir / "pixel1_fullres.jpg"
        assert out_file.stat().st_size == len(orig_payload)
        meta = inspect_media_file(out_file)
        assert (meta["width"], meta["height"]) == (5000, 5000)


def test_webdav_and_ftp_uploads_trigger_conversion(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_protocols")
    with TestClient(app) as client:
        client.post("/api/settings", json={"ftp_enabled": True, "ftp_port": 0})
        st = client.get("/api/status").json()
        out_dir = Path(st["folders"]["output"]["path"])
        ftp_port = st["network"]["ftp_port"]

        # 1. Test WebDAV OPTIONS + PROPFIND + PUT into /webdav/Incoming/
        opt = client.request("OPTIONS", "/webdav/")
        assert opt.status_code == 200
        assert "1, 2" in opt.headers.get("DAV", "")

        prop = client.request("PROPFIND", "/webdav/", headers={"Depth": "1"})
        assert prop.status_code == 207
        assert "/webdav/Incoming/" in prop.text

        dav_buf = io.BytesIO()
        Image.new("RGB", (5200, 3900), (180, 70, 90)).save(dav_buf, format="PNG")
        put_res = client.put("/webdav/Incoming/webdav_20mp.png", content=dav_buf.getvalue())
        assert put_res.status_code in (201, 204)

        assert _wait_until(lambda: (out_dir / "webdav_20mp.jpg").is_file(), timeout=12.0)
        dav_meta = inspect_media_file(out_dir / "webdav_20mp.jpg")
        assert dav_meta["width"] * dav_meta["height"] <= 16_000_000

        # 2. Test FTP upload directly into /Incoming/
        ftp_buf = io.BytesIO()
        Image.new("RGB", (5000, 4000), (20, 160, 110)).save(ftp_buf, format="JPEG", quality=94)
        ftp_buf.seek(0)

        with ftplib.FTP() as ftp:
            ftp.connect("127.0.0.1", ftp_port, timeout=5)
            ftp.login("pixel", "pixel")
            ftp.cwd("Incoming")
            ftp.storbinary("STOR ftp_20mp_camera.jpg", ftp_buf)

        assert _wait_until(
            lambda: (out_dir / "ftp_20mp_camera.jpg").is_file(), timeout=12.0
        )
        ftp_meta = inspect_media_file(out_dir / "ftp_20mp_camera.jpg")
        assert ftp_meta["width"] * ftp_meta["height"] <= 16_000_000
