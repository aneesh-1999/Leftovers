package com.leftovers.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.leftovers.app.data.investment.InvestmentAsset
import com.leftovers.app.data.investment.InvestmentDao
import com.leftovers.app.data.investment.InvestmentTransaction
import com.leftovers.app.data.investment.MarketPriceHistory
import com.leftovers.app.data.investment.MarketPriceSnapshot
import com.leftovers.app.data.investment.WatchlistItem
import com.leftovers.app.data.creditcard.CreditCard
import com.leftovers.app.data.creditcard.CreditCardDao
import com.leftovers.app.data.creditcard.CreditCardStatement
import com.leftovers.app.data.creditcard.CreditCardTransaction
import com.leftovers.app.data.creditcard.StatementPaymentAllocation
import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.RoomExpenseDao

@Database(
    entities = [
        Category::class, Transaction::class, Recurring::class, Goal::class, GoalDeposit::class,
        Account::class, Transfer::class, SmsSuggestion::class, Debt::class, DeletedTransaction::class,
        SeenMessage::class,
        Shift::class, Workplace::class, LeaveEntry::class, WorkplacePayment::class,
        DeletedShift::class, DeletedLeaveEntry::class,
        Recipient::class, Remittance::class, RemittanceStatusHistory::class,
        InvestmentAsset::class, InvestmentTransaction::class, MarketPriceSnapshot::class,
        MarketPriceHistory::class, WatchlistItem::class,
        CreditCard::class, CreditCardStatement::class, CreditCardTransaction::class,
        StatementPaymentAllocation::class,
        RoomExpense::class,
    ],
    version = 23,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun recurringDao(): RecurringDao
    abstract fun goalDao(): GoalDao
    abstract fun accountDao(): AccountDao
    abstract fun transferDao(): TransferDao
    abstract fun smsDao(): SmsDao
    abstract fun backupDao(): BackupDao
    abstract fun debtDao(): DebtDao
    abstract fun deletedDao(): DeletedDao
    abstract fun shiftDao(): ShiftDao
    abstract fun workplaceDao(): WorkplaceDao
    abstract fun leaveDao(): LeaveDao
    abstract fun workplacePaymentDao(): WorkplacePaymentDao
    abstract fun recipientDao(): RecipientDao
    abstract fun remittanceDao(): RemittanceDao
    abstract fun remittanceStatusHistoryDao(): RemittanceStatusHistoryDao
    abstract fun investmentDao(): InvestmentDao
    abstract fun creditCardDao(): CreditCardDao
    abstract fun roomExpenseDao(): RoomExpenseDao


    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "leftovers.db")
                .addCallback(SeedCategories)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}

/**
 * v23 adds optional accountId to debts so money lent/borrowed can be linked to an account
 * (e.g. Cash or Bank) and automatically deducted/adjusted from the account's balance.
 */
private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `accountId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_accountId` ON `debts` (`accountId`)")
    }
}

/**
 * v22 introduces the Shared Room Expenses cache:
 * - room_expenses: local cache for the shared household Firestore ledger (ROOM-EXPANCE)
 */
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `room_expenses` (" +
                "`id` TEXT PRIMARY KEY NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`amount` REAL NOT NULL, " +
                "`paidBy` TEXT NOT NULL, " +
                "`splitType` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`splitOzric` REAL NOT NULL DEFAULT 0.0, " +
                "`splitPawan` REAL NOT NULL DEFAULT 0.0, " +
                "`paidOzric` REAL NOT NULL DEFAULT 0.0, " +
                "`paidPawan` REAL NOT NULL DEFAULT 0.0, " +
                "`isCoin` INTEGER NOT NULL DEFAULT 0, " +
                "`isSettlement` INTEGER NOT NULL DEFAULT 0, " +
                "`isLendBorrow` INTEGER NOT NULL DEFAULT 0, " +
                "`paymentMethod` TEXT, " +
                "`itemizedDetails` TEXT, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'confirmed', " +
                "`createdBy` TEXT, " +
                "`deleteRequested` INTEGER NOT NULL DEFAULT 0, " +
                "`deleteRequestedBy` TEXT, " +
                "`lastSyncedAt` INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_expenses_timestamp` ON `room_expenses` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_expenses_isSettlement` ON `room_expenses` (`isSettlement`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_expenses_isCoin` ON `room_expenses` (`isCoin`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_expenses_isLendBorrow` ON `room_expenses` (`isLendBorrow`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_expenses_status` ON `room_expenses` (`status`)")
    }
}

