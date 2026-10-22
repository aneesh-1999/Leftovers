package com.leftovers.app.data.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.leftovers.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CloudUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
)

sealed interface AuthResult {
    data class Success(val user: CloudUser) : AuthResult
    data class Cancelled(val message: String = "Sign in cancelled") : AuthResult
    data class Error(val message: String, val cause: Throwable? = null) : AuthResult
}

class FirebaseAuthManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "FirebaseAuthManager"
    }

    private val firebaseApp: FirebaseApp by lazy {
        getOrInitFirebaseApp(context)
    }

    val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance(firebaseApp)
    }

    private val credentialManager by lazy {
        CredentialManager.create(context)
    }

    private val _currentUser = MutableStateFlow<CloudUser?>(null)
    val currentUser: StateFlow<CloudUser?> = _currentUser.asStateFlow()

    init {
        // Sync with existing auth state
        auth.addAuthStateListener { fa ->
            val u = fa.currentUser?.toCloudUser()
            _currentUser.value = u
            scope.launch {
                settingsRepository.setCloudUserInfo(u?.email, u?.displayName)
                if (u?.displayName?.isNotBlank() == true) {
                    val s = settingsRepository.settings.first()
                    if (s.userName.isBlank()) {
                        settingsRepository.setUserName(u.displayName)
                    }
                }
            }
        }
    }

    private fun getOrInitFirebaseApp(context: Context): FirebaseApp =
        FirebaseConfig.getOrInitApp(context)

    suspend fun ensureValidToken(): String {
        val user = auth.currentUser ?: error("You must be signed in to access cloud backups")
        return user.getIdToken(false).await()?.token ?: error("Unable to retrieve Firebase Auth token")
    }

    /**
     * Signs in using Google via Android Credential Manager.
     * Takes the calling Activity so CredentialManager can display the bottom sheet selector.
     */
    suspend fun signInWithGoogle(activityContext: Context): AuthResult = withContext(Dispatchers.Main) {
        try {
            val settings = settingsRepository.settings.first()
            val savedClientId = settings.cloudBackupGoogleClientId.trim()
            val serverClientId = if (savedClientId.isBlank() || savedClientId.contains("466782740690")) {
                if (savedClientId.contains("466782740690")) {
                    scope.launch { settingsRepository.setCloudBackupGoogleClientId("") }
                }
                FirebaseConfig.DEFAULT_WEB_CLIENT_ID
            } else {
                savedClientId
            }

            Log.d(TAG, "Starting Google Sign-In with serverClientId=$serverClientId")

            if (serverClientId.isBlank()) {
                return@withContext AuthResult.Error("Google Web Client ID is not configured. Please configure it in Cloud Settings.")
            }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val act = activityContext.findActivity() ?: activityContext
            val result = credentialManager.getCredential(
                context = act,
                request = request,
            )

            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(firebaseCred).await()
                val user = authResult.user?.toCloudUser() ?: error("Sign in succeeded but user was null")

                settingsRepository.setCloudUserInfo(user.email, user.displayName)
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Unsupported credential type: ${credential.type}")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Google sign-in cancelled by user")
            AuthResult.Cancelled()
        } catch (e: GetCredentialException) {
            Log.e(TAG, "CredentialManager error: ${e.message}", e)
            AuthResult.Error(e.message ?: "Google sign in failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in error: ${e.message}", e)
            AuthResult.Error(e.message ?: "Authentication failed", e)
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val res = auth.signInWithEmailAndPassword(email.trim(), pass).await()
            val user = res.user?.toCloudUser() ?: error("User is null after sign in")
            settingsRepository.setCloudUserInfo(user.email, user.displayName)
            AuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Email sign-in failed: ${e.message}", e)
            AuthResult.Error(e.message ?: "Email sign-in failed", e)
        }
    }

    suspend fun registerWithEmail(email: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val res = auth.createUserWithEmailAndPassword(email.trim(), pass).await()
            val user = res.user?.toCloudUser() ?: error("User is null after register")
            settingsRepository.setCloudUserInfo(user.email, user.displayName)
            AuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed: ${e.message}", e)
            AuthResult.Error(e.message ?: "Registration failed", e)
        }
    }

    fun signOut() {
        auth.signOut()
        _currentUser.value = null
        scope.launch {
            settingsRepository.setCloudUserInfo(null, null)
        }
    }

    private fun FirebaseUser.toCloudUser(): CloudUser =
        CloudUser(
            uid = uid,
            email = email,
            displayName = displayName ?: email?.substringBefore('@'),
            photoUrl = photoUrl?.toString(),
        )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
