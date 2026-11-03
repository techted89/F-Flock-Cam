package com.techted89.flockcam.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class FlockMonitoringService : Service() {

    companion object {
        private const val CHANNEL_ID = "flock_monitoring_channel"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"

        fun start(context: Context, action: String) {
            val intent = Intent(context, FlockMonitoringService::class.java).apply {
                putExtra(EXTRA_ACTION, action)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlockMonitoringService::class.java))
        }

        private const val EXTRA_ACTION = "flock_action_extra"
    }

    private var notification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "F Flock Cam",
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("F Flock Cam")
            .setContentText("Monitoring nearby flock cameras")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START_MONITORING
        when (action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        val current = notification ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("F Flock Cam")
            .setContentText("Monitoring nearby flock cameras")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, current)
        (applicationContext as? com.techted89.flockcam.FlockCamApplication)?.locationMonitor?.start()
    }

    private fun stopMonitoring() {
        stopForeground(STOP_FOREGROUND_DETACH)
        (applicationContext as? com.techted89.flockcam.FlockCamApplication)?.locationMonitor?.stop()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
