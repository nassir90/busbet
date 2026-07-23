package net.uzoukwu.tfiapp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires once for a single watched bus, posts its notification, then forgets the watch. */
class BusWatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tripId = intent.getStringExtra("tripId") ?: return
        val stopCode = intent.getStringExtra("stopCode") ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = BusWatchStore(appContext)
                val watch = store.all().find { it.tripId == tripId && it.stopCode == stopCode }
                store.remove(tripId, stopCode)
                if (watch != null) postNotification(appContext, watch)
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, watch: BusWatch) {
        BusNotificationService.ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = PendingIntent.getActivity(
            context, watch.requestCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra("stopCode", watch.stopCode)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        nm.notify(
            watch.requestCode(),
            NotificationCompat.Builder(context, BusNotificationService.BUS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${watch.routeShortName} is due")
                .setContentText(watch.stopName)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
        )
    }
}
