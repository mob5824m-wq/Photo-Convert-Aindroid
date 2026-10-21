from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional


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
                    profile_name TEXT DEFAULT '',
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
                    metadata_note TEXT DEFAULT '',
                    original_deleted INTEGER DEFAULT 1,
                    duration_ms INTEGER DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'completed',
                    error_message TEXT DEFAULT '',
                    created_at REAL NOT NULL
                )
                """
            )
            cols = {
                row[1]
                for row in conn.execute("PRAGMA table_info(conversion_history)").fetchall()
            }
            if "metadata_note" not in cols:
                conn.execute(
                    "ALTER TABLE conversion_history ADD COLUMN metadata_note TEXT DEFAULT ''"
                )
            if "profile_name" not in cols:
                conn.execute(
                    "ALTER TABLE conversion_history ADD COLUMN profile_name TEXT DEFAULT ''"
                )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS seen_hashes (
                    sha256 TEXT PRIMARY KEY,
                    output_filename TEXT NOT NULL,
                    created_at REAL NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS quarantine_items (
                    quarantine_filename TEXT PRIMARY KEY,
                    original_rel_path TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 2,
                    error_reason TEXT NOT NULL,
                    quarantined_at REAL NOT NULL
                )
                """
            )
            conn.commit()

    def lookup_hash(self, sha256: str) -> Optional[str]:
        if not sha256:
            return None
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT output_filename FROM seen_hashes WHERE sha256 = ? LIMIT 1",
                (sha256,),
            ).fetchone()
            return str(row["output_filename"]) if row else None

    def record_hash(self, sha256: str, output_filename: str) -> None:
        if not sha256:
            return
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO seen_hashes (sha256, output_filename, created_at)
                VALUES (?, ?, ?)
                """,
                (sha256, output_filename, time.time()),
            )
            conn.commit()

    def record_quarantine(
        self,
        quarantine_filename: str,
        original_rel_path: str,
        size_bytes: int,
        attempts: int,
        error_reason: str,
    ) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO quarantine_items (
                    quarantine_filename, original_rel_path, size_bytes, attempts, error_reason, quarantined_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    quarantine_filename,
                    original_rel_path,
                    size_bytes,
                    attempts,
                    error_reason,
                    time.time(),
                ),
            )
            conn.commit()

    def get_quarantine_meta(self, quarantine_filename: str) -> Optional[Dict]:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM quarantine_items WHERE quarantine_filename = ? LIMIT 1",
                (quarantine_filename,),
            ).fetchone()
            return dict(row) if row else None

    def remove_quarantine_meta(self, quarantine_filename: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "DELETE FROM quarantine_items WHERE quarantine_filename = ?",
                (quarantine_filename,),
            )
            conn.commit()

    def clear_quarantine_meta(self) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM quarantine_items")
            conn.commit()

    def record(self, entry: Dict) -> int:
        with self._lock, self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO conversion_history (
                    original_filename, output_filename, media_type, mode, profile_name,
                    orig_width, orig_height, orig_mp, orig_bytes,
                    out_width, out_height, out_mp, out_bytes,
                    saved_bytes, saved_pct, exif_preserved,
                    camera_model, date_taken, gps_coords, metadata_note,
                    original_deleted, duration_ms, status, error_message, created_at
                ) VALUES (
                    :original_filename, :output_filename, :media_type, :mode, :profile_name,
                    :orig_width, :orig_height, :orig_mp, :orig_bytes,
                    :out_width, :out_height, :out_mp, :out_bytes,
                    :saved_bytes, :saved_pct, :exif_preserved,
                    :camera_model, :date_taken, :gps_coords, :metadata_note,
                    :original_deleted, :duration_ms, :status, :error_message, :created_at
                )
                """,
                {
                    "original_filename": entry.get("original_filename", ""),
                    "output_filename": entry.get("output_filename", ""),
                    "media_type": entry.get("media_type", "photo"),
                    "mode": entry.get("mode", "storage_saver"),
                    "profile_name": entry.get("profile_name", ""),
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
                    "metadata_note": entry.get("metadata_note", ""),
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
                    COALESCE(SUM(CASE WHEN mode = 'duplicate_skipped' THEN 1 ELSE 0 END), 0) AS duplicates_skipped_count,
                    COALESCE(SUM(CASE WHEN mode NOT IN ('storage_saver', 'duplicate_skipped') THEN 1 ELSE 0 END), 0) AS passthrough_count,
                    COALESCE(SUM(orig_bytes), 0) AS total_orig_bytes,
                    COALESCE(SUM(out_bytes), 0) AS total_out_bytes,
                    COALESCE(SUM(saved_bytes), 0) AS total_saved_bytes,
                    COALESCE(SUM(original_deleted), 0) AS originals_deleted_count
                FROM conversion_history
                WHERE status = 'completed'
                """
            ).fetchone()
            q_row = conn.execute(
                "SELECT COUNT(*) AS quarantined_total FROM conversion_history WHERE status = 'quarantined'"
            ).fetchone()
            data = dict(row) if row else {}
            orig = data.get("total_orig_bytes") or 0
            saved = data.get("total_saved_bytes") or 0
            data["avg_saved_pct"] = round((saved / orig) * 100.0, 1) if orig > 0 else 0.0
            data["quarantined_total"] = int(q_row["quarantined_total"]) if q_row else 0
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
            conn.execute("DELETE FROM seen_hashes")
            conn.commit()
