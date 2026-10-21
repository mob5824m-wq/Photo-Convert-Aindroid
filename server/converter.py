from __future__ import annotations

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

from server.config import NasSettings

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
}


def get_ffmpeg_binary() -> Optional[str]:
    sys_ff = shutil.which("ffmpeg")
    if sys_ff:
        return sys_ff
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


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
    """Compute Google Photos Storage Saver target dimensions (width * height <= max_mp)."""
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


def build_clean_exif_bytes(raw_exif_bytes: Optional[bytes]) -> Optional[bytes]:
    """Preserve Date/Time, GPS, Camera Make/Model/Lens tags cleanly while normalizing Orientation=1."""
    if not raw_exif_bytes:
        return None
    try:
        exif_dict = piexif.load(raw_exif_bytes)
        exif_dict["thumbnail"] = None
        exif_dict["1st"] = {}
        if "0th" not in exif_dict or not isinstance(exif_dict["0th"], dict):
            exif_dict["0th"] = {}
        # Pixels are normalized upright via ImageOps.exif_transpose(), so Orientation = 1
        exif_dict["0th"][piexif.ImageIFD.Orientation] = 1
        if "Exif" in exif_dict and isinstance(exif_dict["Exif"], dict):
            # Remove oversized proprietary MakerNote if present so JPEG APP1 never overflows 64KB
            exif_dict["Exif"].pop(piexif.ExifIFD.MakerNote, None)
            # Sanitize wrong-type SceneType if integer instead of bytes
            scene = exif_dict["Exif"].get(piexif.ExifIFD.SceneType)
            if isinstance(scene, int):
                exif_dict["Exif"][piexif.ExifIFD.SceneType] = bytes([scene])
        return piexif.dump(exif_dict)
    except Exception:
        return raw_exif_bytes


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
    """When run inside Android / Termux, notify MediaStore so Google Photos detects the new file immediately."""
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


def inspect_media_file(path: Path) -> Dict:
    stat = path.stat()
    ext = path.suffix.lower()
    info = {
        "name": path.name,
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
                # Account for rotated EXIF orientation when reporting displayed WxH
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


def process_media_file(source_path: Path, settings: NasSettings) -> Dict:
    """
    Atomically convert or move `source_path` into `settings.output_dir`, verify integrity,
    preserve EXIF/timestamps, and delete the original file from `settings.incoming_dir`.
    """
    t0 = time.perf_counter()
    if not source_path.is_file():
        raise FileNotFoundError(f"Source file missing: {source_path}")

    output_dir = Path(settings.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    orig_stat = source_path.stat()
    orig_bytes = orig_stat.st_size
    orig_mtime = orig_stat.st_mtime
    ext = source_path.suffix.lower()

    if ext in PHOTO_EXTENSIONS:
        result = _process_photo(source_path, output_dir, orig_bytes, orig_mtime, settings)
    elif ext in VIDEO_EXTENSIONS:
        result = _process_video(source_path, output_dir, orig_bytes, orig_mtime, settings)
    else:
        raise ValueError(f"Unsupported file format: {ext}")

    output_file = Path(result["output_path"])
    # Verify output integrity strictly BEFORE deleting higher-res original!
    if not output_file.is_file() or output_file.stat().st_size == 0:
        raise RuntimeError("Output verification failed; keeping original safe in Incoming.")

    original_deleted = False
    if settings.delete_originals and source_path.exists():
        source_path.unlink()
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
    orig_bytes: int,
    orig_mtime: float,
    settings: NasSettings,
) -> Dict:
    with Image.open(source_path) as raw_im:
        raw_exif = raw_im.info.get("exif")
        exif_meta = inspect_photo_exif(raw_exif)

        if not settings.storage_saver_enabled:
            # Direct Original Quality Pass-through mode (e.g., Pixel 1 mode or conversion disabled)
            im_oriented = ImageOps.exif_transpose(raw_im)
            orig_w, orig_h = im_oriented.size
            final_path = resolve_unique_output_path(
                output_dir, source_path.stem, source_path.suffix
            )
            tmp_out = output_dir / f".converting_{uuid.uuid4().hex}{source_path.suffix}"
            try:
                shutil.copy2(source_path, tmp_out)
                if tmp_out.stat().st_size != orig_bytes:
                    raise RuntimeError("Byte-for-byte copy size mismatch")
                os.replace(tmp_out, final_path)
            finally:
                if tmp_out.exists():
                    tmp_out.unlink(missing_ok=True)
            os.utime(final_path, (orig_mtime, orig_mtime))
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
                "out_bytes": orig_bytes,
                "exif_preserved": bool(raw_exif),
                **exif_meta,
            }

        # Storage Saver Mode: normalize EXIF orientation, downscale >16MP -> <=16MP, encode high-efficiency JPEG
        im = ImageOps.exif_transpose(raw_im)
        orig_w, orig_h = im.size
        target_w, target_h, was_resized = compute_target_dimensions(
            orig_w, orig_h, settings.max_megapixels
        )

        # Smart guard: if already <= target MP JPEG and user wants no wasteful re-encode if re-encode grows size
        clean_exif = build_clean_exif_bytes(raw_exif)

        if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
            bg = Image.new("RGB", im.size, (255, 255, 255))
            alpha = im.convert("RGBA")
            bg.paste(alpha, mask=alpha.split()[-1])
            im_rgb = bg
        elif im.mode != "RGB":
            im_rgb = im.convert("RGB")
        else:
            im_rgb = im

        if was_resized:
            im_out = im_rgb.resize((target_w, target_h), Image.Resampling.LANCZOS)
        else:
            im_out = im_rgb

        final_path = resolve_unique_output_path(output_dir, source_path.stem, ".jpg")
        tmp_out = output_dir / f".converting_{uuid.uuid4().hex}.jpg"
        try:
            save_kwargs = {
                "format": "JPEG",
                "quality": settings.jpeg_quality,
                "optimize": True,
                "progressive": True,
                "subsampling": "4:2:0",
            }
            if clean_exif:
                save_kwargs["exif"] = clean_exif
            im_out.save(tmp_out, **save_kwargs)

            # If input was already a <=16MP JPEG and re-encoding turned out larger than original, keep original bytes!
            if (
                not was_resized
                and settings.avoid_recompressing_small_jpegs
                and source_path.suffix.lower() in (".jpg", ".jpeg")
                and tmp_out.stat().st_size >= orig_bytes
            ):
                shutil.copy2(source_path, tmp_out)

            # Verify valid image decode before touching source
            with Image.open(tmp_out) as check_im:
                check_im.verify()

            os.replace(tmp_out, final_path)
        finally:
            if tmp_out.exists():
                tmp_out.unlink(missing_ok=True)

        os.utime(final_path, (orig_mtime, orig_mtime))
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
            **exif_meta,
        }


def _process_video(
    source_path: Path,
    output_dir: Path,
    orig_bytes: int,
    orig_mtime: float,
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
            output_dir, source_path.stem, source_path.suffix
        )
        tmp_out = output_dir / f".converting_{uuid.uuid4().hex}{source_path.suffix}"
        try:
            shutil.copy2(source_path, tmp_out)
            os.replace(tmp_out, final_path)
        finally:
            if tmp_out.exists():
                tmp_out.unlink(missing_ok=True)
        os.utime(final_path, (orig_mtime, orig_mtime))
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
        }

    final_path = resolve_unique_output_path(output_dir, source_path.stem, ".mp4")
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

    os.utime(final_path, (orig_mtime, orig_mtime))
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
    }
