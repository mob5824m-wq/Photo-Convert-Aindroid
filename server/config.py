from __future__ import annotations

import copy
import json
import os
import re
import socket
import subprocess
import threading
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List


DEFAULT_FOLDER_PROFILES: List[Dict] = [
    {
        "name": "StorageSaver",
        "folder_name": "StorageSaver",
        "mode": "storage_saver",
        "storage_saver_enabled": True,
        "max_megapixels": 16.0,
        "jpeg_quality": 85,
        "compress_videos": True,
        "output_subfolder": "",
        "description": "Shrinks >16 MP photos to 16 MP JPEG (85%) & >1080p video to 1080p MP4, deletes full-res originals.",
    },
    {
        "name": "Original",
        "folder_name": "Original",
        "mode": "original_passthrough",
        "storage_saver_enabled": False,
        "max_megapixels": 16.0,
        "jpeg_quality": 95,
        "compress_videos": False,
        "output_subfolder": "",
        "description": "Keeps 100% full-resolution originals untouched in Output and removes staging copies.",
    },
    {
        "name": "Family",
        "folder_name": "Family",
        "mode": "storage_saver",
        "storage_saver_enabled": True,
        "max_megapixels": 16.0,
        "jpeg_quality": 85,
        "compress_videos": True,
        "output_subfolder": "Family",
        "description": "Converts to Storage Saver and saves into dedicated Output/Family device folder.",
    },
]


PIXEL_PROFILES: Dict[str, dict] = {
    "pixel_1": {
        "id": "pixel_1",
        "name": "Pixel / Pixel XL (Gen 1 · 2016)",
        "codenames": ["sailfish", "marlin"],
        "android_versions": "Android 8.0 – 10 (API 26–29)",
        "google_photos_tier": "Unlimited Original Quality",
        "recommended_saver": False,
        "notes": "Pixel 1 backs up unlimited Original Quality forever. Drop files in /Incoming/Original or toggle Storage Saver ON to save local 32GB/128GB space.",
    },
    "pixel_2": {
        "id": "pixel_2",
        "name": "Pixel 2 / 2 XL (2017)",
        "codenames": ["walleye", "taimen"],
        "android_versions": "Android 8.0 – 11 (API 26–30)",
        "google_photos_tier": "Unlimited Storage Saver",
        "recommended_saver": True,
        "notes": "Unlimited free Storage Saver backups (16MP photos / 1080p video). Pre-converting frees up to 90% of local storage before Google Photos syncs.",
    },
    "pixel_3": {
        "id": "pixel_3",
        "name": "Pixel 3 / 3 XL / 3a / 3a XL (2018–2019)",
        "codenames": ["blueline", "crosshatch", "sargo", "bonito"],
        "android_versions": "Android 9 – 12 (API 28–31)",
        "google_photos_tier": "Unlimited Storage Saver",
        "recommended_saver": True,
        "notes": "Unlimited free Storage Saver backups. Pre-converting prevents 50MP–200MP Android bursts from filling 64GB storage.",
    },
    "pixel_4": {
        "id": "pixel_4",
        "name": "Pixel 4 / 4 XL / 4a (2019–2020)",
        "codenames": ["flame", "coral", "sunfish"],
        "android_versions": "Android 10 – 13 (API 29–33)",
        "google_photos_tier": "Unlimited Storage Saver",
        "recommended_saver": True,
        "notes": "6GB RAM & fast UFS storage make 50MP photo & 4K->1080p video conversion smooth and cool.",
    },
    "pixel_5": {
        "id": "pixel_5",
        "name": "Pixel 4a (5G) / Pixel 5 (2020)",
        "codenames": ["bramble", "redfin"],
        "android_versions": "Android 11 – 14 (API 30–34)",
        "google_photos_tier": "Unlimited Storage Saver",
        "recommended_saver": True,
        "notes": "Last Google Pixel line with unlimited free Storage Saver backups. 8GB RAM + 128GB storage makes an ideal 24/7 home photo NAS.",
    },
}


def sanitize_folder_segment(raw: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._ -]+", "", (raw or "").strip())
    return cleaned.strip(". /\\")


def detect_runtime_root() -> Path:
    env_dir = os.environ.get("PIXEL_NAS_DATA_DIR")
    if env_dir:
        return Path(env_dir).resolve()
    android_shared = Path("/storage/emulated/0")
    if android_shared.is_dir() and os.access(android_shared, os.W_OK):
        return android_shared / "PixelNAS"
    termux_shared = Path.home() / "storage" / "shared"
    if termux_shared.is_dir() and os.access(termux_shared, os.W_OK):
        return termux_shared / "PixelNAS"
    return (Path(__file__).resolve().parent.parent / "runtime_data").resolve()


