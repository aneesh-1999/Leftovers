package com.leftovers.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Transfer lifecycle status for a remittance record. */
enum class RemittanceStatus(val label: String) {
    PLANNED("Planned"),
    SENT("Sent"),
    PROCESSING("Processing"),
    RECEIVED("Received"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    REFUNDED("Refunded");

    val isTerminal: Boolean get() = this == RECEIVED || this == FAILED || this == CANCELLED || this == REFUNDED
    val isSuccessful: Boolean get() = this == RECEIVED
    val isPending: Boolean get() = this == SENT || this == PROCESSING
}

/** Whether the transfer fee is charged on top or deducted from the principal amount sent. */
enum class FeeMethod(val label: String, val description: String) {
    SEPARATE("Charged separately", "Fee is paid in addition to the principal"),
    DEDUCTED_FROM_PRINCIPAL("Deducted from principal", "Fee is deducted from the principal before currency conversion"),
}

/** A saved person or account you send money to. */
@Entity(tableName = "recipients")
data class Recipient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relationship: String? = null,
    val country: String = "Nepal",
    val preferredCurrency: String = "NPR",
    val preferredProvider: String? = null,
    val bankOrWalletLabel: String? = null,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A record of an individual manual remittance transfer. */
@Entity(
    tableName = "remittances",
    foreignKeys = [
        ForeignKey(
            entity = Recipient::class,
            parentColumns = ["id"],
            childColumns = ["recipientId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["sendingAccountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["financeTransactionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("recipientId"),
        Index("transferDateEpochDay"),
        Index("status"),
        Index("sendingAccountId"),
        Index("financeTransactionId"),
    ],
)
data class Remittance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientId: Long? = null,
    val recipientName: String,
    val recipientCountry: String = "Nepal",
    val recipientBankOrWallet: String? = null,
    val transferDateEpochDay: Long,
    val provider: String,
    /** Principal sending amount in minor units (e.g. 20000 = €200.00). */
    val sendAmountMinor: Long,
    val sendCurrency: String = "EUR",
    /** Transfer fee in minor units (e.g. 150 = €1.50). */
    val feeAmountMinor: Long = 0L,
    val feeCurrency: String = "EUR",
    val feeMethod: FeeMethod = FeeMethod.SEPARATE,
    /** Exchange rate: 1 unit of sending currency = X units of receiving currency. */
    val exchangeRate: Double = 0.0,
    /** Expected receiving amount in receiving currency minor units (e.g. 2940000 = NPR 29,400.00). */
    val expectedReceiveAmountMinor: Long = 0L,
    /** Actual amount received in receiving currency minor units; null until confirmed. */
    val actualReceiveAmountMinor: Long? = null,
    val receiveCurrency: String = "NPR",
    val status: RemittanceStatus = RemittanceStatus.SENT,
    val receivedDateEpochDay: Long? = null,
    /** Optional link to a local finance account. */
    val sendingAccountId: Long? = null,
    /** Optional link to an existing finance transaction. */
    val financeTransactionId: Long? = null,
    val referenceNumber: String? = null,
    val notes: String = "",
    val receiptPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val transferDate: LocalDate get() = LocalDate.ofEpochDay(transferDateEpochDay)
    val receivedDate: LocalDate? get() = receivedDateEpochDay?.let { LocalDate.ofEpochDay(it) }
}

/** Record of status transitions for audit trail and history. */
@Entity(
    tableName = "remittance_status_history",
    foreignKeys = [
        ForeignKey(
            entity = Remittance::class,
            parentColumns = ["id"],
            childColumns = ["remittanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("remittanceId")],
)
data class RemittanceStatusHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remittanceId: Long,
    val fromStatus: RemittanceStatus?,
    val toStatus: RemittanceStatus,
    val changedAt: Long = System.currentTimeMillis(),
    val note: String? = null,
)

/** Recipient with aggregated statistics. */
data class RecipientWithStats(
    val recipient: Recipient,
    val transferCount: Int,
    val totalSentEurMinor: Long,
    val totalReceivedNprMinor: Long,
    val lastTransferDateEpochDay: Long?,
)
