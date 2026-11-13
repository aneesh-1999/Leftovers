package com.leftovers.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.util.Currency
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System default") }

/** How the home-screen widgets look. */
enum class WidgetStyle(val label: String) { GLASS("Glass"), DARK("Dark"), LIGHT("Light") }

/** How times are shown: like the phone, or always 12- or 24-hour. */
enum class TimeFormat(val label: String) { PHONE("Phone"), H12("12-hour"), H24("24-hour") }

data class AppSettings(
    val onboarded: Boolean = false,
    val currencyCode: String = "EUR",
    val userName: String = "",
    val userPhotoPath: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Frosted, see-through bars, cards and dialogs; off gives solid surfaces. */
    val glassEffects: Boolean = true,
    /** Black background for OLED screens, when the dark theme is in use. */
    val pureBlack: Boolean = false,
    /** Over a photo: liquid glass that bends the picture, or frosted glass, lighter on slower phones. */
    val liquidGlass: Boolean = true,
    /** What's behind every screen ([com.leftovers.app.ui.theme.Backdrop] name). */
    val backdrop: String = "AURORA",
    /** How much the background photo is dimmed, 0.2 to 0.85; changes with each new photo too ([photoVersion]). */
    val photoDim: Float = 0.1f,
    /** Text over a photo background: "AUTO" (by how bright the photo is), "WHITE" or "BLACK". */
    val photoText: String = "AUTO",
    /** How soft the background photo is, 0 (sharp) to 1 (very soft). */
    val photoBlur: Float = 0.25f,
    val photoVersion: Long = 0L,
    /** A chosen accent colour (ARGB), or 0 for the default. */
    val accentColor: Long = 0L,
    val timeFormat: TimeFormat = TimeFormat.PHONE,
    val widgetStyle: WidgetStyle = WidgetStyle.GLASS,
    /** Last exchange rate used per currency in travel mode, e.g. "AED" to "22.7" (home currency per unit). */
    val foreignRates: Map<String, String> = emptyMap(),
    val plan: BudgetPlan = BudgetPlan(),
    val budgetAlerts: Boolean = true,
    /** A split bill shows as one entry with its parts under it; off, each part is listed on its own. */
    val splitsAsOneBill: Boolean = true,
    /** Activity shows every entry rather than one month's: the last choice, kept (#41). */
    val activityAllTime: Boolean = false,
    val defaultAccountId: Long = 0L,
    val reminderEnabled: Boolean = false,
    /** Minutes after midnight for the daily reminder. */
    val reminderMinutes: Int = 20 * 60,
    val appLock: Boolean = false,
    /** No screenshots or screen recording, and a blank preview in Recents (#44); always so with [appLock]. */
    val blockScreenshots: Boolean = false,
    /** How long the app can be away before it locks again: 0 is straight away. */
    val lockAfterSeconds: Int = 0,
    /** With an app PIN or password, fingerprint or face can unlock too. */
    val lockFingerprint: Boolean = true,
    val smsDetection: Boolean = false,
    /** Small spending from bank SMS added straight away, up to [smsAutoMaxMinor], instead of waiting to be checked. */
    val smsAutoAdd: Boolean = false,
    val smsAutoMaxMinor: Long = 20_000,
    /** Read payment apps' notifications (FamApp, Google Pay, PhonePe...) for payments; needs notification access. */
    val appNotifications: Boolean = false,
    /** Month (yyyy-MM) whose recap the user has already opened or dismissed. */
    val recapSeen: String = "",
    /** Folder (document tree uri) for weekly automatic backups; null when off. */
    val autoBackupDir: String? = null,
    /** When the last automatic backup finished (epoch millis), 0 if never. */
    val autoBackupLast: Long = 0,
    /** Why the last automatic backup failed, until one works again. */
    val autoBackupError: String? = null,
    /** Notify the day before a subscription is charged. */
    val billReminders: Boolean = true,
    /** A greeting on the main festivals where the user lives (from their currency). */
    val festivalGreetings: Boolean = true,
    /** A short note on the week's spending, Sunday evenings. */
    val weeklyNotes: Boolean = true,
    /** Subscription suggestions the user said no to. */
    val dismissedSuggestions: Set<String> = emptySet(),
    /** How long deleted entries are kept for restoring, in days; 0 means forever. */
    val deletedKeepDays: Int = 90,
    /** The account Home is showing; null means all accounts. */
    val homeAccountId: Long? = null,
    /** Extra words that mark a bank message as a payment, for banks the built-in rules miss. */
    val detectionKeywords: Set<String> = emptySet(),
    /** The balance each account's bank last reported in an SMS, by account id (#59). Only the number and when. */
    val bankBalances: Map<Long, BankFigure> = emptyMap(),
    /** Days between automatic backups: 1, 7 or 30. */
    val autoBackupDays: Int = 7,
    /** How many automatic backup files to keep; older ones are removed. */
    val autoBackupKeep: Int = 4,
    /** Put receipt and Money owed photos inside backups (bigger files). */
    val backupPhotos: Boolean = false,
    /** Whether roster and shift reminder notifications are enabled. */
    val shiftRemindersEnabled: Boolean = true,
    /** Minutes after midnight for the night-before shift reminder (default 20:00 / 8 PM). */
    val shiftReminderNightMinutes: Int = 20 * 60,
    /** Hours before shift start for the advance shift reminder (default 5 hours). */
    val shiftReminderAdvanceHours: Int = 5,
    /** Prompt user to set an alarm for early morning shifts. */
    val shiftReminderAlarmSuggestion: Boolean = true,
    /** Whether credit card payment reminders are enabled. */
    val creditCardRemindersEnabled: Boolean = true,
    /** Room Expense active roommate profile: "Ozric" or "Pawan". */
    val roomExpenseRoommate: String = "Ozric",
    /** Whether to import user's allocated share into personal finance expenses via review confirmation. */
    val roomExpenseIncludeInPersonal: Boolean = true,
    /** Last timestamp (epoch millis) when Room Expense successfully synced from Firestore. */
    val roomExpenseLastSync: Long = 0L,
    /** Cutoff timestamp (epoch millis). Room expenses created before this are ignored as old payments. */
    val roomExpenseCutoffTimestamp: Long = 0L,
    /** Cohabit & Roster Encrypted API Base URL. */
    val cohabitBaseUrl: String = "https://ais-pre-fakno4cgzgk52rt56zapwg-628972999672.asia-southeast1.run.app",
    /** Cohabit & Roster Encrypted API Key or signed Bridge Token. */
    val cohabitApiKey: String = "leaftover-ozric-key-2026",
    /** Active Cohabit authenticated profile: "Ozric" or "Pawan". */
    val cohabitRoommate: String = "Ozric",
    /** Timestamp of last successful Cohabit Bridge sync. */
    val cohabitLastSync: Long = 0L,
    /** Whether notification-based transaction detection is enabled. */
    val notificationDetection: Boolean = false,
    /** Whether high-confidence detected transactions can be auto-saved directly (review mode if false). */
    val notificationAutoSave: Boolean = false,
    /** Specific package names enabled for notification listening by the user. */
    val enabledNotificationApps: Set<String> = emptySet(),
    /** Whether automatic background cloud backup is enabled. */
    val cloudBackupEnabled: Boolean = false,
    /** Timestamp of the last successful cloud backup. */
    val cloudBackupLast: Long = 0L,
    /** Optional Google OAuth 2.0 Web Client ID configured by the user for Google Sign-In. */
    val cloudBackupGoogleClientId: String = "",
    /** Email of the authenticated cloud user, if signed in. */
    val cloudUserEmail: String? = null,
    /** Display name of the authenticated cloud user, if signed in. */
    val cloudUserDisplayName: String? = null,
    /** Whether automatic background checks for GitHub releases are enabled. */
    val autoUpdateChecks: Boolean = true,
    /** Whether automatic background update checks/downloads should only happen on unmetered Wi-Fi. */
    val updateCheckWifiOnly: Boolean = false,
    /** Timestamp in epoch millis when updates were last checked. */
    val lastUpdateCheckTime: Long = 0L,
    /** Release tag name of the latest notification shown to prevent duplicate alerts. */
    val lastNotifiedUpdateTag: String = "",
    /** Epoch millis timestamp when GitHub API rate-limit resets, 0 if not rate-limited. */
    val updateRateLimitResetTime: Long = 0L,
    /** Cached JSON response body of the latest verified GitHub release. */
    val updateCachedReleaseJson: String = "",
    /** Cached HTTP ETag for conditional If-None-Match requests. */
    val updateCachedEtag: String = "",
    /** Epoch millis timestamp when the release cache was last saved or refreshed via HTTP 200/304. */
    val updateCacheTimestamp: Long = 0L,
    /** Custom remittance and money transfer providers saved by the user. */
    val customRemittanceProviders: Set<String> = emptySet(),
    /** Folder (document tree uri) shared with the user's other phones for Sync between devices; null when off. */
    val syncDir: String? = null,
    /** The user's WebDAV folder (https) for Sync between devices, instead of [syncDir]; null when not used. */
    val syncServer: String? = null,
    val syncUser: String? = null,
    /** This phone's name in the sync folder, made once. */
    val syncDevice: String? = null,
    /** When the last sync finished (epoch millis), 0 if never. */
    val syncLast: Long = 0,
    /** Fetch today's exchange rates daily for accounts and entries in other currencies; off unless turned on. */
    val autoRates: Boolean = false,
    /** Tell the user when a newer version is out (asks GitHub weekly); off unless turned on. */
    val updateCheck: Boolean = false,
    /** The newest version already told about, so each is told once. */
    val updateNotified: String? = null,
    /** The version whose What's new card was closed, so it shows once after each update. */
    val whatsNewSeen: String? = null,
    /** A newer version found by the last check: version, link and when it went up, a line each. */
    val updateAvailable: String? = null,
    /** The newer version already seen in Notices, so the bell's dot goes once it has been. */
    val updateSeen: String? = null,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboarded = p[ONBOARDED] ?: false,
            currencyCode = p[CURRENCY] ?: defaultCurrencyCode(),
            userName = p[USER_NAME].orEmpty(),
            userPhotoPath = p[USER_PHOTO],
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: ThemeMode.DARK,
            dynamicColor = p[DYNAMIC_COLOR] ?: false,
            glassEffects = p[GLASS_EFFECTS] ?: true,
            pureBlack = p[PURE_BLACK] ?: false,
            liquidGlass = p[LIQUID_GLASS] ?: true,
            backdrop = p[BACKDROP] ?: "AURORA",
            photoDim = p[PHOTO_DIM] ?: 0.1f,
            photoBlur = p[PHOTO_BLUR] ?: 0.25f,
            photoText = p[PHOTO_TEXT] ?: "AUTO",
            photoVersion = p[PHOTO_VERSION] ?: 0L,
            accentColor = p[ACCENT_COLOR] ?: 0L,
            timeFormat = TimeFormat.entries.firstOrNull { it.name == p[TIME_FORMAT] } ?: TimeFormat.PHONE,
            widgetStyle = WidgetStyle.entries.firstOrNull { it.name == p[WIDGET_STYLE] } ?: WidgetStyle.GLASS,
            foreignRates = p[FOREIGN_RATES].orEmpty().split(';').mapNotNull { pair ->
                pair.split('=').takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] }
            }.toMap(),
            plan = BudgetPlan(
                mode = BudgetMode.entries.firstOrNull { it.name == p[BUDGET_MODE] } ?: BudgetMode.MONTHLY,
                monthlyMinor = p[MONTHLY_BUDGET] ?: 0L,
                yearlyMinor = p[YEARLY_BUDGET] ?: 0L,
                split = YearSplit.entries.firstOrNull { it.name == p[YEAR_SPLIT] } ?: YearSplit.EVEN,
                custom = BudgetPlan.decodeCustom(p[CUSTOM_SPLIT]),
                carry = CarryMode.entries.firstOrNull { it.name == p[CARRY_MODE] } ?: CarryMode.ASK,
                carryFrom = p[CARRY_FROM]?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
                carryChoices = BudgetPlan.decodeChoices(p[CARRY_CHOICES]),
                extraIncome = p[EXTRA_INCOME] ?: false,
                startDay = p[BUDGET_START_DAY] ?: 1,
                rangeMinor = p[RANGE_BUDGET] ?: 0L,
                buckets = p[BUCKETS] ?: false,
                bucketIncome = p[BUCKET_INCOME] ?: 0L,
                bucketPicker = p[BUCKET_PICKER] ?: false,
                periodMinor = p[PERIOD_BUDGET] ?: 0L,
                periodDays = p[PERIOD_DAYS] ?: 14,
                periodFrom = p[PERIOD_FROM]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                rangeTag = p[RANGE_TAG],
                rangeAccount = p[RANGE_ACCOUNT],
                rangeFrom = p[RANGE_FROM]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                rangeTo = p[RANGE_TO]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
            ),
            budgetAlerts = p[BUDGET_ALERTS] ?: true,
            splitsAsOneBill = p[SPLITS_ONE_BILL] ?: true,
            activityAllTime = p[ACTIVITY_ALL_TIME] ?: false,
            defaultAccountId = p[DEFAULT_ACCOUNT] ?: 1L,
            reminderEnabled = p[REMINDER] ?: false,
            reminderMinutes = p[REMINDER_TIME] ?: (21 * 60),
            appLock = p[APP_LOCK] ?: false,
            blockScreenshots = p[BLOCK_SCREENSHOTS] ?: false,
            lockAfterSeconds = p[LOCK_AFTER] ?: 0,
            lockFingerprint = p[LOCK_FINGERPRINT] ?: true,
            smsDetection = p[SMS] ?: false,
            smsAutoAdd = p[SMS_AUTO_ADD] ?: false,
            smsAutoMaxMinor = p[SMS_AUTO_MAX] ?: 20_000,
            appNotifications = p[APP_NOTIFICATIONS] ?: false,
            recapSeen = p[RECAP_SEEN].orEmpty(),
            autoBackupDir = p[AUTO_BACKUP_DIR],
            autoBackupLast = p[AUTO_BACKUP_LAST] ?: 0L,
            autoBackupError = p[AUTO_BACKUP_ERROR],
            billReminders = p[BILL_REMINDERS] ?: true,
            festivalGreetings = p[FESTIVAL_GREETINGS] ?: true,
            weeklyNotes = p[WEEKLY_NOTES] ?: true,
            dismissedSuggestions = p[DISMISSED_SUGGESTIONS].orEmpty(),
            deletedKeepDays = p[DELETED_KEEP_DAYS] ?: 90,
            homeAccountId = p[HOME_ACCOUNT],
            detectionKeywords = p[DETECTION_KEYWORDS].orEmpty(),
            bankBalances = BankFigure.decode(p[BANK_BALANCES]),
            backupPhotos = p[BACKUP_PHOTOS] ?: false,
            autoBackupDays = p[AUTO_BACKUP_DAYS] ?: 7,
            autoBackupKeep = p[AUTO_BACKUP_KEEP] ?: 4,
            shiftRemindersEnabled = p[SHIFT_REMINDERS] ?: true,
            shiftReminderNightMinutes = p[SHIFT_REMINDER_NIGHT_TIME] ?: (20 * 60),
            shiftReminderAdvanceHours = p[SHIFT_REMINDER_ADVANCE_HOURS] ?: 5,
            shiftReminderAlarmSuggestion = p[SHIFT_REMINDER_ALARM_SUGGESTION] ?: true,
            creditCardRemindersEnabled = p[CREDIT_CARD_REMINDERS] ?: true,
            roomExpenseRoommate = p[ROOM_EXPENSE_ROOMMATE] ?: "Ozric",
            roomExpenseIncludeInPersonal = p[ROOM_EXPENSE_INCLUDE_IN_PERSONAL] ?: true,
            roomExpenseLastSync = p[ROOM_EXPENSE_LAST_SYNC] ?: 0L,
            roomExpenseCutoffTimestamp = p[ROOM_EXPENSE_CUTOFF_TIMESTAMP] ?: 0L,
            cohabitBaseUrl = p[COHABIT_BASE_URL] ?: "https://ais-pre-fakno4cgzgk52rt56zapwg-628972999672.asia-southeast1.run.app",
            cohabitApiKey = p[COHABIT_API_KEY] ?: "leaftover-ozric-key-2026",
            cohabitRoommate = p[COHABIT_ROOMMATE] ?: "Ozric",
            cohabitLastSync = p[COHABIT_LAST_SYNC] ?: 0L,
            notificationDetection = p[NOTIFICATION_DETECTION] ?: false,
            notificationAutoSave = p[NOTIFICATION_AUTO_SAVE] ?: false,
            enabledNotificationApps = p[ENABLED_NOTIFICATION_APPS].orEmpty(),
            cloudBackupEnabled = p[CLOUD_BACKUP_ENABLED] ?: false,
            cloudBackupLast = p[CLOUD_BACKUP_LAST] ?: 0L,
            cloudBackupGoogleClientId = p[CLOUD_BACKUP_GOOGLE_CLIENT_ID].orEmpty(),
            cloudUserEmail = p[CLOUD_USER_EMAIL],
            cloudUserDisplayName = p[CLOUD_USER_DISPLAY_NAME],
            autoUpdateChecks = p[AUTO_UPDATE_CHECKS] ?: true,
            updateCheckWifiOnly = p[UPDATE_CHECK_WIFI_ONLY] ?: false,
            lastUpdateCheckTime = p[LAST_UPDATE_CHECK_TIME] ?: 0L,
            lastNotifiedUpdateTag = p[LAST_NOTIFIED_UPDATE_TAG].orEmpty(),
            updateRateLimitResetTime = p[UPDATE_RATE_LIMIT_RESET_TIME] ?: 0L,
            updateCachedReleaseJson = p[UPDATE_CACHED_RELEASE_JSON].orEmpty(),
            updateCachedEtag = p[UPDATE_CACHED_ETAG].orEmpty(),
            updateCacheTimestamp = p[UPDATE_CACHE_TIMESTAMP] ?: 0L,
            customRemittanceProviders = p[CUSTOM_REMITTANCE_PROVIDERS] ?: emptySet(),
            syncDir = p[SYNC_DIR],
            syncServer = p[SYNC_SERVER],
            syncUser = p[SYNC_USER],
            syncDevice = p[SYNC_DEVICE],
            syncLast = p[SYNC_LAST] ?: 0L,
            updateCheck = p[UPDATE_CHECK] ?: false,
            autoRates = p[AUTO_RATES] ?: false,
            updateNotified = p[UPDATE_NOTIFIED],
            whatsNewSeen = p[WHATS_NEW_SEEN],
            updateAvailable = p[UPDATE_AVAILABLE],
            updateSeen = p[UPDATE_SEEN],
        )
    }.sharedLive()

    val customRemittanceProviders: Flow<List<String>> = store.data.map { p ->
        p[CUSTOM_REMITTANCE_PROVIDERS]?.toList()?.sorted() ?: emptyList()
    }


    /** Forgets every setting, as on a fresh install. */
    suspend fun clearAll() = store.edit { it.clear() }

    suspend fun completeOnboarding(currencyCode: String, userName: String = "", userPhotoPath: String? = null) = store.edit {
        it[CURRENCY] = currencyCode
        if (userName.isNotBlank()) it[USER_NAME] = userName.trim()
        if (!userPhotoPath.isNullOrBlank()) it[USER_PHOTO] = userPhotoPath
        it[ONBOARDED] = true
    }

    suspend fun setOnboarded(onboarded: Boolean = true) = store.edit {
        it[ONBOARDED] = onboarded
    }

    suspend fun restoreSettings(
        currencyCode: String,
        userName: String = "",
        userPhotoPath: String? = null,
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        dynamicColor: Boolean = false,
        plan: BudgetPlan = BudgetPlan(),
        budgetAlerts: Boolean = true,
        defaultAccountId: Long = 1L,
        reminderEnabled: Boolean = false,
        reminderMinutes: Int = 20 * 60,
        appLock: Boolean = false,
        smsDetection: Boolean = false,
        billReminders: Boolean = true,
        shiftRemindersEnabled: Boolean = true,
        shiftReminderNightMinutes: Int = 20 * 60,
        shiftReminderAdvanceHours: Int = 5,
        shiftReminderAlarmSuggestion: Boolean = true,
        detectionKeywords: Set<String> = emptySet(),
    ) = store.edit {
        it[CURRENCY] = currencyCode
        if (userName.isNotBlank()) it[USER_NAME] = userName.trim() else it.remove(USER_NAME)
        if (!userPhotoPath.isNullOrBlank()) it[USER_PHOTO] = userPhotoPath else it.remove(USER_PHOTO)
        it[THEME] = themeMode.name
        it[DYNAMIC_COLOR] = dynamicColor
        it[BUDGET_MODE] = plan.mode.name
        it[MONTHLY_BUDGET] = plan.monthlyMinor
        it[YEARLY_BUDGET] = plan.yearlyMinor
        it[YEAR_SPLIT] = plan.split.name
        it[CUSTOM_SPLIT] = BudgetPlan.encodeCustom(plan.custom)
        it[CARRY_MODE] = plan.carry.name
        if (plan.carryChoices.isNotEmpty()) it[CARRY_CHOICES] = BudgetPlan.encodeChoices(plan.carryChoices)
        val from = plan.carryFrom
        if (plan.isSet) it[CARRY_FROM] = (from ?: YearMonth.now()).toString() else it.remove(CARRY_FROM)
        it[BUDGET_ALERTS] = budgetAlerts
        it[DEFAULT_ACCOUNT] = defaultAccountId
        it[REMINDER] = reminderEnabled
        it[REMINDER_TIME] = reminderMinutes
        it[APP_LOCK] = appLock
        it[SMS] = smsDetection
        it[BILL_REMINDERS] = billReminders
        it[SHIFT_REMINDERS] = shiftRemindersEnabled
        it[SHIFT_REMINDER_NIGHT_TIME] = shiftReminderNightMinutes
        it[SHIFT_REMINDER_ADVANCE_HOURS] = shiftReminderAdvanceHours
        it[SHIFT_REMINDER_ALARM_SUGGESTION] = shiftReminderAlarmSuggestion
        if (detectionKeywords.isNotEmpty()) it[DETECTION_KEYWORDS] = detectionKeywords
        it[ONBOARDED] = true
    }

    suspend fun setUserName(name: String) = store.edit {
        if (name.isBlank()) it.remove(USER_NAME) else it[USER_NAME] = name.trim()
    }

    suspend fun setUserPhoto(path: String?) = store.edit {
        if (path.isNullOrBlank()) it.remove(USER_PHOTO) else it[USER_PHOTO] = path
    }

    suspend fun setCurrency(code: String) = store.edit { it[CURRENCY] = code }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[DYNAMIC_COLOR] = enabled }
    suspend fun setGlassEffects(enabled: Boolean) = store.edit { it[GLASS_EFFECTS] = enabled }

    suspend fun setPureBlack(enabled: Boolean) = store.edit { it[PURE_BLACK] = enabled }
    suspend fun setLiquidGlass(enabled: Boolean) = store.edit { it[LIQUID_GLASS] = enabled }
    suspend fun setBackdrop(name: String) = store.edit { it[BACKDROP] = name }
    suspend fun setPhotoDim(value: Float) = store.edit { it[PHOTO_DIM] = value }
    suspend fun setPhotoBlur(value: Float) = store.edit { it[PHOTO_BLUR] = value }
    suspend fun setPhotoText(value: String) = store.edit { it[PHOTO_TEXT] = value }
    /** A new background photo was saved: shown with the photo background. */
    suspend fun photoChanged() = store.edit { it[PHOTO_VERSION] = System.currentTimeMillis(); it[BACKDROP] = "PHOTO" }
    suspend fun setAccentColor(argb: Long) = store.edit { it[ACCENT_COLOR] = argb }

    /** Remembers the rate last used for [code], so the next entry in that currency starts with it. */
    suspend fun setTimeFormat(format: TimeFormat) = store.edit { it[TIME_FORMAT] = format.name }
    suspend fun setWidgetStyle(style: WidgetStyle) = store.edit { it[WIDGET_STYLE] = style.name }

    suspend fun setForeignRate(code: String, rate: String) = store.edit { p ->
        val rates = p[FOREIGN_RATES].orEmpty().split(';').filter { it.isNotBlank() && !it.startsWith("$code=") } + "$code=$rate"
        p[FOREIGN_RATES] = rates.takeLast(20).joinToString(";")
    }
    suspend fun setMonthlyBudget(minor: Long) = store.edit { it[MONTHLY_BUDGET] = minor }

    suspend fun savePlan(plan: BudgetPlan) = store.edit {
        it[BUDGET_MODE] = plan.mode.name
        it[MONTHLY_BUDGET] = plan.monthlyMinor
        it[YEARLY_BUDGET] = plan.yearlyMinor
        it[YEAR_SPLIT] = plan.split.name
        it[CUSTOM_SPLIT] = BudgetPlan.encodeCustom(plan.custom)
        it[CARRY_MODE] = plan.carry.name
        it[EXTRA_INCOME] = plan.extraIncome
        it[BUDGET_START_DAY] = plan.startDay
        it[RANGE_BUDGET] = plan.rangeMinor
        it[BUCKETS] = plan.buckets
        it[BUCKET_INCOME] = plan.bucketIncome
        it[BUCKET_PICKER] = plan.bucketPicker
        it[PERIOD_BUDGET] = plan.periodMinor
        it[PERIOD_DAYS] = plan.periodDays
        if (plan.periodFrom == null) it.remove(PERIOD_FROM) else it[PERIOD_FROM] = plan.periodFrom.toString()
        if (plan.rangeTag == null) it.remove(RANGE_TAG) else it[RANGE_TAG] = plan.rangeTag
        if (plan.rangeAccount == null) it.remove(RANGE_ACCOUNT) else it[RANGE_ACCOUNT] = plan.rangeAccount
        if (plan.rangeFrom == null) it.remove(RANGE_FROM) else it[RANGE_FROM] = plan.rangeFrom.toString()
        if (plan.rangeTo == null) it.remove(RANGE_TO) else it[RANGE_TO] = plan.rangeTo.toString()
        if (plan.carryChoices.isNotEmpty()) it[CARRY_CHOICES] = BudgetPlan.encodeChoices(plan.carryChoices)
        val from = plan.carryFrom ?: it[CARRY_FROM]?.let { raw -> runCatching { YearMonth.parse(raw) }.getOrNull() }
        if (plan.isSet) it[CARRY_FROM] = (from ?: YearMonth.now()).toString() else it.remove(CARRY_FROM)
    }

    /** Records whether [month]'s leftover moves into the following month. */
    suspend fun setCarryChoice(month: YearMonth, carry: Boolean) = store.edit {
        it[CARRY_CHOICES] = BudgetPlan.encodeChoices(BudgetPlan.decodeChoices(it[CARRY_CHOICES]) + (month to carry))
    }

    /** Budgets saved before carry-over existed start carrying from the current month. */
    suspend fun ensureCarryStart() = store.edit {
        val set = (it[MONTHLY_BUDGET] ?: 0L) > 0 || (it[YEARLY_BUDGET] ?: 0L) > 0
        if (set && it[CARRY_FROM] == null) it[CARRY_FROM] = YearMonth.now().toString()
    }
    suspend fun setBudgetAlerts(enabled: Boolean) = store.edit { it[BUDGET_ALERTS] = enabled }
    suspend fun setSplitsAsOneBill(enabled: Boolean) = store.edit { it[SPLITS_ONE_BILL] = enabled }

    /** Keeps what [accountId]'s bank said its balance was, as of [at]; only a newer figure replaces it. */
    suspend fun setBankBalance(accountId: Long, minor: Long, at: Long) = store.edit {
        val all = BankFigure.decode(it[BANK_BALANCES]).toMutableMap()
        if ((all[accountId]?.at ?: 0L) <= at) all[accountId] = BankFigure(minor, at)
        it[BANK_BALANCES] = BankFigure.encode(all)
    }
    suspend fun setActivityAllTime(allTime: Boolean) = store.edit { it[ACTIVITY_ALL_TIME] = allTime }
    suspend fun setDefaultAccount(id: Long) = store.edit { it[DEFAULT_ACCOUNT] = id }
    suspend fun setReminder(enabled: Boolean, minutes: Int) = store.edit {
        it[REMINDER] = enabled
        it[REMINDER_TIME] = minutes
    }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[APP_LOCK] = enabled }
    suspend fun setBlockScreenshots(enabled: Boolean) = store.edit { it[BLOCK_SCREENSHOTS] = enabled }

    suspend fun setLockAfter(seconds: Int) = store.edit { it[LOCK_AFTER] = seconds }

    suspend fun setLockFingerprint(enabled: Boolean) = store.edit { it[LOCK_FINGERPRINT] = enabled }
    suspend fun setSmsDetection(enabled: Boolean) = store.edit { it[SMS] = enabled }
    suspend fun setSmsAutoAdd(enabled: Boolean) = store.edit { it[SMS_AUTO_ADD] = enabled }
    suspend fun setSmsAutoMax(minor: Long) = store.edit { it[SMS_AUTO_MAX] = minor }

    /** How friends can pay you back, put in the reminders you send from Money owed. Empty until it's set. */
    val payDetailsFlow = store.data.map { p ->
        com.leftovers.app.util.SharedOwed.Pay(p[PAY_UPI].orEmpty(), p[PAY_NAME].orEmpty(), p[PAY_LINK].orEmpty(), p[PAY_BANK].orEmpty())
    }
    /** Whether reminders carry the payment details (on unless turned off). */
    val payShareFlow = store.data.map { it[PAY_SHARE] ?: true }
    suspend fun payShare(): Boolean = payShareFlow.first()
    suspend fun setPayShare(on: Boolean) = store.edit { it[PAY_SHARE] = on }
    suspend fun payDetails(): com.leftovers.app.util.SharedOwed.Pay = payDetailsFlow.first()
    suspend fun setPayDetails(pay: com.leftovers.app.util.SharedOwed.Pay) = store.edit {
        it[PAY_UPI] = pay.upi
        it[PAY_NAME] = pay.name
        it[PAY_LINK] = pay.link
        it[PAY_BANK] = pay.bank
    }

    suspend fun setAppNotifications(enabled: Boolean) = store.edit { it[APP_NOTIFICATIONS] = enabled }
    suspend fun setRecapSeen(month: String) = store.edit { it[RECAP_SEEN] = month }
    suspend fun setAutoBackupDir(dir: String?) = store.edit { if (dir == null) it.remove(AUTO_BACKUP_DIR) else it[AUTO_BACKUP_DIR] = dir }
    suspend fun setAutoBackupDone(at: Long) = store.edit {
        it[AUTO_BACKUP_LAST] = at
        it.remove(AUTO_BACKUP_ERROR)
    }
    suspend fun setAutoBackupError(message: String?) = store.edit { if (message == null) it.remove(AUTO_BACKUP_ERROR) else it[AUTO_BACKUP_ERROR] = message }
    suspend fun setBillReminders(enabled: Boolean) = store.edit { it[BILL_REMINDERS] = enabled }
    suspend fun setFestivalGreetings(enabled: Boolean) = store.edit { it[FESTIVAL_GREETINGS] = enabled }
    suspend fun setWeeklyNotes(enabled: Boolean) = store.edit { it[WEEKLY_NOTES] = enabled }
    suspend fun setHomeAccount(id: Long?) = store.edit { if (id == null) it.remove(HOME_ACCOUNT) else it[HOME_ACCOUNT] = id }
    suspend fun setAutoBackupDays(days: Int) = store.edit { it[AUTO_BACKUP_DAYS] = days }
    suspend fun setAutoBackupKeep(count: Int) = store.edit { it[AUTO_BACKUP_KEEP] = count }
    suspend fun setSyncDir(dir: String?) = store.edit { if (dir == null) it.remove(SYNC_DIR) else it[SYNC_DIR] = dir }
    suspend fun setSyncServer(url: String?, user: String?) = store.edit {
        if (url == null) it.remove(SYNC_SERVER) else it[SYNC_SERVER] = url
        if (user == null) it.remove(SYNC_USER) else it[SYNC_USER] = user
    }
    suspend fun setSyncDone(at: Long) = store.edit { it[SYNC_LAST] = at }
    suspend fun setUpdateCheck(enabled: Boolean) = store.edit { it[UPDATE_CHECK] = enabled }
    suspend fun setAutoRates(enabled: Boolean) = store.edit { it[AUTO_RATES] = enabled }
    suspend fun setUpdateNotified(version: String) = store.edit { it[UPDATE_NOTIFIED] = version }
    suspend fun setWhatsNewSeen(version: String) = store.edit { it[WHATS_NEW_SEEN] = version }
    suspend fun setUpdateAvailable(release: String) = store.edit { it[UPDATE_AVAILABLE] = release }
    suspend fun setUpdateSeen(release: String) = store.edit { it[UPDATE_SEEN] = release }

    /** This phone's name in the sync folder, like "pixel-7-k3f9q2", made the first time it's needed. */
    suspend fun syncDevice(): String {
        var name = ""
        store.edit { p ->
            name = p[SYNC_DEVICE] ?: run {
                val model = android.os.Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).ifEmpty { "phone" }
                val tag = (1..6).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
                "$model-$tag"
            }
            p[SYNC_DEVICE] = name
        }
        return name
    }
    suspend fun setBackupPhotos(enabled: Boolean) = store.edit { it[BACKUP_PHOTOS] = enabled }
    suspend fun setDetectionKeywords(words: Set<String>) = store.edit { it[DETECTION_KEYWORDS] = words }
    suspend fun setDeletedKeepDays(days: Int) = store.edit { it[DELETED_KEEP_DAYS] = days }
    suspend fun dismissSuggestion(key: String) = store.edit { it[DISMISSED_SUGGESTIONS] = it[DISMISSED_SUGGESTIONS].orEmpty() + key }

    suspend fun setShiftRemindersEnabled(enabled: Boolean) = store.edit { it[SHIFT_REMINDERS] = enabled }
    suspend fun setShiftReminderNightMinutes(minutes: Int) = store.edit { it[SHIFT_REMINDER_NIGHT_TIME] = minutes }
    suspend fun setShiftReminderAdvanceHours(hours: Int) = store.edit { it[SHIFT_REMINDER_ADVANCE_HOURS] = hours }
    suspend fun setShiftReminderAlarmSuggestion(enabled: Boolean) = store.edit { it[SHIFT_REMINDER_ALARM_SUGGESTION] = enabled }
    suspend fun setCreditCardRemindersEnabled(enabled: Boolean) = store.edit { it[CREDIT_CARD_REMINDERS] = enabled }

    /** Records that an alert was shown; returns false if it had already been sent. */
    suspend fun markAlertSent(key: String): Boolean {
        var added = false
        store.edit {
            val sent = it[SENT_ALERTS].orEmpty()
            if (key !in sent) {
                it[SENT_ALERTS] = sent + key
                added = true
            }
        }
        return added
    }

    suspend fun clearBudgetData() = store.edit {
        listOf(MONTHLY_BUDGET, YEARLY_BUDGET).forEach(it::remove)
        listOf(BUDGET_MODE, YEAR_SPLIT, CUSTOM_SPLIT, CARRY_FROM, CARRY_MODE, CARRY_CHOICES).forEach(it::remove)
        it.remove(SENT_ALERTS)
    }

    suspend fun setRoomExpenseRoommate(roommate: String) = store.edit {
        it[ROOM_EXPENSE_ROOMMATE] = roommate
    }

    suspend fun setRoomExpenseIncludeInPersonal(include: Boolean) = store.edit {
        it[ROOM_EXPENSE_INCLUDE_IN_PERSONAL] = include
    }

    suspend fun setRoomExpenseLastSync(timestamp: Long) = store.edit {
        it[ROOM_EXPENSE_LAST_SYNC] = timestamp
    }

    suspend fun setRoomExpenseCutoffTimestamp(timestamp: Long) = store.edit {
        it[ROOM_EXPENSE_CUTOFF_TIMESTAMP] = timestamp
    }

    suspend fun setCohabitBaseUrl(url: String) = store.edit {
        it[COHABIT_BASE_URL] = url.trim()
    }

    suspend fun setCohabitApiKey(key: String) = store.edit {
        it[COHABIT_API_KEY] = key.trim()
    }

    suspend fun setCohabitRoommate(roommate: String) = store.edit {
        it[COHABIT_ROOMMATE] = roommate
    }

    suspend fun setCohabitLastSync(timestamp: Long) = store.edit {
        it[COHABIT_LAST_SYNC] = timestamp
    }

    suspend fun setNotificationDetection(enabled: Boolean) = store.edit {
        it[NOTIFICATION_DETECTION] = enabled
    }

    suspend fun setNotificationAutoSave(enabled: Boolean) = store.edit {
        it[NOTIFICATION_AUTO_SAVE] = enabled
    }

    suspend fun setEnabledNotificationApps(apps: Set<String>) = store.edit {
        it[ENABLED_NOTIFICATION_APPS] = apps
    }

    suspend fun toggleNotificationApp(packageName: String, enabled: Boolean) = store.edit {
        val current = it[ENABLED_NOTIFICATION_APPS].orEmpty().toMutableSet()
        if (enabled) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        it[ENABLED_NOTIFICATION_APPS] = current
    }

    suspend fun setCloudBackupEnabled(enabled: Boolean) = store.edit {
        it[CLOUD_BACKUP_ENABLED] = enabled
    }

    suspend fun setCloudBackupLast(timestamp: Long) = store.edit {
        it[CLOUD_BACKUP_LAST] = timestamp
    }

    suspend fun setCloudBackupGoogleClientId(clientId: String) = store.edit {
        it[CLOUD_BACKUP_GOOGLE_CLIENT_ID] = clientId.trim()
    }

    suspend fun setCloudUserInfo(email: String?, displayName: String?) = store.edit {
        if (email != null) it[CLOUD_USER_EMAIL] = email else it.remove(CLOUD_USER_EMAIL)
        if (displayName != null) it[CLOUD_USER_DISPLAY_NAME] = displayName else it.remove(CLOUD_USER_DISPLAY_NAME)
    }

    suspend fun setAutoUpdateChecks(enabled: Boolean) = store.edit {
        it[AUTO_UPDATE_CHECKS] = enabled
    }

    suspend fun setUpdateCheckWifiOnly(wifiOnly: Boolean) = store.edit {
        it[UPDATE_CHECK_WIFI_ONLY] = wifiOnly
    }

    suspend fun setLastUpdateCheckTime(timestamp: Long) = store.edit {
        it[LAST_UPDATE_CHECK_TIME] = timestamp
    }

    suspend fun setLastNotifiedUpdateTag(tag: String) = store.edit {
        it[LAST_NOTIFIED_UPDATE_TAG] = tag
    }

    suspend fun setUpdateRateLimitResetTime(timestamp: Long) = store.edit {
        it[UPDATE_RATE_LIMIT_RESET_TIME] = timestamp
    }

    suspend fun setUpdateCache(json: String, etag: String, timestamp: Long) = store.edit {
        it[UPDATE_CACHED_RELEASE_JSON] = json
        it[UPDATE_CACHED_ETAG] = etag
        it[UPDATE_CACHE_TIMESTAMP] = timestamp
    }

    suspend fun touchUpdateCacheTimestamp(timestamp: Long) = store.edit {
        it[UPDATE_CACHE_TIMESTAMP] = timestamp
    }

    suspend fun addCustomRemittanceProvider(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.edit { p ->
            val current = p[CUSTOM_REMITTANCE_PROVIDERS] ?: emptySet()
            p[CUSTOM_REMITTANCE_PROVIDERS] = current + trimmed
        }
    }

    suspend fun updateCustomRemittanceProvider(oldName: String, newName: String) {
        val trimmedNew = newName.trim()
        if (trimmedNew.isEmpty()) return
        store.edit { p ->
            val current = p[CUSTOM_REMITTANCE_PROVIDERS] ?: emptySet()
            p[CUSTOM_REMITTANCE_PROVIDERS] = (current - oldName) + trimmedNew
        }
    }

    suspend fun deleteCustomRemittanceProvider(name: String) {
        store.edit { p ->
            val current = p[CUSTOM_REMITTANCE_PROVIDERS] ?: emptySet()
            p[CUSTOM_REMITTANCE_PROVIDERS] = current - name
        }
    }

    private companion object {
        val CUSTOM_REMITTANCE_PROVIDERS = stringSetPreferencesKey("custom_remittance_providers")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val CURRENCY = stringPreferencesKey("currency")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PHOTO = stringPreferencesKey("user_photo_path")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val GLASS_EFFECTS = booleanPreferencesKey("glass_effects")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val LIQUID_GLASS = booleanPreferencesKey("liquid_glass")
        val BACKDROP = stringPreferencesKey("backdrop")
        val PHOTO_DIM = floatPreferencesKey("photo_dim")
        val PHOTO_BLUR = floatPreferencesKey("photo_blur")
        val PHOTO_TEXT = stringPreferencesKey("photo_text")
        val PHOTO_VERSION = longPreferencesKey("photo_version")
        val ACCENT_COLOR = longPreferencesKey("accent_color")
        val FOREIGN_RATES = stringPreferencesKey("foreign_rates")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val WIDGET_STYLE = stringPreferencesKey("widget_style")
        val MONTHLY_BUDGET = longPreferencesKey("monthly_budget")
        val YEARLY_BUDGET = longPreferencesKey("yearly_budget")
        val BUDGET_MODE = stringPreferencesKey("budget_mode")
        val YEAR_SPLIT = stringPreferencesKey("year_split")
        val CUSTOM_SPLIT = stringPreferencesKey("custom_split")
        val CARRY_MODE = stringPreferencesKey("carry_mode")
        val EXTRA_INCOME = booleanPreferencesKey("extra_income_adds")
        val BUDGET_START_DAY = intPreferencesKey("budget_start_day")
        val RANGE_BUDGET = longPreferencesKey("range_budget")
        val BUCKETS = booleanPreferencesKey("buckets_503020")
        val BUCKET_INCOME = longPreferencesKey("bucket_income")
        val BUCKET_PICKER = booleanPreferencesKey("bucket_picker")
        val PERIOD_BUDGET = longPreferencesKey("period_budget")
        val PERIOD_DAYS = intPreferencesKey("period_days")
        val PERIOD_FROM = stringPreferencesKey("period_from")
        val RANGE_TAG = stringPreferencesKey("range_tag")
        val RANGE_ACCOUNT = longPreferencesKey("range_account")
        val RANGE_FROM = stringPreferencesKey("range_from")
        val RANGE_TO = stringPreferencesKey("range_to")
        val CARRY_CHOICES = stringPreferencesKey("carry_choices")
        val CARRY_FROM = stringPreferencesKey("carry_from")
        val BUDGET_ALERTS = booleanPreferencesKey("budget_alerts")
        val SPLITS_ONE_BILL = booleanPreferencesKey("splits_one_bill")
        val BANK_BALANCES = stringPreferencesKey("bank_balances")
        val ACTIVITY_ALL_TIME = booleanPreferencesKey("activity_all_time")
        val SENT_ALERTS = stringSetPreferencesKey("sent_alerts")
        val DISMISSED_SUGGESTIONS = stringSetPreferencesKey("dismissed_suggestions")
        val DELETED_KEEP_DAYS = intPreferencesKey("deleted_keep_days")
        val HOME_ACCOUNT = longPreferencesKey("home_account")
        val DETECTION_KEYWORDS = stringSetPreferencesKey("detection_keywords")
        val BACKUP_PHOTOS = booleanPreferencesKey("backup_photos")
        val AUTO_BACKUP_DAYS = intPreferencesKey("auto_backup_days")
        val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        val SYNC_DIR = stringPreferencesKey("sync_dir")
        val SYNC_DEVICE = stringPreferencesKey("sync_device")
        val SYNC_SERVER = stringPreferencesKey("sync_server")
        val SYNC_USER = stringPreferencesKey("sync_user")
        val SYNC_LAST = longPreferencesKey("sync_last")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val AUTO_RATES = booleanPreferencesKey("auto_rates")
        val UPDATE_NOTIFIED = stringPreferencesKey("update_notified")
        val WHATS_NEW_SEEN = stringPreferencesKey("whats_new_seen")
        val UPDATE_AVAILABLE = stringPreferencesKey("update_available")
        val UPDATE_SEEN = stringPreferencesKey("update_seen")
        val DEFAULT_ACCOUNT = longPreferencesKey("default_account")
        val REMINDER = booleanPreferencesKey("reminder")
        val REMINDER_TIME = intPreferencesKey("reminder_time")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val LOCK_AFTER = androidx.datastore.preferences.core.intPreferencesKey("lock_after_seconds")
        val LOCK_FINGERPRINT = booleanPreferencesKey("lock_fingerprint")
        val SMS = booleanPreferencesKey("sms_detection")
        val SMS_AUTO_ADD = booleanPreferencesKey("sms_auto_add")
        val SMS_AUTO_MAX = longPreferencesKey("sms_auto_max")
        val PAY_UPI = stringPreferencesKey("pay_upi")
        val PAY_NAME = stringPreferencesKey("pay_name")
        val PAY_LINK = stringPreferencesKey("pay_link")
        val PAY_BANK = stringPreferencesKey("pay_bank")
        val PAY_SHARE = booleanPreferencesKey("pay_share")
        val APP_NOTIFICATIONS = booleanPreferencesKey("app_notifications")
        val RECAP_SEEN = stringPreferencesKey("recap_seen")
        val AUTO_BACKUP_DIR = stringPreferencesKey("auto_backup_dir")
        val AUTO_BACKUP_LAST = longPreferencesKey("auto_backup_last")
        val AUTO_BACKUP_ERROR = stringPreferencesKey("auto_backup_error")
        val BILL_REMINDERS = booleanPreferencesKey("bill_reminders")
        val SHIFT_REMINDERS = booleanPreferencesKey("shift_reminders")
        val SHIFT_REMINDER_NIGHT_TIME = intPreferencesKey("shift_reminder_night_time")
        val SHIFT_REMINDER_ADVANCE_HOURS = intPreferencesKey("shift_reminder_advance_hours")
        val SHIFT_REMINDER_ALARM_SUGGESTION = booleanPreferencesKey("shift_reminder_alarm_suggestion")
        val CREDIT_CARD_REMINDERS = booleanPreferencesKey("credit_card_reminders")
        val ROOM_EXPENSE_ROOMMATE = stringPreferencesKey("room_expense_roommate")
        val ROOM_EXPENSE_INCLUDE_IN_PERSONAL = booleanPreferencesKey("room_expense_include_in_personal")
        val ROOM_EXPENSE_LAST_SYNC = longPreferencesKey("room_expense_last_sync")
        val ROOM_EXPENSE_CUTOFF_TIMESTAMP = longPreferencesKey("room_expense_cutoff_timestamp")
        val COHABIT_BASE_URL = stringPreferencesKey("cohabit_base_url")
        val COHABIT_API_KEY = stringPreferencesKey("cohabit_api_key")
        val COHABIT_ROOMMATE = stringPreferencesKey("cohabit_roommate")
        val COHABIT_LAST_SYNC = longPreferencesKey("cohabit_last_sync")
        val NOTIFICATION_DETECTION = booleanPreferencesKey("notification_detection")
        val NOTIFICATION_AUTO_SAVE = booleanPreferencesKey("notification_auto_save")
        val ENABLED_NOTIFICATION_APPS = stringSetPreferencesKey("enabled_notification_apps")
        val CLOUD_BACKUP_ENABLED = booleanPreferencesKey("cloud_backup_enabled")
        val CLOUD_BACKUP_LAST = longPreferencesKey("cloud_backup_last")
        val CLOUD_BACKUP_GOOGLE_CLIENT_ID = stringPreferencesKey("cloud_backup_google_client_id")
        val CLOUD_USER_EMAIL = stringPreferencesKey("cloud_user_email")
        val CLOUD_USER_DISPLAY_NAME = stringPreferencesKey("cloud_user_display_name")
        val AUTO_UPDATE_CHECKS = booleanPreferencesKey("auto_update_checks")
        val UPDATE_CHECK_WIFI_ONLY = booleanPreferencesKey("update_check_wifi_only")
        val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val LAST_NOTIFIED_UPDATE_TAG = stringPreferencesKey("last_notified_update_tag")
        val UPDATE_RATE_LIMIT_RESET_TIME = longPreferencesKey("update_rate_limit_reset_time")
        val UPDATE_CACHED_RELEASE_JSON = stringPreferencesKey("update_cached_release_json")
        val UPDATE_CACHED_ETAG = stringPreferencesKey("update_cached_etag")
        val UPDATE_CACHE_TIMESTAMP = longPreferencesKey("update_cache_timestamp")
        val FESTIVAL_GREETINGS = booleanPreferencesKey("festival_greetings")
        val WEEKLY_NOTES = booleanPreferencesKey("weekly_notes")
        fun defaultCurrencyCode(): String =
            runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"
    }
}

/** A balance an account's bank reported, in minor units, and when the message came ([at], epoch millis). */
data class BankFigure(val minor: Long, val at: Long) {
    companion object {
        /** "id:minor:at" pairs, comma separated. */
        fun encode(all: Map<Long, BankFigure>): String = all.entries.joinToString(",") { (id, f) -> "$id:${f.minor}:${f.at}" }

        fun decode(raw: String?): Map<Long, BankFigure> = raw.orEmpty().split(',').mapNotNull { part ->
            val bits = part.split(':')
            if (bits.size != 3) return@mapNotNull null
            val id = bits[0].toLongOrNull() ?: return@mapNotNull null
            val minor = bits[1].toLongOrNull() ?: return@mapNotNull null
            val at = bits[2].toLongOrNull() ?: return@mapNotNull null
            id to BankFigure(minor, at)
        }.toMap()
    }
}
