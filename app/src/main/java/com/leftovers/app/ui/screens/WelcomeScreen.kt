package com.leftovers.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.leftovers.app.data.cloud.CloudBackupInfo
import com.leftovers.app.data.cloud.CloudUser
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.glassBorder
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import com.leftovers.app.util.ReceiptStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class OnboardingMode(val label: String) {
    NEW_USER("New user"),
    RESTORE("Restore & Sign In"),
}

private data class FeatureSlide(
    val icon: ImageVector,
    val tag: String,
    val title: String,
    val description: String,
)

private val featureSlides = listOf(
    FeatureSlide(
        icon = Lucide.Sparkles,
        tag = "EXPENSES & BUDGETS",
        title = "Take Control of\nYour Money.",
        description = "Track daily spending with category budgets, smart trends, and automatic bank SMS & email alert detection.",
    ),
    FeatureSlide(
        icon = Lucide.CalendarClock,
        tag = "WORK ROSTERS",
        title = "Manage Shifts &\nHourly Earnings.",
        description = "Plan your work schedules, calculate shift pay and overtime, and receive completion reminders right after your shift.",
    ),
    FeatureSlide(
        icon = Lucide.Send,
        tag = "REMITTANCES & AI",
        title = "Send Remittances &\nSmart Insights.",
        description = "Track international money transfers with live exchange rates, monthly recap stories, and an AI financial assistant.",
    ),
)

@Composable
fun WelcomeScreen(
    defaultCurrency: String,
    onDone: (currency: String, name: String, photoPath: String?) -> Unit,
    onRestore: (uri: Uri, password: String?, onNeedPassword: (wrong: Boolean) -> Unit, onResult: (String) -> Unit) -> Unit,
) {
    WelcomeScreen(
        defaultCurrency = defaultCurrency,
        onDone = { currency, name, photoPath, _ -> onDone(currency, name, photoPath) },
        onRestore = onRestore,
    )
}

