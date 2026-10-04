package com.kianabc.freshfix

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reverse-geocodes a fix to a street address using the phone's built-in geocoder.
 * Needs a data connection; returns null offline or after [timeoutMs] so a photo is never held up.
 */
class AddressLookup(context: Context) {

    private val geocoder = Geocoder(context)

    suspend fun lookup(location: Location, timeoutMs: Long = 4_000): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { fetch(location) }.getOrNull()?.let(::format)
        }
    }

    private suspend fun fetch(location: Location): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    location.latitude, location.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }
        }

    private fun format(address: Address): String? {
        val line = address.getAddressLine(0)
        if (!line.isNullOrBlank()) return line
        return listOfNotNull(
            listOfNotNull(address.subThoroughfare, address.thoroughfare).joinToString(" ").ifBlank { null },
            address.locality,
            address.adminArea,
            address.postalCode,
        ).joinToString(", ").ifBlank { null }
    }
}
