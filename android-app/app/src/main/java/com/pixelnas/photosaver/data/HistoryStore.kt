package com.pixelnas.photosaver.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pixelnas.photosaver.model.ConversionRecord
import com.pixelnas.photosaver.model.HistorySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pixel_nas_history.db", null, 1) {

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
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(entry: ConversionRecord): Long {
        val cv = ContentValues().apply {
            put("original_filename", entry.originalFilename)
            put("output_filename", entry.outputFilename)
            put("media_type", entry.mediaType)
            put("mode", entry.mode)
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
        readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*),
                COALESCE(SUM(CASE WHEN mode = 'storage_saver' THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN mode != 'storage_saver' THEN 1 ELSE 0 END), 0),
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
                val orig = c.getLong(3)
                val saved = c.getLong(5)
                val pct = if (orig > 0) round((saved.toDouble() / orig.toDouble()) * 1000.0) / 10.0 else 0.0
                return HistorySummary(
                    totalProcessed = c.getInt(0),
                    saverCount = c.getInt(1),
                    passthroughCount = c.getInt(2),
                    totalOrigBytes = orig,
                    totalOutBytes = c.getLong(4),
                    totalSavedBytes = saved,
                    originalsDeletedCount = c.getInt(6),
                    avgSavedPct = pct
                )
            }
        }
        return HistorySummary()
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("conversion_history", null, null)
        refreshFlows()
    }

    private fun refreshFlows() {
        _recentFlow.value = recent(60)
        _summaryFlow.value = summary()
    }

    private fun android.database.Cursor.toRecord(): ConversionRecord = ConversionRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        originalFilename = getString(getColumnIndexOrThrow("original_filename")),
        outputFilename = getString(getColumnIndexOrThrow("output_filename")),
        mediaType = getString(getColumnIndexOrThrow("media_type")),
        mode = getString(getColumnIndexOrThrow("mode")),
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
        originalDeleted = getInt(getColumnIndexOrThrow("original_deleted")) == 1,
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        status = getString(getColumnIndexOrThrow("status")) ?: "completed",
        errorMessage = getString(getColumnIndexOrThrow("error_message")) ?: "",
        createdAtEpochSec = getDouble(getColumnIndexOrThrow("created_at"))
    )
}
