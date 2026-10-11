package com.leftovers.app.util

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.leftovers.app.data.Account
import com.leftovers.app.data.AppDatabase
import com.leftovers.app.data.BackupDao
import com.leftovers.app.data.BudgetPlan
import com.leftovers.app.data.CarryMode
import com.leftovers.app.data.Category
import com.leftovers.app.data.Debt
import com.leftovers.app.data.Goal
import com.leftovers.app.data.GoalDeposit
import com.leftovers.app.data.LeaveEntry
import com.leftovers.app.data.LeavePaymentStatus
import com.leftovers.app.data.LeaveType
import com.leftovers.app.data.Recurring
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.Shift
import com.leftovers.app.data.ShiftStatus
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.Transfer
import com.leftovers.app.data.TxType
import com.leftovers.app.data.Workplace
import com.leftovers.app.data.WorkplacePayment
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.data.FeeMethod
import com.leftovers.app.data.RemittanceStatusHistory
import com.leftovers.app.data.photosToPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Saves everything to a single JSON file the user picks (local storage, Google Drive, …)
 * and restores from it. Photos are included only when "Include photos" is on (Settings). With a backup password set, the file is
 * encrypted (see [BackupCrypto]).
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    val password: BackupPassword,
) {
    private val dao: BackupDao = database.backupDao()

    private companion object {
        const val MAX_PHOTO_BYTES = 40L * 1024 * 1024
    }

    suspend fun export(target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val transactions = dao.transactions()
            val s = settings.settings.first()
            val debtList = dao.debts()
            val shiftList = dao.shifts()
            val workplaceList = dao.workplaces()
            val leaveList = dao.leaveEntries()
            val paymentList = dao.workplacePayments()
            val recipientList = dao.recipients()
            val remittanceList = dao.remittances()
            val remittanceStatusHistoryList = dao.remittanceStatusHistory()
            val transferList = dao.transfers()
            val recurringList = dao.recurring()
            val goalList = dao.goals()
            val depositList = dao.deposits()
            val accountList = dao.accounts()
            val categoryList = dao.categories()

            // With "Include photos" on, each photo goes in once, by file name; entries point to it. Past
            // MAX_PHOTO_BYTES in total the photos are left out, so a large collection can't run the phone out of memory.
            val photoFiles = (transactions.mapNotNull { it.receiptPath } + debtList.flatMap { it.photos } + remittanceList.mapNotNull { it.receiptPath } + listOfNotNull(s.userPhotoPath))
                .distinct().map { java.io.File(it) }.filter { it.exists() }
            val withPhotos = s.backupPhotos && photoFiles.sumOf { it.length() } <= MAX_PHOTO_BYTES
            val photos = JSONObject()
            fun photoRef(path: String?, forceInclude: Boolean = false): Any {
                if ((!withPhotos && !forceInclude) || path == null) return JSONObject.NULL
                val file = java.io.File(path)
                if (!file.exists()) return JSONObject.NULL
                if (!photos.has(file.name)) photos.put(file.name, java.util.Base64.getEncoder().encodeToString(file.readBytes()))
                return file.name
            }
            val json = JSONObject().apply {
                put("app", "leftovers")
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
                put("settings", JSONObject().apply {
                    put("currency", s.currencyCode)
                    put("userName", s.userName)
                    put("userPhoto", photoRef(s.userPhotoPath, forceInclude = true))
                    put("themeMode", s.themeMode.name)
                    put("dynamicColor", s.dynamicColor)
                    put("budgetMode", s.plan.mode.name)
                    put("monthly", s.plan.monthlyMinor)
                    put("yearly", s.plan.yearlyMinor)
                    put("split", s.plan.split.name)
                    put("custom", BudgetPlan.encodeCustom(s.plan.custom))
                    put("carryMode", s.plan.carry.name)
                    put("carryChoices", BudgetPlan.encodeChoices(s.plan.carryChoices))
                    s.plan.carryFrom?.let { put("carryFrom", it.toString()) }
                    put("budgetAlerts", s.budgetAlerts)
                    put("defaultAccountId", s.defaultAccountId)
                    put("reminderEnabled", s.reminderEnabled)
                    put("reminderMinutes", s.reminderMinutes)
                    put("appLock", s.appLock)
                    put("smsDetection", s.smsDetection)
                    put("billReminders", s.billReminders)
                    put("shiftRemindersEnabled", s.shiftRemindersEnabled)
                    put("shiftReminderNightMinutes", s.shiftReminderNightMinutes)
                    put("shiftReminderAdvanceHours", s.shiftReminderAdvanceHours)
                    put("shiftReminderAlarmSuggestion", s.shiftReminderAlarmSuggestion)
                    if (s.detectionKeywords.isNotEmpty()) put("detectionKeywords", JSONArray(s.detectionKeywords))
                })
                put("categories", JSONArray(categoryList.map { c ->
                    JSONObject().put("id", c.id).put("name", c.name).put("icon", c.emoji).put("color", c.color)
                        .put("type", c.type.name).put("budget", c.budgetMinor ?: JSONObject.NULL)
                        .put("addsToBudget", c.addsToBudget)
                }))
                put("accounts", JSONArray(accountList.map { a ->
                    JSONObject().put("id", a.id).put("name", a.name).put("icon", a.icon).put("color", a.color)
                        .put("opening", a.openingMinor).put("createdAt", a.createdAt).put("smsDigits", a.smsDigits ?: JSONObject.NULL)
                        .put("currency", a.currency ?: JSONObject.NULL)
                }))
                put("transactions", JSONArray(transactions.map { t ->
                    JSONObject().put("id", t.id).put("amount", t.amountMinor).put("type", t.type.name)
                        .put("categoryId", t.categoryId).put("day", t.epochDay).put("note", t.note)
                        .put("createdAt", t.createdAt).put("accountId", t.accountId ?: JSONObject.NULL)
                        .put("receipt", photoRef(t.receiptPath))
                        .put("foreignAmount", t.foreignMinor ?: JSONObject.NULL).put("foreignCurrency", t.foreignCurrency ?: JSONObject.NULL)
                }))
                put("transfers", JSONArray(transferList.map { t ->
                    JSONObject().put("id", t.id).put("from", t.fromAccountId).put("to", t.toAccountId).put("amount", t.amountMinor)
                        .put("day", t.epochDay).put("note", t.note).put("createdAt", t.createdAt)
                }))
                put("recurring", JSONArray(recurringList.map { r ->
                    JSONObject().put("id", r.id).put("name", r.name).put("amount", r.amountMinor).put("type", r.type.name)
                        .put("categoryId", r.categoryId).put("day", r.dayOfMonth).put("start", r.startMonth)
                        .put("lastPosted", r.lastPostedMonth ?: JSONObject.NULL).put("active", r.active)
                        .put("every", r.everyMonths).put("account", r.accountId ?: JSONObject.NULL)
                }))
                put("goals", JSONArray(goalList.map { g ->
                    JSONObject().put("id", g.id).put("name", g.name).put("icon", g.emoji).put("color", g.color)
                        .put("target", g.targetMinor).put("targetMonth", g.targetMonth).put("createdAt", g.createdAt)
                }))
                put("deposits", JSONArray(depositList.map { d ->
                    JSONObject().put("id", d.id).put("goalId", d.goalId).put("amount", d.amountMinor)
                        .put("day", d.epochDay).put("createdAt", d.createdAt)
                }))
                put("debts", JSONArray(debtList.map { d ->
                    JSONObject().put("id", d.id).put("person", d.person).put("amount", d.amountMinor).put("note", d.note)
                        .put("day", d.epochDay).put("settled", d.settled).put("createdAt", d.createdAt)
                        .put("dueDate", d.dueDateEpochDay ?: JSONObject.NULL)
                        .put("reminderEnabled", d.reminderEnabled)
                        .put("receipts", JSONArray(d.photos.map { photoRef(it) }.filter { it != JSONObject.NULL }))
                }))
                put("workplaces", JSONArray(workplaceList.map { w ->
                    JSONObject().put("id", w.id).put("name", w.name).put("role", w.role)
                        .put("address", w.address ?: JSONObject.NULL).put("contactPerson", w.contactPerson ?: JSONObject.NULL)
                        .put("contactDetails", w.contactDetails ?: JSONObject.NULL).put("employmentType", w.employmentType)
                        .put("currency", w.currency).put("rateCents", w.basicHourlyRateCents)
                        .put("monthlyHours", w.basicMonthlyHours).put("overtimeMultiplier", w.overtimeMultiplier)
                        .put("paidBreaks", w.paidBreaks).put("leaveCounts", w.leaveCountsTowardsThreshold)
                        .put("allowance", w.fixedMonthlyAllowanceMinor).put("deduction", w.monthlyDeductionMinor)
                        .put("notes", w.notes).put("archived", w.archived)
                        .put("createdAt", w.createdAt).put("updatedAt", w.updatedAt)
                }))
                put("shifts", JSONArray(shiftList.map { sh ->
                    JSONObject().put("id", sh.id).put("workplaceId", sh.workplaceId ?: JSONObject.NULL)
                        .put("title", sh.title).put("workplace", sh.workplace ?: JSONObject.NULL)
                        .put("start", sh.startEpochMillis).put("end", sh.endEpochMillis)
                        .put("break", sh.breakDurationMinutes).put("notes", sh.notes)
                        .put("status", sh.status.name).put("createdAt", sh.createdAt).put("updatedAt", sh.updatedAt)
                }))
                put("leave_entries", JSONArray(leaveList.map { l ->
                    JSONObject().put("id", l.id).put("workplaceId", l.workplaceId ?: JSONObject.NULL)
                        .put("type", l.type.name).put("startDay", l.startDateEpochDay).put("endDay", l.endDateEpochDay)
                        .put("fullDay", l.isFullDay).put("customHours", l.customHours ?: JSONObject.NULL)
                        .put("paymentStatus", l.paymentStatus.name).put("paidRate", l.paidRatePercent)
                        .put("fixedPay", l.fixedPayAmountMinor ?: JSONObject.NULL)
                        .put("reason", l.reason).put("notes", l.notes)
                        .put("createdAt", l.createdAt).put("updatedAt", l.updatedAt)
                }))
                put("workplace_payments", JSONArray(paymentList.map { p ->
                    JSONObject().put("id", p.id).put("workplaceId", p.workplaceId)
                        .put("month", p.payPeriodMonth).put("day", p.paymentEpochDay)
                        .put("amount", p.amountMinor).put("currency", p.currency)
                        .put("ref", p.payslipReference).put("note", p.note)
                        .put("reviewed", p.reviewed).put("financeTxId", p.financeTransactionId ?: JSONObject.NULL)
                        .put("createdAt", p.createdAt)
                }))
                put("recipients", JSONArray(recipientList.map { rec ->
                    JSONObject().put("id", rec.id).put("name", rec.name)
                        .put("relationship", rec.relationship ?: JSONObject.NULL).put("country", rec.country)
                        .put("currency", rec.preferredCurrency).put("provider", rec.preferredProvider ?: JSONObject.NULL)
                        .put("bankOrWallet", rec.bankOrWalletLabel ?: JSONObject.NULL)
                        .put("notes", rec.notes).put("archived", rec.archived)
                        .put("createdAt", rec.createdAt).put("updatedAt", rec.updatedAt)
                }))
                put("remittances", JSONArray(remittanceList.map { r ->
                    JSONObject().put("id", r.id).put("recipientId", r.recipientId ?: JSONObject.NULL)
                        .put("recipientName", r.recipientName).put("recipientCountry", r.recipientCountry)
                        .put("recipientBankOrWallet", r.recipientBankOrWallet ?: JSONObject.NULL)
                        .put("transferDateEpochDay", r.transferDateEpochDay).put("provider", r.provider)
                        .put("sendAmountMinor", r.sendAmountMinor).put("sendCurrency", r.sendCurrency)
                        .put("feeAmountMinor", r.feeAmountMinor).put("feeCurrency", r.feeCurrency)
                        .put("feeMethod", r.feeMethod.name).put("exchangeRate", r.exchangeRate)
                        .put("expectedReceiveAmountMinor", r.expectedReceiveAmountMinor)
                        .put("actualReceiveAmountMinor", r.actualReceiveAmountMinor ?: JSONObject.NULL)
                        .put("receiveCurrency", r.receiveCurrency)
                        .put("status", r.status.name)
                        .put("receivedDateEpochDay", r.receivedDateEpochDay ?: JSONObject.NULL)
                        .put("sendingAccountId", r.sendingAccountId ?: JSONObject.NULL)
                        .put("financeTransactionId", r.financeTransactionId ?: JSONObject.NULL)
                        .put("referenceNumber", r.referenceNumber ?: JSONObject.NULL)
                        .put("notes", r.notes)
                        .put("receipt", photoRef(r.receiptPath))
                        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
                }))
                put("remittance_status_history", JSONArray(remittanceStatusHistoryList.map { h ->
                    JSONObject().put("id", h.id).put("remittanceId", h.remittanceId)
                        .put("fromStatus", h.fromStatus?.name ?: JSONObject.NULL)
                        .put("toStatus", h.toStatus.name).put("changedAt", h.changedAt)
                        .put("note", h.note ?: JSONObject.NULL)
                }))
                if (photos.length() > 0) put("photos", photos)
            }
            val text = password.get()?.let { BackupCrypto.encrypt(json.toString(), it).toString(2) } ?: json.toString(2)
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
                ?: error("Couldn't open the file")
            val totalExported = transactions.size + debtList.size + shiftList.size + workplaceList.size + leaveList.size + paymentList.size + remittanceList.size
            totalExported
        }
    }

    /**
     * Replaces all data with the backup's contents. A protected backup is opened with [typedPassword],
     * or else the saved backup password; without the right one it fails with [BackupPasswordException].
     */
    suspend fun import(source: Uri, typedPassword: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                ?: error("Couldn't open the file")
            var json = JSONObject(text)
            if (BackupCrypto.isEncrypted(json)) {
                val key = typedPassword ?: password.get() ?: throw BackupPasswordException("This backup has a password")
                json = JSONObject(BackupCrypto.decrypt(json, key))
            }
            // "cash-tracker" is the app's previous name; keep accepting those backups.
            require(json.optString("app") in setOf("leftovers", "cash-tracker")) { "This isn't a Leftovers backup" }

            fun JSONObject.longOrNull(key: String): Long? =
                if (isNull(key) || !has(key)) null else optLong(key)

            fun JSONObject.doubleOrNull(key: String): Double? =
                if (isNull(key) || !has(key)) null else optDouble(key)

            fun JSONObject.stringOrNull(key: String): String? =
                if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

            fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

            // Photos saved in the backup, written back to private storage on demand.
            val savedPhotos = json.optJSONObject("photos")
            val restoredPhotos = mutableMapOf<String, String>()
            fun restorePhoto(name: String): String? {
                if (name.isEmpty() || name == "null") return null
                restoredPhotos[name]?.let { return it }
                val data = savedPhotos?.optString(name)?.takeIf { it.isNotEmpty() } ?: return null
                return ReceiptStore.restore(context, java.util.Base64.getDecoder().decode(data)).also { restoredPhotos[name] = it }
            }

            val categories = json.optJSONArray("categories")?.objects().orEmpty().map {
                val type = TxType.entries.firstOrNull { t -> t.name == it.optString("type") } ?: TxType.EXPENSE
                val name = it.optString("name", "Category")
                // Older backups predate the flag: use the same default as the database migration.
                val adds = it.optBoolean("addsToBudget", type == TxType.INCOME && name != "Salary")
                Category(it.optLong("id", 0L), name, it.optString("icon", "📦"), it.optLong("color", 0xFF00897BL), type, it.longOrNull("budget"), adds)
            }
            val accounts = json.optJSONArray("accounts")?.objects().orEmpty().map {
                Account(it.optLong("id", 0L), it.optString("name", "Account"), it.optString("icon", "💳"), it.optLong("color", 0xFF00897BL), it.optLong("opening", 0L), it.optLong("createdAt", System.currentTimeMillis()), it.stringOrNull("smsDigits"), it.stringOrNull("currency"))
            }
            val transactions = json.optJSONArray("transactions")?.objects().orEmpty().map {
                Transaction(
                    id = it.optLong("id", 0L),
                    amountMinor = it.optLong("amount", 0L),
                    type = TxType.entries.firstOrNull { t -> t.name == it.optString("type") } ?: TxType.EXPENSE,
                    categoryId = it.optLong("categoryId", 0L),
                    epochDay = it.optLong("day", 0L),
                    note = it.optString("note", ""),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    accountId = it.longOrNull("accountId"),
                    receiptPath = restorePhoto(it.optString("receipt")),
                    foreignMinor = it.longOrNull("foreignAmount"),
                    foreignCurrency = it.stringOrNull("foreignCurrency"),
                )
            }
            val transfers = json.optJSONArray("transfers")?.objects().orEmpty().map {
                Transfer(it.optLong("id", 0L), it.optLong("from", 0L), it.optLong("to", 0L), it.optLong("amount", 0L), it.optLong("day", 0L), it.optString("note", ""), it.optLong("createdAt", System.currentTimeMillis()))
            }
            val recurring = json.optJSONArray("recurring")?.objects().orEmpty().map {
                Recurring(
                    id = it.optLong("id", 0L),
                    name = it.optString("name", ""),
                    amountMinor = it.optLong("amount", 0L),
                    type = TxType.entries.firstOrNull { t -> t.name == it.optString("type") } ?: TxType.EXPENSE,
                    categoryId = it.optLong("categoryId", 0L),
                    dayOfMonth = it.optInt("day", 1),
                    startMonth = it.optString("start", ""),
                    lastPostedMonth = it.stringOrNull("lastPosted"),
                    active = it.optBoolean("active", true),
                    everyMonths = it.optInt("every", 1),
                    accountId = it.longOrNull("account"),
                )
            }
            val goals = json.optJSONArray("goals")?.objects().orEmpty().map {
                Goal(it.optLong("id", 0L), it.optString("name", ""), it.optString("icon", "🎯"), it.optLong("color", 0xFF00897BL), it.optLong("target", 0L), it.optString("targetMonth", ""), it.optLong("createdAt", System.currentTimeMillis()))
            }
            val deposits = json.optJSONArray("deposits")?.objects().orEmpty().map {
                GoalDeposit(it.optLong("id", 0L), it.optLong("goalId", 0L), it.optLong("amount", 0L), it.optLong("day", 0L), it.optLong("createdAt", System.currentTimeMillis()))
            }
            val debts = json.optJSONArray("debts")?.objects().orEmpty().map {
                // "receipts" holds several photos; older backups had one "receipt".
                val names = it.optJSONArray("receipts")?.let { a -> (0 until a.length()).map(a::getString) } ?: listOf(it.optString("receipt"))
                Debt(
                    id = it.optLong("id", 0L),
                    person = it.optString("person", ""),
                    amountMinor = it.optLong("amount", 0L),
                    note = it.optString("note", ""),
                    epochDay = it.optLong("day", 0L),
                    settled = it.optBoolean("settled", false),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    receiptPath = photosToPath(names.mapNotNull(::restorePhoto)),
                    dueDateEpochDay = it.longOrNull("dueDate"),
                    reminderEnabled = it.optBoolean("reminderEnabled", false),
                )
            }
            val workplaces = json.optJSONArray("workplaces")?.objects().orEmpty().map {
                Workplace(
                    id = it.optLong("id", 0L),
                    name = it.optString("name", "Workplace"),
                    role = it.optString("role", ""),
                    address = it.stringOrNull("address"),
                    contactPerson = it.stringOrNull("contactPerson"),
                    contactDetails = it.stringOrNull("contactDetails"),
                    employmentType = it.optString("employmentType", "Full-time"),
                    currency = it.optString("currency", "EUR"),
                    basicHourlyRateCents = it.optLong("rateCents", 573L),
                    basicMonthlyHours = it.optDouble("monthlyHours", 173.33),
                    overtimeMultiplier = it.optDouble("overtimeMultiplier", 1.5),
                    paidBreaks = it.optBoolean("paidBreaks", false),
                    leaveCountsTowardsThreshold = it.optBoolean("leaveCounts", false),
                    fixedMonthlyAllowanceMinor = it.optLong("allowance", 0L),
                    monthlyDeductionMinor = it.optLong("deduction", 0L),
                    notes = it.optString("notes", ""),
                    archived = it.optBoolean("archived", false),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
            val shifts = json.optJSONArray("shifts")?.objects().orEmpty().map {
                Shift(
                    id = it.optLong("id", 0L),
                    workplaceId = it.longOrNull("workplaceId"),
                    title = it.optString("title", "Shift"),
                    workplace = it.stringOrNull("workplace"),
                    startEpochMillis = it.optLong("start", System.currentTimeMillis()),
                    endEpochMillis = it.optLong("end", System.currentTimeMillis() + 8 * 3600 * 1000),
                    breakDurationMinutes = it.optInt("break", 0),
                    notes = it.optString("notes", ""),
                    status = ShiftStatus.entries.firstOrNull { s -> s.name == it.optString("status") } ?: ShiftStatus.SCHEDULED,
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
            val leaveEntries = json.optJSONArray("leave_entries")?.objects().orEmpty().map {
                LeaveEntry(
                    id = it.optLong("id", 0L),
                    workplaceId = it.longOrNull("workplaceId"),
                    type = LeaveType.entries.firstOrNull { t -> t.name == it.optString("type") } ?: LeaveType.DAY_OFF,
                    startDateEpochDay = it.optLong("startDay", 0L),
                    endDateEpochDay = it.optLong("endDay", it.optLong("startDay", 0L)),
                    isFullDay = it.optBoolean("fullDay", true),
                    customHours = it.doubleOrNull("customHours"),
                    paymentStatus = LeavePaymentStatus.entries.firstOrNull { p -> p.name == it.optString("paymentStatus") } ?: LeavePaymentStatus.UNPAID,
                    paidRatePercent = it.optDouble("paidRate", 100.0),
                    fixedPayAmountMinor = it.longOrNull("fixedPay"),
                    reason = it.optString("reason", ""),
                    notes = it.optString("notes", ""),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
            val workplacePayments = json.optJSONArray("workplace_payments")?.objects().orEmpty().map {
                WorkplacePayment(
                    id = it.optLong("id", 0L),
                    workplaceId = it.optLong("workplaceId", 0L),
                    payPeriodMonth = it.optString("month", ""),
                    paymentEpochDay = it.optLong("day", 0L),
                    amountMinor = it.optLong("amount", 0L),
                    currency = it.optString("currency", "EUR"),
                    payslipReference = it.optString("ref", ""),
                    note = it.optString("note", ""),
                    reviewed = it.optBoolean("reviewed", true),
                    financeTransactionId = it.longOrNull("financeTxId"),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                )
            }
            val recipients = json.optJSONArray("recipients")?.objects().orEmpty().map {
                Recipient(
                    id = it.optLong("id", 0L),
                    name = it.optString("name", "Unknown"),
                    relationship = it.stringOrNull("relationship"),
                    country = it.optString("country", "Nepal"),
                    preferredCurrency = it.optString("currency", "NPR"),
                    preferredProvider = it.stringOrNull("provider"),
                    bankOrWalletLabel = it.stringOrNull("bankOrWallet"),
                    notes = it.optString("notes", ""),
                    archived = it.optBoolean("archived", false),
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
            val remittances = json.optJSONArray("remittances")?.objects().orEmpty().map {
                Remittance(
                    id = it.optLong("id", 0L),
                    recipientId = it.longOrNull("recipientId"),
                    recipientName = it.optString("recipientName", "Unknown"),
                    recipientCountry = it.optString("recipientCountry", "Nepal"),
                    recipientBankOrWallet = it.stringOrNull("recipientBankOrWallet"),
                    transferDateEpochDay = it.optLong("transferDateEpochDay", LocalDate.now().toEpochDay()),
                    provider = it.optString("provider", "Manual"),
                    sendAmountMinor = it.optLong("sendAmountMinor", 0L),
                    sendCurrency = it.optString("sendCurrency", "EUR"),
                    feeAmountMinor = it.optLong("feeAmountMinor", 0L),
                    feeCurrency = it.optString("feeCurrency", "EUR"),
                    feeMethod = FeeMethod.entries.firstOrNull { m -> m.name == it.optString("feeMethod") } ?: FeeMethod.SEPARATE,
                    exchangeRate = it.optDouble("exchangeRate", 0.0),
                    expectedReceiveAmountMinor = it.optLong("expectedReceiveAmountMinor", 0L),
                    actualReceiveAmountMinor = it.longOrNull("actualReceiveAmountMinor"),
                    receiveCurrency = it.optString("receiveCurrency", "NPR"),
                    status = RemittanceStatus.entries.firstOrNull { s -> s.name == it.optString("status") } ?: RemittanceStatus.SENT,
                    receivedDateEpochDay = it.longOrNull("receivedDateEpochDay"),
                    sendingAccountId = it.longOrNull("sendingAccountId"),
                    financeTransactionId = it.longOrNull("financeTransactionId"),
                    referenceNumber = it.stringOrNull("referenceNumber"),
                    notes = it.optString("notes", ""),
                    receiptPath = it.stringOrNull("receipt")?.let { name -> restorePhoto(name) },
                    createdAt = it.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                )
            }
            val remittanceStatusHistory = json.optJSONArray("remittance_status_history")?.objects().orEmpty().map {
                RemittanceStatusHistory(
                    id = it.optLong("id", 0L),
                    remittanceId = it.optLong("remittanceId", 0L),
                    fromStatus = it.stringOrNull("fromStatus")?.let { st -> RemittanceStatus.entries.firstOrNull { s -> s.name == st } },
                    toStatus = RemittanceStatus.entries.firstOrNull { s -> s.name == it.optString("toStatus") } ?: RemittanceStatus.SENT,
                    changedAt = it.optLong("changedAt", System.currentTimeMillis()),
                    note = it.stringOrNull("note"),
                )
            }

            database.withTransaction {
                dao.clearRemittanceStatusHistory()
                dao.clearRemittances()
                dao.clearRecipients()
                dao.clearWorkplacePayments()
                dao.clearLeaveEntries()
                dao.clearShifts()
                dao.clearWorkplaces()
                dao.clearDebts()
                dao.clearDeletedLeaves()
                dao.clearDeletedShifts()
                dao.clearDeleted()
                dao.clearDeposits()
                dao.clearGoals()
                dao.clearTransfers()
                dao.clearTransactions()
                dao.clearRecurring()
                dao.clearAccounts()
                dao.clearCategories()

                if (categories.isNotEmpty()) dao.insertCategories(categories)
                if (accounts.isNotEmpty()) dao.insertAccounts(accounts)
                if (transactions.isNotEmpty()) dao.insertTransactions(transactions)
                if (transfers.isNotEmpty()) dao.insertTransfers(transfers)
                if (recurring.isNotEmpty()) dao.insertRecurring(recurring)
                if (goals.isNotEmpty()) dao.insertGoals(goals)
                if (deposits.isNotEmpty()) dao.insertDeposits(deposits)
                if (debts.isNotEmpty()) dao.insertDebts(debts)
                if (workplaces.isNotEmpty()) dao.insertWorkplaces(workplaces)
                if (shifts.isNotEmpty()) dao.insertShifts(shifts)
                if (leaveEntries.isNotEmpty()) dao.insertLeaveEntries(leaveEntries)
                if (workplacePayments.isNotEmpty()) dao.insertWorkplacePayments(workplacePayments)
                if (recipients.isNotEmpty()) dao.insertRecipients(recipients)
                if (remittances.isNotEmpty()) dao.insertRemittances(remittances)
                if (remittanceStatusHistory.isNotEmpty()) dao.insertRemittanceStatusHistory(remittanceStatusHistory)
            }
            val userPhoto = json.optJSONObject("settings")?.stringOrNull("userPhoto")?.let { name -> restorePhoto(name) }
            // Photos that belonged to the replaced data are no longer used.
            ReceiptStore.deleteAllExcept(context, (transactions.mapNotNull { it.receiptPath } + debts.flatMap { it.photos } + remittances.mapNotNull { it.receiptPath } + listOfNotNull(userPhoto)).toSet())

            json.optJSONObject("settings")?.let { s ->
                val currency = s.optString("currency", "EUR")
                val userName = s.optString("userName", "")
                val theme = com.leftovers.app.data.ThemeMode.entries.firstOrNull { it.name == s.optString("themeMode") } ?: com.leftovers.app.data.ThemeMode.SYSTEM
                val dynamicColor = s.optBoolean("dynamicColor", false)
                val plan = BudgetPlan(
                    mode = com.leftovers.app.data.BudgetMode.entries.firstOrNull { it.name == s.optString("budgetMode") } ?: com.leftovers.app.data.BudgetMode.MONTHLY,
                    monthlyMinor = s.optLong("monthly", 0L),
                    yearlyMinor = s.optLong("yearly", 0L),
                    split = com.leftovers.app.data.YearSplit.entries.firstOrNull { it.name == s.optString("split") } ?: com.leftovers.app.data.YearSplit.EVEN,
                    custom = BudgetPlan.decodeCustom(s.optString("custom")),
                    carry = CarryMode.entries.firstOrNull { it.name == s.optString("carryMode") } ?: CarryMode.ASK,
                    carryChoices = BudgetPlan.decodeChoices(s.optString("carryChoices")),
                    carryFrom = s.optString("carryFrom").takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
                )
                val budgetAlerts = s.optBoolean("budgetAlerts", true)
                val defaultAccountId = s.optLong("defaultAccountId", 1L)
                val reminderEnabled = s.optBoolean("reminderEnabled", false)
                val reminderMinutes = s.optInt("reminderMinutes", 20 * 60)
                val appLock = s.optBoolean("appLock", false)
                val smsDetection = s.optBoolean("smsDetection", false)
                val billReminders = s.optBoolean("billReminders", true)
                val shiftRemindersEnabled = s.optBoolean("shiftRemindersEnabled", true)
                val shiftReminderNightMinutes = s.optInt("shiftReminderNightMinutes", 20 * 60)
                val shiftReminderAdvanceHours = s.optInt("shiftReminderAdvanceHours", 5)
                val shiftReminderAlarmSuggestion = s.optBoolean("shiftReminderAlarmSuggestion", true)
                val keywords = s.optJSONArray("detectionKeywords")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } ?: emptySet()

                settings.restoreSettings(
                    currencyCode = currency,
                    userName = userName,
                    userPhotoPath = userPhoto,
                    themeMode = theme,
                    dynamicColor = dynamicColor,
                    plan = plan,
                    budgetAlerts = budgetAlerts,
                    defaultAccountId = defaultAccountId,
                    reminderEnabled = reminderEnabled,
                    reminderMinutes = reminderMinutes,
                    appLock = appLock,
                    smsDetection = smsDetection,
                    billReminders = billReminders,
                    shiftRemindersEnabled = shiftRemindersEnabled,
                    shiftReminderNightMinutes = shiftReminderNightMinutes,
                    shiftReminderAdvanceHours = shiftReminderAdvanceHours,
                    shiftReminderAlarmSuggestion = shiftReminderAlarmSuggestion,
                    detectionKeywords = keywords,
                )
            }
            val totalRestored = transactions.size + debts.size + shifts.size + workplaces.size + leaveEntries.size + workplacePayments.size + remittances.size
            totalRestored
        }
    }
}
