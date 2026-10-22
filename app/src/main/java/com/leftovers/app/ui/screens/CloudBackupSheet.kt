package com.leftovers.app.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.AppSettings
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.cloud.AuthResult
import com.leftovers.app.data.cloud.CloudBackupInfo
import com.leftovers.app.data.cloud.CloudBackupRepository
import com.leftovers.app.data.cloud.CloudUser
import com.leftovers.app.data.cloud.FirebaseAuthManager
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.BackupManager
import com.leftovers.app.util.BackupPasswordException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CloudBackupViewModel(
    val authManager: FirebaseAuthManager,
    private val cloudBackup: CloudBackupRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    val currentUser: StateFlow<CloudUser?> = authManager.currentUser
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettings(currencyCode = "EUR", plan = com.leftovers.app.data.BudgetPlan())
        )

    private val _latestCloudBackup = MutableStateFlow<CloudBackupInfo?>(null)
    val latestCloudBackup: StateFlow<CloudBackupInfo?> = _latestCloudBackup.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun refreshLatestBackup() {
        if (currentUser.value == null) return
        viewModelScope.launch {
            cloudBackup.fetchLatestBackupInfo().fold(
                onSuccess = { _latestCloudBackup.value = it },
                onFailure = { _errorMessage.value = it.message }
            )
        }
    }

    fun onUserSignedIn(
        isOnboarding: Boolean,
        onNeedPassword: () -> Unit,
        onGoToApp: () -> Unit,
    ) {
        if (!isOnboarding) {
            refreshLatestBackup()
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _statusMessage.value = "Checking for cloud backup..."
            cloudBackup.fetchLatestBackupInfo().fold(
                onSuccess = { info ->
                    _latestCloudBackup.value = info
                    if (info != null) {
                        if (info.isEncrypted) {
                            _statusMessage.value = "Cloud backup found (${info.totalItems} entries). Please enter your password to restore."
                            _isBusy.value = false
                            onNeedPassword()
                        } else {
                            _statusMessage.value = "Restoring cloud backup (${info.totalItems} entries)..."
                            cloudBackup.restoreFromCloud(null).fold(
                                onSuccess = {
                                    _statusMessage.value = "Restored successfully! Entering Leftovers..."
                                    _isBusy.value = false
                                    onGoToApp()
                                },
                                onFailure = { e ->
                                    _errorMessage.value = "Restore failed: ${e.message}"
                                    _isBusy.value = false
                                }
                            )
                        }
                    } else {
                        // No backup found - complete onboarding with Google name and enter app
                        _statusMessage.value = "No prior backup found. Setting up your profile..."
                        enterApp(onGoToApp)
                        _isBusy.value = false
                    }
                },
                onFailure = { err ->
                    _errorMessage.value = "Error checking backup: ${err.message}"
                    _isBusy.value = false
                }
            )
        }
    }

    fun enterApp(onEnter: () -> Unit) {
        viewModelScope.launch {
            val cur = settingsRepository.settings.first()
            val user = currentUser.value
            val effectiveName = cur.userName.ifBlank { user?.displayName.orEmpty() }
            settingsRepository.completeOnboarding(
                currencyCode = cur.currencyCode,
                userName = effectiveName,
                userPhotoPath = cur.userPhotoPath,
            )
            onEnter()
        }
    }

    fun signInWithGoogle(
        context: Context,
        isOnboarding: Boolean = false,
        onNeedPassword: () -> Unit = {},
        onGoToApp: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            when (val res = authManager.signInWithGoogle(context)) {
                is AuthResult.Success -> {
                    _statusMessage.value = "Signed in as ${res.user.displayName ?: res.user.email}"
                    if (isOnboarding) {
                        onUserSignedIn(isOnboarding = true, onNeedPassword = onNeedPassword, onGoToApp = onGoToApp)
                    } else {
                        refreshLatestBackup()
                        _isBusy.value = false
                    }
                }
                is AuthResult.Cancelled -> {
                    _isBusy.value = false
                }
                is AuthResult.Error -> {
                    _errorMessage.value = res.message
                    _isBusy.value = false
                }
            }
        }
    }

    fun signInWithEmail(
        email: String,
        pass: String,
        isOnboarding: Boolean = false,
        onNeedPassword: () -> Unit = {},
        onGoToApp: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            when (val res = authManager.signInWithEmail(email, pass)) {
                is AuthResult.Success -> {
                    _statusMessage.value = "Signed in successfully"
                    if (isOnboarding) {
                        onUserSignedIn(isOnboarding = true, onNeedPassword = onNeedPassword, onGoToApp = onGoToApp)
                    } else {
                        refreshLatestBackup()
                        _isBusy.value = false
                    }
                }
                is AuthResult.Error -> {
                    _errorMessage.value = res.message
                    _isBusy.value = false
                }
                is AuthResult.Cancelled -> {
                    _isBusy.value = false
                }
            }
        }
    }

    fun registerWithEmail(
        email: String,
        pass: String,
        isOnboarding: Boolean = false,
        onNeedPassword: () -> Unit = {},
        onGoToApp: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            when (val res = authManager.registerWithEmail(email, pass)) {
                is AuthResult.Success -> {
                    _statusMessage.value = "Account created and signed in"
                    if (isOnboarding) {
                        onUserSignedIn(isOnboarding = true, onNeedPassword = onNeedPassword, onGoToApp = onGoToApp)
                    } else {
                        refreshLatestBackup()
                        _isBusy.value = false
                    }
                }
                is AuthResult.Error -> {
                    _errorMessage.value = res.message
                    _isBusy.value = false
                }
                is AuthResult.Cancelled -> {
                    _isBusy.value = false
                }
            }
        }
    }

    fun signOut() {
        authManager.signOut()
        _latestCloudBackup.value = null
        clearMessages()
    }

    fun backupNow() {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            cloudBackup.backupNow().fold(
                onSuccess = {
                    _latestCloudBackup.value = it
                    _statusMessage.value = "Cloud backup completed (${it.totalItems} entries, ${it.compressedSizeFormatted})"
                },
                onFailure = {
                    _errorMessage.value = "Backup failed: ${it.message}"
                }
            )
            _isBusy.value = false
        }
    }

    fun restoreFromCloud(typedPassword: String? = null, onNeedPassword: () -> Unit, onSuccess: (Int) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            cloudBackup.restoreFromCloud(typedPassword).fold(
                onSuccess = { count ->
                    _statusMessage.value = "Restored $count items from cloud"
                    _isBusy.value = false
                    onSuccess(count)
                },
                onFailure = { e ->
                    _isBusy.value = false
                    if (e is BackupPasswordException) {
                        onNeedPassword()
                    } else {
                        _errorMessage.value = "Restore failed: ${e.message}"
                    }
                }
            )
        }
    }

    fun deleteCloudData(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            cloudBackup.deleteCloudDataPermanently().fold(
                onSuccess = {
                    _latestCloudBackup.value = null
                    _statusMessage.value = "All cloud backup data was permanently deleted"
                    _isBusy.value = false
                    onSuccess()
                },
                onFailure = { e ->
                    _errorMessage.value = "Failed to delete cloud data: ${e.message}"
                    _isBusy.value = false
                }
            )
        }
    }

    fun deleteCloudAccount(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            clearMessages()
            cloudBackup.deleteCloudAccountPermanently().fold(
                onSuccess = {
                    _latestCloudBackup.value = null
                    _statusMessage.value = "Your cloud account and data were permanently deleted"
                    _isBusy.value = false
                    onSuccess()
                },
                onFailure = { e ->
                    _errorMessage.value = if (e.message?.contains("recent-login", ignoreCase = true) == true) {
                        "Please sign in again and retry (Firebase requires recent authentication to delete an account)."
                    } else {
                        "Failed to delete account: ${e.message}"
                    }
                    _isBusy.value = false
                }
            )
        }
    }

    fun setCloudBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCloudBackupEnabled(enabled)
        }
    }

    fun setGoogleClientId(clientId: String) {
        viewModelScope.launch {
            settingsRepository.setCloudBackupGoogleClientId(clientId.trim())
            _statusMessage.value = "Google Web Client ID saved"
        }
    }
}

