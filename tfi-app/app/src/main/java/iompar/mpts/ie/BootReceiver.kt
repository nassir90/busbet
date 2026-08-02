package iompar.mpts.ie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import iompar.mpts.ie.server.OnDeviceServerService
import iompar.mpts.ie.server.ServerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationScheduler.reschedule(context)
            BusWatchScheduler.rescheduleAll(context)

            // Bring the on-device server back up only if the user opted into start-on-boot. This is
            // deliberately separate from the "enabled" toggle: by default a reboot leaves the server
            // down (starting it here is also what tripped the dataSync-from-BOOT_COMPLETED ban).
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (ServerSettingsStore(appContext).startOnBoot.first()) {
                        OnDeviceServerService.start(appContext)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
