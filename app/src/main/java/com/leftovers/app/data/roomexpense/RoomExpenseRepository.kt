package com.leftovers.app.data.roomexpense

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.leftovers.app.data.CategoryDao
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.SmsRepository
import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionDao
import com.leftovers.app.data.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

class RoomExpenseRepository(
    private val context: Context,
    private val dao: RoomExpenseDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val settingsRepository: SettingsRepository,
    private val smsRepository: SmsRepository,
) {
    companion object {
        private const val TAG = "RoomExpenseRepo"
        const val FIREBASE_APP_NAME = "RoomExpenseFirebaseApp"
        val PROJECT_ID: String get() = com.leftovers.app.BuildConfig.ROOM_EXPENSE_PROJECT_ID
        val API_KEY: String get() = com.leftovers.app.BuildConfig.ROOM_EXPENSE_API_KEY
        val APP_ID: String get() = com.leftovers.app.BuildConfig.ROOM_EXPENSE_APP_ID
        val STORAGE_BUCKET: String get() = com.leftovers.app.BuildConfig.ROOM_EXPENSE_STORAGE_BUCKET
        val DATABASE_ID: String get() = com.leftovers.app.BuildConfig.ROOM_EXPENSE_DATABASE_ID
        const val COLLECTION_EXPENSES = "expenses"
        const val SOURCE_PREFIX = "[Room Expense]"

        fun isConfigured(): Boolean = PROJECT_ID.isNotBlank() && API_KEY.isNotBlank() && DATABASE_ID.isNotBlank()
    }

    private val firestore: FirebaseFirestore by lazy {
        initFirestore()
    }

    private fun initFirestore(): FirebaseFirestore {
        if (!isConfigured()) {
            error("Room Expense credentials are not configured in local.properties")
        }
        val existingApp = FirebaseApp.getApps(context).find { it.name == FIREBASE_APP_NAME }
        val app = existingApp ?: run {
            val options = FirebaseOptions.Builder()
                .setApplicationId(APP_ID)
                .setApiKey(API_KEY)
                .setProjectId(PROJECT_ID)
                .setStorageBucket(STORAGE_BUCKET)
                .build()
            FirebaseApp.initializeApp(context, options, FIREBASE_APP_NAME)
        }

        val db = FirebaseFirestore.getInstance(app, DATABASE_ID)
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

    fun observeExpenses(): Flow<List<RoomExpense>> = dao.observeAll()

    fun observeSummary(month: YearMonth, currentUser: Roommate): Flow<RoomExpenseSummary> =
        dao.observeAll().map { list ->
            RoomExpenseMath.calculateSummary(list, month, currentUser)
        }

    /**
     * Synchronizes room expenses from the named Firestore database.
     * Caches all records into local Room database and optionally imports the user's allocated share
     * into personal transactions if enabled in settings.
     */
    suspend fun syncFromFirebase(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting sync from Firestore database $DATABASE_ID...")
            val snapshot = firestore.collection(COLLECTION_EXPENSES)
                .get()
                .await()

            val now = System.currentTimeMillis()
            val parsedExpenses = ArrayList<RoomExpense>(snapshot.size())

            for (doc in snapshot.documents) {
                val data = doc.data ?: continue

                val id = doc.id
                val title = doc.getString("title") ?: ""
                val amount = when (val amt = data["amount"]) {
                    is Number -> amt.toDouble()
                    else -> 0.0
                }
                val paidBy = doc.getString("paidBy") ?: ""
                val splitType = doc.getString("splitType") ?: "50/50"
                val category = doc.getString("category") ?: "Other"

                val timestamp = when (val ts = data["timestamp"]) {
                    is Timestamp -> ts.toDate().time
                    is Number -> ts.toLong()
                    is Map<*, *> -> {
                        val seconds = (ts["seconds"] as? Number)?.toLong() ?: 0L
                        val nanos = (ts["nanoseconds"] as? Number)?.toLong() ?: 0L
                        (seconds * 1000) + (nanos / 1_000_000)
                    }
                    else -> now
                }

                val splitMap = data["splitDetails"] as? Map<*, *>
                val splitOzric = (splitMap?.get("OzricAmount") as? Number)?.toDouble() ?: 0.0
                val splitPawan = (splitMap?.get("PawanAmount") as? Number)?.toDouble() ?: 0.0

                val payMap = data["paymentDistribution"] as? Map<*, *>
                val paidOzric = (payMap?.get("OzricAmount") as? Number)?.toDouble() ?: 0.0
                val paidPawan = (payMap?.get("PawanAmount") as? Number)?.toDouble() ?: 0.0

                val isCoin = doc.getBoolean("isCoin") ?: false
                val isSettlement = doc.getBoolean("isSettlement") ?: false
                val isLendBorrow = doc.getBoolean("isLendBorrow") ?: false
                val paymentMethod = doc.getString("paymentMethod")
                val itemizedDetails = data["itemizedDetails"]?.toString()
                val notes = doc.getString("notes") ?: ""
                val status = doc.getString("status") ?: "confirmed"
                val createdBy = doc.getString("createdBy")
                val deleteRequested = doc.getBoolean("deleteRequested") ?: false
                val deleteRequestedBy = doc.getString("deleteRequestedBy")

                parsedExpenses.add(
                    RoomExpense(
                        id = id,
                        title = title,
                        amount = amount,
                        paidBy = paidBy,
                        splitType = splitType,
                        category = category,
                        timestamp = timestamp,
                        splitOzric = splitOzric,
                        splitPawan = splitPawan,
                        paidOzric = paidOzric,
                        paidPawan = paidPawan,
                        isCoin = isCoin,
                        isSettlement = isSettlement,
                        isLendBorrow = isLendBorrow,
                        paymentMethod = paymentMethod,
                        itemizedDetails = itemizedDetails,
                        notes = notes,
                        status = status,
                        createdBy = createdBy,
                        deleteRequested = deleteRequested,
                        deleteRequestedBy = deleteRequestedBy,
                        lastSyncedAt = now,
                    )
                )
            }

            // Save to local cache in Room
            dao.upsertAll(parsedExpenses)
            settingsRepository.setRoomExpenseLastSync(now)

            // Personal Finance Review: Queue as suggestions needing user confirmation
            val settings = settingsRepository.settings.first()
            if (settings.roomExpenseIncludeInPersonal) {
                val startOfToday = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                var cutoff = settings.roomExpenseCutoffTimestamp
                if (cutoff <= 0L) {
                    cutoff = startOfToday
                    settingsRepository.setRoomExpenseCutoffTimestamp(cutoff)
                }

                // Purge any historical room expense suggestions that were created before the cutoff
                val purged = smsRepository.deleteRoomExpenseSuggestionsBefore(cutoff)
                if (purged > 0) {
                    Log.d(TAG, "Purged $purged old room expense suggestions created before cutoff")
                }

                processExpensesIntoSuggestions(
                    expenses = parsedExpenses,
                    activeRoommate = Roommate.fromString(settings.roomExpenseRoommate),
                    cutoffTimestamp = cutoff,
                )
            }

            Log.d(TAG, "Successfully synced ${parsedExpenses.size} room expenses.")
            Result.success(parsedExpenses.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync room expenses from Firestore: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Converts new Room Expenses into detected payment suggestions in Leftovers' review queue.
     * Rejects old historical expenses (before cutoff) and duplicate payments.
     */
    private suspend fun processExpensesIntoSuggestions(
        expenses: List<RoomExpense>,
        activeRoommate: Roommate,
        cutoffTimestamp: Long,
    ) {
        for (expense in expenses) {
            // 1. Don't add old payments (created before cutoff timestamp)
            if (expense.timestamp < cutoffTimestamp) {
                continue
            }

            // 2. Only confirmed, non-coin, non-settlement, non-lend/borrow expenses
            if (expense.isCoin || expense.isSettlement || expense.isLendBorrow || expense.isPending) {
                continue
            }

            // 3. Check if already imported into transactions by doc ID
            val stableTag = "$SOURCE_PREFIX [Doc: ${expense.id}]"
            if (transactionDao.findByNoteContaining(stableTag) != null) {
                continue
            }

            // 4. Fingerprint hash for spotting repeats
            val hash = sha256("room_expense:${expense.id}")

            // If already suggested in the queue or seen, skip
            if (smsRepository.hasSuggestionWithBody(hash) || smsRepository.hasSeen(hash)) {
                continue
            }

            val isOzricPaying = expense.paidBy.equals("Ozric", ignoreCase = true) || expense.paidOzric > 0
            val isPawanPaying = expense.paidBy.equals("Pawan", ignoreCase = true) || expense.paidPawan > 0

            val userIsPayer = (activeRoommate == Roommate.OZRIC && isOzricPaying) ||
                              (activeRoommate == Roommate.PAWAN && isPawanPaying)

            val userShare = expense.userShare(activeRoommate)
            val totalAmount = expense.amount

            // If you paid: the suggestion shows total bill amount you paid (e.g. €30.00)
            // If roommate paid: the suggestion shows your share that you owe (e.g. €15.00)
            val displayAmountMinor = if (userIsPayer) {
                (totalAmount * 100.0).roundToLong()
            } else {
                (userShare * 100.0).roundToLong()
            }

            if (displayAmountMinor <= 0) continue

            // 5. Check if a transaction for this exact amount on this day is already logged
            val existingTx = transactionDao.findByAmountAndDay(
                amountMinor = displayAmountMinor,
                epochDay = expense.date.toEpochDay(),
                type = TxType.EXPENSE,
            )
            if (existingTx != null) {
                // User already manually recorded this payment: mark seen and avoid duplicate!
                smsRepository.markSeen(hash)
                Log.d(TAG, "Skipped duplicate room expense; already logged as '${existingTx.note}'")
                continue
            }

            val sender = if (userIsPayer) {
                "Room Expense · Paid by ${activeRoommate.displayName} (You)"
            } else {
                "Room Expense · Paid by ${activeRoommate.other.displayName}"
            }

            val suggestion = SmsSuggestion(
                amountMinor = displayAmountMinor,
                merchant = expense.title.ifBlank { "Room Expense" },
                sender = sender,
                epochDay = expense.date.toEpochDay(),
                body = hash,
                receivedAt = expense.timestamp,
                source = SmsSuggestion.SOURCE_ROOM_EXPENSE,
                accountDigits = expense.id,
                isIncome = false,
            )

            val added = smsRepository.insertSuggestion(suggestion)
            if (added) {
                Log.d(TAG, "Queued room expense suggestion: ${expense.title} (€$totalAmount, payer=${expense.paidBy})")
            } else {
                Log.d(TAG, "Skipped duplicate room expense suggestion: ${expense.title}")
            }
        }
    }

    /**
     * Creates a new shared room expense in Firestore and immediately triggers sync.
     */
    suspend fun createExpense(
        title: String,
        amount: Double,
        paidBy: String,
        category: String,
        splitType: String,
        splitOzric: Double,
        splitPawan: Double,
        notes: String = "",
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val docData = hashMapOf<String, Any>(
                "title" to title.trim(),
                "amount" to amount,
                "paidBy" to paidBy,
                "splitType" to splitType,
                "category" to category,
                "timestamp" to Timestamp.now(),
                "splitDetails" to mapOf(
                    "OzricAmount" to splitOzric,
                    "PawanAmount" to splitPawan,
                ),
                "paymentDistribution" to mapOf(
                    "OzricAmount" to (if (paidBy.equals("Ozric", ignoreCase = true)) amount else 0.0),
                    "PawanAmount" to (if (paidBy.equals("Pawan", ignoreCase = true)) amount else 0.0),
                ),
                "isCoin" to false,
                "isSettlement" to false,
                "isLendBorrow" to false,
                "status" to "confirmed",
                "createdBy" to "Ozric",
                "notes" to notes.trim(),
            )

            val docRef = firestore.collection(COLLECTION_EXPENSES).add(docData).await()
            Log.d(TAG, "Created room expense in Firestore with ID: ${docRef.id}")
            syncFromFirebase()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create room expense: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
