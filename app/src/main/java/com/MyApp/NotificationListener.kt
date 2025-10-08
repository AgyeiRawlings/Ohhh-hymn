package com.example.socketclient

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val title = it.notification.extras.getString("android.title") ?: "No Title"
            val text = it.notification.extras.getCharSequence("android.text")?.toString() ?: "No Text"
            Log.d("Notification", "Posted: $title - $text from ${it.packageName}")
            
            // TODO: Add code here to forward notifications or trigger socket commands
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            Log.d("Notification", "Removed from package: ${it.packageName}")
            
            // TODO: Add any cleanup or state update if necessary
        }
    }
}
