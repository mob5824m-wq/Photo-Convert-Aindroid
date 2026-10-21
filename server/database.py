from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Dict, List


class HistoryDatabase:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self._lock = threading.Lock()
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS conversion_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    original_filename TEXT NOT NULL,
                    output_filename TEXT NOT NULL,
                    media_type TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    orig_width INTEGER DEFAULT 0,
                    orig_height INTEGER DEFAULT 0,
                    orig_mp REAL DEFAULT 0.0,
                    orig_bytes INTEGER NOT NULL,
                    out_width INTEGER DEFAULT 0,
                    out_height INTEGER DEFAULT 0,
                    out_mp REAL DEFAULT 0.0,
                    out_bytes INTEGER NOT NULL,
                    saved_bytes INTEGER NOT NULL,
                    saved_pct REAL NOT NULL,
                    exif_preserved INTEGER DEFAULT 1,
                    camera_model TEXT DEFAULT '',
                    date_taken TEXT DEFAULT '',
                    gps_coords TEXT DEFAULT '',
                    original_deleted INTEGER DEFAULT 1,
                    duration_ms INTEGER DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'completed',
                    error_message TEXT DEFAULT '',
                    created_at REAL NOT NULL
                )
                """
            )
            conn.commit()

    def record(self, entry: Dict) -> int:
        with self._lock, self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO conversion_history (
                    original_filename, output_filename, media_type, mode,
                    orig_width, orig_height, orig_mp, orig_bytes,
                    out_width, out_height, out_mp, out_bytes,
                    saved_bytes, saved_pct, exif_preserved,
                    camera_model, date_taken, gps_coords,
                    original_deleted, duration_ms, status, error_message, created_at
                ) VALUES (
                    :original_filename, :output_filename, :media_type, :mode,
                    :orig_width, :orig_height, :orig_mp, :orig_bytes,
                    :out_width, :out_height, :out_mp, :out_bytes,
                    :saved_bytes, :saved_pct, :exif_preserved,
                    :camera_model, :date_taken, :gps_coords,
                    :original_deleted, :duration_ms, :status, :error_message, :created_at
                )
                """,
                {
                    "original_filename": entry.get("original_filename", ""),
                    "output_filename": entry.get("output_filename", ""),
                    "media_type": entry.get("media_type", "photo"),
                    "mode": entry.get("mode", "storage_saver"),
                    "orig_width": entry.get("orig_width", 0),
                    "orig_height": entry.get("orig_height", 0),
                    "orig_mp": entry.get("orig_mp", 0.0),
                    "orig_bytes": entry.get("orig_bytes", 0),
                    "out_width": entry.get("out_width", 0),
                    "out_height": entry.get("out_height", 0),
                    "out_mp": entry.get("out_mp", 0.0),
                    "out_bytes": entry.get("out_bytes", 0),
                    "saved_bytes": entry.get("saved_bytes", 0),
                    "saved_pct": entry.get("saved_pct", 0.0),
                    "exif_preserved": 1 if entry.get("exif_preserved", True) else 0,
                    "camera_model": entry.get("camera_model", ""),
                    "date_taken": entry.get("date_taken", ""),
                    "gps_coords": entry.get("gps_coords", ""),
                    "original_deleted": 1 if entry.get("original_deleted", True) else 0,
                    "duration_ms": entry.get("duration_ms", 0),
                    "status": entry.get("status", "completed"),
                    "error_message": entry.get("error_message", ""),
                    "created_at": entry.get("created_at", time.time()),
                },
            )
            conn.commit()
            return int(cur.lastrowid or 0)

    def recent(self, limit: int = 100) -> List[Dict]:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM conversion_history ORDER BY id DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [dict(r) for r in rows]

    def summary(self) -> Dict:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                """
                SELECT
                    COUNT(*) AS total_processed,
                    COALESCE(SUM(CASE WHEN mode = 'storage_saver' THEN 1 ELSE 0 END), 0) AS saver_count,
                    COALESCE(SUM(CASE WHEN mode != 'storage_saver' THEN 1 ELSE 0 END), 0) AS passthrough_count,
                    COALESCE(SUM(orig_bytes), 0) AS total_orig_bytes,
                    COALESCE(SUM(out_bytes), 0) AS total_out_bytes,
                    COALESCE(SUM(saved_bytes), 0) AS total_saved_bytes,
                    COALESCE(SUM(original_deleted), 0) AS originals_deleted_count
                FROM conversion_history
                WHERE status = 'completed'
                """
            ).fetchone()
            data = dict(row) if row else {}
            orig = data.get("total_orig_bytes") or 0
            saved = data.get("total_saved_bytes") or 0
            data["avg_saved_pct"] = round((saved / orig) * 100.0, 1) if orig > 0 else 0.0
            return data

    def lookup_by_output(self, output_filename: str) -> Dict | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                """
                SELECT * FROM conversion_history
                WHERE output_filename = ?
                ORDER BY id DESC LIMIT 1
                """,
                (output_filename,),
            ).fetchone()
            return dict(row) if row else None

    def clear(self) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM conversion_history")
            conn.commit()
