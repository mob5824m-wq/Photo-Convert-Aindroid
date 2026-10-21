from __future__ import annotations

import logging
import threading
import warnings
from pathlib import Path
from typing import Optional

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.filesystems import AbstractedFS
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

from server.config import SettingsManager
from server.watcher import PipelineWorker

logger = logging.getLogger("pixelnas.ftp")


class PixelVirtualFTPFS(AbstractedFS):
    """
    Android FUSE-safe virtual FTP filesystem (no OS symlinks required):
      /              -> lists virtual folders ['Incoming', 'Output', 'Quarantine']
      /Incoming/*    -> mapped directly to `settings_mgr.incoming_dir` (includes StorageSaver/, Original/, Family/)
      /Output/*      -> mapped directly to `settings_mgr.output_dir`
      /Quarantine/*  -> mapped directly to `settings_mgr.quarantine_dir`
    """

    settings_mgr: Optional[SettingsManager] = None

    def ftp2fs(self, ftppath: str) -> str:
        norm = self.ftpnorm(ftppath)
        if not self.settings_mgr:
            return super().ftp2fs(ftppath)
        inc_dir, out_dir = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        stripped = norm.lstrip("/")
        if not stripped:
            return str(self.root)
        parts = stripped.split("/", 1)
        head = parts[0].lower()
        tail = parts[1] if len(parts) > 1 else ""
        if head == "incoming":
            target = (inc_dir / tail).resolve() if tail else inc_dir
            return str(target) if str(target).startswith(str(inc_dir)) else str(inc_dir)
        if head in ("output", "googlephotos_output"):
            target = (out_dir / tail).resolve() if tail else out_dir
            return str(target) if str(target).startswith(str(out_dir)) else str(out_dir)
        if head == "quarantine":
            target = (quar_dir / tail).resolve() if tail else quar_dir
            return str(target) if str(target).startswith(str(quar_dir)) else str(quar_dir)
        # Allow quick FTP clients uploading directly to '/' root to land straight in Incoming
        target = (inc_dir / stripped).resolve()
        return str(target) if str(target).startswith(str(inc_dir)) else str(inc_dir)

    def fs2ftp(self, fspath: str) -> str:
        if not self.settings_mgr:
            return super().fs2ftp(fspath)
        resolved = str(Path(fspath).resolve())
        inc_dir, out_dir = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        if resolved == str(Path(self.root).resolve()):
            return "/"
        if resolved == str(inc_dir):
            return "/Incoming"
        if resolved.startswith(str(inc_dir) + "/"):
            return "/Incoming/" + resolved[len(str(inc_dir)) + 1 :]
        if resolved == str(out_dir):
            return "/Output"
        if resolved.startswith(str(out_dir) + "/"):
            return "/Output/" + resolved[len(str(out_dir)) + 1 :]
        if resolved == str(quar_dir):
            return "/Quarantine"
        if resolved.startswith(str(quar_dir) + "/"):
            return "/Quarantine/" + resolved[len(str(quar_dir)) + 1 :]
        return "/"

    def validpath(self, path: str) -> bool:
        if not self.settings_mgr:
            return super().validpath(path)
        resolved = str(Path(path).resolve())
        inc_dir, out_dir = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        allowed_roots = (
            str(Path(self.root).resolve()),
            str(inc_dir),
            str(out_dir),
            str(quar_dir),
        )
        return any(
            resolved == r or resolved.startswith(r + "/") for r in allowed_roots
        )

    def listdir(self, path: str):
        if Path(path).resolve() == Path(self.root).resolve():
            return ["Incoming", "Output", "Quarantine"]
        return [name for name in super().listdir(path) if not name.startswith(".")]


class _PixelNasFTPHandler(FTPHandler):
    worker: Optional[PipelineWorker] = None
    settings_mgr: Optional[SettingsManager] = None

    def on_file_received(self, file: str) -> None:
        p = Path(file).resolve()
        if self.worker and self.settings_mgr:
            inc_dir, _ = self.settings_mgr.ensure_directories()
            if str(p).startswith(str(inc_dir)):
                self.worker.enqueue_if_auto(p)


class EmbeddedFtpServer:
    """Unprivileged LAN FTP server (default port 2121 — no root needed on Pixel 1–5!)."""

    def __init__(self, settings_mgr: SettingsManager, worker: PipelineWorker) -> None:
        self.settings_mgr = settings_mgr
        self.worker = worker
        self._server: Optional[FTPServer] = None
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self.bound_port: int = 0

    def start_or_reload(self) -> None:
        with self._lock:
            self._stop_unlocked()
            cfg = self.settings_mgr.get()
            if not cfg.ftp_enabled:
                return

            ftp_root = self.settings_mgr.base_dir / "ftp_virtual_root"
            ftp_root.mkdir(parents=True, exist_ok=True)
            self.settings_mgr.ensure_directories()

            authorizer = DummyAuthorizer()
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                if cfg.auth_enabled and cfg.auth_password:
                    authorizer.add_user(
                        cfg.auth_username,
                        cfg.auth_password,
                        str(ftp_root),
                        perm="elradfmwMT",
                    )
                else:
                    authorizer.add_anonymous(str(ftp_root), perm="elradfmwMT")
                    authorizer.add_user(
                        cfg.auth_username or "pixel",
                        cfg.auth_password or "pixel",
                        str(ftp_root),
                        perm="elradfmwMT",
                    )

            fs_cls = type(
                "BoundPixelVirtualFTPFS",
                (PixelVirtualFTPFS,),
                {"settings_mgr": self.settings_mgr},
            )
            handler = type(
                "BoundPixelFTPHandler",
                (_PixelNasFTPHandler,),
                {
                    "worker": self.worker,
                    "settings_mgr": self.settings_mgr,
                    "abstracted_fs": fs_cls,
                },
            )
            handler.authorizer = authorizer
            handler.banner = "Pixel Photo NAS FTP Ready (/Incoming, /Output, /Quarantine)"
            handler.passive_ports = range(50000, 50030)

            try:
                srv = FTPServer(("0.0.0.0", cfg.ftp_port), handler)
                srv.max_cons = 32
                srv.max_cons_per_ip = 16
                self._server = srv
                self.bound_port = srv.socket.getsockname()[1]
                self._thread = threading.Thread(
                    target=srv.serve_forever,
                    kwargs={"timeout": 0.5, "blocking": True},
                    name="pixelnas-ftp",
                    daemon=True,
                )
                self._thread.start()
            except Exception as exc:
                logger.warning("FTP server could not bind port %s: %s", cfg.ftp_port, exc)
                self._server = None
                self.bound_port = 0

    def stop(self) -> None:
        with self._lock:
            self._stop_unlocked()

    def _stop_unlocked(self) -> None:
        if self._server is not None:
            try:
                self._server.close_all()
            except Exception:
                pass
            self._server = None
            self.bound_port = 0
