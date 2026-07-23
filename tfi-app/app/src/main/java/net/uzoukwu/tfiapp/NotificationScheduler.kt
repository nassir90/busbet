package com.example.tfiapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Drives the [BusNotificationService] lifecycle without keeping anything running between windows.
 *
 * - If a window is active right now → start the service (it promotes to foreground & ticks).
 * - Otherwise → schedule an exact alarm at the next window's start time and run nothing.
 *
 * This keeps the persistent foreground-service icon out of the status bar except while a
 * notification window is actually active.
 */
object NotificationScheduler {
    private const val REQ_CODE = 2001

    fun reschedule(context: Context) {
        val windows = runBlocking { NotificationWindowStore(context).flow.first() }
        val now = LocalDateTime.now()

        if (windows.any { it.enabled && isActiveAt(it, now) }) {
            cancelAlarm(context)
            BusNotificationService.start(context)
            return
        }

        val next = nextStart(windows, now)
        if (next == null) {
            cancelAlarm(context)
            return
        }
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_CODE,
            Intent(context, NotificationAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun isActiveAt(w: NotificationWindow, dt: LocalDateTime): Boolean {
        val mins = dt.hour * 60 + dt.minute
        return dt.dayOfWeek.value in w.days && mins in w.startMinute until w.endMinute
    }

    /** Earliest start datetime, scanning the next 8 days, that is strictly after [from]. */
    private fun nextStart(windows: List<NotificationWindow>, from: LocalDateTime): LocalDateTime? {
        var best: LocalDateTime? = null
        windows.filter { it.enabled && it.days.isNotEmpty() }.forEach { w ->
            for (offset in 0..7) {
                val day = from.toLocalDate().plusDays(offset.toLong())
                if (day.dayOfWeek.value !in w.days) continue
                val candidate = day.atStartOfDay().plusMinutes(w.startMinute.toLong())
                if (candidate.isAfter(from) && (best == null || candidate.isBefore(best))) best = candidate
            }
        }
        return best
    }
}