/**
 * v21 introduces the Credit Card Manager & Payment Reminders feature:
 * - credit_cards: card profiles, limits, issuer, cycle days, min payment formula
 * - credit_card_statements: periodic confirmed billing statements with due dates and balances
 * - credit_card_transactions: purchases, payments, refunds, fees, interest, reversals
 * - statement_payment_allocations: explicit allocation of payments to specific statements
 */
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credit_cards` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`nickname` TEXT NOT NULL, " +
                "`bankIssuer` TEXT NOT NULL, " +
                "`network` TEXT NOT NULL DEFAULT '', " +
                "`lastFourDigits` TEXT NOT NULL DEFAULT '', " +
                "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`creditLimitMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`billingCycleClosingDay` INTEGER NOT NULL DEFAULT 0, " +
                "`paymentDueDay` INTEGER NOT NULL DEFAULT 0, " +
                "`paymentDueDaysAfterClosing` INTEGER NOT NULL DEFAULT 0, " +
                "`minPaymentPercentage` REAL NOT NULL DEFAULT 0.0, " +
                "`minPaymentFlatMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                "`color` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_cards_isArchived` ON `credit_cards` (`isArchived`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credit_card_statements` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`cardId` INTEGER NOT NULL, " +
                "`closingEpochDay` INTEGER NOT NULL, " +
                "`dueEpochDay` INTEGER NOT NULL, " +
                "`statementBalanceMinor` INTEGER NOT NULL, " +
                "`minPaymentDueMinor` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`reference` TEXT NOT NULL DEFAULT '', " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`cardId`) REFERENCES `credit_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_statements_cardId` ON `credit_card_statements` (`cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_statements_dueEpochDay` ON `credit_card_statements` (`dueEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_statements_closingEpochDay` ON `credit_card_statements` (`closingEpochDay`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credit_card_transactions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`cardId` INTEGER NOT NULL, " +
                "`statementId` INTEGER, " +
                "`type` TEXT NOT NULL, " +
                "`dateEpochDay` INTEGER NOT NULL, " +
                "`amountMinor` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`description` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL DEFAULT '', " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`linkedFinanceTxId` INTEGER, " +
                "`linkedAccountId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`cardId`) REFERENCES `credit_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`statementId`) REFERENCES `credit_card_statements`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_transactions_cardId` ON `credit_card_transactions` (`cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_transactions_statementId` ON `credit_card_transactions` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_transactions_dateEpochDay` ON `credit_card_transactions` (`dateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_transactions_type` ON `credit_card_transactions` (`type`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `statement_payment_allocations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`statementId` INTEGER NOT NULL, " +
                "`transactionId` INTEGER NOT NULL, " +
                "`amountMinor` INTEGER NOT NULL, " +
                "`allocatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`statementId`) REFERENCES `credit_card_statements`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`transactionId`) REFERENCES `credit_card_transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_payment_allocations_statementId` ON `statement_payment_allocations` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_payment_allocations_transactionId` ON `statement_payment_allocations` (`transactionId`)")
    }
}

/**
 * v20 introduces the Investment Portfolio feature:
 * - investment_assets: master record of investment assets (NEPSE stocks, Gold, Silver)
 * - investment_transactions: ledger of buy, sell, fee, and valuation transactions
 * - market_price_snapshots: latest cached market quotes with timestamps and sources
 * - market_price_history: historical price points for performance charts
 * - watchlist_items: user watchlist for tracking stocks/metals without holding them
 */
