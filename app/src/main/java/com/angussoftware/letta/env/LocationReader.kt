package com.angussoftware.letta.env

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LocationReader — framework LocationManager, no play-services (spec §3).
 * Fine+coarse declared; FINE requested at runtime from MainActivity.
 *
 * Read = last-known across GPS/NETWORK/PASSIVE providers (newest wins),
 * falling back to a single getCurrentLocation with a 15s timeout when no
 * cache exists. The scoped permission grant IS the consent — errors are
 * actionable JSON, never silent.
 */
object LocationReader {

    fun read(ctx: Context): JSONObject {
        if (!granted(ctx)) return JSONObject().put("ok", false)
            .put("error", "location not granted — open the Agents Beamdown app > " +
                "Telemetry and grant location access, then retry")

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val cached = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

        return if (cached != null) format(cached)
        else fetchCurrent(lm)?.let { format(it) }
            ?: JSONObject().put("ok", false)
                .put("error", "no location available (no last-known fix; current fetch " +
                    "timed out or all providers off)")
    }

    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun fetchCurrent(lm: LocationManager): Location? {
        // getCurrentLocation (API 30+). On API 26–29 fall back to a one-shot
        // requestLocationUpdates + remove — same effect, older surface.
        val done = CountDownLatch(1)
        var result: Location? = null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                result = l
                done.countDown()
                runCatching { lm.removeUpdates(this) }
            }
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null,
                    java.util.concurrent.Executor { it.run() },
                    java.util.function.Consumer<Location?> { r -> result = r; done.countDown() })
            } else {
                lm.requestLocationUpdates(provider, 0L, 0f, listener,
                    Looper.getMainLooper())
            }
        }.onFailure { return null }
        done.await(15, TimeUnit.SECONDS)
        runCatching { lm.removeUpdates(listener) }
        return result
    }

    private fun format(l: Location): JSONObject = JSONObject().put("ok", true)
        .put("lat", l.latitude)
        .put("lon", l.longitude)
        .put("accuracyM", if (l.hasAccuracy()) l.accuracy.toDouble() else null)
        .put("ageSec", (System.currentTimeMillis() - l.time) / 1000)
        .put("provider", l.provider)
}
