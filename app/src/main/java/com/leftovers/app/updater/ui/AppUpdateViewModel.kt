package com.leftovers.app.updater.ui

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.updater.installer.AppInstaller
import com.leftovers.app.updater.model.DownloadStatus
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.UpdateCheckStatus
import com.leftovers.app.updater.repository.AppUpdateRepository
import com.leftovers.app.updater.worker.AppUpdateCheckWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class AppUpdateUiState(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val checkStatus: UpdateCheckStatus = UpdateCheckStatus.Idle,
    val downloadStatus: DownloadStatus = DownloadStatus.Idle,
    val lastCheckedTime: Long = 0L,
    val autoCheckEnabled: Boolean = true,
    val wifiOnlyEnabled: Boolean = false,
    val releaseNotesToView: GitHubRelease? = null,
    val promptInstallPermission: Boolean = false,
    val downloadedApkFile: File? = null,
)

class AppUpdateViewModel(
    private val appContext: Context,
    private val updateRepository: AppUpdateRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val packageInfo = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    val currentVersionName: String = packageInfo?.versionName ?: "1.7.0"
    val currentVersionCode: Long = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 20L

    private val checkStatusFlow = MutableStateFlow<UpdateCheckStatus>(UpdateCheckStatus.Idle)
    private val downloadStatusFlow = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    private val releaseNotesFlow = MutableStateFlow<GitHubRelease?>(null)
    private val promptPermissionFlow = MutableStateFlow(false)
    private val downloadedApkFlow = MutableStateFlow<File?>(null)

    private var downloadJob: Job? = null

    val uiState: StateFlow<AppUpdateUiState> = combine(
        settingsRepository.settings,
        checkStatusFlow,
        downloadStatusFlow,
        releaseNotesFlow,
        promptPermissionFlow,
        downloadedApkFlow,
    ) { args: Array<Any?> ->
        val settings = args[0] as com.leftovers.app.data.AppSettings
        val checkStatus = args[1] as UpdateCheckStatus
        val downloadStatus = args[2] as DownloadStatus
        val releaseNotes = args[3] as GitHubRelease?
        val promptPermission = args[4] as Boolean
        val downloadedApk = args[5] as File?

        AppUpdateUiState(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            checkStatus = checkStatus,
            downloadStatus = downloadStatus,
            lastCheckedTime = settings.lastUpdateCheckTime,
            autoCheckEnabled = settings.autoUpdateChecks,
            wifiOnlyEnabled = settings.updateCheckWifiOnly,
            releaseNotesToView = releaseNotes,
            promptInstallPermission = promptPermission,
            downloadedApkFile = downloadedApk,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppUpdateUiState(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
        ),
    )

    /**
     * Checks GitHub for updates. Prevents concurrent requests.
     */
    fun checkForUpdates() {
        if (checkStatusFlow.value is UpdateCheckStatus.Checking) return

        checkStatusFlow.value = UpdateCheckStatus.Checking

        viewModelScope.launch {
            val result = updateRepository.checkForUpdates(currentVersionName, currentVersionCode)
            val now = System.currentTimeMillis()
            settingsRepository.setLastUpdateCheckTime(now)
            checkStatusFlow.value = result
        }
    }

    /**
     * Starts downloading the APK from the available release asset.
     */
    fun startDownload() {
        val currentCheck = checkStatusFlow.value
        val release = (currentCheck as? UpdateCheckStatus.UpdateAvailable)?.release
            ?: (downloadStatusFlow.value as? DownloadStatus.ReadyToInstall)?.release
            ?: return

        downloadJob?.cancel()
        downloadStatusFlow.value = DownloadStatus.Downloading(0L, release.apkAsset?.size ?: 0L, 0)

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = updateRepository.downloadApk(
                    release = release,
                    onProgress = { downloaded, total ->
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        downloadStatusFlow.value = DownloadStatus.Downloading(downloaded, total, percent)
                    },
                ).getOrThrow()

                downloadedApkFlow.value = file
                downloadStatusFlow.value = DownloadStatus.ReadyToInstall(file, release)
            } catch (ce: CancellationException) {
                downloadStatusFlow.value = DownloadStatus.Cancelled
            } catch (e: Exception) {
                downloadStatusFlow.value = DownloadStatus.Failed(
                    error = e.message ?: "Failed to download update",
                    canRetry = true,
                )
            }
        }
    }

    /**
     * Cancels an ongoing download and cleans up.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadStatusFlow.value = DownloadStatus.Cancelled
    }

    /**
     * Initiates installation of the downloaded APK.
     */
    fun installDownloadedApk(context: Context) {
        val apkFile = downloadedApkFlow.value ?: return

        if (!AppInstaller.canRequestPackageInstalls(context)) {
            promptPermissionFlow.value = true
            return
        }

        AppInstaller.installApk(context, apkFile).onFailure { err ->
            downloadStatusFlow.value = DownloadStatus.Failed(
                error = "Installation failed: ${err.message}",
                canRetry = false,
            )
        }
    }

    fun dismissPermissionPrompt() {
        promptPermissionFlow.value = false
    }

    fun showReleaseNotes(release: GitHubRelease) {
        releaseNotesFlow.value = release
    }

    fun dismissReleaseNotes() {
        releaseNotesFlow.value = null
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateChecks(enabled)
            AppUpdateCheckWorker.schedulePeriodicCheck(appContext, enabled, uiState.value.wifiOnlyEnabled)
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUpdateCheckWifiOnly(wifiOnly)
            AppUpdateCheckWorker.schedulePeriodicCheck(appContext, uiState.value.autoCheckEnabled, wifiOnly)
        }
    }
}