def detect_android_pixel_profile() -> str:
    try:
        proc = subprocess.run(
            ["getprop", "ro.product.device"],
            capture_output=True,
            text=True,
            timeout=1.5,
            check=False,
        )
        codename = proc.stdout.strip().lower()
        for key, profile in PIXEL_PROFILES.items():
            if codename in profile["codenames"]:
                return key
    except Exception:
        pass
    return "pixel_5"


@dataclass
class NasSettings:
    device_profile: str = field(default_factory=detect_android_pixel_profile)
    storage_saver_enabled: bool = True
    delete_originals: bool = True
    auto_watch_enabled: bool = True
    compress_videos: bool = True
    avoid_recompressing_small_jpegs: bool = True
    skip_duplicates: bool = True
    recursive_subfolders: bool = True
    prefix_subfolder_name: bool = True
    restore_exif_from_filename: bool = True
    merge_takeout_json: bool = True
    quarantine_enabled: bool = True
    quarantine_max_retries: int = 2
    auto_purge_output_days: int = 0
    min_free_storage_gb: float = 0.0
    max_megapixels: float = 16.0
    jpeg_quality: int = 85
    max_video_short_side: int = 1080
    thermal_limit_celsius: float = 41.0
    incoming_dir: str = ""
    output_dir: str = ""
    quarantine_dir: str = ""
    folder_profiles: List[Dict] = field(
        default_factory=lambda: copy.deepcopy(DEFAULT_FOLDER_PROFILES)
    )
    ftp_enabled: bool = True
    ftp_port: int = 2121
    smb_enabled: bool = True
    smb_port: int = 4445
    smb_share_name: str = "PixelNAS"
    auth_enabled: bool = False
    auth_username: str = "pixel"
    auth_password: str = ""

    def sanitized_dict(self) -> dict:
        data = asdict(self)
        norm_profiles = []
        for p in data.get("folder_profiles") or []:
            fn = p.get("folder_name") or p.get("name") or ""
            is_saver = (
                bool(p["storage_saver_enabled"])
                if "storage_saver_enabled" in p
                else (p.get("mode") != "original_passthrough")
            )
            norm_profiles.append(
                {
                    **p,
                    "name": fn,
                    "folder_name": fn,
                    "mode": "storage_saver" if is_saver else "original_passthrough",
                    "storage_saver_enabled": is_saver,
                    "max_megapixels": float(p.get("max_megapixels", 16.0)),
                    "jpeg_quality": int(p.get("jpeg_quality", 85)),
                    "compress_videos": bool(p.get("compress_videos", is_saver)),
                }
            )
        data["folder_profiles"] = norm_profiles
        data["profile_details"] = PIXEL_PROFILES.get(
            self.device_profile, PIXEL_PROFILES["pixel_5"]
        )
        return data

    def match_folder_profile(self, subfolder_name: str) -> Dict | None:
        needle = (subfolder_name or "").strip().lower()
        if not needle:
            return None
        for prof in self.folder_profiles:
            cand = str(prof.get("folder_name") or prof.get("name") or "").strip().lower()
            if cand == needle:
                return prof
        return None


