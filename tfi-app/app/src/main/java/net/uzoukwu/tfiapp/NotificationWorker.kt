package net.uzoukwu.tfiapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "bus_alerts"

class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val windows = NotificationWindowStore(applicationContext).flow.first()
        val nowMins  = LocalTime.now().let { it.hour * 60 + it.minute }
        val today    = LocalDate.now().dayOfWeek.value  // 1=Mon, 7=Sun

        for (window in windows) {
            if (!window.enabled) continue
            if (today !in window.days) continue
            if (nowMins < window.startMinute || nowMins >= window.endMinute) continue

            val deps = runCatching { Api.service.departures(window.stopCode).departures }
                .getOrNull() ?: continue

            val matching = deps.filter { d ->
                window.routes.isEmpty() || d.routeShortName in window.routes
            }.take(2)

            if (matching.isEmpty()) continue
            postNotification(window, matching)
        }
        return Result.success()
    }

    private fun postNotification(window: NotificationWindow, deps: List<Departure>) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bus Alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .also { it.description = "Upcoming bus notifications" }
            )
        }

        val lines = deps.joinToString("\n") { d ->
            val eff = d.estimatedDeparture ?: d.scheduledDeparture
            val drift = if (d.estimatedDeparture != null && d.estimatedDeparture != d.scheduledDeparture)
                " (sched ${d.scheduledDeparture})" else ""
            "${d.routeShortName} → ${d.tripHeadsign}  $eff$drift"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${window.name} — ${window.stopName}")
            .setContentText(lines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(window.id.hashCode(), notification)
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "bus_notification_check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
