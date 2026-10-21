from __future__ import annotations

import logging
import os
import queue
import shutil
import threading
import time
from pathlib import Path
from typing import Dict, Iterator, List, Optional, Set

from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer

from server.config import SettingsManager
from server.converter import (
    find_takeout_sidecar,
    is_supported_media,
    process_media_file,
    resolve_unique_output_path,
)
from server.database import HistoryDatabase

logger = logging.getLogger("pixelnas.watcher")


class _IncomingHandler(FileSystemEventHandler):
    def __init__(self, notify_fn) -> None:
        super().__init__()
        self._notify = notify_fn

    def on_created(self, event) -> None:
        if not event.is_directory:
            self._notify(Path(event.src_path))

    def on_modified(self, event) -> None:
        if not event.is_directory:
            self._notify(Path(event.src_path))

    def on_moved(self, event) -> None:
        if not event.is_directory:
            self._notify(Path(event.dest_path))


def iter_incoming_media(inc_dir: Path, recursive: bool) -> Iterator[Path]:
    if not inc_dir.is_dir():
        return
    entries = inc_dir.rglob("*") if recursive else inc_dir.iterdir()
    for p in sorted(entries):
        if p.is_file() and is_supported_media(p):
            rel_parts = p.relative_to(inc_dir).parts[:-1]
            if any(part.startswith(".") for part in rel_parts):
                continue
            yield p


def iter_output_media(out_dir: Path) -> Iterator[Path]:
    if not out_dir.is_dir():
        return
    for p in out_dir.rglob("*"):
        if p.is_file() and not p.name.startswith("."):
            yield p


def prune_empty_subfolders(inc_dir: Path, protected_profile_names: Set[str]) -> None:
    """Prune temporary nested upload subfolders while preserving top-level profile folders."""
    if not inc_dir.is_dir():
        return
    protected_lower = {n.lower() for n in protected_profile_names}
    for sub in sorted(inc_dir.rglob("*"), reverse=True):
        if sub.is_dir() and sub != inc_dir:
            if sub.parent == inc_dir and sub.name.lower() in protected_lower:
                continue
            try:
                next(sub.iterdir())
            except StopIteration:
                sub.rmdir()
            except OSError:
                pass


def enforce_output_retention(
    out_dir: Path,
    retention_days: int,
    min_free_gb: float,
    db: Optional[HistoryDatabase] = None,
) -> int:
    if not out_dir.is_dir():
        return 0
    purged = 0
    now = time.time()

    def _ingested_time(p: Path) -> float:
        rel_key = p.relative_to(out_dir).as_posix()
        if db is not None:
            rec = db.lookup_by_output(rel_key) or db.lookup_by_output(p.name)
            if rec and rec.get("created_at"):
                return float(rec["created_at"])
        return p.stat().st_ctime

    all_files = list(iter_output_media(out_dir))
    if retention_days > 0:
        cutoff = now - (retention_days * 86400.0)
        for p in all_files:
            try:
                if _ingested_time(p) <= cutoff:
                    p.unlink()
                    purged += 1
            except OSError:
                pass

    if min_free_gb > 0.0:
        min_bytes = int(min_free_gb * (1024**3))
        candidates = sorted(iter_output_media(out_dir), key=_ingested_time)
        for p in candidates:
            if shutil.disk_usage(out_dir).free >= min_bytes:
                break
            try:
                p.unlink()
                purged += 1
            except OSError:
                pass

    return purged


