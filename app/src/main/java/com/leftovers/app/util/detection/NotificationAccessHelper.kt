package com.leftovers.app.util.detection

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object NotificationAccessHelper {
    /**
     * Checks whether the user has granted Notification Listener permission
     * to Leftovers in Android system settings.
     */
    fun isAccessGranted(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    /**
     * Builds an Intent to navigate directly to the Notification Access screen in Android Settings.
     */
    fun buildSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
