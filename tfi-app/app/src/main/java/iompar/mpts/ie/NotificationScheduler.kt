package iompar.mpts.ie

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
/**
 * Whether this install may schedule exact alarms.
 *
 * The manifest asks for SCHEDULE_EXACT_ALARM (user-grantable), not USE_EXACT_ALARM (install-time,
 * and gated by Play policy review). Below Android 12 there is no such gate and exact is always
 * available. Refusal is not an error: every caller falls back to an inexact alarm, so a bus
 * notification still arrives, just batched by the OS and possibly a few minutes late.
 */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
}

/**
 * Intent that opens the system screen where the user grants exact-alarm access. There is no
 * runtime-permission dialog for SCHEDULE_EXACT_ALARM; this settings page is the only route.
 */
fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))

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
        if (canScheduleExactAlarms(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
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

    private fun isActiveAt(w: NotificationWindow, dt: LocalDateTime): Boolean = w.isActiveAt(dt)

    /**
     * Earliest start datetime, scanning the next 8 days, that is strictly after [from]. Delegates
     * per window to [nextStartAfter] so the editor's countdown and this alarm are computed by the
     * same code rather than two copies of it.
     */
    private fun nextStart(windows: List<NotificationWindow>, from: LocalDateTime): LocalDateTime? =
        windows.filter { it.enabled }
            .mapNotNull { it.nextStartAfter(from) }
            .minOrNull()
}
