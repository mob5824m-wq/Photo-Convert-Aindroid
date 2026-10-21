from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from typing import List

import piexif
from PIL import Image, ImageDraw

from server.converter import get_ffmpeg_binary


def _deg_to_dms_rational(deg_float: float):
    deg_abs = abs(deg_float)
    d = int(deg_abs)
    m_float = (deg_abs - d) * 60.0
    m = int(m_float)
    s = int(round((m_float - m) * 60.0 * 10000))
    return ((d, 1), (m, 1), (s, 10000))


def _make_sample_exif(
    make: str,
    model: str,
    dt_str: str,
    lat: float,
    lon: float,
    orientation: int = 1,
) -> bytes:
    exif_dict = {
        "0th": {
            piexif.ImageIFD.Make: make.encode("utf-8"),
            piexif.ImageIFD.Model: model.encode("utf-8"),
            piexif.ImageIFD.Orientation: orientation,
            piexif.ImageIFD.DateTime: dt_str.encode("utf-8"),
        },
        "Exif": {
            piexif.ExifIFD.DateTimeOriginal: dt_str.encode("utf-8"),
            piexif.ExifIFD.DateTimeDigitized: dt_str.encode("utf-8"),
            piexif.ExifIFD.FocalLength: (24, 1),
            piexif.ExifIFD.ISOSpeedRatings: 100,
        },
        "GPS": {
            piexif.GPSIFD.GPSLatitudeRef: (b"N" if lat >= 0 else b"S"),
            piexif.GPSIFD.GPSLatitude: _deg_to_dms_rational(lat),
            piexif.GPSIFD.GPSLongitudeRef: (b"E" if lon >= 0 else b"W"),
            piexif.GPSIFD.GPSLongitude: _deg_to_dms_rational(lon),
        },
        "1st": {},
        "thumbnail": None,
    }
    return piexif.dump(exif_dict)


def _render_scenic_canvas(
    width: int,
    height: int,
    palette: tuple[tuple[int, int, int], tuple[int, int, int], tuple[int, int, int]],
    label: str,
) -> Image.Image:
    base_w, base_h = 800, 600
    base = Image.new("RGB", (base_w, base_h), palette[0])
    draw = ImageDraw.Draw(base)
    c1, c2, c3 = palette
    for y in range(base_h):
        t = y / max(1, base_h - 1)
        r = int(c1[0] * (1 - t) + c2[0] * t)
        g = int(c1[1] * (1 - t) + c2[1] * t)
        b = int(c1[2] * (1 - t) + c2[2] * t)
        draw.line([(0, y), (base_w, y)], fill=(r, g, b))

    draw.ellipse([540, 80, 660, 200], fill=(255, 240, 196))
    draw.polygon(
        [(0, 440), (140, 290), (290, 390), (470, 245), (650, 360), (800, 270), (800, 600), (0, 600)],
        fill=c3,
    )
    darker = (max(0, c3[0] - 28), max(0, c3[1] - 28), max(0, c3[2] - 24))
    draw.polygon(
        [(0, 500), (200, 380), (390, 470), (590, 355), (800, 450), (800, 600), (0, 600)],
        fill=darker,
    )
    draw.rectangle([28, 28, 520, 86], fill=(15, 23, 42))
    draw.text((44, 48), label, fill=(248, 250, 252))

    upscaled = base.resize((width, height), Image.Resampling.BILINEAR)
    noise_tile = Image.effect_noise((512, 512), 16.0).convert("RGB")
    tiled = Image.new("RGB", (width, height))
    for y in range(0, height, 512):
        for x in range(0, width, 512):
            tiled.paste(noise_tile, (x, y))
    return Image.blend(upscaled, tiled, alpha=0.14)


