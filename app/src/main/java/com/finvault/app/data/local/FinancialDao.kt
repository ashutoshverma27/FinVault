package com.finvault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts ORDER BY accountType, name")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccount(id: String): AccountEntity?

    @Query("""
        SELECT
            a.id AS accountId,
            a.name AS name,
            a.accountType AS accountType,
            CASE
                WHEN a.accountType IN ('ASSET', 'EXPENSE')
                    THEN COALESCE(SUM(j.debitMinor - j.creditMinor), 0)
                ELSE COALESCE(SUM(j.creditMinor - j.debitMinor), 0)
            END AS balanceMinor
        FROM accounts a
        LEFT JOIN journal_lines j ON j.accountId = a.id
        GROUP BY a.id, a.name, a.accountType
        ORDER BY a.accountType, a.name
    """)
    fun observeAccountBalances(): Flow<List<AccountBalance>>
}

@Dao
interface TransactionDao {
    @Insert
    suspend fun insertTransaction(transaction: FinancialTransactionEntity)

    @Query("""
        SELECT * FROM financial_transactions
        ORDER BY occurredAt DESC, createdAt DESC
    """)
    fun observeTransactions(): Flow<List<FinancialTransactionEntity>>
}

@Dao
interface LedgerDao {
    @Insert
    suspend fun insertJournalEntry(entry: JournalEntryEntity)

    @Insert
    suspend fun insertJournalLines(lines: List<JournalLineEntity>)

    @Query("""
        SELECT COALESCE(SUM(debitMinor), 0) = COALESCE(SUM(creditMinor), 0)
        FROM journal_lines
        WHERE journalEntryId = :entryId
    """)
    suspend fun isEntryBalanced(entryId: String): Boolean
}
