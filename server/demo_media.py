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
    draw.rectangle([28, 28, 470, 86], fill=(15, 23, 42))
    draw.text((44, 48), label, fill=(248, 250, 252))

    upscaled = base.resize((width, height), Image.Resampling.BILINEAR)
    noise_tile = Image.effect_noise((512, 512), 16.0).convert("RGB")
    tiled = Image.new("RGB", (width, height))
    for y in range(0, height, 512):
        for x in range(0, width, 512):
            tiled.paste(noise_tile, (x, y))
    return Image.blend(upscaled, tiled, alpha=0.14)


def generate_sample_batch(incoming_dir: Path, include_video: bool = True) -> List[str]:
    """
    Generate realistic high-res test media including:
      - 48MP iPhone 16 Pro JPEG with GPS & EXIF
      - 24MP Portrait Sony A7 IV JPEG (EXIF Orientation=6)
      - WhatsApp stripped-EXIF photo (to demonstrate automatic filename -> EXIF Date restoration!)
      - Google Takeout photo + .json sidecar pair (to demonstrate Takeout timestamp + GPS merging!)
      - Optional 4K 2160p UHD MP4 video clip
    """
    incoming_dir.mkdir(parents=True, exist_ok=True)
    created: List[str] = []

    samples = [
        {
            "filename": "IMG_48MP_iPhone16Pro_LakeLouise.jpg",
            "size": (8000, 6000),
            "palette": ((29, 78, 137), (125, 185, 222), (35, 69, 82)),
            "label": "48.0 MP RAW-JPEG  |  iPhone 16 Pro  |  8000x6000",
            "make": "Apple",
            "model": "iPhone 16 Pro",
            "dt": "2026:08:14 17:42:19",
            "lat": 51.4167,
            "lon": -116.2167,
            "orientation": 1,
        },
        {
            "filename": "DSC_24MP_SonyA7IV_Portrait_Shibuya.jpg",
            "size": (6000, 4000),
            "palette": ((88, 44, 131), (236, 120, 102), (42, 34, 68)),
            "label": "24.0 MP Portrait EXIF=6  |  Sony A7 IV",
            "make": "Sony",
            "model": "ILCE-7M4",
            "dt": "2026:09:29 20:15:04",
            "lat": 35.6595,
            "lon": 139.7004,
            "orientation": 6,
        },
    ]

    for item in samples:
        tmp = incoming_dir / f".tmp_{uuid.uuid4().hex}.jpg"
        dest = incoming_dir / item["filename"]
        img = _render_scenic_canvas(
            item["size"][0], item["size"][1], item["palette"], item["label"]
        )
        exif_bytes = _make_sample_exif(
            make=item["make"],
            model=item["model"],
            dt_str=item["dt"],
            lat=item["lat"],
            lon=item["lon"],
            orientation=item["orientation"],
        )
        img.save(tmp, format="JPEG", quality=96, subsampling=0, exif=exif_bytes)
        os.replace(tmp, dest)
        created.append(dest.name)

    # 3. Stripped-EXIF WhatsApp photo (no EXIF tags inside file — date parsed from filename!)
    wa_tmp = incoming_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    wa_dest = incoming_dir / "WhatsApp Image 2023-07-19 at 18.45.12.jpeg"
    wa_img = _render_scenic_canvas(
        5000,
        3750,
        ((16, 124, 92), (110, 212, 168), (22, 68, 52)),
        "Stripped-EXIF Chat Photo  |  WhatsApp 2023-07-19",
    )
    wa_img.save(wa_tmp, format="JPEG", quality=94)
    os.replace(wa_tmp, wa_dest)
    created.append(wa_dest.name)

    # 4. Google Takeout photo + .json sidecar metadata pair (write .json FIRST so atomic watcher reads both!)
    to_dest = incoming_dir / "Takeout_KyotoFushimiInari_50MP.jpg"
    to_json = incoming_dir / "Takeout_KyotoFushimiInari_50MP.jpg.json"
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
    to_tmp = incoming_dir / f".tmp_{uuid.uuid4().hex}.jpg"
    to_img = _render_scenic_canvas(
        8160,
        6120,
        ((154, 52, 18), (245, 158, 11), (68, 42, 27)),
        "50.0 MP Google Takeout + JSON Sidecar  |  Kyoto",
    )
    to_img.save(to_tmp, format="JPEG", quality=96, subsampling=0)
    os.replace(to_tmp, to_dest)
    created.append(to_dest.name)

    ffmpeg = get_ffmpeg_binary()
    if include_video and ffmpeg:
        tmp_vid = incoming_dir / f".tmp_{uuid.uuid4().hex}.mp4"
        dest_vid = incoming_dir / "VID_4K_UHD_2160p_SampleClip.mp4"
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
            created.append(dest_vid.name)
        elif tmp_vid.exists():
            tmp_vid.unlink(missing_ok=True)
    time.sleep(0.05)
    return created
