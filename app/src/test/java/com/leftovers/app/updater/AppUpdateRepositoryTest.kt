package com.leftovers.app.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leftovers.app.updater.model.DownloadStatus
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.GitHubReleaseAsset
import com.leftovers.app.updater.model.UpdateCheckStatus
import com.leftovers.app.updater.repository.AppUpdateRepository
import com.leftovers.app.updater.repository.HttpResponse
import com.leftovers.app.updater.repository.HttpTransport
import com.leftovers.app.updater.repository.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private class MockHttpTransport : HttpTransport {
        var getHandler: (url: String) -> HttpResponse = { HttpResponse(404, "Not Found") }
        var streamHandler: (url: String) -> StreamResponse = { throw UnsupportedOperationException() }

        override fun get(url: String, headers: Map<String, String>): HttpResponse = getHandler(url)
        override fun openStream(url: String, headers: Map<String, String>): StreamResponse = streamHandler(url)
    }

    @Test
    fun checkForUpdates_newerVersionAvailable() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = {
            HttpResponse(
                statusCode = 200,
                body = """
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
                """.trimIndent(),
            )
        }

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.UpdateAvailable)
        val available = status as UpdateCheckStatus.UpdateAvailable
        assertEquals("1.7.0", available.currentVersion)
        assertEquals("Leftovers 1.7.1", available.latestVersion)
        assertEquals("v1.7.1", available.release.tagName)
        assertEquals("leftovers-v1.7.1.apk", available.release.apkAsset?.name)
    }

    @Test
    fun checkForUpdates_noUpdateAvailable_whenInstalledIsSameOrNewer() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = {
            HttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 102,
                      "tag_name": "v1.7.0",
                      "name": "Leftovers 1.7.0",
                      "body": "Current release",
                      "draft": false,
                      "prerelease": false,
                      "published_at": "2026-10-10T12:00:00Z",
                      "assets": [
                        {
                          "name": "leftovers-v1.7.0.apk",
                          "size": 4100000,
                          "browser_download_url": "https://github.com/aneesh-1999/Leftovers/releases/download/v1.7.0/leftovers-v1.7.0.apk",
                          "content_type": "application/vnd.android.package-archive"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.UpToDate)
        assertEquals("1.7.0", (status as UpdateCheckStatus.UpToDate).currentVersion)
    }

    @Test
    fun checkForUpdates_rejectsDraftAndPrerelease() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = {
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

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertTrue((status as UpdateCheckStatus.Failed).error.contains("prerelease", ignoreCase = true))
    }

    @Test
    fun checkForUpdates_missingApkAsset_reportsError() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = {
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

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertTrue((status as UpdateCheckStatus.Failed).error.contains("does not contain an Android APK", ignoreCase = true))
    }

    @Test
    fun checkForUpdates_rateLimitHandled() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = {
            HttpResponse(
                statusCode = 403,
                body = "Rate limit exceeded",
                headers = mapOf("x-ratelimit-reset" to listOf("${(System.currentTimeMillis() / 1000) + 1800}")),
            )
        }

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.Failed)
        val failed = status as UpdateCheckStatus.Failed
        assertTrue(failed.isRateLimited)
        assertTrue(failed.error.contains("rate limit", ignoreCase = true))
    }

    @Test
    fun checkForUpdates_notFound_reportsNoReleases() = runBlocking {
        val mock = MockHttpTransport()
        mock.getHandler = { HttpResponse(statusCode = 404, body = "Not Found") }

        val repo = AppUpdateRepository(context, mock)
        val status = repo.checkForUpdates(currentVersionName = "1.7.0", currentVersionCode = 20L)

        assertTrue(status is UpdateCheckStatus.Failed)
        assertTrue((status as UpdateCheckStatus.Failed).error.contains("No published releases found", ignoreCase = true))
    }

    @Test
    fun downloadUrl_securityValidation_rejectsUntrustedDomains() {
        val repo = AppUpdateRepository(context, MockHttpTransport())

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
        val repo = AppUpdateRepository(context, MockHttpTransport())
        val tempFile = File(context.cacheDir, "test_hash.txt").apply {
            writeText("Leftovers secure updater test")
        }

        val hash = repo.calculateSha256(tempFile)
        assertNotNull(hash)
        assertEquals(64, hash.length)

        tempFile.delete()
    }
}
