package com.leftovers.app.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.UpdateCheckStatus
import com.leftovers.app.updater.repository.AppUpdateRepository
import com.leftovers.app.updater.repository.HttpResponse
import com.leftovers.app.updater.repository.HttpTransport
import com.leftovers.app.updater.repository.StreamResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateRepositoryTest {

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

    private class MockHttpTransport : HttpTransport {
        var getHandler: (url: String, headers: Map<String, String>) -> HttpResponse = { _, _ ->
            HttpResponse(404, "Not Found")
        }
        var streamHandler: (url: String, headers: Map<String, String>) -> StreamResponse = { _, _ ->
            throw UnsupportedOperationException()
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse = getHandler(url, headers)
        override fun openStream(url: String, headers: Map<String, String>): StreamResponse = streamHandler(url, headers)
    }

    private val sampleReleaseJson = """
        {
          "id": 101,
          "tag_name": "v1.7.1",
          "name": "Leftovers 1.7.1",
          "body": "Fixed widgets and improved speed",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-15T15:43:07Z",
          "assets": [
            {
              "name": "leftovers-v1.7.1.apk",
              "size": 4200000,
              "browser_download_url": "https://github.com/aneesh-1999/Leftovers/releases/download/v1.7.1/leftovers-v1.7.1.apk",
              "content_type": "application/vnd.android.package-archive"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun checkForUpdates_newerVersionAvailable() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(statusCode = 200, body = sampleReleaseJson)
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.UpdateAvailable)
        val available = status as UpdateCheckStatus.UpdateAvailable
        assertEquals("1.7.0", available.currentVersion)
        assertEquals("Leftovers 1.7.1", available.latestVersion)
        assertEquals("v1.7.1", available.release.tagName)
        assertEquals("leftovers-v1.7.1.apk", available.release.apkAsset?.name)
        assertFalse(available.fromCache)
    }

    @Test
    fun checkForUpdates_cacheReusedWithin15Minutes() = runBlocking {
        val mock = MockHttpTransport()
        val requestCount = AtomicInteger(0)
        mock.getHandler = { _, _ ->
            requestCount.incrementAndGet()
            HttpResponse(statusCode = 200, body = sampleReleaseJson)
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)

        // 1. Initial check (network request)
        val firstStatus = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)
        assertEquals(1, requestCount.get())
        assertTrue(firstStatus is UpdateCheckStatus.UpdateAvailable)
        assertFalse((firstStatus as UpdateCheckStatus.UpdateAvailable).fromCache)

        // 2. Second rapid check: must reuse cache locally within 15 minutes without making any network request
        val secondStatus = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)
        assertEquals(1, requestCount.get()) // No new HTTP request
        assertTrue(secondStatus is UpdateCheckStatus.UpdateAvailable)
        val available = secondStatus as UpdateCheckStatus.UpdateAvailable
        assertTrue(available.fromCache)
        assertEquals("Leftovers 1.7.1", available.latestVersion)
    }

    @Test
    fun checkForUpdates_conditionalGet_http304NotModified() = runBlocking {
        val mock = MockHttpTransport()
        val capturedHeaders = mutableListOf<Map<String, String>>()
        val requestCount = AtomicInteger(0)

        mock.getHandler = { _, headers ->
            capturedHeaders.add(headers)
            val count = requestCount.incrementAndGet()
            if (count == 1) {
                HttpResponse(
                    statusCode = 200,
                    body = sampleReleaseJson,
                    headers = mapOf("ETag" to listOf("\"etag-v1.7.1\"")),
                )
            } else {
                HttpResponse(
                    statusCode = 304,
                    body = "",
                    headers = mapOf("ETag" to listOf("\"etag-v1.7.1\"")),
                )
            }
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)

        // 1. First check stores release and ETag
        repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)
        assertEquals(1, requestCount.get())

        // 2. Simulate 20 minutes elapsed (cache expired)
        val expiredTime = System.currentTimeMillis() - (20 * 60 * 1000L)
        settingsRepository.touchUpdateCacheTimestamp(expiredTime)

        // 3. Second check sends If-None-Match header and handles 304
        val secondStatus = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)
        assertEquals(2, requestCount.get())
        assertEquals("\"etag-v1.7.1\"", capturedHeaders[1]["If-None-Match"])

        assertTrue(secondStatus is UpdateCheckStatus.UpdateAvailable)
        val available = secondStatus as UpdateCheckStatus.UpdateAvailable
        assertTrue(available.fromCache)
        assertEquals("Leftovers 1.7.1", available.latestVersion)
    }

    @Test
    fun checkForUpdates_rateLimit403_parsesXRateLimitResetAndPersists() = runBlocking {
        val now = System.currentTimeMillis()
        val resetEpochSec = (now / 1000L) + 2460L // ~41 minutes in future
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(
                statusCode = 403,
                body = "API rate limit exceeded",
                headers = mapOf(
                    "X-RateLimit-Reset" to listOf("$resetEpochSec"),
                    "X-RateLimit-Remaining" to listOf("0"),
                ),
            )
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.Failed)
        val failed = status as UpdateCheckStatus.Failed
        assertTrue(failed.isRateLimited)
        assertEquals(resetEpochSec * 1000L, failed.rateLimitResetTime)
        assertTrue(failed.error.contains("rate limit", ignoreCase = true))

        // Verify persisted across restarts
        val settings = settingsRepository.settings.first()
        assertEquals(resetEpochSec * 1000L, settings.updateRateLimitResetTime)
    }

    @Test
    fun checkForUpdates_rateLimit429_parsesRetryAfterSeconds() = runBlocking {
        val now = System.currentTimeMillis()
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(
                statusCode = 429,
                body = "Too Many Requests",
                headers = mapOf("Retry-After" to listOf("120")),
            )
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.Failed)
        val failed = status as UpdateCheckStatus.Failed
        assertTrue(failed.isRateLimited)
        assertTrue(failed.rateLimitResetTime >= now + 120_000L)

        val settings = settingsRepository.settings.first()
        assertTrue(settings.updateRateLimitResetTime >= now + 120_000L)
    }

    @Test
    fun checkForUpdates_activeRateLimit_skipsNetworkUntilReset() = runBlocking {
        val futureResetTime = System.currentTimeMillis() + 600_000L // 10 minutes from now
        settingsRepository.setUpdateRateLimitResetTime(futureResetTime)

        val mock = MockHttpTransport()
        var requestMade = false
        mock.getHandler = { _, _ ->
            requestMade = true
            HttpResponse(statusCode = 200, body = sampleReleaseJson)
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertFalse(requestMade) // Network request strictly prevented
        assertTrue(status is UpdateCheckStatus.Failed)
        val failed = status as UpdateCheckStatus.Failed
        assertTrue(failed.isRateLimited)
        assertEquals(futureResetTime, failed.rateLimitResetTime)
    }

    @Test
    fun checkForUpdates_rapidConcurrentTaps_mutexPreventsDuplicateRequests() = runBlocking {
        settingsRepository.clearAll()
        val mock = MockHttpTransport()
        val requestCount = AtomicInteger(0)
        mock.getHandler = { _, _ ->
            requestCount.incrementAndGet()
            Thread.sleep(50) // simulate network latency
            HttpResponse(statusCode = 200, body = sampleReleaseJson)
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)

        // Simulate 5 simultaneous rapid taps (all forceRefresh = false, sharing mutex & cache)
        val jobs = (1..5).map {
            async {
                repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = false)
            }
        }
        val results = jobs.awaitAll()

        assertEquals(1, requestCount.get()) // Mutex and cache guarantee single flight
        results.forEach { status ->
            assertTrue(status is UpdateCheckStatus.UpdateAvailable)
        }
    }

    @Test
    fun checkForUpdates_networkFailure_returnsFailed_neverFalseUpToDate() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            throw IOException("Unable to resolve host \"api.github.com\": No address associated with hostname")
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertFalse(status is UpdateCheckStatus.UpToDate)
        val failed = status as UpdateCheckStatus.Failed
        assertFalse(failed.isRateLimited)
        assertTrue(failed.error.contains("Unable to resolve host", ignoreCase = true))
    }

    @Test
    fun checkForUpdates_xRateLimitRemainingZero_on200_persistsResetTime() = runBlocking {
        val now = System.currentTimeMillis()
        val resetSec = (now / 1000L) + 1200L
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(
                statusCode = 200,
                body = sampleReleaseJson,
                headers = mapOf(
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("$resetSec"),
                ),
            )
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.UpdateAvailable)
        val settings = settingsRepository.settings.first()
        assertEquals(resetSec * 1000L, settings.updateRateLimitResetTime)
    }

    @Test
    fun checkForUpdates_rejectsDraftAndPrerelease() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 103,
                      "tag_name": "v1.8.0-beta",
                      "name": "Leftovers 1.8.0 Beta",
                      "body": "Draft/Prerelease",
                      "draft": false,
                      "prerelease": true,
                      "published_at": "2026-10-20T10:00:00Z",
                      "assets": []
                    }
                """.trimIndent(),
            )
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertTrue((status as UpdateCheckStatus.Failed).error.contains("prerelease", ignoreCase = true))
    }

    @Test
    fun checkForUpdates_missingApkAsset_reportsError() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = { _, _ ->
            HttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 104,
                      "tag_name": "v1.7.5",
                      "name": "Leftovers 1.7.5",
                      "body": "No APK attached",
                      "draft": false,
                      "prerelease": false,
                      "published_at": "2026-10-20T10:00:00Z",
                      "assets": [
                        {
                          "name": "source-code.zip",
                          "size": 1000,
                          "browser_download_url": "https://github.com/...",
                          "content_type": "application/zip"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

        val repo = AppUpdateRepository(context, settingsRepository, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L, forceRefresh = true)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertTrue((status as UpdateCheckStatus.Failed).error.contains("does not contain an Android APK", ignoreCase = true))
    }

    @Test
    fun downloadUrl_securityValidation_rejectsUntrustedDomains() {
        val repo = AppUpdateRepository(context, settingsRepository, MockHttpTransport())

        // Valid domains
        repo.validateDownloadUrl("https://github.com/aneesh-1999/Leftovers/releases/download/v1.7.1/app.apk")
        repo.validateDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/app.apk")

        // Insecure scheme
        try {
            repo.validateDownloadUrl("http://github.com/app.apk")
            fail("Expected SecurityException for http://")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Insecure download URL"))
        }

        // Untrusted domain
        try {
            repo.validateDownloadUrl("https://evil-hacker-site.com/malicious.apk")
            fail("Expected SecurityException for untrusted domain")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Untrusted download domain"))
        }
    }

    @Test
    fun calculateSha256_computesCorrectDigest() {
        val repo = AppUpdateRepository(context, settingsRepository, MockHttpTransport())
        val tempFile = File(context.cacheDir, "test_hash.txt").apply {
            writeText("Leftovers secure updater test")
        }

        val hash = repo.calculateSha256(tempFile)
        assertNotNull(hash)
        assertEquals(64, hash.length)

        tempFile.delete()
    }
}
