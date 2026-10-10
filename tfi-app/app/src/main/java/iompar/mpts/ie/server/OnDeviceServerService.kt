package iompar.mpts.ie.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import iompar.mpts.ie.MainActivity
import iompar.mpts.ie.service.DefaultAppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the on-device [AppServer]. Running in the foreground (with a
 * persistent notification) keeps Android from killing the Ktor server when the screen is off.
 * Start/stop is driven by the Settings toggle (and by [iompar.mpts.ie.BootReceiver] on boot if
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
        // Android requires startForeground() promptly after startForegroundService(), but the OS can
        // still refuse the promotion (background-start rules, timeouts) and throws an
        // IllegalStateException subclass. Treat that as "can't run now": stand down instead of
        // crashing the process (this is what surfaced as Sentry ANDROID-J from BOOT_COMPLETED).
        try {
            startForeground(FOREGROUND_NOTIF_ID, buildNotification("Starting on-device server…"))
        } catch (e: IllegalStateException) {
            android.util.Log.w("tfi", "on-device server foreground start refused", e)
            iompar.mpts.ie.Telemetry.captureException(e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (appServer == null) {
            scope.launch {
                val store = ServerSettingsStore(applicationContext)
                val mode = store.bindMode.first()
                val port = store.port.first()
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

                val host = bindHost(mode)
                if (host == null) {
                    // e.g. TAILSCALE selected but the tailnet isn't up — can't bind, so stand down.
                    nm.notify(FOREGROUND_NOTIF_ID, buildNotification("Can't start: ${mode.name.lowercase()} address unavailable"))
                    stopSelf()
                    return@launch
                }

                val server = AppServer(DefaultAppServices(applicationContext))
                server.start(host, port)
                appServer = server

                // Update the notification to show the reachable base URL.
                val shownHost = reachableHost(mode) ?: host
                nm.notify(FOREGROUND_NOTIF_ID, buildNotification("http://$shownHost:$port  (API /api · MCP /mcp)"))
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
