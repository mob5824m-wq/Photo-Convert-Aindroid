package com.pixelnas.photosaver.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.HistorySummary
import com.pixelnas.photosaver.model.QuarantineItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pixel_nas_history.db", null, 3) {

    private val _recentFlow = MutableStateFlow<List<ConversionRecord>>(emptyList())
    val recentFlow: StateFlow<List<ConversionRecord>> = _recentFlow.asStateFlow()

    private val _summaryFlow = MutableStateFlow(HistorySummary())
    val summaryFlow: StateFlow<HistorySummary> = _summaryFlow.asStateFlow()

    init {
        refreshFlows()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversion_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_filename TEXT NOT NULL,
                output_filename TEXT NOT NULL,
                media_type TEXT NOT NULL,
                mode TEXT NOT NULL,
                profile_name TEXT DEFAULT 'default',
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
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS seen_hashes (
                sha256 TEXT PRIMARY KEY,
                output_filename TEXT NOT NULL,
                created_at REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quarantine_items (
                quarantine_name TEXT PRIMARY KEY,
                original_rel_path TEXT NOT NULL,
                attempts INTEGER DEFAULT 2,
                error_reason TEXT DEFAULT '',
                quarantined_at REAL NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE conversion_history ADD COLUMN metadata_note TEXT DEFAULT ''")
            } catch (_: Exception) {
            }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS seen_hashes (
                    sha256 TEXT PRIMARY KEY,
                    output_filename TEXT NOT NULL,
                    created_at REAL NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE conversion_history ADD COLUMN profile_name TEXT DEFAULT 'default'")
            } catch (_: Exception) {
            }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS quarantine_items (
                    quarantine_name TEXT PRIMARY KEY,
                    original_rel_path TEXT NOT NULL,
                    attempts INTEGER DEFAULT 2,
                    error_reason TEXT DEFAULT '',
                    quarantined_at REAL NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    fun lookupHash(sha256: String): String? {
        if (sha256.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT output_filename FROM seen_hashes WHERE sha256 = ? LIMIT 1",
            arrayOf(sha256)
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    @Synchronized
    fun recordHash(sha256: String, outputFilename: String) {
        if (sha256.isBlank()) return
        val cv = ContentValues().apply {
            put("sha256", sha256)
            put("output_filename", outputFilename)
            put("created_at", System.currentTimeMillis() / 1000.0)
        }
        writableDatabase.insertWithOnConflict("seen_hashes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun recordQuarantine(
        quarantineName: String,
        originalRelPath: String,
        attempts: Int,
        errorReason: String
    ) {
        val cv = ContentValues().apply {
            put("quarantine_name", quarantineName)
            put("original_rel_path", originalRelPath)
            put("attempts", attempts)
            put("error_reason", errorReason)
            put("quarantined_at", System.currentTimeMillis() / 1000.0)
        }
        writableDatabase.insertWithOnConflict(
            "quarantine_items",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshFlows()
    }

    @Synchronized
    fun getQuarantineMeta(quarantineName: String): QuarantineItem? {
        readableDatabase.rawQuery(
            "SELECT quarantine_name, original_rel_path, attempts, error_reason, quarantined_at FROM quarantine_items WHERE quarantine_name = ? LIMIT 1",
            arrayOf(quarantineName)
        ).use { c ->
            if (c.moveToFirst()) {
                return QuarantineItem(
                    quarantineName = c.getString(0),
                    originalRelPath = c.getString(1),
                    attempts = c.getInt(2),
                    errorReason = c.getString(3) ?: "",
                    quarantinedAtEpochSec = c.getDouble(4)
                )
            }
        }
        return null
    }

    @Synchronized
    fun removeQuarantineMeta(quarantineName: String) {
        writableDatabase.delete("quarantine_items", "quarantine_name = ?", arrayOf(quarantineName))
        refreshFlows()
    }

    @Synchronized
    fun clearQuarantineMeta() {
        writableDatabase.delete("quarantine_items", null, null)
        refreshFlows()
    }

    @Synchronized
    fun record(entry: ConversionRecord): Long {
        val cv = ContentValues().apply {
            put("original_filename", entry.originalFilename)
            put("output_filename", entry.outputFilename)
            put("media_type", entry.mediaType)
            put("mode", entry.mode)
            put("profile_name", entry.profileName)
            put("orig_width", entry.origWidth)
            put("orig_height", entry.origHeight)
            put("orig_mp", entry.origMp)
            put("orig_bytes", entry.origBytes)
            put("out_width", entry.outWidth)
            put("out_height", entry.outHeight)
            put("out_mp", entry.outMp)
            put("out_bytes", entry.outBytes)
            put("saved_bytes", entry.savedBytes)
            put("saved_pct", entry.savedPct)
            put("exif_preserved", if (entry.exifPreserved) 1 else 0)
            put("camera_model", entry.cameraModel)
            put("date_taken", entry.dateTaken)
            put("gps_coords", entry.gpsCoords)
            put("metadata_note", entry.metadataNote)
            put("original_deleted", if (entry.originalDeleted) 1 else 0)
            put("duration_ms", entry.durationMs)
            put("status", entry.status)
            put("error_message", entry.errorMessage)
            put("created_at", entry.createdAtEpochSec)
        }
        val id = writableDatabase.insert("conversion_history", null, cv)
        refreshFlows()
        return id
    }

    @Synchronized
    fun recent(limit: Int = 60): List<ConversionRecord> {
        val list = mutableListOf<ConversionRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM conversion_history ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(c.toRecord())
            }
        }
        return list
    }

    @Synchronized
    fun lookupByOutput(outputFilename: String): ConversionRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM conversion_history WHERE output_filename = ? ORDER BY id DESC LIMIT 1",
            arrayOf(outputFilename)
        ).use { c ->
            if (c.moveToFirst()) return c.toRecord()
        }
        return null
    }

    @Synchronized
    fun summary(): HistorySummary {
        var qCount = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM quarantine_items", null).use { qc ->
            if (qc.moveToFirst()) qCount = qc.getInt(0)
        }
        readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*),
                COALESCE(SUM(CASE WHEN mode = 'storage_saver' THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN mode = 'duplicate_skipped' THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN mode NOT IN ('storage_saver', 'duplicate_skipped') THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(orig_bytes), 0),
                COALESCE(SUM(out_bytes), 0),
                COALESCE(SUM(saved_bytes), 0),
                COALESCE(SUM(original_deleted), 0)
            FROM conversion_history
            WHERE status = 'completed'
            """.trimIndent(),
            null
        ).use { c ->
            if (c.moveToFirst()) {
                val orig = c.getLong(4)
                val saved = c.getLong(6)
                val pct = if (orig > 0) round((saved.toDouble() / orig.toDouble()) * 1000.0) / 10.0 else 0.0
                return HistorySummary(
                    totalProcessed = c.getInt(0),
                    saverCount = c.getInt(1),
                    duplicatesSkippedCount = c.getInt(2),
                    passthroughCount = c.getInt(3),
                    quarantinedCount = qCount,
                    totalOrigBytes = orig,
                    totalOutBytes = c.getLong(5),
                    totalSavedBytes = saved,
                    originalsDeletedCount = c.getInt(7),
                    avgSavedPct = pct
                )
            }
        }
        return HistorySummary(quarantinedCount = qCount)
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("conversion_history", null, null)
        writableDatabase.delete("seen_hashes", null, null)
        refreshFlows()
    }

    private fun refreshFlows() {
        _recentFlow.value = recent(60)
        _summaryFlow.value = summary()
    }

    private fun android.database.Cursor.toRecord(): ConversionRecord {
        val profIdx = getColumnIndex("profile_name")
        return ConversionRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            originalFilename = getString(getColumnIndexOrThrow("original_filename")),
            outputFilename = getString(getColumnIndexOrThrow("output_filename")),
            mediaType = getString(getColumnIndexOrThrow("media_type")),
            mode = getString(getColumnIndexOrThrow("mode")),
            profileName = if (profIdx >= 0) (getString(profIdx) ?: "default") else "default",
            origWidth = getInt(getColumnIndexOrThrow("orig_width")),
            origHeight = getInt(getColumnIndexOrThrow("orig_height")),
            origMp = getDouble(getColumnIndexOrThrow("orig_mp")),
            origBytes = getLong(getColumnIndexOrThrow("orig_bytes")),
            outWidth = getInt(getColumnIndexOrThrow("out_width")),
            outHeight = getInt(getColumnIndexOrThrow("out_height")),
            outMp = getDouble(getColumnIndexOrThrow("out_mp")),
            outBytes = getLong(getColumnIndexOrThrow("out_bytes")),
            savedBytes = getLong(getColumnIndexOrThrow("saved_bytes")),
            savedPct = getDouble(getColumnIndexOrThrow("saved_pct")),
            exifPreserved = getInt(getColumnIndexOrThrow("exif_preserved")) == 1,
            cameraModel = getString(getColumnIndexOrThrow("camera_model")) ?: "",
            dateTaken = getString(getColumnIndexOrThrow("date_taken")) ?: "",
            gpsCoords = getString(getColumnIndexOrThrow("gps_coords")) ?: "",
            metadataNote = getString(getColumnIndexOrThrow("metadata_note")) ?: "",
            originalDeleted = getInt(getColumnIndexOrThrow("original_deleted")) == 1,
            durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
            status = getString(getColumnIndexOrThrow("status")) ?: "completed",
            errorMessage = getString(getColumnIndexOrThrow("error_message")) ?: "",
            createdAtEpochSec = getDouble(getColumnIndexOrThrow("created_at"))
        )
    }
}
