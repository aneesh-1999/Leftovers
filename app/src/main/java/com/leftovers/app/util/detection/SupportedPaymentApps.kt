package com.leftovers.app.util.detection

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Metadata for a supported payment/banking application. */
data class SupportedPaymentApp(
    val packageName: String,
    val displayName: String,
    val defaultEnabled: Boolean = false,
    val isInstalled: Boolean = false,
    val versionName: String? = null,
)

object SupportedPaymentApps {

    /**
     * Known payment and banking applications supported by Leftovers parsers.
     * Note: Package names are verified against installed metadata on the device.
     */
    val KNOWN_PRESETS = listOf(
        SupportedPaymentApp(
            packageName = "com.revolut.revolut",
            displayName = "Revolut",
        ),
        SupportedPaymentApp(
            packageName = "com.revolut.business",
            displayName = "Revolut Business",
        ),
        SupportedPaymentApp(
            packageName = "com.f1soft.esewa",
            displayName = "eSewa",
        ),
        SupportedPaymentApp(
            packageName = "com.esewa.mobile",
            displayName = "eSewa Mobile",
        ),
        SupportedPaymentApp(
            packageName = "com.khalti",
            displayName = "Khalti Digital Wallet",
        ),
        SupportedPaymentApp(
            packageName = "com.f1soft.banksmart.siddhartha",
            displayName = "Siddhartha BankSmart",
        ),
    )

    /**
     * Inspects the device for known supported payment apps and verifies their installation metadata.
     */
    fun getKnownAppsWithStatus(context: Context): List<SupportedPaymentApp> {
        val pm = context.packageManager
        return KNOWN_PRESETS.map { app ->
            val packageInfo = runCatching {
                pm.getPackageInfo(app.packageName, 0)
            }.getOrNull()

            val installed = packageInfo != null
            val resolvedName = if (installed && packageInfo != null) {
                packageInfo.applicationInfo?.let { appInfo ->
                    runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull()
                } ?: app.displayName
            } else {
                app.displayName
            }

            app.copy(
                displayName = resolvedName,
                isInstalled = installed,
                versionName = packageInfo?.versionName,
            )
        }
    }

    /**
     * Verifies if a given package name is installed on the device and returns its app label.
     */
    fun resolveAppLabel(context: Context, packageName: String): String {
        KNOWN_PRESETS.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }?.let {
            return it.displayName
        }
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    /**
     * Checks if a package name belongs to a known supported payment application.
     */
    fun isKnownPaymentApp(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        return KNOWN_PRESETS.any { it.packageName.lowercase() == normalized } ||
            normalized.startsWith("com.revolut.") ||
            normalized.startsWith("com.f1soft.") ||
            normalized.contains("esewa") ||
            normalized.contains("khalti")
    }
}
