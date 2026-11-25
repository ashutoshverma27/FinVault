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

    @Query(
        """
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
        """
    )
    fun observeAccountBalances(): Flow<List<AccountBalance>>
}

@Dao
interface TransactionDao {

    @Insert
    suspend fun insertTransaction(transaction: FinancialTransactionEntity)

    @Query(
        """
        SELECT * FROM financial_transactions
        ORDER BY occurredAt DESC, createdAt DESC
        """
    )
    fun observeTransactions(): Flow<List<FinancialTransactionEntity>>

    @Query(
        """
        SELECT
            t.id AS id,
            t.title AS title,
            t.amountMinor AS amountMinor,
            t.type AS type,
            t.occurredAt AS occurredAt,
            t.note AS note,
            t.sourceObservationId AS sourceObservationId,
            t.categoryAccountId AS categoryId,
            t.toAccountId AS toAccountId,
            c.name AS categoryName,
            f.name AS fromName,
            d.name AS toName
        FROM financial_transactions t
        LEFT JOIN accounts c ON c.id = t.categoryAccountId
        LEFT JOIN accounts f ON f.id = t.fromAccountId
        LEFT JOIN accounts d ON d.id = t.toAccountId
        WHERE t.occurredAt >= :startMillis
          AND t.occurredAt < :endMillis
        ORDER BY t.occurredAt DESC, t.createdAt DESC
        """
    )
    fun observeTransactionItems(
        startMillis: Long,
        endMillis: Long
    ): Flow<List<TransactionListItem>>

    @Query("SELECT * FROM financial_transactions WHERE id = :id LIMIT 1")
    suspend fun getTransaction(id: String): FinancialTransactionEntity?

    @Query("SELECT id FROM financial_transactions WHERE sourceObservationId = :smsId LIMIT 1")
    suspend fun getTransactionIdForSms(smsId: String): String?

    @Query("DELETE FROM financial_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String): Int
}

@Dao
interface LedgerDao {

    @Insert
    suspend fun insertJournalEntry(entry: JournalEntryEntity)

    @Insert
    suspend fun insertJournalLines(lines: List<JournalLineEntity>)

    @Query(
        """
        SELECT COALESCE(SUM(debitMinor), 0) =
               COALESCE(SUM(creditMinor), 0)
        FROM journal_lines
        WHERE journalEntryId = :entryId
        """
    )
    suspend fun isEntryBalanced(entryId: String): Boolean

    @Query(
        """
        DELETE FROM journal_lines
        WHERE journalEntryId IN (
            SELECT id FROM journal_entries WHERE transactionId = :transactionId
        )
        """
    )
    suspend fun deleteLinesForTransaction(transactionId: String): Int

    @Query("DELETE FROM journal_entries WHERE transactionId = :transactionId")
    suspend fun deleteEntryForTransaction(transactionId: String): Int
}

@Dao
interface SmsObservationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SmsObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SmsObservationEntity>): List<Long>

    @Query(
        """
    SELECT * FROM sms_observations
    WHERE status = 'NEEDS_REVIEW'
    ORDER BY receivedAt DESC
    LIMIT 50
    """
    )
    fun observePending(): Flow<List<SmsObservationEntity>>

    @Query("SELECT COUNT(*) FROM sms_observations WHERE status = 'NEEDS_REVIEW'")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM sms_observations
        WHERE status = 'AUTO_POSTED'
        ORDER BY receivedAt DESC
        LIMIT 50
        """
    )
    fun observeAutoPosted(): Flow<List<SmsObservationEntity>>

    @Query("SELECT COUNT(*) FROM sms_observations WHERE status = 'AUTO_POSTED'")
    fun observeAutoPostedCount(): Flow<Int>

    @Query("UPDATE sms_observations SET status = 'POSTED' WHERE status = 'AUTO_POSTED'")
    suspend fun markAllAutoPostedReviewed(): Int

    @Query("SELECT * FROM sms_observations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SmsObservationEntity?

    @Query("SELECT * FROM sms_observations WHERE status = 'NEEDS_REVIEW'")
    suspend fun getAllPending(): List<SmsObservationEntity>

    /**
     * Candidates for content-based de-duplication. The live receiver and the
     * inbox scan can see slightly different timestamps for the same message.
     */
    @Query(
        """
        SELECT * FROM sms_observations
        WHERE receivedAt >= :fromMillis
          AND receivedAt <= :toMillis
          AND suggestedAmountMinor = :amountMinor
        """
    )
    suspend fun findNearby(
        fromMillis: Long,
        toMillis: Long,
        amountMinor: Long
    ): List<SmsObservationEntity>

    @Query(
        """
        UPDATE sms_observations
        SET suggestedAmountMinor = :amountMinor,
            suggestedType = :type,
            suggestedTitle = :title,
            confidence = :confidence,
            reviewNote = :reviewNote,
            manualOnly = :manualOnly
        WHERE id = :id
        """
    )
    suspend fun updateSuggestion(
        id: String,
        amountMinor: Long,
        type: String,
        title: String,
        confidence: Int,
        reviewNote: String,
        manualOnly: Boolean
    ): Int

    @Query(
        """
        UPDATE sms_observations
        SET status = :status
        WHERE id = :id
        """
    )
    suspend fun updateStatus(id: String, status: String): Int

    @Query(
        """
        SELECT * FROM sms_observations
        WHERE body != ''
          AND body NOT LIKE 'FV-SMS-V1:%'
        LIMIT 100
        """
    )
    suspend fun getUnencryptedBodyBatch(): List<SmsObservationEntity>

    @Query(
        """
        UPDATE sms_observations
        SET body = :encryptedBody
        WHERE id = :id
        """
    )
    suspend fun updateStoredBody(
        id: String,
        encryptedBody: String
    ): Int
}