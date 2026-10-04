package com.kianabc.freshfix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException

data class DownloadProgress(
    val name: String,
    val tilesDone: Long = 0,
    val tilesRequired: Long = 0,
    val mapDone: Boolean = false,
    val addressesDone: Boolean = false,
    val addressCount: Int = 0,
    val note: String? = null,
)

/**
 * Downloads one area for offline use: vector map tiles (shown on the map screen) and
 * OpenStreetMap addresses (used to stamp photos when there's no signal), in parallel.
 */
class AreaDownloader(context: Context, private val store: OfflineAreaStore) {

    private val appContext = context.applicationContext
    private val offlineManager = OfflineManager.getInstance(appContext)
    private val overpass = OverpassClient()

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress

    private val _areas = MutableStateFlow(store.areas())
    val areas: StateFlow<List<OfflineArea>> = _areas

    private val _message = MutableStateFlow<String?>(null)
    /** Outcome of the last download, for the map screen to show. */
    val message: StateFlow<String?> = _message

    private var job: Job? = null

    /** Runs in [scope] (the activity's) so the download survives leaving the map screen. */
    fun start(scope: CoroutineScope, name: String, bounds: Bounds) {
        if (job?.isActive == true) return
        _message.value = null
        job = scope.launch {
            _message.value = try {
                download(name, bounds)
                val area = _areas.value.firstOrNull()
                "Saved $name: ${area?.addressCount ?: 0} addresses, ${area?.streetCount ?: 0} streets"
            } catch (e: CancellationException) {
                "Download cancelled"
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Throws on failure; partial data is removed so a failed area never half-exists. */
    private suspend fun download(name: String, bounds: Bounds) {
        val areaId = store.createArea(name, bounds)
        var regionId: Long? = null
        _progress.value = DownloadProgress(name)
        try {
            coroutineScope {
                val map = async {
                    downloadTiles(name, bounds) { regionId = it }.also {
                        _progress.update { p -> p?.copy(mapDone = true) }
                    }
                }
                val addresses = async(Dispatchers.IO) {
                    overpass.download(
                        bounds,
                        onAddresses = { batch ->
                            store.insertAddresses(areaId, batch)
                            _progress.update { p -> p?.copy(addressCount = p.addressCount + batch.size) }
                        },
                        onStreets = { store.insertStreets(areaId, it) },
                    ).also { _progress.update { p -> p?.copy(addressesDone = true) } }
                }
                val counts = addresses.await()
                store.finishArea(areaId, map.await(), counts.addresses, counts.streets)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.deleteArea(areaId) }
                regionId?.let { deleteRegion(it) }
            }
            throw e
        } finally {
            _progress.value = null
            _areas.value = store.areas()
        }
    }

    suspend fun delete(area: OfflineArea) {
        area.mapRegionId?.let { deleteRegion(it) }
        withContext(Dispatchers.IO) { store.deleteArea(area.id) }
        _areas.value = store.areas()
    }

    private suspend fun downloadTiles(name: String, bounds: Bounds, onCreated: (Long) -> Unit): Long =
        suspendCancellableCoroutine { cont ->
            val definition = OfflineTilePyramidRegionDefinition(
                STYLE_URL,
                LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
                MIN_ZOOM, MAX_ZOOM,
                appContext.resources.displayMetrics.density,
            )
            offlineManager.createOfflineRegion(definition, name.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    onCreated(offlineRegion.id)
                    cont.invokeOnCancellation { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            _progress.update {
                                it?.copy(tilesDone = status.completedResourceCount, tilesRequired = status.requiredResourceCount)
                            }
                            if (status.isComplete && cont.isActive) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                cont.resume(offlineRegion.id)
                            }
                        }

                        // MapLibre keeps retrying on its own, so surface errors without failing the download.
                        override fun onError(error: OfflineRegionError) {
                            _progress.update { it?.copy(note = "Map: ${error.message} (retrying)") }
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Map download failed: $error"))
                }
            })
        }

    private suspend fun deleteRegion(regionId: Long) = suspendCancellableCoroutine { cont ->
        offlineManager.getOfflineRegion(regionId, object : OfflineManager.GetOfflineRegionCallback {
            override fun onRegion(offlineRegion: OfflineRegion) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) = cont.resume(Unit)
                })
            }

            override fun onRegionNotFound() = cont.resume(Unit)
            override fun onError(error: String) = cont.resume(Unit)
        })
    }

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        // OpenFreeMap's vector tiles stop at z14; the map over-zooms them for closer views.
        private const val MIN_ZOOM = 8.0
        private const val MAX_ZOOM = 14.0
        const val MAX_SIDE_KM = 15.0
    }
}
