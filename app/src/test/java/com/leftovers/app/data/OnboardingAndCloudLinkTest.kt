package com.leftovers.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingAndCloudLinkTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
        runBlocking {
            settingsRepository.clearAll()
        }
    }

    @Test
    fun testInitialOnboardingStateIsUncompleted() {
        runBlocking {
            val s = settingsRepository.settings.first()
            assertFalse("New install should not be marked as onboarded", s.onboarded)
            assertFalse("Cloud backup should be disabled initially", s.cloudBackupEnabled)
            assertTrue("Currency code should default to locale currency", s.currencyCode.isNotBlank())
            assertEquals("", s.userName)
            assertNull(s.userPhotoPath)
        }
    }

    @Test
    fun testNewUserCompletesOnboardingWithDetailsAndCloudLink() {
        runBlocking {
            val chosenCurrency = "NPR"
            val chosenName = "Anil"
            val photoPath = "/data/user/0/com.leftovers.app/files/receipts/receipt-avatar-test.jpg"

            settingsRepository.completeOnboarding(
                currencyCode = chosenCurrency,
                userName = chosenName,
                userPhotoPath = photoPath,
            )
            settingsRepository.setCloudBackupEnabled(true)
            settingsRepository.setCloudUserInfo("test@example.com", "Anil Google")

            val s = settingsRepository.settings.first()
            assertTrue("User should now be marked onboarded", s.onboarded)
            assertEquals("NPR", s.currencyCode)
            assertEquals("Anil", s.userName)
            assertEquals(photoPath, s.userPhotoPath)
            assertTrue("Cloud backup should be enabled", s.cloudBackupEnabled)
            assertEquals("test@example.com", s.cloudUserEmail)
            assertEquals("Anil Google", s.cloudUserDisplayName)
        }
    }

    @Test
    fun testOfflineUserCompletesOnboardingWithoutCloudLink() {
        runBlocking {
            val chosenCurrency = "USD"
            val chosenName = "Offline User"

            settingsRepository.completeOnboarding(
                currencyCode = chosenCurrency,
                userName = chosenName,
                userPhotoPath = null,
            )

            val s = settingsRepository.settings.first()
            assertTrue("User should be onboarded", s.onboarded)
            assertEquals("USD", s.currencyCode)
            assertEquals("Offline User", s.userName)
            assertNull(s.userPhotoPath)
            assertFalse("Cloud backup should remain disabled for offline user", s.cloudBackupEnabled)
            assertNull(s.cloudUserEmail)
        }
    }
}
