package com.example.mgrskor.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Компас на базі TYPE_ROTATION_VECTOR (має калібрування + low-pass прямо в сенсорній підсистемі).
 *
 * Видає азимут [0..360) у градусах **відносно істинної півночі** (true north),
 * якщо передано поточну геопозицію — інакше відносно магнітної півночі.
 *
 * Чому не TYPE_ORIENTATION: deprecated з API 8 (Froyo), має ризик гімбал-локу.
 * Чому не сирі ACCELEROMETER+MAGNETIC_FIELD: довелося б самим робити фільтр і калібрування.
 */
class CompassReader(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = rotationVector != null

    /** Читання азимуту. Закривається при cancel-і колектора. */
    fun headings(
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        altitudeMeters: Float = 0f
    ): Flow<HeadingSample> = callbackFlow {
        val sensor = rotationVector ?: run { close(); return@callbackFlow }

        val rMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        // Магнітна декларація для перерахунку magnetic→true north.
        val declination: Float = if (currentLatitude != null && currentLongitude != null) {
            GeomagneticField(
                currentLatitude.toFloat(),
                currentLongitude.toFloat(),
                altitudeMeters,
                System.currentTimeMillis()
            ).declination
        } else 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rMatrix, event.values)
                SensorManager.getOrientation(rMatrix, orientation)
                val azimuthRad = orientation[0] // -π..π, від магнітної півночі
                val pitchRad = orientation[1]
                val rollRad = orientation[2]
                val azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()) + declination + 360.0) % 360.0).toFloat()
                trySend(HeadingSample(azimuthDeg, Math.toDegrees(pitchRad.toDouble()).toFloat(),
                    Math.toDegrees(rollRad.toDouble()).toFloat(), declination))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { /* no-op */ }
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    data class HeadingSample(
        /** Азимут у градусах [0..360), 0 = північ, 90 = схід. True north якщо було передано lat/lon. */
        val azimuthDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        /** Магнітна декларація, що була застосована (град). */
        val declinationDeg: Float
    )

    companion object {
        @Suppress("unused")
        fun isSupported(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD
        }
    }
}
