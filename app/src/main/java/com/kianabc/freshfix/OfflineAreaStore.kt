package com.kianabc.freshfix

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.cos
import kotlin.math.hypot

data class Bounds(val north: Double, val south: Double, val east: Double, val west: Double) {
    val widthKm get() = (east - west) * KM_PER_DEG_LAT * cos(Math.toRadians((north + south) / 2))
    val heightKm get() = (north - south) * KM_PER_DEG_LAT

    companion object {
        const val KM_PER_DEG_LAT = 111.32
    }
}

data class OfflineArea(
    val id: Long,
    val name: String,
    val bounds: Bounds,
    val createdAt: Long,
    val addressCount: Int,
    val streetCount: Int,
    val mapRegionId: Long?,
)

data class OsmAddress(
    val lat: Double, val lon: Double,
    val number: String, val street: String,
    val city: String?, val state: String?, val postcode: String?,
)

data class OsmStreet(val name: String, val points: List<Pair<Double, Double>>)

/**
 * Local copy of OpenStreetMap addresses and named streets for downloaded areas, so the
 * photo stamp can still show an address with no data connection.
 */
class OfflineAreaStore(context: Context) :
    SQLiteOpenHelper(context, "offline_areas.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE areas (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
               north REAL, south REAL, east REAL, west REAL, created_at INTEGER,
               address_count INTEGER DEFAULT 0, street_count INTEGER DEFAULT 0, map_region_id INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE addresses (area_id INTEGER, lat REAL, lon REAL, number TEXT, street TEXT,
               city TEXT, state TEXT, postcode TEXT)"""
        )
        db.execSQL("CREATE INDEX addresses_lat ON addresses(lat, lon)")
        db.execSQL(
            """CREATE TABLE street_segments (area_id INTEGER, name TEXT, lat1 REAL, lon1 REAL, lat2 REAL, lon2 REAL,
               min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL)"""
        )
        db.execSQL("CREATE INDEX street_segments_lat ON street_segments(min_lat, max_lat)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun areas(): List<OfflineArea> = readableDatabase.rawQuery(
        "SELECT id, name, north, south, east, west, created_at, address_count, street_count, map_region_id FROM areas ORDER BY created_at DESC",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                OfflineArea(
                    id = c.getLong(0), name = c.getString(1),
                    bounds = Bounds(c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)),
                    createdAt = c.getLong(6), addressCount = c.getInt(7), streetCount = c.getInt(8),
                    mapRegionId = if (c.isNull(9)) null else c.getLong(9),
                )
            )
        }
    }

    fun createArea(name: String, bounds: Bounds): Long = writableDatabase.insert("areas", null, ContentValues().apply {
        put("name", name)
        put("north", bounds.north); put("south", bounds.south); put("east", bounds.east); put("west", bounds.west)
        put("created_at", System.currentTimeMillis())
    })

    fun finishArea(id: Long, mapRegionId: Long, addressCount: Int, streetCount: Int) {
        writableDatabase.update("areas", ContentValues().apply {
            put("map_region_id", mapRegionId); put("address_count", addressCount); put("street_count", streetCount)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteArea(id: Long) {
        val args = arrayOf(id.toString())
        writableDatabase.inTx {
            delete("addresses", "area_id = ?", args)
            delete("street_segments", "area_id = ?", args)
            delete("areas", "id = ?", args)
        }
    }

    fun insertAddresses(areaId: Long, batch: List<OsmAddress>) = writableDatabase.inTx {
        val stmt = compileStatement(
            "INSERT INTO addresses (area_id, lat, lon, number, street, city, state, postcode) VALUES (?,?,?,?,?,?,?,?)"
        )
        for (a in batch) {
            stmt.clearBindings()
            stmt.bindLong(1, areaId); stmt.bindDouble(2, a.lat); stmt.bindDouble(3, a.lon)
            stmt.bindString(4, a.number); stmt.bindString(5, a.street)
            a.city?.let { stmt.bindString(6, it) }; a.state?.let { stmt.bindString(7, it) }
            a.postcode?.let { stmt.bindString(8, it) }
            stmt.executeInsert()
        }
    }

    fun insertStreets(areaId: Long, batch: List<OsmStreet>) = writableDatabase.inTx {
        val stmt = compileStatement(
            "INSERT INTO street_segments (area_id, name, lat1, lon1, lat2, lon2, min_lat, max_lat, min_lon, max_lon) VALUES (?,?,?,?,?,?,?,?,?,?)"
        )
        for (street in batch) {
            for ((a, b) in street.points.zipWithNext()) {
                stmt.clearBindings()
                stmt.bindLong(1, areaId); stmt.bindString(2, street.name)
                stmt.bindDouble(3, a.first); stmt.bindDouble(4, a.second)
                stmt.bindDouble(5, b.first); stmt.bindDouble(6, b.second)
                stmt.bindDouble(7, minOf(a.first, b.first)); stmt.bindDouble(8, maxOf(a.first, b.first))
                stmt.bindDouble(9, minOf(a.second, b.second)); stmt.bindDouble(10, maxOf(a.second, b.second))
                stmt.executeInsert()
            }
        }
    }

    /**
     * Nearest house address within [MAX_ADDRESS_M], otherwise "near <street>" for the nearest
     * named street within [MAX_STREET_M]. Null when the point isn't inside downloaded data.
     */
    fun lookup(lat: Double, lon: Double): String? {
        val db = readableDatabase
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(lat))
        fun dist(pLat: Double, pLon: Double) = hypot((pLat - lat) * METERS_PER_DEG_LAT, (pLon - lon) * metersPerDegLon)

        val dLat = SEARCH_M / METERS_PER_DEG_LAT
        val dLon = SEARCH_M / metersPerDegLon
        val box = arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())

        val nearestAddress = db.rawQuery(
            "SELECT lat, lon, number, street, city, state, postcode FROM addresses WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            box,
        ).use { c ->
            var best: Pair<Double, OsmAddress>? = null
            while (c.moveToNext()) {
                val a = OsmAddress(c.getDouble(0), c.getDouble(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
                val d = dist(a.lat, a.lon)
                if (best == null || d < best.first) best = d to a
            }
            best
        }
        if (nearestAddress != null && nearestAddress.first <= MAX_ADDRESS_M) {
            val a = nearestAddress.second
            return listOfNotNull("${a.number} ${a.street}", a.city, listOfNotNull(a.state, a.postcode).joinToString(" ").ifBlank { null })
                .joinToString(", ")
        }

        val nearestStreet = db.rawQuery(
            "SELECT name, lat1, lon1, lat2, lon2 FROM street_segments WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?",
            arrayOf(box[1], box[0], box[3], box[2]),
        ).use { c ->
            var best: Pair<Double, String>? = null
            while (c.moveToNext()) {
                val d = distanceToSegment(
                    0.0, 0.0,
                    (c.getDouble(2) - lon) * metersPerDegLon, (c.getDouble(1) - lat) * METERS_PER_DEG_LAT,
                    (c.getDouble(4) - lon) * metersPerDegLon, (c.getDouble(3) - lat) * METERS_PER_DEG_LAT,
                )
                if (best == null || d < best.first) best = d to c.getString(0)
            }
            best
        }
        if (nearestStreet == null || nearestStreet.first > MAX_STREET_M) return null
        val city = nearestAddress?.second?.city ?: nearbyCity(lat, lon)
        return listOfNotNull("near ${nearestStreet.second}", city).joinToString(", ")
    }

    private fun nearbyCity(lat: Double, lon: Double): String? = readableDatabase.rawQuery(
        "SELECT city FROM addresses WHERE city IS NOT NULL AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 1",
        arrayOf((lat - 0.02).toString(), (lat + 0.02).toString(), (lon - 0.02).toString(), (lon + 0.02).toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private inline fun <T> SQLiteDatabase.inTx(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val SEARCH_M = 120.0
        private const val MAX_ADDRESS_M = 40.0
        private const val MAX_STREET_M = 60.0
    }
}
