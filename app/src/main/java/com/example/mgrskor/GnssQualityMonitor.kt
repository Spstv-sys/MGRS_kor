package com.example.mgrskor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Реєструє GNSS-callbacks. Сама лише реєстрація `GnssMeasurementsCallback`
 * стимулює деякі чіпсети активувати L5/E5a (двочастотний прийом),
 * що різко зменшує multipath-помилки під кронами дерев.
 *
 * Також віддає назовні: к-сть видимих супутників, к-сть використаних у fix-у,
 * середній/макс CN0 (дБГц) — індикатор «сили» сигналу.
 */
class GnssQualityMonitor(
    private val context: Context,
    private val onUpdate: (Stats) -> Unit
) {
    data class Stats(
        val visible: Int = 0,
        val usedInFix: Int = 0,
        val maxCn0: Float = 0f,
        val avgCn0: Float = 0f,
        val hasL5: Boolean = false
    )

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var statusCb: GnssStatus.Callback? = null
    private var measCb: GnssMeasurementsEvent.Callback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (statusCb != null) return // вже запущено

        statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val supportsCarrierFreq = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                val n = status.satelliteCount
                var used = 0
                var sumCn0 = 0f
                var maxCn0 = 0f
                var hasL5 = false
                for (i in 0 until n) {
                    val cn0 = status.getCn0DbHz(i)
                    if (status.usedInFix(i)) {
                        used++
                        sumCn0 += cn0
                        if (cn0 > maxCn0) maxCn0 = cn0
                    }
                    // L5 ≈ 1176.45 МГц, E5a той самий діапазон
                    if (supportsCarrierFreq && status.hasCarrierFrequencyHz(i)) {
                        val freq = status.getCarrierFrequencyHz(i)
                        if (freq in 1.16e9f..1.19e9f) hasL5 = true
                    }
                }
                val avg = if (used > 0) sumCn0 / used else 0f
                onUpdate(Stats(n, used, maxCn0, avg, hasL5))
            }
        }
        lm.registerGnssStatusCallback(statusCb!!, handler)

        // Сама реєстрація вже «вмикає» сирі виміри і стимулює L1+L5.
        measCb = object : GnssMeasurementsEvent.Callback() {}
        try {
            lm.registerGnssMeasurementsCallback(measCb!!)
        } catch (_: Throwable) { /* пристрій може не підтримувати */ }
    }

    fun stop() {
        statusCb?.let { lm.unregisterGnssStatusCallback(it) }
        measCb?.let { lm.unregisterGnssMeasurementsCallback(it) }
        statusCb = null
        measCb = null
    }
}
