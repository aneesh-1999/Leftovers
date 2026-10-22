package com.leftovers.app.updater.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.screens.GlassAlertDialog
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.ListRow
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.updater.installer.AppInstaller
import com.leftovers.app.updater.model.DownloadStatus
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.UpdateCheckStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AppUpdateSection(
    viewModel: AppUpdateViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = LocalAppColors.current

    Glass(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: App name, installed version & update badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Leftovers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    Text(
                        text = "Installed: ${state.currentVersionName} (${state.currentVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }

                StatusBadge(status = state.checkStatus)
            }

            // Version comparison & last checked details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val latestVer = when (val s = state.checkStatus) {
                    is UpdateCheckStatus.UpdateAvailable -> s.latestVersion
                    is UpdateCheckStatus.UpToDate -> s.currentVersion
                    else -> "—"
                }

                Text(
                    text = "Latest available: $latestVer",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )

                Text(
                    text = formatLastChecked(state.lastCheckedTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }

            // Error display if update check failed
            if (state.checkStatus is UpdateCheckStatus.Failed) {
                val error = (state.checkStatus as UpdateCheckStatus.Failed).error
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.negative,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.negative.copy(alpha = 0.1f))
                        .padding(8.dp),
                )
            }

            // Active update card when an update is available
            val availableRelease = (state.checkStatus as? UpdateCheckStatus.UpdateAvailable)?.release
            if (availableRelease != null) {
                UpdateAvailableCard(
                    release = availableRelease,
                    downloadStatus = state.downloadStatus,
                    onViewReleaseNotes = { viewModel.showReleaseNotes(availableRelease) },
                    onStartDownload = viewModel::startDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onInstall = { viewModel.installDownloadedApk(context) },
                )
            } else {
                // Check for updates button
                PrimaryButton(
                    text = if (state.checkStatus is UpdateCheckStatus.Checking) "Checking GitHub..." else "Check for updates",
                    onClick = viewModel::checkForUpdates,
                    enabled = state.checkStatus !is UpdateCheckStatus.Checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            RowDivider()

            // Automatic background update checks toggle
            ToggleRow(
                icon = Lucide.Repeat,
                title = "Automatic update checks",
                subtitle = "Check daily in background via WorkManager",
                checked = state.autoCheckEnabled,
                onChange = viewModel::setAutoCheck,
            )

            AnimatedVisibility(visible = state.autoCheckEnabled) {
                Column {
                    RowDivider()
                    ToggleRow(
                        icon = Lucide.Sparkles,
                        title = "Check on Wi-Fi only",
                        subtitle = "Saves mobile data on metered networks",
                        checked = state.wifiOnlyEnabled,
                        onChange = viewModel::setWifiOnly,
                    )
                }
            }
        }
    }

    // Release notes dialog
    state.releaseNotesToView?.let { release ->
        ReleaseNotesDialog(
            release = release,
            onDismiss = viewModel::dismissReleaseNotes,
            onUpdateNow = {
                viewModel.dismissReleaseNotes()
                viewModel.startDownload()
            },
        )
    }

    // Permission prompt dialog for unknown app sources on Android 8.0+
    if (state.promptInstallPermission) {
        InstallPermissionDialog(
            context = context,
            onDismiss = viewModel::dismissPermissionPrompt,
        )
    }
}

