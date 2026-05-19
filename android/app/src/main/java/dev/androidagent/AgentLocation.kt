package dev.androidagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class AgentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String?,
    val capturedAtMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("capturedAtMs", capturedAtMs)
            .also { json ->
                accuracyMeters?.let { json.put("accuracyMeters", it.toDouble()) }
                provider?.takeIf { it.isNotBlank() }?.let { json.put("provider", it) }
            }
    }
}

object AgentLocationProvider {
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun currentBestEffortLocation(context: Context): AgentLocation? {
        if (!hasLocationPermission(context)) {
            return null
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // Permission can still be revoked between the check and the call, so each provider read is isolated.
        val location = runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxWithOrNull(compareBy<Location> { it.time }.thenBy { if (it.hasAccuracy()) -it.accuracy else Float.NEGATIVE_INFINITY })
        }.getOrNull() ?: return null

        return AgentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            provider = location.provider,
            capturedAtMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }
}
