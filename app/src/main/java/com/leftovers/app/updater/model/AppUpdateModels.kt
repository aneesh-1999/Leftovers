package com.leftovers.app.updater.model

import java.io.File

/**
 * Downloadable asset metadata parsed from a GitHub Release.
 */
data class GitHubReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val contentType: String,
)

/**
 * Release information retrieved from GitHub Releases API.
 */
data class GitHubRelease(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val publishedAt: String,
    val apkAsset: GitHubReleaseAsset?,
    val expectedChecksum: String? = null,
    val htmlUrl: String = "",
) {
    val displayVersion: String
        get() = if (name.isNotBlank()) name else tagName
}

/**
 * Parsed semantic or numeric version components.
 */
data class VersionInfo(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int = 0,
    val rawTag: String = "",
) : Comparable<VersionInfo> {
    override fun compareTo(other: VersionInfo): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return build.compareTo(other.build)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (build > 0) ".$build" else ""
}

/**
 * Result of checking for updates against GitHub Releases.
 */
sealed interface UpdateCheckStatus {
    data object Idle : UpdateCheckStatus
    data object Checking : UpdateCheckStatus
    data class UpToDate(
        val currentVersion: String,
        val checkedAt: Long,
        val fromCache: Boolean = false,
        val release: GitHubRelease? = null,
    ) : UpdateCheckStatus
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val release: GitHubRelease,
        val checkedAt: Long,
        val fromCache: Boolean = false,
    ) : UpdateCheckStatus
    data class Failed(
        val error: String,
        val isRateLimited: Boolean = false,
        val rateLimitResetTime: Long = 0L,
        val checkedAt: Long = System.currentTimeMillis(),
        val cachedRelease: GitHubRelease? = null,
    ) : UpdateCheckStatus
}

/**
 * State of the update download process.
 */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
    ) : DownloadStatus
    data class Verifying(val tempFile: File) : DownloadStatus
    data class ReadyToInstall(
        val apkFile: File,
        val release: GitHubRelease,
    ) : DownloadStatus
    data class Failed(
        val error: String,
        val canRetry: Boolean = true,
    ) : DownloadStatus
    data object Cancelled : DownloadStatus
}
