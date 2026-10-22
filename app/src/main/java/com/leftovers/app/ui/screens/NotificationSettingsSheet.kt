package com.leftovers.app.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.leftovers.app.data.AppSettings
import com.leftovers.app.data.detection.CandidateType
import com.leftovers.app.data.detection.DetectionConfidence
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.LocalMoney
import com.leftovers.app.util.detection.NotificationAccessHelper
import com.leftovers.app.util.detection.PaymentNotificationParserRegistry
import com.leftovers.app.util.detection.SupportedPaymentApp
import com.leftovers.app.util.detection.SupportedPaymentApps

@Composable
fun NotificationSettingsSheet(
    settings: AppSettings,
    onToggleAutoSave: (Boolean) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val money = LocalMoney.current

    var isPermissionGranted by remember { mutableStateOf(NotificationAccessHelper.isAccessGranted(context)) }
    var knownApps by remember { mutableStateOf<List<SupportedPaymentApp>>(emptyList()) }

    var customPackage by remember { mutableStateOf("") }
    var testPackage by remember { mutableStateOf("com.revolut.revolut") }
    var testTitle by remember { mutableStateOf("Paid €14.50") }
    var testText by remember { mutableStateOf("Paid €14.50 to Starbucks with card ...1234") }

    fun refreshApps() {
        isPermissionGranted = NotificationAccessHelper.isAccessGranted(context)
        knownApps = SupportedPaymentApps.getKnownAppsWithStatus(context)
    }

    LaunchedEffect(Unit) {
        refreshApps()
    }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Notification detection", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Detect transactions directly from your payment and banking app notifications. " +
                    "Leftovers processes notifications locally and never stores raw messages.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )

            // 1. Permission status card
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            if (isPermissionGranted) Lucide.Check else Lucide.CircleAlert,
                            if (isPermissionGranted) c.positive else c.warning,
                            size = 36.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isPermissionGranted) "Notification access granted" else "Notification access required",
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary,
                            )
                            Text(
                                if (isPermissionGranted) {
                                    "Leftovers is listening for transactions from your enabled apps."
                                } else {
                                    "Android requires system notification access to read transaction alerts."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                    }
                    if (!isPermissionGranted) {
                        PrimaryButton(
                            text = "Grant notification access",
                            onClick = {
                                context.startActivity(NotificationAccessHelper.buildSettingsIntent())
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 2. Auto-save mode toggle
            Glass(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAutoSave(!settings.notificationAutoSave) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Lucide.Sparkles, c.textPrimary, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Auto-save high-confidence transactions",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = settings.notificationAutoSave,
                        onCheckedChange = onToggleAutoSave,
                    )
                }
            }
            Text(
                if (settings.notificationAutoSave) {
                    "Completed expenses and incomes with verified amounts and merchants will save automatically. " +
                        "Transfers, refunds, and duplicate suspects are always sent to Home for review."
                } else {
                    "Review mode is active: all detected transactions will appear on Home for your confirmation before being saved."
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            RowDivider(inset = 0.dp)

            // 3. Supported & Enabled Apps
            Text("Supported applications", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(
                "Enable only the payment apps you use on this device. Non-enabled apps are ignored immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )

            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    knownApps.forEachIndexed { i, app ->
                        if (i > 0) RowDivider()
                        val isEnabled = if (settings.enabledNotificationApps.isEmpty()) {
                            app.isInstalled
                        } else {
                            settings.enabledNotificationApps.contains(app.packageName)
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggleApp(app.packageName, !isEnabled) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.displayName, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                                    Spacer(Modifier.width(8.dp))
                                    if (app.isInstalled) {
                                        Chip("Installed", {}, selected = true)
                                    }
                                }
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            }
                            androidx.compose.material3.Switch(
                                checked = isEnabled,
                                onCheckedChange = { onToggleApp(app.packageName, it) },
                            )
                        }
                    }

                    // Additional custom packages enabled by the user
                    val customApps = settings.enabledNotificationApps.filter { pkg ->
                        knownApps.none { it.packageName.equals(pkg, ignoreCase = true) }
                    }
                    customApps.forEach { pkg ->
                        RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pkg, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                Text("Custom app", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            }
                            RoundButton(Lucide.Trash2, "Remove", { onToggleApp(pkg, false) }, tint = c.negative)
                        }
                    }
                }
            }

            // 4. Add Custom Package
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassTextField(
                    customPackage,
                    { customPackage = it.trim() },
                    placeholder = "Add custom package e.g. com.bank.app",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Chip(
                    "Add",
                    {
                        if (customPackage.contains(".")) {
                            onToggleApp(customPackage, true)
                            customPackage = ""
                        }
                    },
                    icon = Lucide.Plus,
                    selected = customPackage.contains("."),
                )
            }

            RowDivider(inset = 0.dp)

            // 5. Test Notification Parser
            Text("Test notification parser", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(
                "Test sample notifications from Revolut, eSewa, or banking apps in sandbox mode.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )

            GlassTextField(testPackage, { testPackage = it }, placeholder = "App package (e.g. com.revolut.revolut)")
            GlassTextField(testTitle, { testTitle = it }, placeholder = "Notification title")
            GlassTextField(testText, { testText = it }, placeholder = "Notification text", singleLine = false)

            if (testTitle.isNotBlank() || testText.isNotBlank()) {
                val candidate = remember(testPackage, testTitle, testText) {
                    PaymentNotificationParserRegistry.parse(
                        packageName = testPackage,
                        title = testTitle,
                        text = testText,
                        subText = null,
                        postTime = System.currentTimeMillis(),
                        fingerprint = "test-fingerprint",
                    )
                }

                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (candidate != null) {
                            val formattedAmount = (if (candidate.isIncome) "+" else "") +
                                (candidate.currency ?: "") + " " + money.format(candidate.amountMinor)
                            Text(
                                "Extracted: $formattedAmount",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (candidate.isIncome) c.positive else c.textPrimary,
                            )
                            Text("Merchant: ${candidate.merchant.ifBlank { "(None detected)" }}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text("Type: ${candidate.type.name} · Status: ${candidate.status.name} · Confidence: ${candidate.confidence.name}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            if (candidate.accountDigits != null) {
                                Text("Card / Account: ...${candidate.accountDigits}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            }
                            if (candidate.referenceId != null) {
                                Text("Ref ID: ${candidate.referenceId}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            }
                            val autoSaveEligible = candidate.confidence == DetectionConfidence.HIGH &&
                                (candidate.type == CandidateType.EXPENSE || candidate.type == CandidateType.INCOME)
                            Text(
                                if (autoSaveEligible) "Eligible for auto-save (if enabled)" else "Requires manual review",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (autoSaveEligible) c.positive else c.warning,
                            )
                        } else {
                            Text("Not recognised as a transaction", style = MaterialTheme.typography.titleMedium, color = c.warning)
                            Text("The parser found no valid monetary amount or rejected the alert as non-financial.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
