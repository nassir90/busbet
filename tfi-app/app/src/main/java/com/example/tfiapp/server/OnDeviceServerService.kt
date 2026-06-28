package com.example.tfiapp.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.tfiapp.MainActivity
import com.example.tfiapp.service.DefaultAppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the on-device [AppServer]. Running in the foreground (with a
 * persistent notification) keeps Android from killing the Ktor server when the screen is off.
 * Start/stop is driven by the Settings toggle (and by [com.example.tfiapp.BootReceiver] on boot if
 * the server was left enabled). Bind address + port are read from [ServerSettingsStore].
 */
class OnDeviceServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appServer: AppServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() promptly after startForegroundService().
        startForeground(FOREGROUND_NOTIF_ID, buildNotification("Starting on-device server…"))
        if (appServer == null) {
            scope.launch {
                val store = ServerSettingsStore(applicationContext)
                val bindLan = store.bindLan.first()
                val port = store.port.first()
                val server = AppServer(DefaultAppServices(applicationContext))
                server.start(bindHost(bindLan), port)
                appServer = server

                // Update the notification to show the reachable base URL.
                val host = if (bindLan) (lanIpv4() ?: "<this device's IP>") else LOOPBACK_HOST
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(FOREGROUND_NOTIF_ID, buildNotification("http://$host:$port  (API /api · MCP /mcp)"))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        appServer?.stop()
        appServer = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("On-device server running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val FOREGROUND_NOTIF_ID = 1002
        const val CHANNEL_ID = "server_fg"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "On-device server", NotificationManager.IMPORTANCE_LOW)
                    .also { it.description = "Persistent notification while the MCP/HTTP server is running" },
            )
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OnDeviceServerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OnDeviceServerService::class.java))
        }
    }
}
