package net.uzoukwu.tfiapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.runBlocking

/**
 * Arms/disarms the single exact alarm behind a [BusWatch]. Unlike [NotificationScheduler], there is
 * never anything recurring here — each watch fires its alarm exactly once and is then discarded.
 */
object BusWatchScheduler {
    fun schedule(context: Context, watch: BusWatch) {
        runBlocking { BusWatchStore(context).save(watch) }
        setAlarm(context, watch)
    }

    fun cancel(context: Context, tripId: String, stopCode: String) {
        runBlocking { BusWatchStore(context).remove(tripId, stopCode) }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context, tripId, stopCode))
    }

    /** Re-arms watches still pending after a reboot; drops any whose trigger time has already passed. */
    fun rescheduleAll(context: Context) {
        val store = BusWatchStore(context)
        val now = System.currentTimeMillis()
        runBlocking { store.all() }.forEach { watch ->
            if (watch.triggerAtMillis <= now) {
                runBlocking { store.remove(watch.tripId, watch.stopCode) }
            } else {
                setAlarm(context, watch)
            }
        }
    }

    private fun setAlarm(context: Context, watch: BusWatch) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context, watch.tripId, watch.stopCode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, watch.triggerAtMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, watch.triggerAtMillis, pi)
        }
    }

    private fun alarmIntent(context: Context, tripId: String, stopCode: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, tripId.hashCode() xor stopCode.hashCode(),
            Intent(context, BusWatchAlarmReceiver::class.java).apply {
                putExtra("tripId", tripId)
                putExtra("stopCode", stopCode)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
