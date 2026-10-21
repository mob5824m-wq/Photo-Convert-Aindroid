from __future__ import annotations

import copy
import datetime as dt_mod
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Dict, Optional, Tuple

import piexif
from PIL import Image, ImageOps

try:
    from pillow_heif import register_heif_opener

    register_heif_opener()
except Exception:
    pass

from server.config import NasSettings, sanitize_folder_segment
from server.database import HistoryDatabase

PHOTO_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".webp",
    ".heic",
    ".heif",
    ".avif",
    ".bmp",
    ".tif",
    ".tiff",
}

VIDEO_EXTENSIONS = {
    ".mp4",
    ".mov",
    ".mkv",
    ".webm",
    ".m4v",
    ".3gp",
    ".avi",
}

IGNORED_SUFFIXES = {
    ".tmp",
    ".part",
    ".partial",
    ".crdownload",
    ".download",
    ".lock",
    ".json",
}

FILENAME_DATE_PATTERNS = [
    re.compile(
        r"(?:IMG|VID|PXL|DSC|MVIMG|SAM|Screenshot|Signal)[_-]?(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})",
        re.IGNORECASE,
    ),
    re.compile(
        r"WhatsApp\s+(?:Image|Video)\s+(\d{4})-(\d{2})-(\d{2})\s+at\s+(\d{1,2})\.(\d{2})\.(\d{2})",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?<!\d)(20\d{2}|19\d{2})[-_](\d{2})[-_](\d{2})[\s_T-]+(\d{2})[.:_-](\d{2})[.:_-](\d{2})(?!\d)"
    ),
    re.compile(r"(?<!\d)(20\d{2}|19\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?!\d)"),
]


def get_ffmpeg_binary() -> Optional[str]:
    sys_ff = shutil.which("ffmpeg")
    if sys_ff:
        return sys_ff
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def compute_file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def parse_datetime_from_filename(filename: str) -> Optional[Tuple[str, float]]:
    stem = Path(filename).stem
    for pat in FILENAME_DATE_PATTERNS:
        m = pat.search(stem)
        if not m:
            continue
        y, mo, d, hh, mm, ss = (int(x) for x in m.groups())
        try:
            dt_obj = dt_mod.datetime(y, mo, d, hh, mm, ss)
            if 1970 <= dt_obj.year <= 2100:
                exif_str = dt_obj.strftime("%Y:%m:%d %H:%M:%S")
                return exif_str, dt_obj.timestamp()
        except ValueError:
            continue
    return None


def find_takeout_sidecar(media_path: Path) -> Optional[Path]:
    candidates = [
        media_path.parent / f"{media_path.name}.json",
        media_path.parent / f"{media_path.name}.supplemental-metadata.json",
        media_path.with_suffix(".json"),
    ]
    for cand in candidates:
        if cand.is_file():
            return cand
    return None


def read_takeout_sidecar(sidecar_path: Path) -> Dict:
    extracted: Dict = {}
    try:
        raw = json.loads(sidecar_path.read_text(encoding="utf-8", errors="ignore"))
        ts_str = (raw.get("photoTakenTime") or {}).get("timestamp") or (
            raw.get("creationTime") or {}
        ).get("timestamp")
        if ts_str:
            ts = int(ts_str)
            if ts > 0:
                dt_utc = dt_mod.datetime.fromtimestamp(ts)
                extracted["exif_datetime"] = dt_utc.strftime("%Y:%m:%d %H:%M:%S")
                extracted["timestamp"] = float(ts)
        geo = raw.get("geoData") or raw.get("geoDataExif") or {}
        lat = float(geo.get("latitude") or 0.0)
        lon = float(geo.get("longitude") or 0.0)
        if abs(lat) > 0.00001 or abs(lon) > 0.00001:
            extracted["lat"] = lat
            extracted["lon"] = lon
    except Exception:
        pass
    return extracted


def _deg_to_dms_rational(deg_float: float):
    deg_abs = abs(deg_float)
    d = int(deg_abs)
    m_float = (deg_abs - d) * 60.0
    m = int(m_float)
    s = int(round((m_float - m) * 60.0 * 10000))
    return ((d, 1), (m, 1), (s, 10000))


