package com.kianabc.freshfix

import android.content.Context
import java.io.File

/** Append-only CSV of every photo taken, importable into Google My Maps, Earth, QGIS, Excel. */
class PhotoLog(context: Context) {

    val file = File(context.getExternalFilesDir(null), "freshfix_log.csv")

    fun count(): Int =
        if (!file.exists()) 0 else file.useLines { lines -> (lines.count() - 1).coerceAtLeast(0) }

    @Synchronized
    fun append(record: PhotoRecord) {
        if (!file.exists()) file.writeText(HEADER + "\n")
        file.appendText(record.toCsvRow() + "\n")
    }

    companion object {
        const val HEADER =
            "photo,taken_at,latitude,longitude,accuracy_m,altitude_m,fix_offset_ms," +
                "satellites_used,speed_mps,bearing_deg,mock,status,address"
    }
}

data class PhotoRecord(
    val fileName: String,
    val takenAt: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val altitudeM: Double?,
    /** Fix time minus shutter time; negative means the fix came just before the shot. */
    val fixOffsetMs: Long?,
    val satellitesUsed: Int,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val mock: Boolean,
    val status: FixStatus,
    val address: String?,
) {
    fun toCsvRow(): String = listOf(
        fileName,
        takenAt,
        latitude?.let { "%.7f".format(java.util.Locale.US, it) },
        longitude?.let { "%.7f".format(java.util.Locale.US, it) },
        accuracyM?.let { "%.1f".format(java.util.Locale.US, it) },
        altitudeM?.let { "%.1f".format(java.util.Locale.US, it) },
        fixOffsetMs?.toString(),
        satellitesUsed.toString(),
        speedMps?.let { "%.2f".format(java.util.Locale.US, it) },
        bearingDeg?.let { "%.0f".format(java.util.Locale.US, it) },
        mock.toString(),
        status.name,
        address,
    ).joinToString(",") { csvEscape(it ?: "") }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value
}

enum class FixStatus { OK, WEAK, NO_FIX }
