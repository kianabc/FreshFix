package com.kianabc.freshfix

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Streams raw GNSS fixes once per second while the camera is open.
 *
 * The stock camera geotags with whatever position the OS last cached, which is why a
 * walk down a street collapses onto two or three points. This class never asks for
 * a "last known" location; every photo is matched to a live fix taken around the
 * moment the shutter was pressed.
 */
class GpsTracker(context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<Location>()
    private var running = false

    private val _latest = MutableStateFlow<Location?>(null)
    val latest: StateFlow<Location?> = _latest

    private val _satellitesUsed = MutableStateFlow(0)
    val satellitesUsed: StateFlow<Int> = _satellitesUsed

    private val listener = LocationListener { location ->
        synchronized(history) {
            history.addLast(location)
            while (history.size > HISTORY_SIZE) history.removeFirst()
        }
        _latest.value = location
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            _satellitesUsed.value = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    @SuppressLint("MissingPermission") // Callers only start after the permission is granted.
    fun start() {
        if (running) return
        running = true
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
        )
        locationManager.registerGnssStatusCallback(gnssCallback, mainHandler)
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
    }

    /**
     * Returns the fix closest in time to [shutterNanos] (an elapsedRealtimeNanos value).
     *
     * Waits up to [maxWaitMs] for the first fix at or after the shutter so a photo taken
     * just before the next 1 Hz fix arrives still gets the nearest one. Returns null only
     * when no fix has been received at all.
     */
    suspend fun fixFor(shutterNanos: Long, maxWaitMs: Long = 4_000): Location? {
        withTimeoutOrNull(maxWaitMs) {
            latest.first { it != null && it.elapsedRealtimeNanos >= shutterNanos }
        }
        return synchronized(history) {
            history.minByOrNull { abs(it.elapsedRealtimeNanos - shutterNanos) }
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val HISTORY_SIZE = 120
    }
}