def is_supported_media(path: Path) -> bool:
    name = path.name
    if not name or name.startswith(".") or name.startswith("~"):
        return False
    lower = name.lower()
    if any(lower.endswith(s) for s in IGNORED_SUFFIXES) or ".syncthing." in lower:
        return False
    ext = path.suffix.lower()
    return ext in PHOTO_EXTENSIONS or ext in VIDEO_EXTENSIONS


def compute_target_dimensions(
    width: int, height: int, max_megapixels: float = 16.0
) -> Tuple[int, int, bool]:
    if width <= 0 or height <= 0:
        return max(1, width), max(1, height), False
    max_pixels = int(round(max_megapixels * 1_000_000))
    total_pixels = width * height
    if total_pixels <= max_pixels:
        return width, height, False
    scale = math.sqrt(max_pixels / float(total_pixels))
    new_w = max(1, int(math.floor(width * scale)))
    new_h = max(1, int(math.floor(height * scale)))
    while new_w * new_h > max_pixels:
        if new_w >= new_h and new_w > 1:
            new_w -= 1
        elif new_h > 1:
            new_h -= 1
        else:
            break
    return new_w, new_h, True


def _decode_rational(val) -> float:
    if isinstance(val, tuple) and len(val) == 2 and val[1] != 0:
        return float(val[0]) / float(val[1])
    return float(val)


def _gps_to_decimal(dms, ref: str) -> Optional[float]:
    try:
        deg = _decode_rational(dms[0])
        minutes = _decode_rational(dms[1])
        seconds = _decode_rational(dms[2])
        result = deg + (minutes / 60.0) + (seconds / 3600.0)
        if ref in ("S", "W"):
            result = -result
        return round(result, 6)
    except Exception:
        return None


def inspect_photo_exif(raw_exif_bytes: Optional[bytes]) -> Dict[str, str]:
    summary = {"camera_model": "", "date_taken": "", "gps_coords": ""}
    if not raw_exif_bytes:
        return summary
    try:
        exif_dict = piexif.load(raw_exif_bytes)
        zeroth = exif_dict.get("0th", {})
        exif_ifd = exif_dict.get("Exif", {})
        gps_ifd = exif_dict.get("GPS", {})

        make = zeroth.get(piexif.ImageIFD.Make, b"")
        model = zeroth.get(piexif.ImageIFD.Model, b"")
        if isinstance(make, bytes):
            make = make.decode("utf-8", errors="ignore").strip("\x00 ")
        if isinstance(model, bytes):
            model = model.decode("utf-8", errors="ignore").strip("\x00 ")
        if make and model and not model.lower().startswith(make.lower()):
            summary["camera_model"] = f"{make} {model}"
        else:
            summary["camera_model"] = str(model or make)

        dt = (
            exif_ifd.get(piexif.ExifIFD.DateTimeOriginal)
            or exif_ifd.get(piexif.ExifIFD.DateTimeDigitized)
            or zeroth.get(piexif.ImageIFD.DateTime)
            or b""
        )
        if isinstance(dt, bytes):
            dt = dt.decode("utf-8", errors="ignore").strip("\x00 ")
        summary["date_taken"] = str(dt)

        lat = gps_ifd.get(piexif.GPSIFD.GPSLatitude)
        lat_ref = gps_ifd.get(piexif.GPSIFD.GPSLatitudeRef, b"N")
        lon = gps_ifd.get(piexif.GPSIFD.GPSLongitude)
        lon_ref = gps_ifd.get(piexif.GPSIFD.GPSLongitudeRef, b"E")
        if isinstance(lat_ref, bytes):
            lat_ref = lat_ref.decode("ascii", errors="ignore").strip("\x00 ") or "N"
        if isinstance(lon_ref, bytes):
            lon_ref = lon_ref.decode("ascii", errors="ignore").strip("\x00 ") or "E"
        if lat and lon:
            lat_dec = _gps_to_decimal(lat, lat_ref)
            lon_dec = _gps_to_decimal(lon, lon_ref)
            if lat_dec is not None and lon_dec is not None:
                summary["gps_coords"] = f"{lat_dec}, {lon_dec}"
    except Exception:
        pass
    return summary


