package com.example.tfiapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired at a window's start time; starts the service which promotes itself to foreground. */
class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BusNotificationService.start(context)
    }
}
