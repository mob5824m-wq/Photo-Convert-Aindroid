from __future__ import annotations

import io
import os
import shutil
import uuid
from contextlib import asynccontextmanager
from functools import lru_cache
from pathlib import Path
from typing import List, Optional
from urllib.parse import quote

import qrcode
import uvicorn
from fastapi import FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps

from server.config import (
    PIXEL_PROFILES,
    SettingsManager,
    get_lan_ips,
    sanitize_folder_segment,
)
from server.converter import (
    PHOTO_EXTENSIONS,
    VIDEO_EXTENSIONS,
    get_ffmpeg_binary,
    inspect_media_file,
    is_supported_media,
)
from server.database import HistoryDatabase
from server.demo_media import generate_sample_batch, seed_corrupted_transfer
from server.ftp_server import EmbeddedFtpServer
from server.smb_server import EmbeddedSmbServer
from server.versioning import read_repo_version
from server.watcher import PipelineWorker, iter_incoming_media, iter_output_media
from server.webdav import create_webdav_router


def create_app(data_dir: Optional[Path] = None) -> FastAPI:
    settings_mgr = SettingsManager(base_dir=data_dir)
    db = HistoryDatabase(settings_mgr.db_file)
    worker = PipelineWorker(settings_mgr, db)
    ftp_server = EmbeddedFtpServer(settings_mgr, worker)
    smb_server = EmbeddedSmbServer(settings_mgr, worker)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        worker.start()
        ftp_server.start_or_reload()
        smb_server.start_or_reload()
        yield
        smb_server.stop()
        ftp_server.stop()
        worker.stop()

    app = FastAPI(
        title="Pixel Photo NAS (Google Photos Storage Saver Relay)",
        version=read_repo_version(),
        lifespan=lifespan,
    )
    app.state.settings_mgr = settings_mgr
    app.state.db = db
    app.state.worker = worker
    app.state.ftp_server = ftp_server
    app.state.smb_server = smb_server

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(create_webdav_router(settings_mgr, worker))

    def _folder_path(folder: str) -> Path:
        inc_dir, out_dir = settings_mgr.ensure_directories()
        if folder == "incoming":
            return inc_dir
        if folder == "output":
            return out_dir
        if folder == "quarantine":
            return settings_mgr.quarantine_path()
        raise HTTPException(
            status_code=400, detail="folder must be 'incoming', 'output', or 'quarantine'"
        )

    @app.get("/api/status")
    def get_status(request: Request):
        cfg = settings_mgr.get()
        inc_dir, out_dir = settings_mgr.ensure_directories()
        quar_dir = settings_mgr.quarantine_path()
        disk = shutil.disk_usage(out_dir)

        inc_files = list(iter_incoming_media(inc_dir, cfg.recursive_subfolders))
        out_files = list(iter_output_media(out_dir))
        quar_files = [
            p for p in quar_dir.iterdir() if p.is_file() and not p.name.startswith(".")
        ]

        lan_ips = get_lan_ips()
        host_header = request.headers.get("x-forwarded-host") or request.headers.get(
            "host", f"{lan_ips[0]}:8080"
        )
        proto = request.headers.get("x-forwarded-proto") or request.url.scheme or "http"
        base_url = f"{proto}://{host_header}"

        return {
            "app_name": "Pixel Photo NAS",
            "version": read_repo_version(),
            "settings": cfg.sanitized_dict(),
            "profiles": PIXEL_PROFILES,
            "worker": worker.status(),
            "stats": db.summary(),
            "folders": {
                "incoming": {
                    "path": str(inc_dir),
                    "file_count": len(inc_files),
                    "total_bytes": sum(p.stat().st_size for p in inc_files),
                    "nomedia_protected": (inc_dir / ".nomedia").exists(),
                },
                "output": {
                    "path": str(out_dir),
                    "file_count": len(out_files),
                    "total_bytes": sum(p.stat().st_size for p in out_files),
                },
                "quarantine": {
                    "path": str(quar_dir),
                    "file_count": len(quar_files),
                    "total_bytes": sum(p.stat().st_size for p in quar_files),
                },
            },
            "storage": {
                "total_bytes": disk.total,
                "used_bytes": disk.used,
                "free_bytes": disk.free,
                "used_pct": round((disk.used / disk.total) * 100.0, 1) if disk.total else 0.0,
            },
            "network": {
                "lan_ips": lan_ips,
                "web_ui_url": base_url,
                "webdav_url": f"{base_url}/webdav/",
                "webdav_incoming_url": f"{base_url}/webdav/Incoming/",
                "webdav_saver_url": f"{base_url}/webdav/Incoming/StorageSaver/",
                "webdav_original_url": f"{base_url}/webdav/Incoming/Original/",
                "webdav_family_url": f"{base_url}/webdav/Incoming/Family/",
                "ftp_enabled": ftp_server.bound_port > 0,
                "ftp_port": ftp_server.bound_port or cfg.ftp_port,
                "ftp_url": f"ftp://{lan_ips[0]}:{ftp_server.bound_port or cfg.ftp_port}/Incoming/",
                "smb_enabled": smb_server.bound_port > 0,
                "smb_port": smb_server.bound_port or cfg.smb_port,
                "smb_share_name": cfg.smb_share_name or "PixelNAS",
                "smb_unc": f"\\\\{lan_ips[0]}\\{cfg.smb_share_name or 'PixelNAS'}",
                "smb_incoming_unc": f"\\\\{lan_ips[0]}\\{cfg.smb_share_name or 'PixelNAS'}\\Incoming",
                "smb_saver_unc": f"\\\\{lan_ips[0]}\\{cfg.smb_share_name or 'PixelNAS'}\\Incoming\\StorageSaver",
                "smb_url": (
                    f"smb://{lan_ips[0]}/{cfg.smb_share_name or 'PixelNAS'}/"
                    if (smb_server.bound_port or cfg.smb_port) == 445
                    else f"smb://{lan_ips[0]}:{smb_server.bound_port or cfg.smb_port}/{cfg.smb_share_name or 'PixelNAS'}/"
                ),
                "video_engine_available": get_ffmpeg_binary() is not None,
            },
        }

    @app.get("/api/settings")
    def get_settings():
        return settings_mgr.get().sanitized_dict()

    @app.post("/api/settings")
    async def update_settings(payload: dict):
        old_cfg = settings_mgr.get()
        updated = settings_mgr.update(payload)
        if (
            old_cfg.ftp_enabled != updated.ftp_enabled
            or old_cfg.ftp_port != updated.ftp_port
            or old_cfg.auth_enabled != updated.auth_enabled
            or old_cfg.auth_username != updated.auth_username
            or old_cfg.auth_password != updated.auth_password
        ):
            ftp_server.start_or_reload()
        if (
            old_cfg.smb_enabled != updated.smb_enabled
            or old_cfg.smb_port != updated.smb_port
            or old_cfg.smb_share_name != updated.smb_share_name
            or old_cfg.auth_enabled != updated.auth_enabled
            or old_cfg.auth_username != updated.auth_username
            or old_cfg.auth_password != updated.auth_password
        ):
            smb_server.start_or_reload()
        if updated.auto_watch_enabled:
            worker.process_all_now()
        return updated.sanitized_dict()

    @app.get("/api/files")
    def list_files(folder: str = Query("output", pattern="^(incoming|output)$")):
        target_dir = _folder_path(folder)
        pending = set(worker.pending_filenames())
        file_iter = (
            iter_incoming_media(target_dir, settings_mgr.get().recursive_subfolders)
            if folder == "incoming"
            else iter_output_media(target_dir)
        )
        items = []
        for p in sorted(file_iter, key=lambda x: x.stat().st_mtime, reverse=True):
            meta = inspect_media_file(p, root_dir=target_dir)
            rel_path = meta["rel_path"]
            enc_rel = "/".join(quote(seg) for seg in rel_path.split("/"))
            meta["folder"] = folder
            meta["is_processing"] = p.name in pending or (
                worker.status().get("active_file") == p.name
            )
            meta["media_url"] = f"/api/media/{folder}/{enc_rel}"
            meta["thumb_url"] = (
                f"/api/thumb/{folder}/{enc_rel}?v={int(meta['modified_at'])}"
            )
            if folder == "output":
                hist = db.lookup_by_output(rel_path) or db.lookup_by_output(p.name)
                if hist:
                    meta["conversion"] = hist
            items.append(meta)
        return {"folder": folder, "path": str(target_dir), "files": items}

    @app.get("/api/quarantine")
    def list_quarantine():
        quar_dir = settings_mgr.quarantine_path()
        items = []
        for p in sorted(
            quar_dir.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True
        ):
            if not p.is_file() or p.name.startswith("."):
                continue
            st = p.stat()
            qmeta = db.get_quarantine_meta(p.name) or {}
            items.append(
                {
                    "name": p.name,
                    "original_rel_path": qmeta.get("original_rel_path") or p.name,
                    "size_bytes": st.st_size,
                    "attempts": qmeta.get("attempts", 2),
                    "error_reason": qmeta.get(
                        "error_reason", "Corrupted or unreadable media stream"
                    ),
                    "quarantined_at": qmeta.get("quarantined_at", st.st_mtime),
                    "download_url": f"/api/media/quarantine/{quote(p.name)}",
                }
            )
        return {"path": str(quar_dir), "count": len(items), "items": items}

    @app.post("/api/quarantine/retry")
    def retry_quarantine(payload: Optional[dict] = None):
        filename = (payload or {}).get("filename")
        if filename:
            restored = worker.retry_quarantine_file(str(filename))
            return {"retried": 1, "restored_to": restored}
        count = worker.retry_all_quarantine()
        return {"retried": count}

    @app.delete("/api/quarantine/{filename}")
    def delete_quarantine_item(filename: str):
        quar_dir = settings_mgr.quarantine_path()
        target = (quar_dir / Path(filename).name).resolve()
        if target.parent != quar_dir or not target.is_file():
            raise HTTPException(status_code=404, detail="Quarantined file not found")
        target.unlink()
        db.remove_quarantine_meta(target.name)
        return {"deleted": target.name}

    @app.post("/api/quarantine/clear")
    def clear_quarantine():
        quar_dir = settings_mgr.quarantine_path()
        removed = 0
        for p in quar_dir.iterdir():
            if p.is_file() and not p.name.startswith("."):
                p.unlink()
                removed += 1
        db.clear_quarantine_meta()
        return {"deleted_count": removed}

    @app.post("/api/upload")
    async def upload_media(
        files: List[UploadFile] = File(...),
        profile: str = Query(""),
    ):
        inc_dir, _ = settings_mgr.ensure_directories()
        clean_profile = sanitize_folder_segment(profile)
        target_dir = (inc_dir / clean_profile) if clean_profile else inc_dir
        target_dir.mkdir(parents=True, exist_ok=True)

        saved_names = []
        skipped_names = []
        for uf in files:
            safe_name = Path(uf.filename or f"upload_{uuid.uuid4().hex}.jpg").name
            dest = target_dir / safe_name
            if dest.exists():
                dest = target_dir / f"{dest.stem}_{uuid.uuid4().hex[:6]}{dest.suffix}"
            tmp = target_dir / f".upload_{uuid.uuid4().hex}.part"
            try:
                with tmp.open("wb") as out_f:
                    shutil.copyfileobj(uf.file, out_f, length=1024 * 1024)
                os.replace(tmp, dest)
            finally:
                if tmp.exists():
                    tmp.unlink(missing_ok=True)
            if is_supported_media(dest):
                worker.enqueue_if_auto(dest)
                saved_names.append(dest.relative_to(inc_dir).as_posix())
            else:
                skipped_names.append(dest.name)
        return {
            "uploaded": saved_names,
            "skipped": skipped_names,
            "profile": clean_profile or "default",
            "auto_queued": settings_mgr.get().auto_watch_enabled,
        }

    @app.post("/api/process")
    async def trigger_processing(payload: Optional[dict] = None):
        filename = (payload or {}).get("filename")
        if filename:
            result = worker.process_single_sync(filename)
            return {"processed": 1, "result": result}
        queued = worker.process_all_now()
        return {"queued": queued}

    @app.post("/api/demo/seed")
    def seed_sample_media(payload: Optional[dict] = None):
        inc_dir, _ = settings_mgr.ensure_directories()
        body = payload or {}
        include_video = bool(body.get("include_video", True))
        include_corrupted = bool(body.get("include_corrupted", False))
        created = generate_sample_batch(
            inc_dir, include_video=include_video, include_corrupted=include_corrupted
        )
        if settings_mgr.get().auto_watch_enabled:
            worker.process_all_now()
        return {
            "seeded": created,
            "count": len(created),
            "auto_queued": settings_mgr.get().auto_watch_enabled,
        }

    @app.post("/api/demo/corrupt")
    def seed_corrupt_test():
        inc_dir, _ = settings_mgr.ensure_directories()
        created = seed_corrupted_transfer(inc_dir, "StorageSaver")
        if settings_mgr.get().auto_watch_enabled:
            worker.process_all_now()
        return {"seeded": created}

    @app.post("/api/files/{folder}/clear")
    def clear_folder(folder: str):
        base = _folder_path(folder)
        removed = 0
        for p in list(base.rglob("*")):
            if p.is_file() and not p.name.startswith("."):
                p.unlink()
                removed += 1
        return {"folder": folder, "deleted_count": removed}

    @app.delete("/api/files/{folder}/{filepath:path}")
    def delete_media_file(folder: str, filepath: str):
        base = _folder_path(folder)
        target = (base / filepath).resolve()
        if not str(target).startswith(str(base)) or not target.is_file():
            raise HTTPException(status_code=404, detail="File not found")
        target.unlink()
        return {"deleted": filepath}

    @app.get("/api/history")
    def get_history(limit: int = 100):
        return {"items": db.recent(limit=limit), "summary": db.summary()}

    @app.delete("/api/history")
    def clear_history():
        db.clear()
        return {"cleared": True}

    @app.get("/api/media/{folder}/{filepath:path}")
    def serve_media(folder: str, filepath: str):
        base = _folder_path(folder)
        target = (base / filepath).resolve()
        if not str(target).startswith(str(base)) or not target.is_file():
            raise HTTPException(status_code=404, detail="File not found")
        return FileResponse(path=str(target), filename=target.name)

    @lru_cache(maxsize=256)
    def _render_cached_thumb(path_str: str, mtime_ns: int) -> bytes:
        p = Path(path_str)
        ext = p.suffix.lower()
        buf = io.BytesIO()
        if ext in PHOTO_EXTENSIONS:
            try:
                with Image.open(p) as im:
                    im = ImageOps.exif_transpose(im)
                    if im.mode != "RGB":
                        im = im.convert("RGB")
                    im.thumbnail((520, 520), Image.Resampling.BILINEAR)
                    im.save(buf, format="JPEG", quality=80)
                    return buf.getvalue()
            except Exception:
                pass
        elif ext in VIDEO_EXTENSIONS:
            ffmpeg = get_ffmpeg_binary()
            if ffmpeg:
                import subprocess

                proc = subprocess.run(
                    [
                        ffmpeg,
                        "-hide_banner",
                        "-ss",
                        "0.2",
                        "-i",
                        str(p),
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale='min(520,iw)':-2",
                        "-f",
                        "image2",
                        "-vcodec",
                        "mjpeg",
                        "pipe:1",
                    ],
                    capture_output=True,
                    timeout=10,
                    check=False,
                )
                if proc.returncode == 0 and proc.stdout:
                    return proc.stdout
        img = Image.new("RGB", (320, 200), (30, 41, 59))
        img.save(buf, format="JPEG", quality=80)
        return buf.getvalue()

    @app.get("/api/thumb/{folder}/{filepath:path}")
    def serve_thumbnail(folder: str, filepath: str):
        base = _folder_path(folder)
        target = (base / filepath).resolve()
        if not str(target).startswith(str(base)) or not target.is_file():
            raise HTTPException(status_code=404, detail="File not found")
        st = target.stat()
        data = _render_cached_thumb(str(target), st.st_mtime_ns)
        return Response(content=data, media_type="image/jpeg")

    @app.get("/api/qrcode")
    def generate_qr(text: str = Query(..., min_length=1, max_length=512)):
        qr = qrcode.QRCode(box_size=6, border=2)
        qr.add_data(text)
        qr.make(fit=True)
        img = qr.make_image(fill_color="#0f172a", back_color="#ffffff")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return Response(content=buf.getvalue(), media_type="image/png")

    webui_dir = Path(__file__).resolve().parent.parent / "webui"
    if webui_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(webui_dir), html=True), name="webui")

    return app


app = create_app()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    uvicorn.run("server.main:app", host="0.0.0.0", port=port, reload=False)
