package com.myai.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AssistantNotificationService : NotificationListenerService() {

    companion object {
        val recentNotifications = mutableListOf<String>()

        fun getLatestSummary(): String {
            if (recentNotifications.isEmpty()) return "Abhi koi naya notification nahi aaya hai."
            return recentNotifications.takeLast(8).joinToString("\n")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appLabel = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("mms") || pkg.contains("messaging") -> "SMS"
            pkg.contains("youtube") -> "YouTube"
            else -> pkg.substringAfterLast(".")
        }

        val entry = "[$appLabel] $title: $text"
        synchronized(recentNotifications) {
            if (recentNotifications.size > 20) recentNotifications.removeAt(0)
            recentNotifications.add(entry)
        }
    }
}
