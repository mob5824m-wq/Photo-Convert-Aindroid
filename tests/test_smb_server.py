from __future__ import annotations

import io
import time
from pathlib import Path

import piexif
import pytest
import smbclient
from fastapi.testclient import TestClient
from PIL import Image
from smbprotocol.exceptions import LogonFailure

from server.config import SettingsManager
from server.database import HistoryDatabase
from server.main import create_app
from server.smb_server import EmbeddedSmbServer
from server.watcher import PipelineWorker


def _make_highres_jpeg(width: int = 5200, height: int = 3800, color=(42, 128, 198)) -> bytes:
    """Create a ~19.76 MP JPEG (> 16 MP threshold) with embedded EXIF DateTimeOriginal & GPS."""
    img = Image.new("RGB", (width, height), color=color)
    exif_bytes = piexif.dump(
        {
            "0th": {
                piexif.ImageIFD.Make: b"Samsung",
                piexif.ImageIFD.Model: b"Galaxy S24 Ultra",
            },
            "Exif": {
                piexif.ExifIFD.DateTimeOriginal: b"2026:10:21 14:30:15",
            },
            "GPS": {
                piexif.GPSIFD.GPSLatitudeRef: b"N",
                piexif.GPSIFD.GPSLatitude: ((43, 1), (27, 1), (0, 1)),
                piexif.GPSIFD.GPSLongitudeRef: b"W",
                piexif.GPSIFD.GPSLongitude: ((80, 1), (29, 1), (0, 1)),
            },
        }
    )
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92, exif=exif_bytes)
    return buf.getvalue()


def _clear_smb_session(port: int) -> None:
    try:
        smbclient.delete_session("127.0.0.1", port=port)
    except Exception:
        pass


def test_smb2_guest_and_member_upload_triggers_storage_saver(tmp_path: Path):
    mgr = SettingsManager(base_dir=tmp_path)
    mgr.update({"smb_port": 0, "ftp_enabled": False})
    db = HistoryDatabase(mgr.db_file)
    worker = PipelineWorker(mgr, db)
    smb = EmbeddedSmbServer(mgr, worker)

    worker.start()
    smb.start_or_reload()
    port = smb.bound_port
    assert port > 0

    try:
        _clear_smb_session(port)
        smbclient.register_session("127.0.0.1", username="Guest", password="", port=port)

        root_entries = set(smbclient.listdir(r"\\127.0.0.1\PixelNAS", port=port))
        assert {"Incoming", "Output", "Quarantine"}.issubset(root_entries)

        inc_entries = set(smbclient.listdir(r"\\127.0.0.1\PixelNAS\Incoming", port=port))
        assert {"StorageSaver", "Original", "Family"}.issubset(inc_entries)

        # Upload a >16 MP photo atomically via .part -> .jpg into Incoming/StorageSaver
        photo_bytes = _make_highres_jpeg()
        part_unc = r"\\127.0.0.1\PixelNAS\Incoming\StorageSaver\.upload_family_001.part"
        final_unc = r"\\127.0.0.1\PixelNAS\Incoming\StorageSaver\family_pic_001.jpg"
        with smbclient.open_file(part_unc, mode="wb", port=port) as f:
            f.write(photo_bytes)
        smbclient.rename(part_unc, final_unc, port=port)

        # Also upload a distinct full-resolution original into Incoming/Original
        orig_unc = r"\\127.0.0.1\PixelNAS\Incoming\Original\keep_full_res.jpg"
        with smbclient.open_file(orig_unc, mode="wb", port=port) as f:
            f.write(_make_highres_jpeg(color=(210, 90, 60)))

        # Wait for both automatic conversions into Output (Output also contains 'Family' subfolder)
        deadline = time.time() + 12.0
        out_files = []
        while time.time() < deadline:
            out_files = smbclient.listdir(r"\\127.0.0.1\PixelNAS\Output", port=port)
            if any("family_pic_001" in n for n in out_files) and any(
                "keep_full_res" in n for n in out_files
            ):
                break
            time.sleep(0.25)

        assert len(out_files) >= 2, f"Expected >=2 converted files in Output, got {out_files}"

        # High-res staging originals in Incoming must be deleted after verification
        remaining_saver = smbclient.listdir(
            r"\\127.0.0.1\PixelNAS\Incoming\StorageSaver", port=port
        )
        assert remaining_saver == []

        # Verify StorageSaver output was shrunk to <= 16.05 MP with EXIF preserved,
        # while Original output remained full 5200x3800 resolution!
        saver_matches = [n for n in out_files if "family_pic_001" in n]
        orig_matches = [n for n in out_files if "keep_full_res" in n]
        assert len(saver_matches) == 1
        assert len(orig_matches) == 1

        with smbclient.open_file(
            rf"\\127.0.0.1\PixelNAS\Output\{saver_matches[0]}", mode="rb", port=port
        ) as f:
            saver_img = Image.open(io.BytesIO(f.read()))
            w, h = saver_img.size
            assert (w * h) / 1_000_000 <= 16.05
            exif = piexif.load(saver_img.info["exif"])
            assert exif["Exif"][piexif.ExifIFD.DateTimeOriginal] == b"2026:10:21 14:30:15"

        with smbclient.open_file(
            rf"\\127.0.0.1\PixelNAS\Output\{orig_matches[0]}", mode="rb", port=port
        ) as f:
            orig_img = Image.open(io.BytesIO(f.read()))
            assert orig_img.size == (5200, 3800)
    finally:
        _clear_smb_session(port)
        smb.stop()
        worker.stop()


def test_smb2_ntlmv2_signed_auth_and_wrong_password_rejection(tmp_path: Path):
    mgr = SettingsManager(base_dir=tmp_path)
    mgr.update(
        {
            "smb_port": 0,
            "ftp_enabled": False,
            "auth_enabled": True,
            "auth_username": "household",
            "auth_password": "SambaSecret!42",
        }
    )
    db = HistoryDatabase(mgr, mgr.db_file) if False else HistoryDatabase(mgr.db_file)
    worker = PipelineWorker(mgr, db)
    smb = EmbeddedSmbServer(mgr, worker)
    smb.start_or_reload()
    port = smb.bound_port

    try:
        _clear_smb_session(port)
        with pytest.raises(LogonFailure):
            smbclient.register_session(
                "127.0.0.1", username="household", password="WrongPassword", port=port
            )

        _clear_smb_session(port)
        smbclient.register_session(
            "127.0.0.1", username="household", password="SambaSecret!42", port=port
        )
        # Direct share mount \\127.0.0.1\Incoming plus per-member folder creation
        smbclient.makedirs(r"\\127.0.0.1\Incoming\AlexPhone", exist_ok=True, port=port)
        with smbclient.open_file(
            r"\\127.0.0.1\Incoming\AlexPhone\probe.txt", mode="wb", port=port
        ) as f:
            f.write(b"signed-ntlmv2-ok")
        with smbclient.open_file(
            r"\\127.0.0.1\Incoming\AlexPhone\probe.txt", mode="rb", port=port
        ) as f:
            assert f.read() == b"signed-ntlmv2-ok"
    finally:
        _clear_smb_session(port)
        smb.stop()


def test_api_status_exposes_samba_network_details(tmp_path: Path):
    app = create_app(data_dir=tmp_path)
    with TestClient(app) as client:
        res = client.get("/api/status")
        assert res.status_code == 200
        net = res.json()["network"]
        assert net["smb_enabled"] is True
        assert net["smb_port"] > 0
        assert "\\PixelNAS" in net["smb_unc"]
        assert "smb://" in net["smb_url"]