@Composable
private fun StatusBadge(status: UpdateCheckStatus) {
    val c = LocalAppColors.current

    val (badgeBg, badgeFg, icon, label) = when (status) {
        is UpdateCheckStatus.UpToDate -> Tuple4(
            c.positive.copy(alpha = 0.15f),
            c.positive,
            Lucide.CircleCheck,
            "Up to date",
        )
        is UpdateCheckStatus.UpdateAvailable -> Tuple4(
            c.accent.copy(alpha = 0.2f),
            c.accent,
            Lucide.Sparkles,
            "Update available",
        )
        is UpdateCheckStatus.Checking -> Tuple4(
            c.textSecondary.copy(alpha = 0.15f),
            c.textSecondary,
            null,
            "Checking...",
        )
        is UpdateCheckStatus.Failed -> Tuple4(
            c.negative.copy(alpha = 0.15f),
            c.negative,
            Lucide.CircleAlert,
            "Check failed",
        )
        is UpdateCheckStatus.Idle -> Tuple4(
            c.glass,
            c.textTertiary,
            Lucide.Info,
            "Idle",
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(badgeBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(14.dp))
        } else {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = badgeFg,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = badgeFg,
        )
    }
}

@Composable
private fun UpdateAvailableCard(
    release: GitHubRelease,
    downloadStatus: DownloadStatus,
    onViewReleaseNotes: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val c = LocalAppColors.current

    Glass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        strong = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Version ${release.displayVersion}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    release.apkAsset?.let {
                        val sizeMb = "%.1f MB".format(Locale.US, it.size / (1024.0 * 1024.0))
                        Text(
                            text = "Download size: $sizeMb",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                }

                TextButton(onClick = onViewReleaseNotes) {
                    Text("Release notes", color = c.accent, style = MaterialTheme.typography.labelMedium)
                }
            }

            when (downloadStatus) {
                is DownloadStatus.Idle, is DownloadStatus.Cancelled -> {
                    PrimaryButton(
                        text = "Update now",
                        onClick = onStartDownload,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is DownloadStatus.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadStatus.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = c.accent,
                            trackColor = c.glass,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dlMb = "%.1f".format(Locale.US, downloadStatus.bytesDownloaded / (1024.0 * 1024.0))
                            val totalMb = "%.1f MB".format(Locale.US, downloadStatus.totalBytes / (1024.0 * 1024.0))
                            Text(
                                text = "Downloading: $dlMb / $totalMb (${downloadStatus.percent}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.textSecondary,
                            )
                            RoundButton(Lucide.X, "Cancel", onCancelDownload, size = 28.dp)
                        }
                    }
                }
                is DownloadStatus.Verifying -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying APK package integrity...", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                is DownloadStatus.ReadyToInstall -> {
                    PrimaryButton(
                        text = "Install update now",
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is DownloadStatus.Failed -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Download error: ${downloadStatus.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.negative,
                        )
                        if (downloadStatus.canRetry) {
                            PrimaryButton(
                                text = "Retry download",
                                onClick = onStartDownload,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesDialog(
    release: GitHubRelease,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    val c = LocalAppColors.current

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "What's New in ${release.displayVersion}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                )
                if (release.publishedAt.isNotBlank()) {
                    Text(
                        text = "Published on ${release.publishedAt.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = release.body.ifBlank { "No detailed release notes provided with this version." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            PrimaryButton(text = "Update now", onClick = onUpdateNow)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = c.textSecondary)
            }
        },
    )
}

@Composable
private fun InstallPermissionDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Installation Permission Required", color = c.textPrimary) },
        text = {
            Text(
                text = "Android requires your permission to install downloaded APK updates for Leftovers. Tap Settings to enable 'Install unknown apps'.",
                color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            PrimaryButton(
                text = "Open settings",
                onClick = {
                    onDismiss()
                    runCatching {
                        context.startActivity(AppInstaller.createManageUnknownAppSourcesIntent(context))
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.textSecondary)
            }
        },
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalAppColors.current
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = { IconTile(icon, c.textPrimary, size = 36.dp) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.accent,
                    checkedThumbColor = c.onAccent,
                    uncheckedTrackColor = c.glass,
                    uncheckedBorderColor = c.borderTop,
                    uncheckedThumbColor = c.textSecondary,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

private fun formatLastChecked(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Last checked: Never"
    return runCatching {
        val dt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
        "Last checked: ${dt.format(formatter)}"
    }.getOrElse { "Last checked: Recently" }
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
