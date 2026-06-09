package com.example.tfiapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

class BusNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            scope.launch { checkAndNotify() }
        } else {
            scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        while (true) {
            checkAndNotify()
            delay(TICK_MS)
        }
    }

    private suspend fun checkAndNotify() {
        val windows = NotificationWindowStore(this).flow.first()
        val today   = LocalDate.now().dayOfWeek.value
        val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }

        val active = windows.filter { it.enabled && today in it.days && nowMins in it.startMinute..it.endMinute }
        if (active.isEmpty()) return

        val theme = ThemeStore(this).flow.first()
        val accentColor = themeAccentColor(theme)

        active.forEach { window ->
            runCatching {
                val deps = Api.service.departures(window.stopCode).departures
                val matching = deps.filter { d ->
                    window.routes.isEmpty() || d.routeShortName in window.routes
                }.take(2)
                if (matching.isNotEmpty()) postBusNotification(window, matching, accentColor)
            }
        }
    }

    private fun postBusNotification(window: NotificationWindow, deps: List<Departure>, accentColor: Int) {
        val nm      = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }

        // Tap opens the stop board for this window's stop
        val tapIntent = PendingIntent.getActivity(
            this, window.id.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                putExtra("stopCode", window.stopCode)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(window.name)
            .setSummaryText(window.stopName)

        deps.forEach { d ->
            val effMins = toMinutes(d.estimatedDeparture ?: d.scheduledDeparture)
            val due     = (effMins - nowMins).let { if (it < -720) it + 1440 else it }
            val dueStr  = if (due <= 0) "Due" else "${due} min"
            val timeStr = d.estimatedDeparture ?: d.scheduledDeparture

            // Bold the route number, plain text for the rest
            val line = SpannableString("${d.routeShortName}  ${d.tripHeadsign}  $timeStr  $dueStr")
            line.setSpan(StyleSpan(Typeface.BOLD), 0, d.routeShortName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            style.addLine(line)
        }

        // Summary line shown in collapsed state
        val summary = deps.joinToString("   ") { d ->
            val effMins = toMinutes(d.estimatedDeparture ?: d.scheduledDeparture)
            val due = (effMins - nowMins).let { if (it < -720) it + 1440 else it }
            "${d.routeShortName} ${if (due <= 0) "Due" else "${due}m"}"
        }

        nm.notify(
            window.id.hashCode(),
            NotificationCompat.Builder(this, BUS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(window.name)
                .setContentText(summary)
                .setSubText(window.stopName)
                .setStyle(style)
                .setColor(accentColor)
                .setColorized(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(tapIntent)
                .build()
        )
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bus notifications active")
            .setContentText("Checking departures every 2 minutes")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toMinutes(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return h * 60 + m
    }

    companion object {
        private const val ACTION_REFRESH      = "com.example.tfiapp.REFRESH"
        private const val FOREGROUND_NOTIF_ID = 1001
        private const val TICK_MS             = 2 * 60 * 1000L
        const val FG_CHANNEL_ID               = "bus_fg"
        const val BUS_CHANNEL_ID              = "bus_alerts"

        fun themeAccentColor(theme: AppTheme): Int = when (theme) {
            AppTheme.TFI     -> 0xFF003B8C.toInt()
            AppTheme.GREEN   -> 0xFF3D5663.toInt()
            AppTheme.DEFAULT -> 0xFF6750A4.toInt()
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(FG_CHANNEL_ID, "Bus notifications (background)", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(BUS_CHANNEL_ID, "Bus departure alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .also { it.description = "Upcoming bus notifications" }
            )
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BusNotificationService::class.java))
        }

        fun refresh(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BusNotificationService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BusNotificationService::class.java))
        }
    }
}
