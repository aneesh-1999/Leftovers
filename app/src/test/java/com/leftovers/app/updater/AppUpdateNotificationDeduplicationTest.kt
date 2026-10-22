package com.leftovers.app.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.GitHubReleaseAsset
import com.leftovers.app.updater.worker.AppUpdateCheckWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateNotificationDeduplicationTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
    }

    @Test
    fun notificationDeduplication_logicWorksAcrossReleases() = runBlocking {
        // Initial state: no previous notified tag
        val initialSettings = settingsRepository.settings.first()
        assertEquals("", initialSettings.lastNotifiedUpdateTag)

        val releaseV171 = GitHubRelease(
            id = 101,
            tagName = "v1.7.1",
            name = "v1.7.1",
            body = "Notes",
            isDraft = false,
            isPrerelease = false,
            publishedAt = "2026-10-15T15:00:00Z",
            apkAsset = GitHubReleaseAsset("leftovers.apk", 1000L, "https://github.com/...", ""),
        )

        // First notification: should notify because not equal
        val shouldNotifyFirst = initialSettings.lastNotifiedUpdateTag != releaseV171.tagName
        assertTrue("Should notify on first detection of v1.7.1", shouldNotifyFirst)

        // Save last notified tag
        settingsRepository.setLastNotifiedUpdateTag(releaseV171.tagName)

        val updatedSettings = settingsRepository.settings.first()
        assertEquals("v1.7.1", updatedSettings.lastNotifiedUpdateTag)

        // Subsequent check for same release: should NOT notify
        val shouldNotifyDuplicate = updatedSettings.lastNotifiedUpdateTag != releaseV171.tagName
        assertFalse("Should NOT notify again for the same release tag v1.7.1", shouldNotifyDuplicate)

        // New release v1.7.2 arrives: should notify
        val releaseV172 = releaseV171.copy(tagName = "v1.7.2", name = "v1.7.2")
        val shouldNotifyNewVersion = updatedSettings.lastNotifiedUpdateTag != releaseV172.tagName
        assertTrue("Should notify when new version tag v1.7.2 arrives", shouldNotifyNewVersion)
    }

    @Test
    fun autoUpdateSettings_toggleSuccessfully() = runBlocking {
        settingsRepository.setAutoUpdateChecks(false)
        assertFalse(settingsRepository.settings.first().autoUpdateChecks)

        settingsRepository.setAutoUpdateChecks(true)
        assertTrue(settingsRepository.settings.first().autoUpdateChecks)

        settingsRepository.setUpdateCheckWifiOnly(true)
        assertTrue(settingsRepository.settings.first().updateCheckWifiOnly)

        settingsRepository.setUpdateCheckWifiOnly(false)
        assertFalse(settingsRepository.settings.first().updateCheckWifiOnly)
    }
}