private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `investment_assets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`symbol` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`unit` TEXT NOT NULL, " +
                "`purity` TEXT, " +
                "`category` TEXT, " +
                "`notes` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_assets_symbol_type` ON `investment_assets` (`symbol`, `type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_assets_type` ON `investment_assets` (`type`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `investment_transactions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`assetId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`epochDay` INTEGER NOT NULL, " +
                "`quantity` REAL NOT NULL, " +
                "`unitPriceMinor` INTEGER NOT NULL, " +
                "`feeMinor` INTEGER NOT NULL, " +
                "`totalAmountMinor` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`unit` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "`linkedAccountId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_transactions_assetId` ON `investment_transactions` (`assetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_transactions_epochDay` ON `investment_transactions` (`epochDay`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `market_price_snapshots` (" +
                "`symbol` TEXT PRIMARY KEY NOT NULL, " +
                "`price` REAL NOT NULL, " +
                "`previousClose` REAL, " +
                "`change` REAL, " +
                "`percentChange` REAL, " +
                "`high` REAL, " +
                "`low` REAL, " +
                "`volume` INTEGER, " +
                "`turnover` REAL, " +
                "`trades` INTEGER, " +
                "`marketCap` REAL, " +
                "`companyName` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`isManual` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`tradingDate` TEXT, " +
                "`currency` TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_market_price_snapshots_symbol` ON `market_price_snapshots` (`symbol`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_price_snapshots_timestamp` ON `market_price_snapshots` (`timestamp`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `market_price_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`symbol` TEXT NOT NULL, " +
                "`price` REAL NOT NULL, " +
                "`tradingDate` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`isClosing` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_market_price_history_symbol_tradingDate` ON `market_price_history` (`symbol`, `tradingDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_price_history_symbol_timestamp` ON `market_price_history` (`symbol`, `timestamp`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watchlist_items` (" +
                "`symbol` TEXT PRIMARY KEY NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`assetType` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_watchlist_items_symbol` ON `watchlist_items` (`symbol`)")
    }
}

/**
 * v19 brings in the upstream additions:
 * - seen_messages table for remembering processed SMS/email hashes
 * - isIncome and accountId in sms_suggestions
 * - foreignMinor and foreignCurrency in transactions & deleted_transactions (travel mode)
 */
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `seen_messages` (`hash` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, PRIMARY KEY(`hash`))")
        db.execSQL("ALTER TABLE `sms_suggestions` ADD COLUMN `isIncome` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `sms_suggestions` ADD COLUMN `accountId` INTEGER")
        for (table in listOf("transactions", "deleted_transactions")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `foreignMinor` INTEGER")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `foreignCurrency` TEXT")
        }
        db.execSQL("INSERT OR IGNORE INTO `seen_messages` (`hash`, `seenAt`) SELECT `body`, `receivedAt` FROM `sms_suggestions` WHERE length(`body`) = 64")
    }
}

