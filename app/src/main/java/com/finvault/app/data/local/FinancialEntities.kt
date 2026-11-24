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
    indices = [Index("occurredAt"), Index("type")]
)
data class FinancialTransactionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val amountMinor: Long,
    // EXPENSE, INCOME, TRANSFER
    val type: String,
    val fromAccountId: String?,
    val toAccountId: String?,
    val categoryAccountId: String?,
    val occurredAt: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
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
    indices = [Index("journalEntryId"), Index("accountId")]
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
