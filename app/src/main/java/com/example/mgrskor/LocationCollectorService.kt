package com.example.mgrskor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mgrskor.location.LocationCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service, що тримає процес живим під час збору координат
 * (потрібно для отримання локації при заблокованому/фоновому Activity на Android 10+),
 * а також показує поточний прогрес у нотифікації.
 *
 * Сама бізнес-логіка живе в [LocationCollector] (синглтоні).
 */
class LocationCollectorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LocationCollector.stopCollecting()
                LocationCollector.stopTracking()
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat(buildNotification(getString(R.string.svc_starting)))
        // Запустити збір лише якщо явно попрошено (інакше сервіс просто тримає процес
        // живим для трекінгу).
        if (intent?.getBooleanExtra(EXTRA_START_COLLECTING, false) == true) {
            LocationCollector.startCollecting(this)
        }
        observeStateAndUpdateNotification()
        return START_STICKY
    }

    private fun observeStateAndUpdateNotification() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            LocationCollector.state.collectLatest { snap ->
                val baseText = when (val p = snap.phase) {
                    LocationCollector.Phase.Idle ->
                        snap.lastResult?.let {
                            getString(R.string.svc_done, it.mgrs)
                        } ?: getString(R.string.svc_starting)
                    LocationCollector.Phase.PermissionRequired ->
                        getString(R.string.permission_denied)
                    LocationCollector.Phase.NoFix ->
                        getString(R.string.no_good_fix)
                    LocationCollector.Phase.Finalizing ->
                        getString(R.string.processing_result)
                    is LocationCollector.Phase.WarmingUp ->
                        getString(R.string.warmup, p.seen, p.total)
                    is LocationCollector.Phase.Collecting -> {
                        val acc = p.currentSigmaMeters?.let {
                            String.format(Locale.US, "%.1f", it)
                        } ?: "—"
                        getString(R.string.svc_collecting, p.collected, p.target, acc)
                    }
                }
                val text = if (snap.tracking) {
                    baseText + " · " + getString(R.string.svc_tracking, snap.trackedCount)
                } else baseText
                val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(NOTIF_ID, buildNotification(text))

                // Не зупиняти сервіс якщо активний трекінг.
                if (!snap.tracking && (
                        snap.phase is LocationCollector.Phase.Idle ||
                        snap.phase is LocationCollector.Phase.NoFix
                    )
                ) {
                    stopSelfSafely()
                }
            }
        }
    }

    private fun stopSelfSafely() {
        observerJob?.cancel()
        observerJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: тип потрібно вказати явно
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.svc_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.svc_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LocationCollectorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.btn_stop), stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "mgrs_collect"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.example.mgrskor.action.STOP"
        const val EXTRA_START_COLLECTING = "start_collecting"

        /** Запуск для збору координат (стартує LocationCollector.startCollecting). */
        fun start(context: Context) = startInternal(context, collecting = true)

        /** Запуск просто для трекінгу / фонової роботи. */
        fun startTrackingOnly(context: Context) = startInternal(context, collecting = false)

        private fun startInternal(context: Context, collecting: Boolean) {
            val i = Intent(context, LocationCollectorService::class.java)
                .putExtra(EXTRA_START_COLLECTING, collecting)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationCollectorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