/** v18 adds dueDateEpochDay and reminderEnabled to debts for money owed return reminders. */
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `dueDateEpochDay` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `reminderEnabled` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v17 adds remittances, recipients, and status history for Manual Remittance Tracker. */
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recipients` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`relationship` TEXT, " +
                "`country` TEXT NOT NULL DEFAULT 'Nepal', " +
                "`preferredCurrency` TEXT NOT NULL DEFAULT 'NPR', " +
                "`preferredProvider` TEXT, " +
                "`bankOrWalletLabel` TEXT, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`archived` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL" +
            ")",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `remittances` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipientId` INTEGER, " +
                "`recipientName` TEXT NOT NULL, " +
                "`recipientCountry` TEXT NOT NULL DEFAULT 'Nepal', " +
                "`recipientBankOrWallet` TEXT, " +
                "`transferDateEpochDay` INTEGER NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`sendAmountMinor` INTEGER NOT NULL, " +
                "`sendCurrency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`feeAmountMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`feeCurrency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`feeMethod` TEXT NOT NULL DEFAULT 'SEPARATE', " +
                "`exchangeRate` REAL NOT NULL DEFAULT 0.0, " +
                "`expectedReceiveAmountMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`actualReceiveAmountMinor` INTEGER, " +
                "`receiveCurrency` TEXT NOT NULL DEFAULT 'NPR', " +
                "`status` TEXT NOT NULL DEFAULT 'SENT', " +
                "`receivedDateEpochDay` INTEGER, " +
                "`sendingAccountId` INTEGER, " +
                "`financeTransactionId` INTEGER, " +
                "`referenceNumber` TEXT, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`receiptPath` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipientId`) REFERENCES `recipients`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(`sendingAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(`financeTransactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL" +
            ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittances_recipientId` ON `remittances` (`recipientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittances_transferDateEpochDay` ON `remittances` (`transferDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittances_status` ON `remittances` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittances_sendingAccountId` ON `remittances` (`sendingAccountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittances_financeTransactionId` ON `remittances` (`financeTransactionId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `remittance_status_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`remittanceId` INTEGER NOT NULL, " +
                "`fromStatus` TEXT, " +
                "`toStatus` TEXT NOT NULL, " +
                "`changedAt` INTEGER NOT NULL, " +
                "`note` TEXT, " +
                "FOREIGN KEY(`remittanceId`) REFERENCES `remittances`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
            ")",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_remittance_status_history_remittanceId` ON `remittance_status_history` (`remittanceId`)")
    }
}

/** v16 adds deleted_shifts and deleted_leave_entries to support full recovery in Deleted entries. */
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deleted_shifts` (" +
                "`id` INTEGER NOT NULL, " +
                "`workplaceId` INTEGER, " +
                "`title` TEXT NOT NULL, " +
                "`workplace` TEXT, " +
                "`startEpochMillis` INTEGER NOT NULL, " +
                "`endEpochMillis` INTEGER NOT NULL, " +
                "`breakDurationMinutes` INTEGER NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`)" +
            ")",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deleted_leave_entries` (" +
                "`id` INTEGER NOT NULL, " +
                "`workplaceId` INTEGER, " +
                "`type` TEXT NOT NULL, " +
                "`startDateEpochDay` INTEGER NOT NULL, " +
                "`endDateEpochDay` INTEGER NOT NULL, " +
                "`isFullDay` INTEGER NOT NULL, " +
                "`customHours` REAL, " +
                "`paymentStatus` TEXT NOT NULL, " +
                "`paidRatePercent` REAL NOT NULL, " +
                "`fixedPayAmountMinor` INTEGER, " +
                "`reason` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`)" +
            ")",
        )
    }
}

