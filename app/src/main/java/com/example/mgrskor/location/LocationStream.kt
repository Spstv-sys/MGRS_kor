package com.example.mgrskor.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Тонка обгортка над FusedLocationProviderClient, що повертає Flow<Location>.
 * Не зберігає стану збору — це справа ViewModel.
 */
class LocationStream(context: Context) {

    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val appContext = context.applicationContext

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Безперервний потік вимірювань. Прибирає підписку при скасуванні корутини.
     * Якщо немає дозволу — кидає SecurityException у callbackFlow.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long, waitForAccurate: Boolean): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(waitForAccurate)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) trySend(loc)
            }
        }

        try {
            fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (se: SecurityException) {
            close(se)
            return@callbackFlow
        }
        awaitClose { fused.removeLocationUpdates(cb) }
    }

    /** Скільки мс минуло з моменту реального fix-а. */
    fun fixAgeMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
}
