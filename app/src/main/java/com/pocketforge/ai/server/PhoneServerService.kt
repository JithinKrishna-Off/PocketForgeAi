package com.pocketforge.ai.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pocketforge.ai.MainActivity
import com.pocketforge.ai.PocketForgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the phone server while the settings toggle is on.
 * START_NOT_STICKY: process death kills the server and the toggle flips back
 * off (see SettingsViewModel), so the user opts in every boot. The service only
 * binds/unbinds the listener; the model stays loaded.
 */
class PhoneServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = applicationContext as PocketForgeApp

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // GlobalScope: the write must survive onDestroy, which cancels serviceScope
            GlobalScope.launch(Dispatchers.IO) { runCatching { app.settingsStore.setServerEnabled(false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat("PocketForge server is starting...")
        serviceScope.launch {
            val s = app.settingsStore.settings.first()
            val ok = PhoneServerController.get(applicationContext).start(s.serverPort, s.serverLanAccess)
            if (!ok) {
                // keep the toggle honest: no bind, no server
                runCatching { app.settingsStore.setServerEnabled(false) }
                stopSelf()
            } else {
                notifyRunning(PhoneServerController.get(applicationContext).status.value.port)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PhoneServerController.get(applicationContext).stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val notification = builder(text).build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyRunning(port: Int) {
        val notification = builder("PocketForge server - port $port").build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun builder(text: String): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, PhoneServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PocketForge server")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desktop server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Local server for the PocketForge desktop client" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "pf_server"
        const val ACTION_STOP = "com.pocketforge.ai.server.STOP"
        const val NOTIFICATION_ID = 8751
    }
}