def build_enriched_exif_bytes(
    raw_exif_bytes: Optional[bytes],
    takeout_meta: Dict,
    fallback_dt_str: Optional[str],
) -> Tuple[Optional[bytes], str]:
    notes = []
    try:
        exif_dict = (
            piexif.load(raw_exif_bytes)
            if raw_exif_bytes
            else {"0th": {}, "Exif": {}, "GPS": {}, "1st": {}, "thumbnail": None}
        )
    except Exception:
        exif_dict = {"0th": {}, "Exif": {}, "GPS": {}, "1st": {}, "thumbnail": None}

    exif_dict["thumbnail"] = None
    exif_dict["1st"] = {}
    exif_dict.setdefault("0th", {})
    exif_dict.setdefault("Exif", {})
    exif_dict.setdefault("GPS", {})
    exif_dict["0th"][piexif.ImageIFD.Orientation] = 1
    exif_dict["Exif"].pop(piexif.ExifIFD.MakerNote, None)
    scene = exif_dict["Exif"].get(piexif.ExifIFD.SceneType)
    if isinstance(scene, int):
        exif_dict["Exif"][piexif.ExifIFD.SceneType] = bytes([scene])

    has_date = bool(exif_dict["Exif"].get(piexif.ExifIFD.DateTimeOriginal))
    if not has_date:
        if takeout_meta.get("exif_datetime"):
            dt_bytes = takeout_meta["exif_datetime"].encode("utf-8")
            exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal] = dt_bytes
            exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized] = dt_bytes
            exif_dict["0th"][piexif.ImageIFD.DateTime] = dt_bytes
            notes.append("Takeout Date")
        elif fallback_dt_str:
            dt_bytes = fallback_dt_str.encode("utf-8")
            exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal] = dt_bytes
            exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized] = dt_bytes
            exif_dict["0th"][piexif.ImageIFD.DateTime] = dt_bytes
            notes.append("Filename Date")

    has_gps = bool(exif_dict["GPS"].get(piexif.GPSIFD.GPSLatitude))
    if not has_gps and "lat" in takeout_meta and "lon" in takeout_meta:
        lat = takeout_meta["lat"]
        lon = takeout_meta["lon"]
        exif_dict["GPS"][piexif.GPSIFD.GPSLatitudeRef] = b"N" if lat >= 0 else b"S"
        exif_dict["GPS"][piexif.GPSIFD.GPSLatitude] = _deg_to_dms_rational(lat)
        exif_dict["GPS"][piexif.GPSIFD.GPSLongitudeRef] = b"E" if lon >= 0 else b"W"
        exif_dict["GPS"][piexif.GPSIFD.GPSLongitude] = _deg_to_dms_rational(lon)
        notes.append("Takeout GPS")

    has_any = bool(exif_dict["Exif"] or exif_dict["GPS"] or len(exif_dict["0th"]) > 1)
    if not has_any:
        return None, ""
    return piexif.dump(exif_dict), " + ".join(notes)


