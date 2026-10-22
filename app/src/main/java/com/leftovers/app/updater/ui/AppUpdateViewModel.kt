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

import com.leftovers.app.updater.util.VersionComparator
import kotlinx.coroutines.delay

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
    val rateLimitResetTime: Long = 0L,
    val rateLimitRemainingSeconds: Long = 0L,
) {
    val isRateLimited: Boolean
        get() = rateLimitRemainingSeconds > 0L ||
            (checkStatus is UpdateCheckStatus.Failed && checkStatus.isRateLimited && checkStatus.rateLimitResetTime > System.currentTimeMillis())
}

class AppUpdateViewModel(
    private val appContext: Context,
    private val updateRepository: AppUpdateRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val packageInfo = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    val currentVersionName: String = packageInfo?.versionName ?: "1.7.2"
    val currentVersionCode: Long = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 22L

    private val checkStatusFlow = MutableStateFlow<UpdateCheckStatus>(UpdateCheckStatus.Idle)
    private val downloadStatusFlow = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    private val releaseNotesFlow = MutableStateFlow<GitHubRelease?>(null)
    private val promptPermissionFlow = MutableStateFlow(false)
    private val downloadedApkFlow = MutableStateFlow<File?>(null)
    private val rateLimitRemainingSecondsFlow = MutableStateFlow(0L)

    private var downloadJob: Job? = null
    private var rateLimitTickerJob: Job? = null
    private var isInitialized = false

    init {
        // Load persisted cache and rate-limit state immediately on startup
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (!isInitialized) {
                    isInitialized = true
                    val now = System.currentTimeMillis()
                    val rateLimitReset = settings.updateRateLimitResetTime
                    val cachedRelease = runCatching {
                        if (settings.updateCachedReleaseJson.isNotBlank()) {
                            updateRepository.parseReleaseJson(settings.updateCachedReleaseJson)
                        } else null
                    }.getOrNull()

                    if (rateLimitReset > now) {
                        val remainingSec = ((rateLimitReset - now) / 1000L).coerceAtLeast(1L)
                        rateLimitRemainingSecondsFlow.value = remainingSec
                        val waitMsg = AppUpdateRepository.formatRemainingWait(rateLimitReset - now, rateLimitReset)
                        checkStatusFlow.value = UpdateCheckStatus.Failed(
                            error = "GitHub API rate limit reached. Try again in $waitMsg.",
                            isRateLimited = true,
                            rateLimitResetTime = rateLimitReset,
                            checkedAt = if (settings.lastUpdateCheckTime > 0) settings.lastUpdateCheckTime else now,
                            cachedRelease = cachedRelease,
                        )
                        startRateLimitTicker(rateLimitReset)
                    } else if (cachedRelease != null && checkStatusFlow.value is UpdateCheckStatus.Idle) {
                        if (VersionComparator.isUpdateAvailable(currentVersionName, currentVersionCode, cachedRelease)) {
                            checkStatusFlow.value = UpdateCheckStatus.UpdateAvailable(
                                currentVersion = currentVersionName,
                                latestVersion = cachedRelease.displayVersion,
                                release = cachedRelease,
                                checkedAt = settings.updateCacheTimestamp,
                                fromCache = true,
                            )
                        } else if (settings.lastUpdateCheckTime > 0L) {
                            checkStatusFlow.value = UpdateCheckStatus.UpToDate(
                                currentVersion = currentVersionName,
                                checkedAt = settings.updateCacheTimestamp,
                                fromCache = true,
                                release = cachedRelease,
                            )
                        }
                    }
                }
            }
        }
    }

    val uiState: StateFlow<AppUpdateUiState> = combine(
        settingsRepository.settings,
        checkStatusFlow,
        downloadStatusFlow,
        releaseNotesFlow,
        promptPermissionFlow,
        downloadedApkFlow,
        rateLimitRemainingSecondsFlow,
    ) { args: Array<Any?> ->
        val settings = args[0] as com.leftovers.app.data.AppSettings
        val checkStatus = args[1] as UpdateCheckStatus
        val downloadStatus = args[2] as DownloadStatus
        val releaseNotes = args[3] as GitHubRelease?
        val promptPermission = args[4] as Boolean
        val downloadedApk = args[5] as File?
        val remainingSec = args[6] as Long

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
            rateLimitResetTime = settings.updateRateLimitResetTime,
            rateLimitRemainingSeconds = remainingSec,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppUpdateUiState(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
        ),
    )

    private fun startRateLimitTicker(resetTimeMillis: Long) {
        rateLimitTickerJob?.cancel()
        rateLimitTickerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingSec = ((resetTimeMillis - now) / 1000L).coerceAtLeast(0L)
                rateLimitRemainingSecondsFlow.value = remainingSec
                if (remainingSec <= 0L) {
                    settingsRepository.setUpdateRateLimitResetTime(0L)
                    if (checkStatusFlow.value is UpdateCheckStatus.Failed &&
                        (checkStatusFlow.value as UpdateCheckStatus.Failed).isRateLimited
                    ) {
                        val cached = (checkStatusFlow.value as UpdateCheckStatus.Failed).cachedRelease
                        if (cached != null && VersionComparator.isUpdateAvailable(currentVersionName, currentVersionCode, cached)) {
                            checkStatusFlow.value = UpdateCheckStatus.UpdateAvailable(
                                currentVersion = currentVersionName,
                                latestVersion = cached.displayVersion,
                                release = cached,
                                checkedAt = System.currentTimeMillis(),
                                fromCache = true,
                            )
                        } else {
                            checkStatusFlow.value = UpdateCheckStatus.Idle
                        }
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    /**
     * Checks GitHub for updates. Reuses valid cache, prevents duplicate requests, and respects rate limits.
     */
    fun checkForUpdates(forceRefresh: Boolean = false) {
        val currentCheck = checkStatusFlow.value
        if (currentCheck is UpdateCheckStatus.Checking) return
        if (uiState.value.isRateLimited) return

        checkStatusFlow.value = UpdateCheckStatus.Checking

        viewModelScope.launch {
            val result = updateRepository.checkForUpdates(
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                forceRefresh = forceRefresh,
            )
            checkStatusFlow.value = result

            if (result is UpdateCheckStatus.Failed && result.isRateLimited && result.rateLimitResetTime > System.currentTimeMillis()) {
                startRateLimitTicker(result.rateLimitResetTime)
            }
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