/** v15 adds workplaces, leave tracking, workplace payments, and links shifts to workplaces. */
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `shifts` ADD COLUMN `workplaceId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shifts_workplaceId` ON `shifts` (`workplaceId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workplaces` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL DEFAULT '', " +
                "`address` TEXT, " +
                "`contactPerson` TEXT, " +
                "`contactDetails` TEXT, " +
                "`employmentType` TEXT NOT NULL DEFAULT 'Full-time', " +
                "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`basicHourlyRateCents` INTEGER NOT NULL DEFAULT 573, " +
                "`basicMonthlyHours` REAL NOT NULL DEFAULT 173.33, " +
                "`overtimeMultiplier` REAL NOT NULL DEFAULT 1.5, " +
                "`paidBreaks` INTEGER NOT NULL DEFAULT 0, " +
                "`leaveCountsTowardsThreshold` INTEGER NOT NULL DEFAULT 0, " +
                "`fixedMonthlyAllowanceMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`monthlyDeductionMinor` INTEGER NOT NULL DEFAULT 0, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`archived` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `leave_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workplaceId` INTEGER, " +
                "`type` TEXT NOT NULL, " +
                "`startDateEpochDay` INTEGER NOT NULL, " +
                "`endDateEpochDay` INTEGER NOT NULL, " +
                "`isFullDay` INTEGER NOT NULL DEFAULT 1, " +
                "`customHours` REAL, " +
                "`paymentStatus` TEXT NOT NULL DEFAULT 'UNPAID', " +
                "`paidRatePercent` REAL NOT NULL DEFAULT 100.0, " +
                "`fixedPayAmountMinor` INTEGER, " +
                "`reason` TEXT NOT NULL DEFAULT '', " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_leave_entries_startDateEpochDay` ON `leave_entries` (`startDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_leave_entries_endDateEpochDay` ON `leave_entries` (`endDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_leave_entries_workplaceId` ON `leave_entries` (`workplaceId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workplace_payments` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workplaceId` INTEGER NOT NULL, " +
                "`payPeriodMonth` TEXT NOT NULL, " +
                "`paymentEpochDay` INTEGER NOT NULL, " +
                "`amountMinor` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                "`payslipReference` TEXT NOT NULL DEFAULT '', " +
                "`note` TEXT NOT NULL DEFAULT '', " +
                "`reviewed` INTEGER NOT NULL DEFAULT 1, " +
                "`financeTransactionId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workplace_payments_workplaceId` ON `workplace_payments` (`workplaceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workplace_payments_payPeriodMonth` ON `workplace_payments` (`payPeriodMonth`)")
    }
}

/** v14 adds work shifts for My Roster. */
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shifts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`workplace` TEXT, " +
                "`startEpochMillis` INTEGER NOT NULL, " +
                "`endEpochMillis` INTEGER NOT NULL, " +
                "`breakDurationMinutes` INTEGER NOT NULL DEFAULT 0, " +
                "`notes` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'SCHEDULED', " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shifts_startEpochMillis` ON `shifts` (`startEpochMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shifts_endEpochMillis` ON `shifts` (`endEpochMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shifts_status` ON `shifts` (`status`)")
    }
}

/** v13 lets each account specify its own currency. */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `currency` TEXT")
    }
}

/** v12 lets SMS payments go to the matching account, by its last digits. */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `smsDigits` TEXT")
        db.execSQL("ALTER TABLE `sms_suggestions` ADD COLUMN `accountDigits` TEXT")
    }
}

/** v11 lets money lent or borrowed carry a photo. */
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `receiptPath` TEXT")
    }
}

/** v10 lets a subscription or recurring income go to a chosen account. */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `accountId` INTEGER")
    }
}

/** v9 keeps deleted entries so they can be restored. */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deleted_transactions` (`id` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `accountId` INTEGER, `receiptPath` TEXT, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** v8 lets detected payments come from email as well as SMS. */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sms_suggestions` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'sms'")
    }
}

/** v7 adds money lent and borrowed. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `person` TEXT NOT NULL, " +
                "`amountMinor` INTEGER NOT NULL, `note` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, `settled` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
        )
    }
}

/** v2 adds recurring payments and savings goals. Existing data is kept. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `type` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, " +
                "`dayOfMonth` INTEGER NOT NULL, `startMonth` TEXT NOT NULL, `lastPostedMonth` TEXT, `active` INTEGER NOT NULL, " +
                "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_categoryId` ON `recurring` (`categoryId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`emoji` TEXT NOT NULL, `color` INTEGER NOT NULL, `targetMinor` INTEGER NOT NULL, `targetMonth` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal_deposits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`goalId` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_deposits_goalId` ON `goal_deposits` (`goalId`)")
    }
}