@Composable
fun WelcomeScreen(
    defaultCurrency: String,
    onDone: (currency: String, name: String, photoPath: String?, enableCloudBackup: Boolean) -> Unit,
    onRestore: (uri: Uri, password: String?, onNeedPassword: (wrong: Boolean) -> Unit, onResult: (String) -> Unit) -> Unit,
    cloudBackupViewModel: CloudBackupViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })

    val cloudUser by cloudBackupViewModel.currentUser.collectAsStateWithLifecycle()
    val isCloudBusy by cloudBackupViewModel.isBusy.collectAsStateWithLifecycle()
    val latestBackup by cloudBackupViewModel.latestCloudBackup.collectAsStateWithLifecycle()
    val cloudStatusMsg by cloudBackupViewModel.statusMessage.collectAsStateWithLifecycle()
    val cloudErrorMsg by cloudBackupViewModel.errorMessage.collectAsStateWithLifecycle()

    var showBackupFoundDialog by remember { mutableStateOf<CloudBackupInfo?>(null) }
    var mode by rememberSaveable { mutableStateOf(OnboardingMode.NEW_USER) }
    var name by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var selected by rememberSaveable { mutableStateOf(defaultCurrency) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var showCloudBackupSheet by rememberSaveable { mutableStateOf(false) }

    // When cloud user signs in, sync name and download profile picture if available
    LaunchedEffect(cloudUser) {
        val u = cloudUser
        if (u != null) {
            if (name.isBlank() && !u.displayName.isNullOrBlank()) {
                name = u.displayName
            }
            if (photoPath.isNullOrBlank() && !u.photoUrl.isNullOrBlank()) {
                ReceiptStore.downloadUrlToLocal(context, u.photoUrl)?.let { downloaded ->
                    photoPath = downloaded
                }
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            cropSourceUri = uri
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isRestoring = true
            restoreStatus = null
            onRestore(
                uri,
                null,
                { wrong ->
                    isRestoring = false
                    passwordDialog = uri to wrong
                },
                { msg ->
                    isRestoring = false
                    restoreStatus = msg
                },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        // Top Navigation Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glass(
                    Modifier.size(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    strong = true,
                ) {
                    Icon(
                        Lucide.Wallet,
                        contentDescription = "Leftovers Logo",
                        tint = c.accent,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "LEFTOVERS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { showCloudBackupSheet = true },
                ) {
                    Icon(
                        if (cloudUser != null) Lucide.Check else Lucide.Cloud,
                        contentDescription = null,
                        tint = if (cloudUser != null) c.positive else c.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (cloudUser != null) "Cloud Linked" else "Sign In",
                        color = if (cloudUser != null) c.positive else c.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (pagerState.currentPage < 3) {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                    ) {
                        Text("Skip", color = c.textSecondary)
                    }
                } else {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    ) {
                        Text("Overview", color = c.textSecondary)
                    }
                }
            }
        }

        // Pager Content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> SlideContent(
                    slide = featureSlides[0],
                    preview = { ExpensePreviewCard() },
                )
                1 -> SlideContent(
                    slide = featureSlides[1],
                    preview = { RosterPreviewCard() },
                )
                2 -> SlideContent(
                    slide = featureSlides[2],
                    preview = { RemittancePreviewCard() },
                )
                3 -> SetupSlide(
                    mode = mode,
                    onModeChange = {
                        mode = it
                        restoreStatus = null
                    },
                    name = name,
                    onNameChange = { name = it },
                    photoPath = photoPath,
                    onPickPhoto = {
                        pickPhoto.launch(
                            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemovePhoto = { photoPath = null },
                    selectedCurrency = selected,
                    onSelectCurrency = { selected = it },
                    cloudUser = cloudUser,
                    isCloudBusy = isCloudBusy,
                    cloudStatusMsg = cloudStatusMsg,
                    cloudErrorMsg = cloudErrorMsg,
                    onLinkGoogle = {
                        cloudBackupViewModel.signInWithGoogle(
                            context = context,
                            isOnboarding = true,
                            onNeedPassword = { showCloudBackupSheet = true },
                            onGoToApp = { /* handled via onboarding completion */ },
                            onNewUserReady = { user ->
                                if (name.isBlank() && !user.displayName.isNullOrBlank()) {
                                    name = user.displayName
                                }
                                if (photoPath.isNullOrBlank() && !user.photoUrl.isNullOrBlank()) {
                                    scope.launch {
                                        ReceiptStore.downloadUrlToLocal(context, user.photoUrl)?.let { p ->
                                            photoPath = p
                                        }
                                    }
                                }
                            },
                        )
                    },
                    onSignOutGoogle = { cloudBackupViewModel.signOut() },
                    isRestoring = isRestoring,
                    restoreStatus = restoreStatus,
                    onOpenCloudRestore = { showCloudBackupSheet = true },
                    onChooseBackup = {
                        if (!isRestoring) openBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                    },
                    onFinish = { onDone(selected, name.trim(), photoPath, cloudUser != null) },
                )
            }
        }

        // Bottom Navigation Bar (for slides 0..2)
        if (pagerState.currentPage < 3) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Animated Page Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(4) { i ->
                            val active = pagerState.currentPage == i
                            val width by animateDpAsState(if (active) 24.dp else 8.dp, label = "dotWidth")
                            val color by animateColorAsState(if (active) c.accent else c.glassStrong, label = "dotColor")
                            Box(
                                Modifier
                                    .height(8.dp)
                                    .width(width)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                        }
                    }

                    PrimaryButton(
                        text = "Continue",
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        icon = Lucide.ChevronRight,
                        modifier = Modifier.width(140.dp),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Already using Leftovers? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    Text(
                        "Restore & Sign In",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = c.accent,
                        modifier = Modifier.clickable {
                            showCloudBackupSheet = true
                        },
                    )
                }
            }
        }
    }

    passwordDialog?.let { (uri, wrong) ->
        var typed by remember(uri, wrong) { mutableStateOf("") }
        GlassAlertDialog(
            onDismissRequest = { passwordDialog = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(30.dp),
            title = { Text("Backup Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This backup is encrypted with a password.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    GlassTextField(typed, { typed = it }, placeholder = "Password", password = true)
                    if (wrong) Text("Incorrect password. Please try again.", style = MaterialTheme.typography.bodySmall, color = c.negative)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.isNotEmpty(),
                    onClick = {
                        val key = typed
                        passwordDialog = null
                        isRestoring = true
                        onRestore(
                            uri,
                            key,
                            { w ->
                                isRestoring = false
                                passwordDialog = uri to w
                            },
                            { msg ->
                                isRestoring = false
                                restoreStatus = msg
                            },
                        )
                    },
                ) { Text("Restore", color = c.textPrimary) }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = null }) { Text("Cancel", color = c.textSecondary) } },
        )
    }

    cropSourceUri?.let { uri ->
        PhotoCropDialog(
            sourceUri = uri,
            onDismiss = { cropSourceUri = null },
            onCrop = { savedPath ->
                photoPath = savedPath
                cropSourceUri = null
            },
        )
    }

    // Dialog when an existing cloud backup is detected for the signed-in account
    showBackupFoundDialog?.let { b ->
        val dt = Instant.ofEpochMilli(b.timestamp).atZone(ZoneId.systemDefault())
        val formattedDate = dt.format(DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a"))
        GlassAlertDialog(
            onDismissRequest = { showBackupFoundDialog = null },
            title = { Text("Cloud Backup Found", color = c.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "We found an existing cloud backup from $formattedDate (${b.totalItems} entries).\n\n" +
                    "Would you like to restore your prior expenses and data, or start fresh as a new user with this Google account linked?",
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toRestore = b
                        showBackupFoundDialog = null
                        if (toRestore.isEncrypted) {
                            showCloudBackupSheet = true
                        } else {
                            cloudBackupViewModel.restoreFromCloud(
                                onNeedPassword = { showCloudBackupSheet = true },
                                onSuccess = { /* App opens via onboarded flag */ }
                            )
                        }
                    }
                ) {
                    Text("Restore Backup", color = c.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackupFoundDialog = null
                    }
                ) {
                    Text("Start Fresh", color = c.textSecondary)
                }
            }
        )
    }

    if (showCloudBackupSheet) {
        CloudBackupSheet(
            onDismiss = { showCloudBackupSheet = false },
            isOnboarding = true,
            onNewUserContinue = { newUser ->
                mode = OnboardingMode.NEW_USER
                if (name.isBlank() && !newUser.displayName.isNullOrBlank()) {
                    name = newUser.displayName
                }
                if (photoPath.isNullOrBlank() && !newUser.photoUrl.isNullOrBlank()) {
                    scope.launch {
                        ReceiptStore.downloadUrlToLocal(context, newUser.photoUrl)?.let { p ->
                            photoPath = p
                        }
                    }
                }
                scope.launch { pagerState.animateScrollToPage(3) }
            },
        )
    }
}

