package com.leftovers.app.util.detection

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.leftovers.app.LeftoversApp
import com.leftovers.app.util.DetectionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Battery-efficient, privacy-preserving notification listener service.
 * Only processes notifications from explicitly enabled payment/banking apps.
 * Operates purely on system event callbacks (no polling, no wake locks, no foreground service).
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // 1. Fast filters to keep battery impact negligible
        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        if (title.isBlank() && text.isBlank()) return

        // Drop stale notifications that were posted > 15 minutes ago (e.g. from notification shade upon restart)
        val maxAgeMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(15)
        if (System.currentTimeMillis() - sbn.postTime > maxAgeMs) {
            DetectionLog.add(
                applicationContext,
                "NOTIF_STALE",
                packageName,
                "Ignored: stale notification from ${java.util.Date(sbn.postTime)}"
            )
            return
        }

        serviceScope.launch {
            try {
                val app = applicationContext as? LeftoversApp ?: return@launch
                val container = app.container
                val settings = container.settings.settings.first()

                // Check master feature toggle
                if (!settings.notificationDetection) return@launch

                // Check user-configured enabled apps
                val enabledApps = settings.enabledNotificationApps
                val isPackageEnabled = if (enabledApps.isEmpty()) {
                    // Default to known apps that are installed if user hasn't explicitly customized yet
                    SupportedPaymentApps.KNOWN_PRESETS.any { it.packageName.equals(packageName, ignoreCase = true) }
                } else {
                    enabledApps.any { it.equals(packageName, ignoreCase = true) }
                }

                if (!isPackageEnabled) return@launch

                // 2. Compute SHA-256 fingerprint for idempotency (never store raw notification body)
                val rawPayload = "$packageName|$title|$text|${sbn.postTime}"
                val fingerprint = sha256(rawPayload)

                // 3. Parse transaction candidate
                val candidate = PaymentNotificationParserRegistry.parse(
                    packageName = packageName,
                    title = title,
                    text = text,
                    subText = subText,
                    postTime = sbn.postTime,
                    fingerprint = fingerprint,
                ) ?: return@launch

                // 4. Ingest through shared pipeline
                container.detectionPipeline.process(
                    candidate = candidate,
                    autoSaveEnabled = settings.notificationAutoSave,
                )
            } catch (e: Exception) {
                // Fail safely without disrupting the listener
                DetectionLog.add(
                    applicationContext,
                    "NOTIF_ERROR",
                    packageName,
                    "Parsing error: ${e.message ?: "unknown"}"
                )
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
