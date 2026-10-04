package com.kianabc.freshfix

import android.util.JsonReader
import android.util.JsonToken
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads OpenStreetMap house addresses and named streets for an area from the Overpass API,
 * streaming the response so a whole neighbourhood never has to sit in memory at once.
 */
class OverpassClient {

    data class Counts(val addresses: Int, val streets: Int)

    /**
     * The public Overpass servers often answer 429/504 under load, so try each in turn.
     * Callbacks only fire once a server starts returning data, so a retry never double-inserts.
     */
    fun download(
        bounds: Bounds,
        onAddresses: (List<OsmAddress>) -> Unit,
        onStreets: (List<OsmStreet>) -> Unit,
    ): Counts {
        // Report the main server's error; the mirrors' errors are less telling.
        var firstError: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                return download(endpoint, bounds, onAddresses, onStreets)
            } catch (e: java.io.IOException) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: IllegalStateException("No address server available")
    }

    private class RetryableException(message: String) : java.io.IOException(message)

    private fun download(
        endpoint: String,
        bounds: Bounds,
        onAddresses: (List<OsmAddress>) -> Unit,
        onStreets: (List<OsmStreet>) -> Unit,
    ): Counts {
        val bbox = "${bounds.south},${bounds.west},${bounds.north},${bounds.east}"
        val query = """
            [out:json][timeout:180];
            nwr["addr:housenumber"]["addr:street"]($bbox);
            out center tags;
            way["highway"]["name"]($bbox);
            out geom tags;
        """.trimIndent()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 200_000
            setRequestProperty("User-Agent", "FreshFix/${BuildConfig.VERSION_NAME} (github.com/kianabc/FreshFix)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) {
                throw RetryableException(
                    when (conn.responseCode) {
                        429 -> "Address servers are busy. Try again in a minute."
                        504 -> "Address servers timed out. Try again or pick a smaller area."
                        else -> "Address server returned HTTP ${conn.responseCode}"
                    }
                )
            }
            // Past this point data may already be stored, so a failure must not fall through to a mirror.
            try {
                return JsonReader(conn.inputStream.bufferedReader()).use { parse(it, onAddresses, onStreets) }
            } catch (e: java.io.IOException) {
                throw IllegalStateException("Address download interrupted: ${e.message}", e)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(
        reader: JsonReader,
        onAddresses: (List<OsmAddress>) -> Unit,
        onStreets: (List<OsmStreet>) -> Unit,
    ): Counts {
        val addresses = ArrayList<OsmAddress>(BATCH)
        val streets = ArrayList<OsmStreet>(BATCH)
        var addressCount = 0
        var streetCount = 0

        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "elements") {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                val element = readElement(reader)
                val tags = element.tags
                val point = element.point
                val number = tags["addr:housenumber"]
                val street = tags["addr:street"]
                if (number != null && street != null && point != null) {
                    addresses += OsmAddress(
                        point.first, point.second, number, street,
                        tags["addr:city"], tags["addr:state"], tags["addr:postcode"],
                    )
                    addressCount++
                    if (addresses.size >= BATCH) { onAddresses(addresses.toList()); addresses.clear() }
                }
                val name = tags["name"]
                if (tags["highway"] != null && name != null && element.geometry.size >= 2) {
                    streets += OsmStreet(name, element.geometry)
                    streetCount++
                    if (streets.size >= BATCH) { onStreets(streets.toList()); streets.clear() }
                }
            }
            reader.endArray()
        }
        reader.endObject()
        if (addresses.isNotEmpty()) onAddresses(addresses)
        if (streets.isNotEmpty()) onStreets(streets)
        return Counts(addressCount, streetCount)
    }

    private class Element(
        val tags: Map<String, String>,
        val point: Pair<Double, Double>?,
        val geometry: List<Pair<Double, Double>>,
    )

    private fun readElement(reader: JsonReader): Element {
        var tags = emptyMap<String, String>()
        var lat: Double? = null
        var lon: Double? = null
        var center: Pair<Double, Double>? = null
        var geometry = emptyList<Pair<Double, Double>>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "lat" -> lat = reader.nextDouble()
                "lon" -> lon = reader.nextDouble()
                "center" -> center = readLatLon(reader)
                "tags" -> tags = readTags(reader)
                "geometry" -> geometry = readGeometry(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val point = if (lat != null && lon != null) lat to lon else center
        return Element(tags, point, geometry)
    }

    private fun readLatLon(reader: JsonReader): Pair<Double, Double>? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        var lat: Double? = null
        var lon: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "lat" -> lat = reader.nextDouble()
                "lon" -> lon = reader.nextDouble()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun readTags(reader: JsonReader): Map<String, String> {
        val tags = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key in WANTED_TAGS) tags[key] = reader.nextString() else reader.skipValue()
        }
        reader.endObject()
        return tags
    }

    private fun readGeometry(reader: JsonReader): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        reader.beginArray()
        // Gaps in a clipped way come back as null entries.
        while (reader.hasNext()) readLatLon(reader)?.let(points::add)
        reader.endArray()
        return points
    }

    companion object {
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        private const val BATCH = 1_000
        private val WANTED_TAGS = setOf(
            "addr:housenumber", "addr:street", "addr:city", "addr:state", "addr:postcode", "highway", "name",
        )
    }
}
