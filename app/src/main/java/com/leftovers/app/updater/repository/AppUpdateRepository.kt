package com.leftovers.app.updater.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.GitHubReleaseAsset
import com.leftovers.app.updater.model.UpdateCheckStatus
import com.leftovers.app.updater.util.VersionComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Abstraction for HTTP operations, enabling isolated unit testing without live network.
 */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    fun openStream(url: String, headers: Map<String, String> = emptyMap()): StreamResponse
}

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

data class StreamResponse(
    val statusCode: Int,
    val contentLength: Long,
    val inputStream: InputStream,
    val disconnect: () -> Unit = {},
)

/**
 * Default HttpTransport implementation using standard Java HttpURLConnection.
 */
class DefaultHttpTransport(
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Leftovers-Android-Updater/1.0")
            setRequestProperty("Accept", "application/vnd.github+json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }.orEmpty()
            HttpResponse(statusCode = code, body = body, headers = conn.headerFields.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    override fun openStream(url: String, headers: Map<String, String>): StreamResponse {
        var currentUrl = url
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false // Manually handle redirects across HTTP/HTTPS boundaries
                setRequestProperty("User-Agent", "Leftovers-Android-Updater/1.0")
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (newLocation != null) {
                    currentUrl = newLocation
                    redirects++
                    continue
                }
            }

            if (code !in 200..299) {
                val errorBody = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }
                conn.disconnect()
                throw IllegalStateException("HTTP $code during stream download: $errorBody")
            }

            val length = conn.contentLengthLong.takeIf { it > 0 } ?: conn.contentLength.toLong()
            return StreamResponse(
                statusCode = code,
                contentLength = length,
                inputStream = conn.inputStream,
                disconnect = { conn.disconnect() },
            )
        }
        throw IllegalStateException("Too many redirects downloading APK")
    }
}

/**
 * Production-ready repository for querying GitHub Releases, checking version compatibility,
 * and downloading and validating APK updates.
 */
