package com.leftovers.app.updater.util

import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.VersionInfo

/**
 * Numeric, semantic version comparator for app versions and GitHub release tags.
 * Strictly avoids alphabetical comparisons (e.g. "1.10.0" is newer than "1.9.0").
 */
object VersionComparator {

    private val VERSION_REGEX = Regex("""(?:v|V)?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?""")
    private val VERSION_CODE_REGEX = Regex("""(?i)(?:versionCode|code|vc)[\s:=_-]*(\d+)""")

    /**
     * Parses a version string (e.g. "v1.7.1", "1.10.0", "v2.0") into a [VersionInfo].
     * Returns null if no numeric version segment can be found.
     */
    fun parseVersion(versionString: String): VersionInfo? {
        val trimmed = versionString.trim()
        val match = VERSION_REGEX.find(trimmed) ?: return null

        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        val build = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0

        return VersionInfo(
            major = major,
            minor = minor,
            patch = patch,
            build = build,
            rawTag = trimmed,
        )
    }

    /**
     * Attempts to extract an explicit versionCode from release notes or asset name.
     * e.g. "vc21", "versionCode: 21", "code 21".
     */
    fun extractVersionCode(release: GitHubRelease): Long? {
        // First check asset name
        release.apkAsset?.name?.let { name ->
            VERSION_CODE_REGEX.find(name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
        // Then check release title
        VERSION_CODE_REGEX.find(release.name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        // Then check release body
        VERSION_CODE_REGEX.find(release.body)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }

        return null
    }

    /**
     * Compares two version strings numerically.
     * Returns:
     * - positive if remote is newer
     * - negative if remote is older
     * - 0 if both are identical
     */
    fun compare(currentVersion: String, remoteVersion: String): Int {
        val current = parseVersion(currentVersion)
        val remote = parseVersion(remoteVersion)

        return when {
            current != null && remote != null -> remote.compareTo(current)
            remote != null -> 1 // Remote parsed, current couldn't -> treat remote as candidate
            else -> 0
        }
    }

    /**
     * Determines whether [remoteRelease] represents a newer update than current app installation.
     */
    fun isUpdateAvailable(
        currentVersionName: String,
        currentVersionCode: Long,
        remoteRelease: GitHubRelease,
    ): Boolean {
        // 1. If remote release specifies an explicit versionCode, compare that first
        val remoteVersionCode = extractVersionCode(remoteRelease)
        if (remoteVersionCode != null && currentVersionCode > 0) {
            return remoteVersionCode > currentVersionCode
        }

        // 2. Otherwise compare semantic version numbers from tag / release name
        val remoteVersionString = remoteRelease.tagName.ifBlank { remoteRelease.name }
        val comparison = compare(currentVersionName, remoteVersionString)
        return comparison > 0
    }
}