@Composable
fun CloudBackupSheet(
    onDismiss: () -> Unit,
    isOnboarding: Boolean = false,
    viewModel: CloudBackupViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val c = LocalAppColors.current

    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val latestBackup by viewModel.latestCloudBackup.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()

    var showEmailAuthDialog by remember { mutableStateOf(false) }
    var isRegisterMode by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showPasswordPromptDialog by remember { mutableStateOf(false) }
    var restorePasswordInput by remember { mutableStateOf("") }

    var showDeleteDataConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteAccountConfirmDialog by remember { mutableStateOf(false) }

    var showConfigClientIdDialog by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf("") }

    LaunchedEffect(user) {
        if (user != null) {
            viewModel.refreshLatestBackup()
        }
    }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Cloud,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Cloud Backup & Sync",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    Text(
                        "Firebase Online & Offline Backup",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }

            // Error / Status banners
            AnimatedVisibility(visible = errorMsg != null) {
                Glass(Modifier.fillMaxWidth()) {
                    Text(
                        text = errorMsg ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.negative,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            AnimatedVisibility(visible = statusMsg != null) {
                Glass(Modifier.fillMaxWidth()) {
                    Text(
                        text = statusMsg ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.positive,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            val currentUser = user
            if (currentUser == null) {
                // Not logged in view
                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Sign in with Google or Email to protect and sync your Leftovers data across devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textPrimary,
                        )

                        listOf(
                            "Safe cloud storage in your private Firebase vault",
                            "Full offline cache: access and backup anytime offline",
                            "Strictly isolated from shared Room Expense ledgers",
                            "Compatible with your existing backup password encryption",
                        ).forEach { feature ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Lucide.Check,
                                    contentDescription = null,
                                    tint = c.positive,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    feature,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textSecondary,
                                )
                            }
                        }
                    }
                }

                if (isBusy) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(32.dp))
                    }
                } else {
                    PrimaryButton(
                        text = "Sign in with Google",
                        onClick = {
                            viewModel.signInWithGoogle(
                                context = context,
                                isOnboarding = isOnboarding || !s.onboarded,
                                onNeedPassword = { showPasswordPromptDialog = true },
                                onGoToApp = onDismiss,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SecondaryButton(
                        text = "Sign in with Email & Password",
                        onClick = {
                            isRegisterMode = false
                            emailInput = ""
                            passwordInput = ""
                            showEmailAuthDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TextButton(
                        onClick = {
                            clientIdInput = s.cloudBackupGoogleClientId
                            showConfigClientIdDialog = true
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            "Custom Google Web Client ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                        )
                    }
                }
            } else {
                // User is authenticated
                Glass(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(c.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.User,
                                contentDescription = null,
                                tint = c.accent,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                currentUser.displayName ?: "Connected User",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = c.textPrimary,
                            )
                            currentUser.email?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textSecondary,
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.signOut() }) {
                            Text("Sign out", color = c.negative)
                        }
                    }
                }

                // If currently in onboarding or not yet onboarded, show direct Go to App entry card
                if (!s.onboarded || isOnboarding) {
                    Glass(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Lucide.Check,
                                    contentDescription = null,
                                    tint = c.positive,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Ready to Enter Leftovers",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = c.textPrimary,
                                )
                            }
                            Text(
                                "You are signed in as ${currentUser.displayName ?: currentUser.email}. Tap 'Go to App' to start using Leftovers, or restore your previous cloud backup below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                            PrimaryButton(
                                text = "Go to App",
                                onClick = {
                                    viewModel.enterApp { onDismiss() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Cloud Backup info card
                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Latest Cloud Backup",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )

                        val b = latestBackup
                        if (b != null) {
                            val dt = Instant.ofEpochMilli(b.timestamp)
                                .atZone(ZoneId.systemDefault())
                            val formattedDate = dt.format(DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a"))

                            if (!b.userName.isNullOrBlank()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Backup Owner", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                    Text(b.userName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = c.textPrimary)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Uploaded", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text(formattedDate, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total items", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text("${b.totalItems} entries", style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Compressed size", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text(b.compressedSizeFormatted, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Device", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text(b.deviceModel, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Encrypted", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text(if (b.isEncrypted) "Yes (Password protected)" else "No", style = MaterialTheme.typography.bodySmall, color = if (b.isEncrypted) c.positive else c.textSecondary)
                            }
                        } else {
                            Text(
                                "No backup stored in cloud yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textTertiary,
                            )
                        }
                    }
                }

                // Auto backup toggle
                Glass(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Automatic daily cloud backup",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = c.textPrimary,
                            )
                            Text(
                                "Silently syncs every 24 hours while connected to internet",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                        Switch(
                            checked = s.cloudBackupEnabled,
                            onCheckedChange = { viewModel.setCloudBackupEnabled(it) },
                        )
                    }
                }

                // Actions
                if (isBusy) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(32.dp))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrimaryButton(
                            text = if (!s.onboarded || isOnboarding) "Restore & Enter App" else "Restore",
                            onClick = { showRestoreConfirmDialog = true },
                            modifier = Modifier.weight(1f),
                        )

                        SecondaryButton(
                            text = "Back Up Now",
                            onClick = { viewModel.backupNow() },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Cloud Data & Privacy: Permanent deletion
                    Glass(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Lucide.Trash2,
                                    contentDescription = null,
                                    tint = c.negative,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Cloud Data & Privacy",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.negative,
                                )
                            }

                            Text(
                                "Permanently erase your data from Firebase Cloud Firestore. Your local entries and transactions on this phone will remain completely safe.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Glass(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = CircleShape,
                                    strong = true,
                                    onClick = { showDeleteDataConfirmDialog = true },
                                ) {
                                    Row(
                                        Modifier.align(Alignment.Center),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Lucide.Trash2,
                                            contentDescription = null,
                                            tint = c.negative,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Delete Cloud Data",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.negative,
                                        )
                                    }
                                }

                                Glass(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = CircleShape,
                                    strong = true,
                                    onClick = { showDeleteAccountConfirmDialog = true },
                                ) {
                                    Row(
                                        Modifier.align(Alignment.Center),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Lucide.User,
                                            contentDescription = null,
                                            tint = c.negative,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Delete Account",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.negative,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Email/Password Sign-In or Register Dialog
    if (showEmailAuthDialog) {
        GlassAlertDialog(
            onDismissRequest = { showEmailAuthDialog = false },
            title = {
                Text(
                    if (isRegisterMode) "Create Leftovers Cloud Account" else "Sign In with Email",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        placeholder = "Email address",
                        label = "Email",
                        keyboardType = KeyboardType.Email,
                    )
                    GlassTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = "Password (min 6 characters)",
                        label = "Password",
                        keyboardType = KeyboardType.Password,
                        password = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { isRegisterMode = !isRegisterMode }) {
                            Text(
                                if (isRegisterMode) "Already have an account? Sign in" else "Need an account? Register",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.accent,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (emailInput.isNotBlank() && passwordInput.length >= 6) {
                            showEmailAuthDialog = false
                            if (isRegisterMode) {
                                viewModel.registerWithEmail(
                                    email = emailInput,
                                    pass = passwordInput,
                                    isOnboarding = isOnboarding || !s.onboarded,
                                    onNeedPassword = { showPasswordPromptDialog = true },
                                    onGoToApp = onDismiss,
                                )
                            } else {
                                viewModel.signInWithEmail(
                                    email = emailInput,
                                    pass = passwordInput,
                                    isOnboarding = isOnboarding || !s.onboarded,
                                    onNeedPassword = { showPasswordPromptDialog = true },
                                    onGoToApp = onDismiss,
                                )
                            }
                        }
                    },
                    enabled = emailInput.isNotBlank() && passwordInput.length >= 6,
                ) {
                    Text(if (isRegisterMode) "Register" else "Sign In", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailAuthDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Restore confirmation dialog
    if (showRestoreConfirmDialog) {
        GlassAlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("Restore from Cloud?", color = c.textPrimary) },
            text = {
                Text(
                    "This will download your latest cloud backup and replace current entries on this device.",
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        viewModel.restoreFromCloud(
                            onNeedPassword = { showPasswordPromptDialog = true },
                            onSuccess = { onDismiss() },
                        )
                    }
                ) {
                    Text("Restore", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Password prompt dialog for encrypted cloud backups
    if (showPasswordPromptDialog) {
        GlassAlertDialog(
            onDismissRequest = { showPasswordPromptDialog = false },
            title = { Text("This cloud backup is encrypted", color = c.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the backup password used when creating this backup:",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    GlassTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it },
                        placeholder = "Backup Password",
                        label = "Password",
                        keyboardType = KeyboardType.Password,
                        password = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (restorePasswordInput.isNotBlank()) {
                            showPasswordPromptDialog = false
                            val p = restorePasswordInput
                            restorePasswordInput = ""
                            viewModel.restoreFromCloud(
                                typedPassword = p,
                                onNeedPassword = { showPasswordPromptDialog = true },
                                onSuccess = { onDismiss() },
                            )
                        }
                    },
                    enabled = restorePasswordInput.isNotBlank(),
                ) {
                    Text("Unlock & Restore", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordPromptDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Google Web Client ID Configuration Dialog
    if (showConfigClientIdDialog) {
        GlassAlertDialog(
            onDismissRequest = { showConfigClientIdDialog = false },
            title = { Text("Google Web Client ID", color = c.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Configure the OAuth 2.0 Web Client ID from your Firebase Google Sign-In console:",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    GlassTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        placeholder = "466782740690-...apps.googleusercontent.com",
                        label = "Web Client ID",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfigClientIdDialog = false
                        viewModel.setGoogleClientId(clientIdInput)
                    }
                ) {
                    Text("Save", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigClientIdDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Confirm Delete Cloud Data Dialog
    if (showDeleteDataConfirmDialog) {
        GlassAlertDialog(
            onDismissRequest = { showDeleteDataConfirmDialog = false },
            title = {
                Text(
                    "Delete Cloud Data?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            },
            text = {
                Text(
                    "This will permanently erase all your backup data stored in Firebase Cloud Firestore.\n\n" +
                    "Your local entries and transactions on this phone will NOT be deleted and remain completely safe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDataConfirmDialog = false
                        viewModel.deleteCloudData()
                    }
                ) {
                    Text("Delete Permanently", color = c.negative, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDataConfirmDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            }
        )
    }

    // Confirm Delete Cloud Account Dialog
    if (showDeleteAccountConfirmDialog) {
        GlassAlertDialog(
            onDismissRequest = { showDeleteAccountConfirmDialog = false },
            title = {
                Text(
                    "Delete Cloud Account?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.negative,
                )
            },
            text = {
                Text(
                    "This will permanently erase all your cloud backups and delete your account from Leftovers cloud.\n\n" +
                    "Your offline records on this device will NOT be affected. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountConfirmDialog = false
                        viewModel.deleteCloudAccount()
                    }
                ) {
                    Text("Delete Account", color = c.negative, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountConfirmDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            }
        )
    }
}
