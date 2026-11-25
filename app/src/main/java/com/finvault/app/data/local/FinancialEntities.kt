package com.finvault.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    // ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
    val accountType: String,
    val currency: String = "INR",
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "financial_transactions",
    indices = [
        Index("occurredAt"),
        Index("type"),
        Index(value = ["sourceObservationId"], unique = true)
    ]
)
data class FinancialTransactionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val amountMinor: Long,
    // EXPENSE, INCOME, TRANSFER, ADJUSTMENT (balance correction against opening equity)
    val type: String,
    val fromAccountId: String?,
    val toAccountId: String?,
    val categoryAccountId: String?,
    val occurredAt: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),

    // Links an approved SMS observation to exactly one ledger transaction.
    val sourceObservationId: String? = null
)

@Entity(
    tableName = "journal_entries",
    foreignKeys = [
        ForeignKey(
            entity = FinancialTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["transactionId"], unique = true)]
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val description: String,
    val postedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "journal_lines",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalEntryId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("journalEntryId"),
        Index("accountId")
    ]
)
data class JournalLineEntity(
    @PrimaryKey val id: String,
    val journalEntryId: String,
    val accountId: String,
    val debitMinor: Long,
    val creditMinor: Long
)

data class AccountBalance(
    val accountId: String,
    val name: String,
    val accountType: String,
    val balanceMinor: Long
)

/**
 * Stores only SMS messages that look like financial transactions.
 * Non-financial messages and OTP messages are not stored.
 */
@Entity(
    tableName = "sms_observations",
    indices = [
        Index("status"),
        Index("receivedAt")
    ]
)
data class SmsObservationEntity(
    @PrimaryKey
    val id: String,

    val sender: String,
    val body: String,
    val receivedAt: Long,

    val suggestedAmountMinor: Long,
    val suggestedType: String,
    val suggestedTitle: String,

    val confidence: Int,
    val reviewNote: String,
    val manualOnly: Boolean,

    // NEEDS_REVIEW, AUTO_POSTED (in ledger, not yet checked), POSTED, IGNORED,
    // MERGED (other half of a self transfer that is recorded on its partner SMS)
    val status: String = "NEEDS_REVIEW"
)

/** A transaction joined with the display names of its accounts. */
data class TransactionListItem(
    val id: String,
    val title: String,
    val amountMinor: Long,
    val type: String,
    val occurredAt: Long,
    val note: String,
    val sourceObservationId: String?,
    val categoryId: String?,
    val toAccountId: String?,
    val categoryName: String?,
    val fromName: String?,
    val toName: String?
)
