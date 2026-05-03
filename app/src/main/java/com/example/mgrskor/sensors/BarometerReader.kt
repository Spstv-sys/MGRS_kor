package com.example.mgrskor.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Барометричний альтиметр. Точність відносної висоти на маршруті — кращ а за GPS-висоту
 * (типово 1–2 м), якщо є опорний тиск.
 *
 * [Altimeter.compute] — чиста функція. Сам клас [BarometerReader] лише крутить підписку.
 */
class BarometerReader(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = pressureSensor != null

    /** Видає тиск у hPa з частотою UI. */
    fun pressuresHpa(): Flow<Float> = callbackFlow {
        val sensor = pressureSensor ?: run { close(); return@callbackFlow }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_PRESSURE) trySend(event.values[0])
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(l) }
    }
}

/**
 * Чиста математика висотомірії — без Android API, тестується.
 */
object Altimeter {

    /** Стандартний тиск на рівні моря, hPa. */
    const val P0_STANDARD: Float = SensorManager.PRESSURE_STANDARD_ATMOSPHERE

    /**
     * Абсолютна висота над рівнем моря у метрах за барометричною формулою.
     * @param pressureHpa поточний тиск, hPa.
     * @param seaLevelPressureHpa опорний тиск на рівні моря (за замовчуванням 1013.25).
     */
    fun altitudeMeters(pressureHpa: Float, seaLevelPressureHpa: Float = P0_STANDARD): Double {
        // Та сама формула, що SensorManager.getAltitude(), але як чиста функція.
        // h = 44330 * (1 - (p/p0)^(1/5.255))
        return 44_330.0 * (1.0 - Math.pow((pressureHpa / seaLevelPressureHpa).toDouble(), 1.0 / 5.255))
    }

    /**
     * Якщо ми знаємо «правильну» висоту в опорній точці (з GPS або з мапи) і поточний тиск там,
     * можемо порахувати ефективний "sea level pressure" і потім міряти всі інші точки відносно нього.
     */
    fun calibrateSeaLevelPressure(currentPressureHpa: Float, knownAltitudeM: Double): Float {
        // p0 = p / (1 - h/44330)^5.255
        val ratio = 1.0 - knownAltitudeM / 44_330.0
        if (ratio <= 0.0) return P0_STANDARD
        return (currentPressureHpa / Math.pow(ratio, 5.255)).toFloat()
    }
}