/** v4 adds accounts, transfers, receipt photos and SMS suggestions. Existing entries go to "Cash". */
/** v6 adds yearly subscriptions; existing ones stay monthly. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `everyMonths` INTEGER NOT NULL DEFAULT 1")
    }
}

/** v5 lets income categories add to the month's budget; on for everything except Salary. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `addsToBudget` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `categories` SET `addsToBudget` = 1 WHERE `type` = 'INCOME' AND `name` <> 'Salary'")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `openingMinor` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromAccountId` INTEGER NOT NULL, " +
                "`toAccountId` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`fromAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`toAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_fromAccountId` ON `transfers` (`fromAccountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_toAccountId` ON `transfers` (`toAccountId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sms_suggestions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amountMinor` INTEGER NOT NULL, " +
                "`merchant` TEXT NOT NULL, `sender` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, `body` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL)",
        )
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `accountId` INTEGER")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `receiptPath` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
        seedAccounts(db)
        db.execSQL("UPDATE transactions SET accountId = 1")
    }
}

private fun seedAccounts(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    DefaultAccounts.forEach { a ->
        db.execSQL(
            "INSERT INTO accounts (name, icon, color, openingMinor, createdAt) VALUES (?, ?, ?, 0, ?)",
            arrayOf<Any>(a.name, a.icon, a.color, now),
        )
    }
}

val DefaultAccounts = listOf(
    Account(name = "Cash", icon = "banknote", color = Palette.MINT),
    Account(name = "Bank", icon = "landmark", color = Palette.SKY),
)

internal object SeedCategories : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        seedAccounts(db)
        DefaultCategories.forEach { c ->
            db.execSQL(
                "INSERT INTO categories (name, emoji, color, type, addsToBudget) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(c.name, c.emoji, c.color, c.type.name, if (c.addsToBudget) 1 else 0),
            )
        }
    }
}

/** v3 swaps emoji for line icons and moves colours to the refined palette. No schema change. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LegacyEmojiToIcon.forEach { (emoji, key) ->
            db.execSQL("UPDATE categories SET emoji = ? WHERE emoji = ?", arrayOf<Any>(key, emoji))
            db.execSQL("UPDATE goals SET emoji = ? WHERE emoji = ?", arrayOf<Any>(key, emoji))
        }
        LegacyColorToPalette.forEach { (old, new) ->
            db.execSQL("UPDATE categories SET color = ? WHERE color = ?", arrayOf<Any>(new, old))
            db.execSQL("UPDATE goals SET color = ? WHERE color = ?", arrayOf<Any>(new, old))
        }
        db.execSQL("UPDATE transactions SET note = replace(note, '🔁 ', '') WHERE note LIKE '🔁 %'")
    }
}

private val LegacyEmojiToIcon = mapOf(
    "🍔" to "utensils", "🍕" to "utensils", "☕" to "coffee", "🛒" to "shopping-cart", "🚕" to "taxi",
    "⛽" to "fuel", "🚌" to "bus", "🛍️" to "shopping-bag", "👕" to "shirt", "🧾" to "receipt", "💡" to "zap",
    "📱" to "smartphone", "🏠" to "house", "🔧" to "wrench", "🎬" to "clapperboard", "🎮" to "gamepad",
    "🎵" to "music", "💊" to "heart-pulse", "🏋️" to "dumbbell", "📚" to "graduation-cap", "✈️" to "plane",
    "🏨" to "building", "🎁" to "gift", "🐶" to "paw", "👶" to "baby", "💄" to "sparkles", "🍺" to "beer",
    "🚬" to "package", "💳" to "credit-card", "📦" to "package", "💼" to "briefcase", "💻" to "laptop",
    "📈" to "trending-up", "🎉" to "party", "💰" to "coins", "🏦" to "landmark", "🪙" to "coins", "🧧" to "gift",
    "🖥️" to "monitor", "🚗" to "car", "🏍️" to "bike", "💍" to "gem", "🎓" to "graduation-cap",
    "📷" to "camera", "🎸" to "guitar", "🛡️" to "shield",
)

private val LegacyColorToPalette = mapOf(
    0xFFFF7043 to Palette.PEACH, 0xFFEF5350 to Palette.CORAL, 0xFFEC407A to Palette.ROSE,
    0xFFAB47BC to Palette.LILAC, 0xFF7E57C2 to Palette.IRIS, 0xFF5C6BC0 to Palette.IRIS,
    0xFF42A5F5 to Palette.SKY, 0xFF29B6F6 to Palette.TEAL, 0xFF26A69A to Palette.TEAL,
    0xFF66BB6A to Palette.MINT, 0xFF9CCC65 to Palette.LIME, 0xFFFFCA28 to Palette.AMBER,
    0xFFFFB300 to Palette.AMBER, 0xFF8D6E63 to Palette.SAND, 0xFF78909C to Palette.SLATE,
    0xFF43A047 to Palette.MINT, 0xFF00ACC1 to Palette.SKY, 0xFF7CB342 to Palette.LIME,
    0xFF8E24AA to Palette.LILAC,
)

/** Soft colours tuned to sit on dark glass without shouting. */
object Palette {
    const val PEACH = 0xFFF4A68AL
    const val AMBER = 0xFFF2C46DL
    const val LIME = 0xFFC8E07AL
    const val MINT = 0xFF7FD9A8L
    const val TEAL = 0xFF6FD0D0L
    const val SKY = 0xFF7DB7F5L
    const val IRIS = 0xFF9C9CF8L
    const val LILAC = 0xFFC59AF2L
    const val ROSE = 0xFFF291B4L
    const val CORAL = 0xFFF27E7EL
    const val SAND = 0xFFD9B99BL
    const val SLATE = 0xFFA3AEBDL