class SettingsManager:
    def __init__(self, base_dir: Path | None = None) -> None:
        self._lock = threading.RLock()
        self.base_dir = (base_dir or detect_runtime_root()).resolve()
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self.settings_file = self.base_dir / "settings.json"
        self.db_file = self.base_dir / "history.db"

        default_incoming = self.base_dir / "Incoming"
        default_quarantine = self.base_dir / "Quarantine"
        android_dcim = Path("/storage/emulated/0/DCIM/PixelNAS")
        if android_dcim.parent.is_dir() and os.access(android_dcim.parent, os.W_OK):
            default_output = android_dcim
        else:
            default_output = self.base_dir / "GooglePhotos_Output"

        self._settings = NasSettings(
            incoming_dir=str(default_incoming),
            output_dir=str(default_output),
            quarantine_dir=str(default_quarantine),
        )
        self._load()
        if not self._settings.quarantine_dir:
            self._settings.quarantine_dir = str(default_quarantine)
            self._save()
        self.ensure_directories()

    def _load(self) -> None:
        if not self.settings_file.is_file():
            self._save()
            return
        try:
            raw = json.loads(self.settings_file.read_text(encoding="utf-8"))
            valid_fields = {f.name for f in self._settings.__dataclass_fields__.values()}
            filtered = {k: v for k, v in raw.items() if k in valid_fields}
            self._settings = NasSettings(**filtered)
        except Exception:
            self._save()

    def _save(self) -> None:
        self.settings_file.write_text(
            json.dumps(asdict(self._settings), indent=2),
            encoding="utf-8",
        )

    def ensure_directories(self) -> tuple[Path, Path]:
        with self._lock:
            inc = Path(self._settings.incoming_dir).expanduser().resolve()
            out = Path(self._settings.output_dir).expanduser().resolve()
            quar = Path(
                self._settings.quarantine_dir or (self.base_dir / "Quarantine")
            ).expanduser().resolve()
            if inc == out:
                out = inc.parent / "GooglePhotos_Output"
                self._settings.output_dir = str(out)
                self._save()
            inc.mkdir(parents=True, exist_ok=True)
            out.mkdir(parents=True, exist_ok=True)
            quar.mkdir(parents=True, exist_ok=True)

            # Shield Incoming & Quarantine with .nomedia so Google Photos never scans raw/corrupt files
            for shielded in (inc, quar):
                nomedia = shielded / ".nomedia"
                if not nomedia.exists():
                    nomedia.write_text("", encoding="utf-8")

            # Ensure all configured Per-Folder Profile directories exist inside Incoming & Output
            for prof in self._settings.folder_profiles:
                pname = sanitize_folder_segment(prof.get("name", ""))
                if pname:
                    (inc / pname).mkdir(parents=True, exist_ok=True)
                out_sub = sanitize_folder_segment(prof.get("output_subfolder", ""))
                if out_sub:
                    sub_dir = out / out_sub
                    sub_dir.mkdir(parents=True, exist_ok=True)
                    nm = sub_dir / ".nomedia"
                    if nm.exists():
                        nm.unlink(missing_ok=True)

            nomedia_out = out / ".nomedia"
            if nomedia_out.exists():
                nomedia_out.unlink(missing_ok=True)
            return inc, out

    def quarantine_path(self) -> Path:
        with self._lock:
            p = Path(
                self._settings.quarantine_dir or (self.base_dir / "Quarantine")
            ).expanduser().resolve()
            p.mkdir(parents=True, exist_ok=True)
            return p

    def get(self) -> NasSettings:
        with self._lock:
            return copy.deepcopy(self._settings)

    def update(self, patch: dict) -> NasSettings:
        with self._lock:
            current = asdict(self._settings)
            for key, value in patch.items():
                if key not in current or value is None:
                    continue
                if key == "max_megapixels":
                    value = max(2.0, min(64.0, float(value)))
                elif key == "jpeg_quality":
                    value = max(40, min(98, int(value)))
                elif key == "max_video_short_side":
                    value = int(value)
                elif key == "quarantine_max_retries":
                    value = max(1, min(5, int(value)))
                elif key == "auto_purge_output_days":
                    value = max(0, min(3650, int(value)))
                elif key == "min_free_storage_gb":
                    value = max(0.0, min(500.0, float(value)))
                elif key == "folder_profiles" and isinstance(value, list):
                    normalized = []
                    seen_names = set()
                    for item in value:
                        if not isinstance(item, dict):
                            continue
                        nm = sanitize_folder_segment(
                            str(item.get("folder_name") or item.get("name") or "")
                        )
                        if not nm or nm.lower() in seen_names:
                            continue
                        seen_names.add(nm.lower())
                        if "storage_saver_enabled" in item:
                            is_saver = bool(item.get("storage_saver_enabled"))
                        else:
                            is_saver = item.get("mode") != "original_passthrough"
                        mode = "storage_saver" if is_saver else "original_passthrough"
                        out_sub = sanitize_folder_segment(
                            str(item.get("output_subfolder", ""))
                        )
                        desc = str(item.get("description", "")).strip() or (
                            f"{'Original Quality' if not is_saver else 'Storage Saver (≤16 MP)'}"
                            + (f" → Output/{out_sub}" if out_sub else " → Output")
                        )
                        normalized.append(
                            {
                                "name": nm,
                                "folder_name": nm,
                                "mode": mode,
                                "storage_saver_enabled": is_saver,
                                "max_megapixels": float(item.get("max_megapixels", 16.0)),
                                "jpeg_quality": int(item.get("jpeg_quality", 85)),
                                "compress_videos": bool(item.get("compress_videos", is_saver)),
                                "output_subfolder": out_sub,
                                "description": desc,
                            }
                        )
                    value = normalized
                elif key == "device_profile":
                    if str(value) not in PIXEL_PROFILES:
                        continue
                current[key] = value
            self._settings = NasSettings(**current)
            self.ensure_directories()
            self._save()
            return self.get()


def get_lan_ips() -> List[str]:
    ips: List[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.2)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        if ip and not ip.startswith("127."):
            ips.append(ip)
        s.close()
    except Exception:
        pass
    try:
        hostname_ips = socket.gethostbyname_ex(socket.gethostname())[2]
        for ip in hostname_ips:
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]