class PipelineWorker:
    """
    Watches Folder 1 (and Per-Folder Profile subfolders), retries transient upload errors,
    quarantines corrupted transfers after `quarantine_max_retries` attempts into `Quarantine/`,
    and enforces retention cleanup on Folder 2.
    """

    def __init__(self, settings_mgr: SettingsManager, db: HistoryDatabase) -> None:
        self.settings_mgr = settings_mgr
        self.db = db
        self._queue: "queue.Queue[Path]" = queue.Queue()
        self._queued_paths: Set[str] = set()
        self._failure_counts: Dict[str, int] = {}
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._active_file: Optional[str] = None
        self._last_error: str = ""
        self._auto_purged_count: int = 0
        self._observer: Optional[Observer] = None
        self._watched_dir: Optional[Path] = None
        self._worker_thread: Optional[threading.Thread] = None
        self._sweep_thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._stop_event.clear()
        self._sync_observer()
        self._worker_thread = threading.Thread(
            target=self._run_worker, name="pixelnas-converter", daemon=True
        )
        self._sweep_thread = threading.Thread(
            target=self._run_sweeper, name="pixelnas-sweeper", daemon=True
        )
        self._worker_thread.start()
        self._sweep_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._observer:
            try:
                self._observer.stop()
                self._observer.join(timeout=2.0)
            except Exception:
                pass
            self._observer = None

    def _sync_observer(self) -> None:
        inc_dir, _ = self.settings_mgr.ensure_directories()
        if self._watched_dir == inc_dir and self._observer is not None:
            return
        if self._observer is not None:
            try:
                self._observer.stop()
                self._observer.join(timeout=1.5)
            except Exception:
                pass
        observer = Observer()
        observer.schedule(
            _IncomingHandler(self.enqueue_if_auto),
            str(inc_dir),
            recursive=True,
        )
        observer.daemon = True
        observer.start()
        self._observer = observer
        self._watched_dir = inc_dir

    def enqueue_if_auto(self, path: Path) -> bool:
        if not self.settings_mgr.get().auto_watch_enabled:
            return False
        return self.enqueue(path)

    def enqueue(self, path: Path) -> bool:
        resolved = path.resolve()
        if not is_supported_media(resolved):
            return False
        key = str(resolved)
        with self._lock:
            if key in self._queued_paths or self._active_file == resolved.name:
                return False
            self._queued_paths.add(key)
            self._queue.put(resolved)
            return True

    def process_all_now(self) -> int:
        cfg = self.settings_mgr.get()
        inc_dir, _ = self.settings_mgr.ensure_directories()
        count = 0
        for p in iter_incoming_media(inc_dir, cfg.recursive_subfolders):
            if self.enqueue(p):
                count += 1
        return count

    def retry_quarantine_file(self, quarantine_filename: str) -> str:
        inc_dir, _ = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        src = (quar_dir / Path(quarantine_filename).name).resolve()
        if src.parent != quar_dir or not src.is_file():
            raise FileNotFoundError(f"Quarantined file not found: {quarantine_filename}")

        meta = self.db.get_quarantine_meta(src.name)
        rel_dest = (meta or {}).get("original_rel_path") or src.name
        dest = (inc_dir / rel_dest).resolve()
        if not str(dest).startswith(str(inc_dir)):
            dest = inc_dir / src.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            dest = resolve_unique_output_path(dest.parent, dest.stem, dest.suffix)

        os.replace(src, dest)
        self.db.remove_quarantine_meta(quarantine_filename)
        with self._lock:
            self._failure_counts.pop(str(dest), None)
        self.enqueue(dest)
        return dest.relative_to(inc_dir).as_posix()

    def retry_all_quarantine(self) -> int:
        quar_dir = self.settings_mgr.quarantine_path()
        restored = 0
        for p in sorted(quar_dir.iterdir()):
            if p.is_file() and not p.name.startswith("."):
                self.retry_quarantine_file(p.name)
                restored += 1
        return restored

    def _quarantine_file(self, source_path: Path, attempts: int, error_msg: str) -> None:
        inc_dir, _ = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        size_bytes = source_path.stat().st_size if source_path.exists() else 0
        try:
            rel_orig = source_path.resolve().relative_to(inc_dir).as_posix()
        except ValueError:
            rel_orig = source_path.name

        dest = resolve_unique_output_path(quar_dir, source_path.stem, source_path.suffix)
        sidecar = find_takeout_sidecar(source_path)
        shutil.move(str(source_path), str(dest))
        if sidecar and sidecar.exists():
            sidecar.unlink(missing_ok=True)

        self.db.record_quarantine(
            quarantine_filename=dest.name,
            original_rel_path=rel_orig,
            size_bytes=size_bytes,
            attempts=attempts,
            error_reason=error_msg,
        )
        self.db.record(
            {
                "original_filename": rel_orig,
                "output_filename": dest.name,
                "media_type": "photo",
                "mode": "quarantined",
                "profile_name": rel_orig.split("/")[0] if "/" in rel_orig else "",
                "orig_bytes": size_bytes,
                "out_bytes": size_bytes,
                "saved_bytes": 0,
                "saved_pct": 0.0,
                "metadata_note": f"Moved to Quarantine ({attempts} failed attempts)",
                "original_deleted": True,
                "status": "quarantined",
                "error_message": error_msg,
            }
        )

    def process_single_sync(self, filename: str) -> Dict:
        cfg = self.settings_mgr.get()
        inc_dir, _ = self.settings_mgr.ensure_directories()
        target = (inc_dir / filename).resolve()
        if not str(target).startswith(str(inc_dir)) or not target.is_file():
            raise FileNotFoundError(f"File not found in Incoming folder: {filename}")
        with self._lock:
            self._active_file = target.name
        try:
            result = process_media_file(target, cfg, db=self.db)
            self.db.record(result)
            protected = {p.get("name", "") for p in cfg.folder_profiles}
            prune_empty_subfolders(inc_dir, protected)
            return result
        finally:
            with self._lock:
                self._active_file = None
                self._queued_paths.discard(str(target))

    def status(self) -> Dict:
        with self._lock:
            return {
                "watcher_running": not self._stop_event.is_set(),
                "auto_watch_enabled": self.settings_mgr.get().auto_watch_enabled,
                "queue_size": self._queue.qsize(),
                "active_file": self._active_file,
                "auto_purged_count": self._auto_purged_count,
                "last_error": self._last_error,
            }

    @staticmethod
    def _wait_until_stable(path: Path, timeout_sec: float = 15.0) -> bool:
        deadline = time.monotonic() + timeout_sec
        prev_size = -1
        while time.monotonic() < deadline:
            if not path.is_file():
                return False
            try:
                cur_size = path.stat().st_size
            except OSError:
                return False
            if cur_size > 0 and cur_size == prev_size:
                return True
            prev_size = cur_size
            time.sleep(0.15)
        return path.is_file() and path.stat().st_size > 0

    def _run_sweeper(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._sync_observer()
                cfg = self.settings_mgr.get()
                inc_dir, out_dir = self.settings_mgr.ensure_directories()
                protected = {p.get("name", "") for p in cfg.folder_profiles}
                if cfg.auto_watch_enabled:
                    for entry in iter_incoming_media(inc_dir, cfg.recursive_subfolders):
                        self.enqueue(entry)
                    if cfg.delete_originals:
                        prune_empty_subfolders(inc_dir, protected)
                if cfg.auto_purge_output_days > 0 or cfg.min_free_storage_gb > 0:
                    removed = enforce_output_retention(
                        out_dir,
                        cfg.auto_purge_output_days,
                        cfg.min_free_storage_gb,
                        db=self.db,
                    )
                    if removed:
                        with self._lock:
                            self._auto_purged_count += removed
            except Exception as exc:
                logger.warning("Sweeper error: %s", exc)
            self._stop_event.wait(1.2)

    def _run_worker(self) -> None:
        while not self._stop_event.is_set():
            try:
                path = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue

            key = str(path)
            with self._lock:
                self._active_file = path.name
            try:
                if path.is_file() and self._wait_until_stable(path):
                    cfg = self.settings_mgr.get()
                    result = process_media_file(path, cfg, db=self.db)
                    self.db.record(result)
                    with self._lock:
                        self._failure_counts.pop(key, None)
                    if cfg.delete_originals:
                        protected = {p.get("name", "") for p in cfg.folder_profiles}
                        prune_empty_subfolders(Path(cfg.incoming_dir).resolve(), protected)
                    self._last_error = ""
            except Exception as exc:
                err_str = str(exc)
                logger.warning("Attempt failed for %s: %s", path, err_str)
                self._last_error = f"{path.name}: {err_str}"
                cfg = self.settings_mgr.get()
                with self._lock:
                    attempts = self._failure_counts.get(key, 0) + 1
                    self._failure_counts[key] = attempts

                if path.exists() and cfg.quarantine_enabled and attempts >= cfg.quarantine_max_retries:
                    try:
                        self._quarantine_file(path, attempts, err_str)
                        with self._lock:
                            self._failure_counts.pop(key, None)
                    except Exception as q_exc:
                        logger.exception("Failed moving file to quarantine: %s", q_exc)
            finally:
                with self._lock:
                    self._queued_paths.discard(key)
                    self._active_file = None
                self._queue.task_done()

    def pending_filenames(self) -> List[str]:
        with self._lock:
            return [Path(p).name for p in self._queued_paths]
