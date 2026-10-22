package com.leftovers.app.updater

import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.GitHubReleaseAsset
import com.leftovers.app.updater.util.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun parseVersion_handlesPrefixesAndFormats() {
        val v1 = VersionComparator.parseVersion("v1.7.1")
        assertNotNull(v1)
        assertEquals(1, v1?.major)
        assertEquals(7, v1?.minor)
        assertEquals(1, v1?.patch)

        val v2 = VersionComparator.parseVersion("2.0.0")
        assertNotNull(v2)
        assertEquals(2, v2?.major)
        assertEquals(0, v2?.minor)
        assertEquals(0, v2?.patch)

        val v3 = VersionComparator.parseVersion("v1.7.0-beta.1")
        assertNotNull(v3)
        assertEquals(1, v3?.major)
        assertEquals(7, v3?.minor)
        assertEquals(0, v3?.patch)

        val invalid = VersionComparator.parseVersion("no-numbers-here")
        assertNull(invalid)
    }

    @Test
    fun compare_avoidsAlphabeticalSortingBugs() {
        // "1.10.0" vs "1.9.0": Alphabetical would say 1.9 > 1.10, but numeric must say 1.10 > 1.9
        assertTrue(VersionComparator.compare("1.9.0", "1.10.0") > 0)
        assertTrue(VersionComparator.compare("1.10.0", "1.9.0") < 0)

        // Equal versions
        assertEquals(0, VersionComparator.compare("1.7.0", "v1.7.0"))
        assertEquals(0, VersionComparator.compare("v1.7.0", "1.7.0"))

        // Patch upgrades
        assertTrue(VersionComparator.compare("1.7.0", "v1.7.1") > 0)
        assertTrue(VersionComparator.compare("1.7.1", "1.7.0") < 0)

        // Major upgrades
        assertTrue(VersionComparator.compare("1.9.9", "2.0.0") > 0)
    }

    @Test
    fun isUpdateAvailable_evaluatesVersionComparisonProperly() {
        val releaseNewer = GitHubRelease(
            id = 1,
            tagName = "v1.7.1",
            name = "v1.7.1",
            body = "Release notes",
            isDraft = false,
            isPrerelease = false,
            publishedAt = "2026-10-15T15:43:07Z",
            apkAsset = GitHubReleaseAsset("leftovers-v1.7.1.apk", 4000000L, "https://github.com/...", "application/vnd.android.package-archive"),
        )

        // Current 1.7.0 vs Remote v1.7.1 -> Update available
        assertTrue(VersionComparator.isUpdateAvailable("1.7.0", 20L, releaseNewer))

        // Current 1.7.1 vs Remote v1.7.1 -> Up to date
        assertFalse(VersionComparator.isUpdateAvailable("1.7.1", 21L, releaseNewer))

        // Current 1.8.0 vs Remote v1.7.1 -> Up to date
        assertFalse(VersionComparator.isUpdateAvailable("1.8.0", 25L, releaseNewer))
    }

    @Test
    fun isUpdateAvailable_usesExplicitVersionCodeWhenPresent() {
        val releaseWithCode25 = GitHubRelease(
            id = 2,
            tagName = "v1.7.0",
            name = "Leftovers 1.7.0 (vc25)",
            body = "Notes",
            isDraft = false,
            isPrerelease = false,
            publishedAt = "2026-10-15T15:43:07Z",
            apkAsset = GitHubReleaseAsset("leftovers-vc25.apk", 4000000L, "https://github.com/...", "application/vnd.android.package-archive"),
        )

        // Remote code 25 > local code 20 -> Update available
        assertTrue(VersionComparator.isUpdateAvailable("1.7.0", 20L, releaseWithCode25))

        // Remote code 25 <= local code 25 -> Up to date
        assertFalse(VersionComparator.isUpdateAvailable("1.7.0", 25L, releaseWithCode25))

        // Remote code 25 < local code 30 -> Up to date (no downgrade)
        assertFalse(VersionComparator.isUpdateAvailable("1.7.0", 30L, releaseWithCode25))
    }
}