@Composable
private fun SlideContent(
    slide: FeatureSlide,
    preview: @Composable () -> Unit,
) {
    val c = LocalAppColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(slide.icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
                Text(
                    slide.tag,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                slide.title,
                style = MaterialTheme.typography.headlineLarge,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                slide.description,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            preview()
        }
    }
}

@Composable
private fun ExpensePreviewCard() {
    val c = LocalAppColors.current
    Glass(
        Modifier.fillMaxWidth(),
        strong = true,
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("October Spending", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Text("$1,240.00", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Budget: $2,000", style = MaterialTheme.typography.bodySmall, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            // Budget bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(c.glassStrong),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(c.accent),
                )
            }

            // Mini transactions
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniChipTile(icon = Lucide.Utensils, label = "Food", amount = "-$42.50", modifier = Modifier.weight(1f))
                MiniChipTile(icon = Lucide.Car, label = "Fuel", amount = "-$24.00", modifier = Modifier.weight(1f))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.glass)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.Sparkles, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                Text("Auto-detect payments from bank SMS & email alerts", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun RosterPreviewCard() {
    val c = LocalAppColors.current
    Glass(
        Modifier.fillMaxWidth(),
        strong = true,
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("This Week's Shifts", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Text("32 hrs • $640.00", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.positive.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("4 Shifts", style = MaterialTheme.typography.bodySmall, color = c.positive, fontWeight = FontWeight.SemiBold)
                }
            }

            // Shift Card 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.glass)
                    .border(1.dp, glassBorder(c), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Clock, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text("Day Shift", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text("08:00 - 16:00 • 8 hrs", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                Text("+$160", style = MaterialTheme.typography.titleMedium, color = c.positive, fontWeight = FontWeight.Bold)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.glass)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.Bell, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                Text("Shift completion notification 30m after finish", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun RemittancePreviewCard() {
    val c = LocalAppColors.current
    Glass(
        Modifier.fillMaxWidth(),
        strong = true,
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("International Remittance", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Text("$500.00 USD", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Sent Home", style = MaterialTheme.typography.bodySmall, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.glass)
                    .border(1.dp, glassBorder(c), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Converted to NPR", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Text("₨ 68,500.00", style = MaterialTheme.typography.titleMedium, color = c.positive, fontWeight = FontWeight.Bold)
                }
                Text("Rate 137.00", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.glass)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.MessageCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                Text("AI financial assistant & personalized monthly recap", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun MiniChipTile(
    icon: ImageVector,
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.glass)
            .border(1.dp, glassBorder(c), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Text(amount, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSlide(
    mode: OnboardingMode,
    onModeChange: (OnboardingMode) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    photoPath: String?,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    selectedCurrency: String,
    onSelectCurrency: (String) -> Unit,
    cloudUser: CloudUser?,
    isCloudBusy: Boolean,
    cloudStatusMsg: String?,
    cloudErrorMsg: String?,
    onLinkGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    isRestoring: Boolean,
    restoreStatus: String?,
    onOpenCloudRestore: () -> Unit,
    onChooseBackup: () -> Unit,
    onFinish: () -> Unit,
) {
    val c = LocalAppColors.current
    val quickCurrencies = listOf("USD", "EUR", "NPR", "GBP", "CAD", "AUD", "INR", "JPY")

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 10.dp),
    ) {
        SegmentedToggle(
            options = listOf(OnboardingMode.NEW_USER, OnboardingMode.RESTORE),
            selected = mode,
            label = { it.label },
            onSelect = onModeChange,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        if (mode == OnboardingMode.NEW_USER) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Profile Avatar & Name card
                Glass(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(
                                photoPath = photoPath,
                                name = name,
                                size = 68.dp,
                                onClick = onPickPhoto,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                GlassTextField(
                                    value = name,
                                    onValueChange = onNameChange,
                                    placeholder = "Your name or nickname",
                                    label = "Display Name",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Chip(
                                text = if (photoPath == null) "Add photo" else "Change photo",
                                onClick = onPickPhoto,
                                icon = Lucide.Camera,
                                selected = photoPath != null,
                            )
                            if (photoPath != null) {
                                Chip(
                                    text = "Remove",
                                    onClick = onRemovePhoto,
                                    icon = Lucide.Trash2,
                                    selected = false,
                                )
                            }
                        }
                    }
                }

                // Currency selection card
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Select main currency", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${Money(selectedCurrency).label} ($selectedCurrency)",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // Popular quick selection pills
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        quickCurrencies.forEach { code ->
                            val isSel = selectedCurrency.equals(code, ignoreCase = true)
                            Chip(
                                text = code,
                                selected = isSel,
                                onClick = { onSelectCurrency(code) },
                            )
                        }
                    }

                    Glass(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        CurrencyList(
                            selected = selectedCurrency,
                            onSelect = { onSelectCurrency(it.code) },
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                // Cloud Backup & Sync Card (Optional for New Users)
                Glass(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Lucide.Cloud,
                                contentDescription = null,
                                tint = c.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Cloud Backup (Optional)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = c.textPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            if (cloudUser != null) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.positive.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Linked", style = MaterialTheme.typography.labelSmall, color = c.positive, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text("Offline First", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                            }
                        }

                        if (cloudUser == null) {
                            Text(
                                "Link your Google account to automatically back up expenses, rosters, and receipts safely to cloud storage. You can also skip this and keep all data 100% offline on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )

                            if (isCloudBusy) {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = c.accent, modifier = Modifier.size(24.dp))
                                }
                            } else {
                                PrimaryButton(
                                    text = "Link Google for Cloud Backup",
                                    onClick = onLinkGoogle,
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Lucide.Cloud,
                                )

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    TextButton(onClick = onOpenCloudRestore) {
                                        Text("More sign-in options (Email / Custom ID)", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                    }
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cloudUser.displayName?.ifBlank { "Connected User" } ?: "Connected User",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = c.textPrimary,
                                    )
                                    cloudUser.email?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                    }
                                }
                                TextButton(onClick = onSignOutGoogle) {
                                    Text("Unlink", color = c.negative, style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.positive.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Lucide.Check, contentDescription = null, tint = c.positive, modifier = Modifier.size(14.dp))
                                Text("Automatic cloud backup enabled for this account", style = MaterialTheme.typography.labelSmall, color = c.positive)
                            }
                        }

                        cloudErrorMsg?.let { err ->
                            Text(err, style = MaterialTheme.typography.bodySmall, color = c.negative)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = if (name.isNotBlank()) "Get Started, $name" else "Continue with ${Money(selectedCurrency).label}",
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (cloudUser != null) "🔒 Cloud backup enabled • Everything is encrypted & private"
                    else "📱 Stored locally on this phone • 100% offline & private",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Already have an account? ",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Text(
                    "Restore & Sign In",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = c.accent,
                    modifier = Modifier.clickable {
                        onModeChange(OnboardingMode.RESTORE)
                    },
                )
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Card 1: Cloud Restore & Sign In
                Glass(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    strong = true,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(c.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Cloud, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Restore from Cloud",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (cloudUser != null) "Signed in as ${cloudUser.displayName ?: cloudUser.email}. Restore your cloud data or start as a new user."
                            else "Sign in with Google or Email to restore your transactions, rosters, remittances, and settings from cloud backup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            text = if (cloudUser != null) "Open Cloud Backup & Restore" else "Sign In & Restore from Cloud",
                            onClick = onOpenCloudRestore,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Lucide.Cloud,
                        )
                        if (cloudUser != null) {
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(
                                text = "Start Fresh with this Google Account",
                                onClick = { onModeChange(OnboardingMode.NEW_USER) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Card 2: Local File Restore
                Glass(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(c.textSecondary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Download, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Restore from Device File",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Select a Leftovers .json backup file from your phone storage or downloads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        SecondaryButton(
                            text = if (isRestoring) "Restoring..." else "Choose Backup File (.json)",
                            onClick = onChooseBackup,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Lucide.Download,
                        )
                    }
                }

                restoreStatus?.let { status ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.startsWith("Restored")) c.positive else c.negative,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

