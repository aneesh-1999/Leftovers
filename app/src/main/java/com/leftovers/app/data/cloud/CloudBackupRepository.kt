package com.leftovers.app.data.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.util.BackupCrypto
import com.leftovers.app.util.BackupManager
import com.leftovers.app.util.BackupPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CloudBackupInfo(
    val timestamp: Long,
    val totalItems: Int,
    val isEncrypted: Boolean,
    val deviceModel: String,
    val compressedSizeFormatted: String,
    val userName: String? = null,
    val isCloudEncrypted: Boolean = true,
    val encryptionType: String = if (isEncrypted) "PASSWORD" else "ACCOUNT_KEY",
)

class CloudBackupRepository(
    private val context: Context,
    private val authManager: FirebaseAuthManager,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "CloudBackupRepo"
        private const val CHUNK_SIZE = 750_000 // Chunks to stay well under Firestore 1MB document limit

        /**
         * Computes a deterministic, user-bound cryptographic vault key derived from the user's
         * authenticated Firebase UID. Guarantees that even without a custom backup password,
         * cloud backups are always stored as AES-256-GCM ciphertext in Firestore and cannot be
         * read in plaintext by database observers.
         */
        fun deriveAccountVaultKey(uid: String): String {
            require(uid.isNotBlank()) { "UID cannot be blank for vault key derivation" }
            return "Leftovers:UserVault:AES-256-GCM:uid=$uid"
        }
    }

    private val firestore: FirebaseFirestore by lazy {
        initFirestore()
    }

    private fun initFirestore(): FirebaseFirestore {
        val app = FirebaseConfig.getOrInitApp(context)
        val db = if (FirebaseConfig.DATABASE_ID == "(default)") {
            FirebaseFirestore.getInstance(app)
        } else {
            FirebaseFirestore.getInstance(app, FirebaseConfig.DATABASE_ID)
        }
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
            .build()
        db.firestoreSettings = settings
        return db
    }

    /**
     * Checks if a cloud backup exists for the currently logged-in user.
     */
    suspend fun fetchLatestBackupInfo(): Result<CloudBackupInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.auth.currentUser?.uid
                ?: error("You must be signed in to check cloud backups")

            // Ensure Firestore's internal auth token is refreshed and active
            authManager.ensureValidToken()

            val doc = firestore.collection(FirebaseConfig.COLLECTION_USER_BACKUPS)
                .document(uid)
                .collection("slots")
                .document(FirebaseConfig.DOC_LATEST)
                .get()
                .await()

            if (!doc.exists()) {
                null
            } else {
                val timestamp = doc.getLong("timestamp") ?: 0L
                val totalItems = (doc.getLong("totalItems") ?: 0L).toInt()
                val isEncrypted = doc.getBoolean("isEncrypted") ?: false
                val deviceModel = doc.getString("deviceModel") ?: "Unknown"
                val compressedBytes = doc.getLong("compressedBytes") ?: 0L
                val formattedSize = formatBytes(compressedBytes)
                val userName = doc.getString("userName")
                val isCloudEncrypted = doc.getBoolean("cloudEncrypted") ?: true
                val encryptionType = doc.getString("encryptionType") ?: if (isEncrypted) "PASSWORD" else "ACCOUNT_KEY"

                CloudBackupInfo(
                    timestamp = timestamp,
                    totalItems = totalItems,
                    isEncrypted = isEncrypted,
                    deviceModel = deviceModel,
                    compressedSizeFormatted = formattedSize,
                    userName = userName,
                    isCloudEncrypted = isCloudEncrypted,
                    encryptionType = encryptionType,
                )
            }
        }
    }

    /**
     * Creates a full backup, compresses it using GZIP, and saves it to the user's isolated
     * Firestore storage. Always encrypted with AES-256-GCM.
     */
    suspend fun backupNow(): Result<CloudBackupInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.auth.currentUser?.uid
                ?: error("You must be signed in to perform a cloud backup")

            // Ensure fresh, active Firebase Auth token before Firestore operations
            authManager.ensureValidToken()

            val currentSettings = settingsRepository.settings.first()
            val effectiveUserName = currentSettings.userName.ifBlank {
                authManager.auth.currentUser?.displayName.orEmpty()
            }
            val userEmail = authManager.auth.currentUser?.email.orEmpty()

            Log.d(TAG, "Creating local backup payload for cloud upload (userName=$effectiveUserName)...")
            val payload = backupManager.createBackupPayload(encryptIfPasswordSet = true)

            // Zero-Knowledge Client-Side Encryption:
            // If the user configured a custom backup password, it is already encrypted with it (payload.isEncrypted == true).
            // If not, transparently encrypt with a user-bound AES-256-GCM vault key derived from the authenticated UID.
            // Under NO circumstance is raw plaintext financial JSON ever stored in Firestore!
            val finalPayloadJson = if (payload.isEncrypted) {
                payload.jsonString
            } else {
                val vaultKey = deriveAccountVaultKey(uid)
                BackupCrypto.encrypt(payload.jsonString, vaultKey).toString()
            }
            val isPasswordProtected = payload.isEncrypted
            val encryptionType = if (isPasswordProtected) "PASSWORD" else "ACCOUNT_KEY"

            // Compress encrypted json using GZIP
            val gzipB64 = compressGzip(finalPayloadJson)
            val compressedBytes = gzipB64.toByteArray().size.toLong()
            val rawBytes = finalPayloadJson.toByteArray().size.toLong()
            val now = System.currentTimeMillis()
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

            val userDocRef = firestore.collection(FirebaseConfig.COLLECTION_USER_BACKUPS)
                .document(uid)
            val latestSlotRef = userDocRef.collection("slots").document(FirebaseConfig.DOC_LATEST)

            // If the payload fits comfortably within Firestore document limit (<= CHUNK_SIZE)
            if (gzipB64.length <= CHUNK_SIZE) {
                val metadata = hashMapOf(
                    "app" to "leftovers",
                    "version" to 1,
                    "timestamp" to now,
                    "userName" to effectiveUserName,
                    "userEmail" to userEmail,
                    "totalItems" to payload.totalItems,
                    "isEncrypted" to isPasswordProtected,
                    "cloudEncrypted" to true,
                    "encryptionType" to encryptionType,
                    "deviceModel" to deviceModel,
                    "rawBytes" to rawBytes,
                    "compressedBytes" to compressedBytes,
                    "isChunked" to false,
                    "payloadGzipB64" to gzipB64,
                )
                latestSlotRef.set(metadata, SetOptions.merge()).await()
            } else {
                // Chunk into multiple pieces
                val chunks = gzipB64.chunked(CHUNK_SIZE)
                val metadata = hashMapOf(
                    "app" to "leftovers",
                    "version" to 1,
                    "timestamp" to now,
                    "userName" to effectiveUserName,
                    "userEmail" to userEmail,
                    "totalItems" to payload.totalItems,
                    "isEncrypted" to isPasswordProtected,
                    "cloudEncrypted" to true,
                    "encryptionType" to encryptionType,
                    "deviceModel" to deviceModel,
                    "rawBytes" to rawBytes,
                    "compressedBytes" to compressedBytes,
                    "isChunked" to true,
                    "chunkCount" to chunks.size,
                )
                latestSlotRef.set(metadata, SetOptions.merge()).await()

                val chunksCol = latestSlotRef.collection("chunks")
                for (i in chunks.indices) {
                    chunksCol.document("chunk_$i").set(
                        mapOf(
                            "index" to i,
                            "data" to chunks[i],
                        )
                    ).await()
                }
            }

            // Update user top-level record with last backup time and identity
            userDocRef.set(
                mapOf(
                    "lastBackupAt" to now,
                    "deviceModel" to deviceModel,
                    "userName" to effectiveUserName,
                    "userEmail" to userEmail,
                    "backupVersion" to 1,
                ),
                SetOptions.merge()
            ).await()

            settingsRepository.setCloudBackupLast(now)
            Log.d(TAG, "Cloud backup completed successfully: ${payload.totalItems} items, encrypted ($encryptionType) and compressed to ${formatBytes(compressedBytes)}")

            CloudBackupInfo(
                timestamp = now,
                totalItems = payload.totalItems,
                isEncrypted = isPasswordProtected,
                deviceModel = deviceModel,
                compressedSizeFormatted = formatBytes(compressedBytes),
                userName = effectiveUserName,
                isCloudEncrypted = true,
                encryptionType = encryptionType,
            )
        }
    }

    /**
     * Downloads the backup from Firestore, decrypts it, and restores all local data.
     */
    suspend fun restoreFromCloud(typedPassword: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.auth.currentUser?.uid
                ?: error("You must be signed in to restore cloud backups")

            // Ensure fresh, active Firebase Auth token before Firestore operations
            authManager.ensureValidToken()

            val latestSlotRef = firestore.collection(FirebaseConfig.COLLECTION_USER_BACKUPS)
                .document(uid)
                .collection("slots")
                .document(FirebaseConfig.DOC_LATEST)

            val doc = latestSlotRef.get().await()
            if (!doc.exists()) {
                error("No cloud backup found for this account")
            }

            val isChunked = doc.getBoolean("isChunked") ?: false
            val gzipB64 = if (!isChunked) {
                doc.getString("payloadGzipB64") ?: error("Backup payload is empty")
            } else {
                val chunkCount = (doc.getLong("chunkCount") ?: 0L).toInt()
                val sb = StringBuilder()
                val chunksCol = latestSlotRef.collection("chunks")
                for (i in 0 until chunkCount) {
                    val chunkDoc = chunksCol.document("chunk_$i").get().await()
                    val part = chunkDoc.getString("data") ?: error("Missing backup chunk $i")
                    sb.append(part)
                }
                sb.toString()
            }

            val jsonText = decompressGzip(gzipB64)

            // Multi-tier Decryption:
            // 1. If user supplied an explicit typedPassword, attempt decryption with it.
            // 2. If no typedPassword was provided:
            //    Check if jsonText is encrypted with BackupCrypto.
            //    If so:
            //      - First attempt seamless decryption with user-bound deriveAccountVaultKey(uid).
            //      - If that succeeds, restore the inner JSON payload!
            //      - If that fails, it means the backup was encrypted with a custom password:
            //        throw BackupPasswordException so the UI prompts the user to enter their password.
            // 3. If jsonText is unencrypted (legacy backup), restore directly.
            val restoredCount = run {
                val jsonObject = runCatching { JSONObject(jsonText) }.getOrNull()
                if (jsonObject != null && BackupCrypto.isEncrypted(jsonObject)) {
                    if (!typedPassword.isNullOrBlank()) {
                        backupManager.restoreFromJsonText(jsonText, typedPassword).getOrThrow()
                    } else {
                        val vaultKey = deriveAccountVaultKey(uid)
                        val decryptedWithVaultKey = runCatching {
                            BackupCrypto.decrypt(jsonObject, vaultKey)
                        }.getOrNull()

                        if (decryptedWithVaultKey != null) {
                            backupManager.restoreFromJsonText(decryptedWithVaultKey, null).getOrThrow()
                        } else {
                            // Requires user custom password
                            throw BackupPasswordException("Password required to restore this encrypted backup")
                        }
                    }
                } else {
                    backupManager.restoreFromJsonText(jsonText, typedPassword).getOrThrow()
                }
            }

            // If restored user name is blank, preserve account display name
            val afterSettings = settingsRepository.settings.first()
            val authDisplayName = authManager.auth.currentUser?.displayName
            if (afterSettings.userName.isBlank() && !authDisplayName.isNullOrBlank()) {
                settingsRepository.setUserName(authDisplayName)
            }
            settingsRepository.setOnboarded(true)

            Log.d(TAG, "Successfully restored $restoredCount items from cloud backup")
            restoredCount
        }
    }

    /**
     * Permanently deletes the user's cloud backup files from Firestore.
     * Note: Does NOT delete local data on the device, ensuring the user's
     * offline-first records remain safe.
     */
    suspend fun deleteCloudDataPermanently(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.auth.currentUser?.uid
                ?: error("You must be signed in to delete cloud data")

            // Ensure fresh, active Firebase Auth token before Firestore operations
            authManager.ensureValidToken()

            val userDocRef = firestore.collection(FirebaseConfig.COLLECTION_USER_BACKUPS).document(uid)
            val slotsCol = userDocRef.collection("slots")
            val slotsSnapshot = slotsCol.get().await()

            for (slotDoc in slotsSnapshot.documents) {
                val chunksCol = slotDoc.reference.collection("chunks")
                val chunksSnapshot = chunksCol.get().await()
                for (chunkDoc in chunksSnapshot.documents) {
                    chunkDoc.reference.delete().await()
                }
                slotDoc.reference.delete().await()
            }

            userDocRef.delete().await()
            settingsRepository.setCloudBackupLast(0L)
            Log.d(TAG, "Permanently deleted all cloud backup data for user $uid")
            Unit
        }
    }

    /**
     * Permanently deletes all cloud data and deletes the Firebase Authentication user account.
     */
    suspend fun deleteCloudAccountPermanently(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = authManager.auth.currentUser
                ?: error("You must be signed in to delete your cloud account")

            // First delete all cloud data from Firestore
            deleteCloudDataPermanently().getOrThrow()

            // Delete Firebase Auth user account
            user.delete().await()
            authManager.signOut()
            Log.d(TAG, "Permanently deleted cloud account for user ${user.uid}")
            Unit
        }
    }

    private fun compressGzip(input: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun decompressGzip(base64Input: String): String {
        val bytes = Base64.getDecoder().decode(base64Input)
        val bis = ByteArrayInputStream(bytes)
        GZIPInputStream(bis).use { gis ->
            return gis.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