class AppUpdateRepository(
    private val context: Context,
    private val httpTransport: HttpTransport = DefaultHttpTransport(),
) {
    companion object {
        const val DEFAULT_REPO_OWNER = "aneesh-1999"
        const val DEFAULT_REPO_NAME = "Leftovers"
        const val GITHUB_API_BASE = "https://api.github.com/repos"

        private val ALLOWED_DOMAINS = listOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "codeload.github.com",
        )

        private val SHA256_HEX_REGEX = Regex("""(?i)\b([a-f0-9]{64})\b""")
    }

    /**
     * Queries the GitHub Releases API for the latest published stable release.
     */
    suspend fun fetchLatestRelease(
        repoOwner: String = DEFAULT_REPO_OWNER,
        repoName: String = DEFAULT_REPO_NAME,
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$GITHUB_API_BASE/$repoOwner/$repoName/releases/latest"
            val response = httpTransport.get(url)

            when (response.statusCode) {
                404 -> throw IllegalStateException("No published releases found for repository $repoOwner/$repoName on GitHub.")
                403 -> {
                    val resetHeader = response.headers["x-ratelimit-reset"]?.firstOrNull()
                    val resetMsg = resetHeader?.toLongOrNull()?.let {
                        val waitMinutes = ((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                        " (resets in ~$waitMinutes min)"
                    } ?: ""
                    throw IllegalStateException("GitHub API rate limit reached$resetMsg. Please try again later.")
                }
                in 200..299 -> parseReleaseJson(response.body)
                else -> throw IllegalStateException("GitHub API returned error code ${response.statusCode}")
            }
        }
    }

    /**
     * Checks if a newer update is available compared to the current version.
     */
    suspend fun checkForUpdates(
        currentVersionName: String,
        currentVersionCode: Long,
        repoOwner: String = DEFAULT_REPO_OWNER,
        repoName: String = DEFAULT_REPO_NAME,
    ): UpdateCheckStatus = withContext(Dispatchers.IO) {
        val result = fetchLatestRelease(repoOwner, repoName)
        val now = System.currentTimeMillis()

        result.fold(
            onSuccess = { release ->
                if (release.apkAsset == null) {
                    UpdateCheckStatus.Failed(
                        error = "Release ${release.displayVersion} exists but does not contain an Android APK (.apk asset).",
                        checkedAt = now,
                    )
                } else if (VersionComparator.isUpdateAvailable(currentVersionName, currentVersionCode, release)) {
                    UpdateCheckStatus.UpdateAvailable(
                        currentVersion = currentVersionName,
                        latestVersion = release.displayVersion,
                        release = release,
                        checkedAt = now,
                    )
                } else {
                    UpdateCheckStatus.UpToDate(
                        currentVersion = currentVersionName,
                        checkedAt = now,
                    )
                }
            },
            onFailure = { error ->
                val isRateLimit = error.message?.contains("rate limit", ignoreCase = true) == true
                UpdateCheckStatus.Failed(
                    error = error.message ?: "Failed to connect to GitHub",
                    isRateLimited = isRateLimit,
                    checkedAt = now,
                )
            },
        )
    }

    /**
     * Downloads the APK asset with progress reporting, cancellation support, and integrity verification.
     */
    suspend fun downloadApk(
        release: GitHubRelease,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val asset = release.apkAsset ?: throw IllegalArgumentException("No APK asset in release")
            validateDownloadUrl(asset.downloadUrl)

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val cleanAssetName = asset.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(updatesDir, cleanAssetName)
            val partFile = File(updatesDir, "$cleanAssetName.part")

            if (partFile.exists()) partFile.delete()

            val streamResponse = httpTransport.openStream(asset.downloadUrl)
            val totalBytes = if (streamResponse.contentLength > 0) streamResponse.contentLength else asset.size

            try {
                BufferedInputStream(streamResponse.inputStream).use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesDownloaded = 0L
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, read)
                            bytesDownloaded += read
                            onProgress(bytesDownloaded, totalBytes)
                        }
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                if (partFile.exists()) partFile.delete()
                throw e
            } finally {
                streamResponse.disconnect()
            }

            // Verify file size
            if (partFile.length() == 0L) {
                partFile.delete()
                throw IllegalStateException("Downloaded APK file is empty.")
            }

            // Verify SHA-256 Checksum if provided
            release.expectedChecksum?.let { expected ->
                val actualSha256 = calculateSha256(partFile)
                if (!actualSha256.equals(expected, ignoreCase = true)) {
                    partFile.delete()
                    throw SecurityException("APK integrity check failed! Expected SHA-256: $expected, but got: $actualSha256")
                }
            }

            // Move part file to final file
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            // Verify valid Android APK and package identity
            verifyApkPackage(targetFile).getOrThrow()

            targetFile
        }
    }

    /**
     * Verifies that the downloaded file is a valid Android APK matching this application's package name.
     */
    fun verifyApkPackage(apkFile: File): Result<PackageInfo> {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: throw SecurityException("Downloaded file is not a valid Android APK package.")

            if (pkgInfo.packageName != context.packageName) {
                throw SecurityException("Package name mismatch! Expected ${context.packageName}, but found ${pkgInfo.packageName}.")
            }

            pkgInfo
        }
    }

    /**
     * Validates that the download URL points strictly to official GitHub infrastructure.
     */
    fun validateDownloadUrl(urlString: String) {
        val uri = URI(urlString)
        if (uri.scheme?.lowercase() != "https") {
            throw SecurityException("Insecure download URL: $urlString. Only HTTPS is permitted.")
        }
        val host = uri.host?.lowercase().orEmpty()
        val isAllowed = ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") }
        if (!isAllowed) {
            throw SecurityException("Untrusted download domain: $host. Releases must originate from GitHub.")
        }
    }

    /**
     * Computes the SHA-256 hash of a file.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Parses the JSON payload from GitHub Releases API into a [GitHubRelease].
     */
    fun parseReleaseJson(jsonString: String): GitHubRelease {
        val json = JSONObject(jsonString)

        val id = json.optLong("id", 0L)
        val tagName = json.optString("tag_name", "").trim()
        val name = json.optString("name", "").trim()
        val body = json.optString("body", "").trim()
        val isDraft = json.optBoolean("draft", false)
        val isPrerelease = json.optBoolean("prerelease", false)
        val publishedAt = json.optString("published_at", "")
        val htmlUrl = json.optString("html_url", "")

        if (isDraft || isPrerelease) {
            throw IllegalStateException("Latest release is a draft or prerelease. Stable releases only are supported.")
        }

        // Parse assets
        val assetsArray = json.optJSONArray("assets") ?: JSONArray()
        var apkAsset: GitHubReleaseAsset? = null
        var sha256AssetUrl: String? = null

        for (i in 0 until assetsArray.length()) {
            val a = assetsArray.getJSONObject(i)
            val aName = a.optString("name", "")
            val aSize = a.optLong("size", 0L)
            val downloadUrl = a.optString("browser_download_url", "")
            val contentType = a.optString("content_type", "")

            if (aName.endsWith(".apk", ignoreCase = true) && apkAsset == null) {
                apkAsset = GitHubReleaseAsset(
                    name = aName,
                    size = aSize,
                    downloadUrl = downloadUrl,
                    contentType = contentType,
                )
            } else if (aName.endsWith(".sha256", ignoreCase = true) || aName.contains("checksum", ignoreCase = true)) {
                sha256AssetUrl = downloadUrl
            }
        }

        // Extract expected SHA-256 from checksum asset or release body
        var expectedChecksum: String? = null
        if (!sha256AssetUrl.isNullOrBlank()) {
            runCatching {
                val sumResponse = httpTransport.get(sha256AssetUrl)
                if (sumResponse.statusCode in 200..299) {
                    expectedChecksum = SHA256_HEX_REGEX.find(sumResponse.body)?.groupValues?.getOrNull(1)
                }
            }
        }

        if (expectedChecksum == null) {
            // Check if SHA-256 is mentioned in the release body
            expectedChecksum = SHA256_HEX_REGEX.find(body)?.groupValues?.getOrNull(1)
        }

        return GitHubRelease(
            id = id,
            tagName = tagName,
            name = name,
            body = body,
            isDraft = isDraft,
            isPrerelease = isPrerelease,
            publishedAt = publishedAt,
            apkAsset = apkAsset,
            expectedChecksum = expectedChecksum,
            htmlUrl = htmlUrl,
        )
    }
}
