from __future__ import annotations

import logging
import queue
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional, Set

from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer

from server.config import SettingsManager
from server.converter import is_supported_media, process_media_file
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


class PipelineWorker:
    """
    Watches the Incoming upload folder via OS filesystem events + resilient background sweep,
    waits for file size stability, converts/moves into Output, and deletes the high-res original.
    """

    def __init__(self, settings_mgr: SettingsManager, db: HistoryDatabase) -> None:
        self.settings_mgr = settings_mgr
        self.db = db
        self._queue: "queue.Queue[Path]" = queue.Queue()
        self._queued_paths: Set[str] = set()
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._active_file: Optional[str] = None
        self._last_error: str = ""
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
        observer.schedule(_IncomingHandler(self.enqueue_if_auto), str(inc_dir), recursive=False)
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
        inc_dir, _ = self.settings_mgr.ensure_directories()
        count = 0
        for p in sorted(inc_dir.iterdir()):
            if p.is_file() and is_supported_media(p):
                if self.enqueue(p):
                    count += 1
        return count

    def process_single_sync(self, filename: str) -> Dict:
        inc_dir, _ = self.settings_mgr.ensure_directories()
        target = (inc_dir / Path(filename).name).resolve()
        if target.parent != inc_dir or not target.is_file():
            raise FileNotFoundError(f"File not found in Incoming folder: {filename}")
        settings = self.settings_mgr.get()
        with self._lock:
            self._active_file = target.name
        try:
            result = process_media_file(target, settings)
            self.db.record(result)
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
                settings = self.settings_mgr.get()
                if settings.auto_watch_enabled:
                    inc_dir = Path(settings.incoming_dir).resolve()
                    if inc_dir.is_dir():
                        for entry in sorted(inc_dir.iterdir()):
                            if entry.is_file() and is_supported_media(entry):
                                self.enqueue(entry)
            except Exception as exc:
                logger.warning("Sweeper error: %s", exc)
            self._stop_event.wait(2.0)

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
                    settings = self.settings_mgr.get()
                    result = process_media_file(path, settings)
                    self.db.record(result)
                    self._last_error = ""
            except Exception as exc:
                logger.exception("Failed converting %s", path)
                self._last_error = f"{path.name}: {exc}"
                try:
                    self.db.record(
                        {
                            "original_filename": path.name,
                            "output_filename": "",
                            "media_type": "photo",
                            "mode": "error",
                            "orig_bytes": path.stat().st_size if path.exists() else 0,
                            "out_bytes": 0,
                            "saved_bytes": 0,
                            "saved_pct": 0.0,
                            "original_deleted": False,
                            "status": "error",
                            "error_message": str(exc),
                        }
                    )
                except Exception:
                    pass
            finally:
                with self._lock:
                    self._queued_paths.discard(key)
                    self._active_file = None
                self._queue.task_done()

    def pending_filenames(self) -> List[str]:
        with self._lock:
            return [Path(p).name for p in self._queued_paths]