    val all = listOf(PEACH, AMBER, LIME, MINT, TEAL, SKY, IRIS, LILAC, ROSE, CORAL, SAND, SLATE)
}

val DefaultCategories = listOf(
    Category(name = "Food & Dining", emoji = "utensils", color = Palette.PEACH, type = TxType.EXPENSE),
    Category(name = "Groceries", emoji = "shopping-cart", color = Palette.MINT, type = TxType.EXPENSE),
    Category(name = "Transport", emoji = "taxi", color = Palette.SKY, type = TxType.EXPENSE),
    Category(name = "Shopping", emoji = "shopping-bag", color = Palette.ROSE, type = TxType.EXPENSE),
    Category(name = "Bills & Utilities", emoji = "receipt", color = Palette.AMBER, type = TxType.EXPENSE),
    Category(name = "Rent", emoji = "house", color = Palette.SAND, type = TxType.EXPENSE),
    Category(name = "Entertainment", emoji = "clapperboard", color = Palette.LILAC, type = TxType.EXPENSE),
    Category(name = "Health", emoji = "heart-pulse", color = Palette.CORAL, type = TxType.EXPENSE),
    Category(name = "Education", emoji = "graduation-cap", color = Palette.IRIS, type = TxType.EXPENSE),
    Category(name = "Travel", emoji = "plane", color = Palette.TEAL, type = TxType.EXPENSE),
    Category(name = "Gifts", emoji = "gift", color = Palette.LIME, type = TxType.EXPENSE),
    Category(name = "Other", emoji = "package", color = Palette.SLATE, type = TxType.EXPENSE),
    Category(name = "Salary", emoji = "briefcase", color = Palette.MINT, type = TxType.INCOME),
    Category(name = "Freelance", emoji = "laptop", color = Palette.SKY, type = TxType.INCOME, addsToBudget = true),
    Category(name = "Investments", emoji = "trending-up", color = Palette.LIME, type = TxType.INCOME, addsToBudget = true),
    Category(name = "Gifts", emoji = "party", color = Palette.AMBER, type = TxType.INCOME, addsToBudget = true),
    Category(name = "Other", emoji = "coins", color = Palette.LILAC, type = TxType.INCOME, addsToBudget = true),
)

/** Every upgrade step, oldest first; also used by the migration tests. */
internal val ALL_MIGRATIONS: Array<Migration>
    get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)

