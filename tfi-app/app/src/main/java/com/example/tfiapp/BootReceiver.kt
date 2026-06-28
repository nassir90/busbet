package com.example.tfiapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tfiapp.server.OnDeviceServerService
import com.example.tfiapp.server.ServerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationScheduler.reschedule(context)
            BusWatchScheduler.rescheduleAll(context)

            // Restart the on-device server if it was left enabled.
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (ServerSettingsStore(appContext).enabled.first()) {
                        OnDeviceServerService.start(appContext)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