def seed_corrupted_transfer(incoming_dir: Path, profile: str = "StorageSaver") -> str:
    """Drop a truncated/corrupted transfer into Incoming so the 2-retry Quarantine shield moves it safely."""
    target_dir = incoming_dir / profile if profile else incoming_dir
    target_dir.mkdir(parents=True, exist_ok=True)
    name = f"CORRUPT_TruncatedTransfer_{uuid.uuid4().hex[:5]}.jpg"
    dest = target_dir / name
    # Partial JPEG SOI marker followed by truncated garbage stream (simulates dropped Wi-Fi transfer)
    dest.write_bytes(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x02\x00\x00\x01" + b"\xab\xcd\xef" * 1200)
    return name


def generate_sample_batch(
    incoming_dir: Path,
    include_video: bool = True,
    include_corrupted: bool = False,
) -> List[str]:
    """
    Generate realistic Android camera & archive media across Per-Folder Profiles:
      - /Incoming/StorageSaver/ -> 50MP Pixel 9 Pro RAW-JPEG (shrunk to <=16.0 MP!)
      - /Incoming/Original/     -> 24MP Samsung Galaxy S24 Ultra Night Sky (kept 100% full-res!)
      - /Incoming/Family/       -> WhatsApp stripped-EXIF image (date restored + routed to Output/Family!)
      - /Incoming/StorageSaver/ -> 50MP Google Takeout + .json sidecar (Date + Kyoto GPS merged!)
      - /Incoming/StorageSaver/ -> Optional 4K 2160p UHD MP4 video clip
    """
    saver_dir = incoming_dir / "StorageSaver"
    orig_dir = incoming_dir / "Original"
    family_dir = incoming_dir / "Family"
    for d in (saver_dir, orig_dir, family_dir):
        d.mkdir(parents=True, exist_ok=True)

    created: List[str] = []

    # 1. /Incoming/StorageSaver -> 50MP Pixel 9 Pro photo
    p9_tmp = saver_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    p9_dest = saver_dir / "PXL_50MP_Pixel9Pro_LakeLouise.jpg"
    p9_img = _render_scenic_canvas(
        8000,
        6000,
        ((29, 78, 137), (125, 185, 222), (35, 69, 82)),
        "StorageSaver Profile | 48MP Pixel 9 Pro",
    )
    p9_img.save(
        p9_tmp,
        format="JPEG",
        quality=96,
        subsampling=0,
        exif=_make_sample_exif(
            "Google", "Pixel 9 Pro", "2026:08:14 17:42:19", 51.4167, -116.2167, 1
        ),
    )
    os.replace(p9_tmp, p9_dest)
    created.append(f"StorageSaver/{p9_dest.name}")

    # 2. /Incoming/Original -> 24MP Samsung Galaxy S24 Ultra (kept FULL 24MP Original Quality!)
    s24_tmp = orig_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    s24_dest = orig_dir / "SAM_24MP_GalaxyS24Ultra_Portrait.jpg"
    s24_img = _render_scenic_canvas(
        6000,
        4000,
        ((88, 44, 131), (236, 120, 102), (42, 34, 68)),
        "Original Profile | Full-Res Galaxy S24 Ultra",
    )
    s24_img.save(
        s24_tmp,
        format="JPEG",
        quality=95,
        subsampling=0,
        exif=_make_sample_exif(
            "Samsung", "SM-S928B", "2026:09:29 20:15:04", 35.6595, 139.7004, 6
        ),
    )
    os.replace(s24_tmp, s24_dest)
    created.append(f"Original/{s24_dest.name}")

    # 3. /Incoming/Family -> WhatsApp stripped-EXIF image routed to Output/Family album!
    wa_tmp = family_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    wa_dest = family_dir / "WhatsApp Image 2023-07-19 at 18.45.12.jpeg"
    wa_img = _render_scenic_canvas(
        5000,
        3750,
        ((16, 124, 92), (110, 212, 168), (22, 68, 52)),
        "Family Profile | WhatsApp Date Restored",
    )
    wa_img.save(wa_tmp, format="JPEG", quality=94)
    os.replace(wa_tmp, wa_dest)
    created.append(f"Family/{wa_dest.name}")

    # 4. /Incoming/StorageSaver -> 50MP Google Takeout + .json sidecar pair
    to_dest = saver_dir / "Takeout_KyotoFushimiInari_50MP.jpg"
    to_json = saver_dir / "Takeout_KyotoFushimiInari_50MP.jpg.json"
    to_json.write_text(
        json.dumps(
            {
                "title": "Takeout_KyotoFushimiInari_50MP.jpg",
                "photoTakenTime": {"timestamp": "1712998200"},
                "geoData": {"latitude": 34.9671, "longitude": 135.7727, "altitude": 64.0},
            }
        ),
        encoding="utf-8",
    )
    to_tmp = saver_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    to_img = _render_scenic_canvas(
        8160,
        6120,
        ((154, 52, 18), (245, 158, 11), (68, 42, 27)),
        "50MP Google Takeout + JSON Sidecar",
    )
    to_img.save(to_tmp, format="JPEG", quality=96, subsampling=0)
    os.replace(to_tmp, to_dest)
    created.append(f"StorageSaver/{to_dest.name}")

    ffmpeg = get_ffmpeg_binary()
    if include_video and ffmpeg:
        tmp_vid = saver_dir / f".tmp_{uuid.uuid4().hex}.mp4"
        dest_vid = saver_dir / "VID_4K_UHD_2160p_SampleClip.mp4"
        cmd = [
            ffmpeg,
            "-y",
            "-hide_banner",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=3840x2160:rate=24:duration=2",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=2",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-crf",
            "17",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            str(tmp_vid),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=45, check=False)
        if proc.returncode == 0 and tmp_vid.is_file() and tmp_vid.stat().st_size > 0:
            os.replace(tmp_vid, dest_vid)
            created.append(f"StorageSaver/{dest_vid.name}")
        elif tmp_vid.exists():
            tmp_vid.unlink(missing_ok=True)

    if include_corrupted:
        corrupt_name = seed_corrupted_transfer(incoming_dir, "StorageSaver")
        created.append(f"StorageSaver/{corrupt_name}")

    time.sleep(0.05)
    return created