def probe_video_info(path: Path) -> Tuple[int, int, str]:
    ffmpeg = get_ffmpeg_binary()
    if not ffmpeg:
        return 0, 0, ""
    try:
        proc = subprocess.run(
            [ffmpeg, "-hide_banner", "-i", str(path)],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
        stderr = proc.stderr or ""
        w, h = 0, 0
        for line in stderr.splitlines():
            if "Video:" in line:
                m = re.search(r"(\d{2,5})x(\d{2,5})", line)
                if m:
                    w, h = int(m.group(1)), int(m.group(2))
                    break
        dur_match = re.search(r"Duration:\s*([0-9:.]+)", stderr)
        dur = dur_match.group(1) if dur_match else ""
        return w, h, dur
    except Exception:
        return 0, 0, ""


def resolve_unique_output_path(output_dir: Path, stem: str, suffix: str) -> Path:
    candidate = output_dir / f"{stem}{suffix}"
    if not candidate.exists():
        return candidate
    idx = 1
    while True:
        candidate = output_dir / f"{stem}_{idx}{suffix}"
        if not candidate.exists():
            return candidate
        idx += 1


def trigger_android_media_scan(file_path: Path) -> None:
    scanner = shutil.which("termux-media-scan")
    if scanner:
        try:
            subprocess.Popen(
                [scanner, str(file_path)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return
        except Exception:
            pass
    am_bin = shutil.which("am")
    if am_bin and str(file_path).startswith("/storage/"):
        try:
            subprocess.Popen(
                [
                    am_bin,
                    "broadcast",
                    "-a",
                    "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d",
                    f"file://{file_path}",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            pass


def inspect_media_file(path: Path, root_dir: Optional[Path] = None) -> Dict:
    stat = path.stat()
    ext = path.suffix.lower()
    rel_name = (
        path.relative_to(root_dir).as_posix()
        if root_dir and str(path).startswith(str(root_dir))
        else path.name
    )
    info = {
        "name": path.name,
        "rel_path": rel_name,
        "size_bytes": stat.st_size,
        "modified_at": stat.st_mtime,
        "media_type": "video" if ext in VIDEO_EXTENSIONS else "photo",
        "width": 0,
        "height": 0,
        "megapixels": 0.0,
        "camera_model": "",
        "date_taken": "",
        "gps_coords": "",
        "duration": "",
    }
    if ext in PHOTO_EXTENSIONS:
        try:
            with Image.open(path) as im:
                raw_exif = im.info.get("exif")
                w, h = im.size
                if raw_exif:
                    try:
                        orientation = piexif.load(raw_exif).get("0th", {}).get(
                            piexif.ImageIFD.Orientation, 1
                        )
                        if orientation in (5, 6, 7, 8):
                            w, h = h, w
                    except Exception:
                        pass
                info["width"] = w
                info["height"] = h
                info["megapixels"] = round((w * h) / 1_000_000.0, 2)
                info.update(inspect_photo_exif(raw_exif))
        except Exception:
            pass
    elif ext in VIDEO_EXTENSIONS:
        w, h, dur = probe_video_info(path)
        info["width"] = w
        info["height"] = h
        info["megapixels"] = round((w * h) / 1_000_000.0, 2) if w and h else 0.0
        info["duration"] = dur
    return info


def process_media_file(
    source_path: Path,
    settings: NasSettings,
    db: Optional[HistoryDatabase] = None,
) -> Dict:
    """
    Resolve Per-Folder Profile (/Incoming/StorageSaver, /Incoming/Original, /Incoming/<Family>),
    verify decode health, deduplicate via SHA-256, merge Takeout JSON / filename dates,
    convert or pass through into Output (or Output/<Subfolder>), and delete high-res original.
    """
    t0 = time.perf_counter()
    if not source_path.is_file():
        raise FileNotFoundError(f"Source file missing: {source_path}")

    incoming_root = Path(settings.incoming_dir).expanduser().resolve()
    base_output_dir = Path(settings.output_dir).expanduser().resolve()

    # Check if file arrived inside a configured Per-Folder Profile (/Incoming/<Profile>/...)
    effective_settings = copy.deepcopy(settings)
    profile_name = ""
    rel_parts: Tuple[str, ...] = ()
    try:
        rel_parent = source_path.parent.resolve().relative_to(incoming_root)
        if str(rel_parent) != ".":
            rel_parts = rel_parent.parts
    except ValueError:
        rel_parts = ()

    remaining_album_parts = rel_parts
    if rel_parts:
        matched_prof = settings.match_folder_profile(rel_parts[0])
        if matched_prof is not None:
            profile_name = str(matched_prof["name"])
            effective_settings.storage_saver_enabled = (
                matched_prof.get("mode") != "original_passthrough"
            )
            out_sub = sanitize_folder_segment(str(matched_prof.get("output_subfolder", "")))
            if out_sub:
                effective_settings.output_dir = str(base_output_dir / out_sub)
            remaining_album_parts = rel_parts[1:]

    target_output_dir = Path(effective_settings.output_dir).expanduser().resolve()
    target_output_dir.mkdir(parents=True, exist_ok=True)

    orig_stat = source_path.stat()
    orig_bytes = orig_stat.st_size
    effective_mtime = orig_stat.st_mtime
    ext = source_path.suffix.lower()

    sidecar_path = (
        find_takeout_sidecar(source_path) if effective_settings.merge_takeout_json else None
    )
    takeout_meta = read_takeout_sidecar(sidecar_path) if sidecar_path else {}
    parsed_fn = (
        parse_datetime_from_filename(source_path.name)
        if effective_settings.restore_exif_from_filename
        else None
    )
    if takeout_meta.get("timestamp"):
        effective_mtime = float(takeout_meta["timestamp"])
    elif parsed_fn:
        effective_mtime = parsed_fn[1]

    # Validate file integrity FIRST so corrupted transfers go to Quarantine instead of seen_hashes!
    if ext in PHOTO_EXTENSIONS:
        with Image.open(source_path) as check_src:
            check_src.load()
    elif ext in VIDEO_EXTENSIONS and get_ffmpeg_binary() is not None:
        vw, vh, _ = probe_video_info(source_path)
        if vw <= 0 or vh <= 0:
            raise ValueError(f"Corrupted or unreadable video stream in {source_path.name}")

    # Compute SHA-256 content hash for zero-duplicate protection
    file_hash = compute_file_sha256(source_path)
    if effective_settings.skip_duplicates and db is not None:
        existing_out = db.lookup_hash(file_hash)
        if existing_out:
            deleted = False
            if effective_settings.delete_originals:
                source_path.unlink(missing_ok=True)
                if sidecar_path and sidecar_path.exists():
                    sidecar_path.unlink(missing_ok=True)
                deleted = not source_path.exists()
            return {
                "original_filename": source_path.name,
                "output_filename": existing_out,
                "output_path": str(base_output_dir / existing_out),
                "media_type": "video" if ext in VIDEO_EXTENSIONS else "photo",
                "mode": "duplicate_skipped",
                "profile_name": profile_name,
                "orig_width": 0,
                "orig_height": 0,
                "orig_mp": 0.0,
                "orig_bytes": orig_bytes,
                "out_width": 0,
                "out_height": 0,
                "out_mp": 0.0,
                "out_bytes": 0,
                "saved_bytes": orig_bytes,
                "saved_pct": 100.0,
                "exif_preserved": True,
                "camera_model": "",
                "date_taken": "",
                "gps_coords": "",
                "metadata_note": "SHA-256 Duplicate Skipped",
                "original_deleted": deleted,
                "duration_ms": int((time.perf_counter() - t0) * 1000),
                "status": "completed",
                "created_at": time.time(),
            }

    output_stem = source_path.stem
    if effective_settings.prefix_subfolder_name and remaining_album_parts:
        prefix = "_".join(remaining_album_parts)
        output_stem = f"{prefix}_{output_stem}"

    if ext in PHOTO_EXTENSIONS:
        result = _process_photo(
            source_path=source_path,
            output_dir=target_output_dir,
            output_stem=output_stem,
            orig_bytes=orig_bytes,
            effective_mtime=effective_mtime,
            takeout_meta=takeout_meta,
            fallback_dt_str=parsed_fn[0] if parsed_fn else None,
            settings=effective_settings,
        )
    elif ext in VIDEO_EXTENSIONS:
        result = _process_video(
            source_path=source_path,
            output_dir=target_output_dir,
            output_stem=output_stem,
            orig_bytes=orig_bytes,
            effective_mtime=effective_mtime,
            metadata_note="Takeout Date" if takeout_meta.get("timestamp") else (
                "Filename Date" if parsed_fn else ""
            ),
            settings=effective_settings,
        )
    else:
        raise ValueError(f"Unsupported file format: {ext}")

    output_file = Path(result["output_path"])
    if not output_file.is_file() or output_file.stat().st_size == 0:
        raise RuntimeError("Output verification failed; keeping original safe in Incoming.")

    rel_out_name = (
        output_file.relative_to(base_output_dir).as_posix()
        if str(output_file).startswith(str(base_output_dir))
        else output_file.name
    )
    result["output_filename"] = rel_out_name
    result["profile_name"] = profile_name

    if db is not None:
        db.record_hash(file_hash, rel_out_name)

    original_deleted = False
    if effective_settings.delete_originals and source_path.exists():
        source_path.unlink()
        if sidecar_path and sidecar_path.exists():
            sidecar_path.unlink(missing_ok=True)
        original_deleted = not source_path.exists()

    trigger_android_media_scan(output_file)

    duration_ms = int((time.perf_counter() - t0) * 1000)
    out_bytes = result["out_bytes"]
    saved_bytes = orig_bytes - out_bytes
    saved_pct = round((saved_bytes / orig_bytes) * 100.0, 1) if orig_bytes > 0 else 0.0

    result.update(
        {
            "saved_bytes": saved_bytes,
            "saved_pct": saved_pct,
            "original_deleted": original_deleted,
            "duration_ms": duration_ms,
            "status": "completed",
            "created_at": time.time(),
        }
    )
    return result


def _process_photo(
    source_path: Path,
    output_dir: Path,
    output_stem: str,
    orig_bytes: int,
    effective_mtime: float,
    takeout_meta: Dict,
    fallback_dt_str: Optional[str],
    settings: NasSettings,
) -> Dict:
    with Image.open(source_path) as raw_im:
        raw_exif = raw_im.info.get("exif")
        enriched_exif, meta_note = build_enriched_exif_bytes(
            raw_exif, takeout_meta, fallback_dt_str
        )
        exif_meta = inspect_photo_exif(enriched_exif or raw_exif)

        if not settings.storage_saver_enabled:
            im_oriented = ImageOps.exif_transpose(raw_im)
            orig_w, orig_h = im_oriented.size
            final_path = resolve_unique_output_path(
                output_dir, output_stem, source_path.suffix
            )
            tmp_out = output_dir / f".converting_{uuid.uuid4().hex}{source_path.suffix}"
            try:
                if meta_note and source_path.suffix.lower() in (".jpg", ".jpeg") and enriched_exif:
                    shutil.copy2(source_path, tmp_out)
                    piexif.insert(enriched_exif, str(tmp_out))
                else:
                    shutil.copy2(source_path, tmp_out)
                os.replace(tmp_out, final_path)
            finally:
                if tmp_out.exists():
                    tmp_out.unlink(missing_ok=True)
            os.utime(final_path, (effective_mtime, effective_mtime))
            out_bytes = final_path.stat().st_size
            return {
                "original_filename": source_path.name,
                "output_filename": final_path.name,
                "output_path": str(final_path),
                "media_type": "photo",
                "mode": "original_passthrough",
                "orig_width": orig_w,
                "orig_height": orig_h,
                "orig_mp": round((orig_w * orig_h) / 1_000_000.0, 2),
                "orig_bytes": orig_bytes,
                "out_width": orig_w,
                "out_height": orig_h,
                "out_mp": round((orig_w * orig_h) / 1_000_000.0, 2),
                "out_bytes": out_bytes,
                "exif_preserved": bool(enriched_exif or raw_exif),
                "metadata_note": meta_note,
                **exif_meta,
            }

        im = ImageOps.exif_transpose(raw_im)
        orig_w, orig_h = im.size
        target_w, target_h, was_resized = compute_target_dimensions(
            orig_w, orig_h, settings.max_megapixels
        )

        if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
            bg = Image.new("RGB", im.size, (255, 255, 255))
            alpha = im.convert("RGBA")
            bg.paste(alpha, mask=alpha.split()[-1])
            im_rgb = bg
        elif im.mode != "RGB":
            im_rgb = im.convert("RGB")
        else:
            im_rgb = im

        im_out = (
            im_rgb.resize((target_w, target_h), Image.Resampling.LANCZOS)
            if was_resized
            else im_rgb
        )

        final_path = resolve_unique_output_path(output_dir, output_stem, ".jpg")
        tmp_out = output_dir / f".converting_{uuid.uuid4().hex}.jpg"
        try:
            save_kwargs = {
                "format": "JPEG",
                "quality": settings.jpeg_quality,
                "optimize": True,
                "progressive": True,
                "subsampling": "4:2:0",
            }
            if enriched_exif:
                save_kwargs["exif"] = enriched_exif
            im_out.save(tmp_out, **save_kwargs)

            if (
                not was_resized
                and settings.avoid_recompressing_small_jpegs
                and source_path.suffix.lower() in (".jpg", ".jpeg")
                and tmp_out.stat().st_size >= orig_bytes
            ):
                shutil.copy2(source_path, tmp_out)
                if meta_note and enriched_exif:
                    piexif.insert(enriched_exif, str(tmp_out))

            with Image.open(tmp_out) as check_im:
                check_im.verify()

            os.replace(tmp_out, final_path)
        finally:
            if tmp_out.exists():
                tmp_out.unlink(missing_ok=True)

        os.utime(final_path, (effective_mtime, effective_mtime))
        out_bytes = final_path.stat().st_size
        return {
            "original_filename": source_path.name,
            "output_filename": final_path.name,
            "output_path": str(final_path),
            "media_type": "photo",
            "mode": "storage_saver",
            "orig_width": orig_w,
            "orig_height": orig_h,
            "orig_mp": round((orig_w * orig_h) / 1_000_000.0, 2),
            "orig_bytes": orig_bytes,
            "out_width": target_w,
            "out_height": target_h,
            "out_mp": round((target_w * target_h) / 1_000_000.0, 2),
            "out_bytes": out_bytes,
            "exif_preserved": True,
            "metadata_note": meta_note,
            **exif_meta,
        }


def _process_video(
    source_path: Path,
    output_dir: Path,
    output_stem: str,
    orig_bytes: int,
    effective_mtime: float,
    metadata_note: str,
    settings: NasSettings,
) -> Dict:
    orig_w, orig_h, _ = probe_video_info(source_path)
    ffmpeg = get_ffmpeg_binary()
    max_short = settings.max_video_short_side
    short_side = min(orig_w, orig_h) if (orig_w and orig_h) else 0

    should_transcode = (
        settings.storage_saver_enabled
        and settings.compress_videos
        and ffmpeg is not None
        and (short_side > max_short or source_path.suffix.lower() not in (".mp4", ".m4v"))
    )

    if not should_transcode:
        final_path = resolve_unique_output_path(
            output_dir, output_stem, source_path.suffix
        )
        tmp_out = output_dir / f".converting_{uuid.uuid4().hex}{source_path.suffix}"
        try:
            shutil.copy2(source_path, tmp_out)
            os.replace(tmp_out, final_path)
        finally:
            if tmp_out.exists():
                tmp_out.unlink(missing_ok=True)
        os.utime(final_path, (effective_mtime, effective_mtime))
        mode = "storage_saver" if settings.storage_saver_enabled else "original_passthrough"
        return {
            "original_filename": source_path.name,
            "output_filename": final_path.name,
            "output_path": str(final_path),
            "media_type": "video",
            "mode": mode,
            "orig_width": orig_w,
            "orig_height": orig_h,
            "orig_mp": round((orig_w * orig_h) / 1_000_000.0, 2) if orig_w and orig_h else 0.0,
            "orig_bytes": orig_bytes,
            "out_width": orig_w,
            "out_height": orig_h,
            "out_mp": round((orig_w * orig_h) / 1_000_000.0, 2) if orig_w and orig_h else 0.0,
            "out_bytes": final_path.stat().st_size,
            "exif_preserved": True,
            "camera_model": "",
            "date_taken": "",
            "gps_coords": "",
            "metadata_note": metadata_note,
        }

    final_path = resolve_unique_output_path(output_dir, output_stem, ".mp4")
    tmp_out = output_dir / f".converting_{uuid.uuid4().hex}.mp4"
    scale_expr = (
        f"scale='if(gt(iw,ih),-2,min({max_short},iw))':"
        f"'if(gt(iw,ih),min({max_short},ih),-2)'"
    )
    try:
        cmd = [
            ffmpeg,
            "-y",
            "-hide_banner",
            "-i",
            str(source_path),
            "-map_metadata",
            "0",
            "-vf",
            scale_expr,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "23",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            str(tmp_out),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600, check=False)
        if proc.returncode != 0 or not tmp_out.is_file() or tmp_out.stat().st_size == 0:
            raise RuntimeError(f"FFmpeg video transcode failed: {proc.stderr[-300:]}")
        os.replace(tmp_out, final_path)
    finally:
        if tmp_out.exists():
            tmp_out.unlink(missing_ok=True)

    os.utime(final_path, (effective_mtime, effective_mtime))
    out_w, out_h, _ = probe_video_info(final_path)
    out_bytes = final_path.stat().st_size
    return {
        "original_filename": source_path.name,
        "output_filename": final_path.name,
        "output_path": str(final_path),
        "media_type": "video",
        "mode": "storage_saver",
        "orig_width": orig_w,
        "orig_height": orig_h,
        "orig_mp": round((orig_w * orig_h) / 1_000_000.0, 2) if orig_w and orig_h else 0.0,
        "orig_bytes": orig_bytes,
        "out_width": out_w,
        "out_height": out_h,
        "out_mp": round((out_w * out_h) / 1_000_000.0, 2) if out_w and out_h else 0.0,
        "out_bytes": out_bytes,
        "exif_preserved": True,
        "camera_model": "",
        "date_taken": "",
        "gps_coords": "",
        "metadata_note": metadata_note,
    }
