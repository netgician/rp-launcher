package com.netgician.rplauncher.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the phone is, from its own location service, and the street address from the
 * phone's own address lookup. Nothing is sent anywhere except in the texts the person sends.
 */
object Where {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /** The place right now (up to [timeoutMs] to find it), with its street address when the phone can look it up. */
    suspend fun place(context: Context, timeoutMs: Long = 20_000): Place? {
        val loc = location(context, timeoutMs) ?: return null
        val address = address(context, loc)
        return Place(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.time, address)
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(context: Context, timeoutMs: Long): Location? {
        if (!allowed(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val last = runCatching {
            lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
        // A fix from the last minute that's good to 100 m is used straight away.
        if (last != null && System.currentTimeMillis() - last.time < 60_000 && last.hasAccuracy() && last.accuracy <= 100f) return last

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        val provider = providers.firstOrNull() ?: return last
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { l -> if (cont.isActive) cont.resume(l) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(l: Location) {
                            if (cont.isActive) cont.resume(l)
                        }

                        // Android 10 has no defaults for these.
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Deprecated("Deprecated in Android")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return fresh ?: last
    }

    /** "12 Oak Street, Raleigh, NC 27601", or null when the phone can't look it up. */
    suspend fun address(context: Context, loc: Location): String? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, com.netgician.rplauncher.i18n.Lang.locale)
        val found: List<Address>? = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    g.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { g.getFromLocation(loc.latitude, loc.longitude, 1) }.getOrNull()
                }
            }
        }
        val a = found?.firstOrNull() ?: return null
        return describe(a)
    }

    private fun describe(a: Address): String? {
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null }
        val parts = listOfNotNull(street, a.locality ?: a.subAdminArea, a.adminArea).distinct()
        return parts.joinToString(", ").ifBlank { a.getAddressLine(0) }
    }
}
