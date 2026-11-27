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
        SELECT CASE
            WHEN a.accountType IN ('ASSET', 'EXPENSE')
                THEN COALESCE(SUM(j.debitMinor - j.creditMinor), 0)
            ELSE COALESCE(SUM(j.creditMinor - j.debitMinor), 0)
        END
        FROM accounts a
        LEFT JOIN journal_lines j ON j.accountId = a.id
        WHERE a.id = :id
        GROUP BY a.id
        """
    )
    suspend fun getBalance(id: String): Long

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

    /** Per-bank and per-card accounts created from SMS ("bank_hdfc_1234", "card_icici_9876"). */
    @Query("SELECT * FROM accounts WHERE id LIKE 'bank|_%' ESCAPE '|' OR id LIKE 'card|_%' ESCAPE '|'")
    suspend fun getOwnSmsAccounts(): List<AccountEntity>
}

@Dao
interface HoldingDao {

    @Query("SELECT * FROM holdings ORDER BY assetType, name")
    fun observeAll(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings")
    suspend fun getAll(): List<HoldingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<HoldingEntity>)

    @Query("DELETE FROM holdings WHERE assetType = :assetType")
    suspend fun deleteType(assetType: String)

    @Query("UPDATE holdings SET price = :price, priceDate = :priceDate, name = :name, updatedAt = :now WHERE id = :id")
    suspend fun updatePrice(id: String, price: Double, priceDate: String, name: String, now: Long = System.currentTimeMillis()): Int
}

@Dao
interface MerchantRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules WHERE merchantKey = :key LIMIT 1")
    suspend fun get(key: String): MerchantRuleEntity?

    @Query("SELECT * FROM merchant_rules WHERE kind = :kind")
    suspend fun getAll(kind: String): List<MerchantRuleEntity>
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
            t.fromAccountId AS fromAccountId,
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

    /** Auto-added SMS transactions that might be the other half of a self transfer. */
    @Query(
        """
        SELECT t.* FROM financial_transactions t
        JOIN sms_observations s ON s.id = t.sourceObservationId
        WHERE s.status = 'AUTO_POSTED'
          AND t.type = :type
          AND t.amountMinor = :amountMinor
          AND t.occurredAt >= :fromMillis
          AND t.occurredAt <= :toMillis
        ORDER BY t.occurredAt
        """
    )
    suspend fun findAutoPostedCandidates(
        type: String,
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long
    ): List<FinancialTransactionEntity>

    /** SMS transactions in the given SMS statuses that could be the other half of a movement. */
    @Query(
        """
        SELECT t.* FROM financial_transactions t
        JOIN sms_observations s ON s.id = t.sourceObservationId
        WHERE s.status IN (:statuses)
          AND t.type = :type
          AND t.amountMinor = :amountMinor
          AND t.occurredAt >= :fromMillis
          AND t.occurredAt <= :toMillis
        ORDER BY t.occurredAt
        """
    )
    suspend fun findSmsCandidates(
        statuses: List<String>,
        type: String,
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long
    ): List<FinancialTransactionEntity>

    /** Any transaction (manual or SMS) of these types and amount in a time window. */
    @Query(
        """
        SELECT * FROM financial_transactions
        WHERE type IN (:types)
          AND amountMinor = :amountMinor
          AND occurredAt >= :fromMillis
          AND occurredAt <= :toMillis
        ORDER BY occurredAt
        """
    )
    suspend fun findByAmount(
        types: List<String>,
        amountMinor: Long,
        fromMillis: Long,
        toMillis: Long
    ): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transactions
        WHERE type = 'EXPENSE'
          AND occurredAt >= :fromMillis
          AND occurredAt <= :toMillis
        ORDER BY occurredAt DESC
        """
    )
    suspend fun getExpensesBetween(fromMillis: Long, toMillis: Long): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT t.* FROM financial_transactions t
        JOIN sms_observations s ON s.id = t.sourceObservationId
        WHERE s.sender LIKE 'EMAIL:%'
        ORDER BY t.occurredAt
        """
    )
    suspend fun getEmailTransactions(): List<FinancialTransactionEntity>

    @Query("SELECT * FROM financial_transactions WHERE type IN ('EXPENSE', 'REFUND')")
    suspend fun getSpendingTransactions(): List<FinancialTransactionEntity>

    /** SMS-created expenses currently in a category (used to re-categorise "General"). */
    @Query(
        """
        SELECT t.* FROM financial_transactions t
        JOIN sms_observations s ON s.id = t.sourceObservationId
        WHERE t.type = 'EXPENSE' AND t.categoryAccountId = :categoryId
        """
    )
    suspend fun getSmsExpensesInCategory(categoryId: String): List<FinancialTransactionEntity>

    @Query("UPDATE financial_transactions SET fromAccountId = :fromId, toAccountId = :toId WHERE id = :id")
    suspend fun updateTransferAccounts(id: String, fromId: String, toId: String): Int

    @Query("UPDATE financial_transactions SET categoryAccountId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: String, categoryId: String): Int

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

    /** Re-points one side of a transaction's journal entry, e.g. when its category changes. */
    @Query(
        """
        UPDATE journal_lines SET accountId = :newAccountId
        WHERE accountId = :oldAccountId
          AND journalEntryId IN (
            SELECT id FROM journal_entries WHERE transactionId = :transactionId
          )
        """
    )
    suspend fun moveLine(transactionId: String, oldAccountId: String, newAccountId: String): Int
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

    @Query("SELECT * FROM sms_observations WHERE sender LIKE 'EMAIL:%' ORDER BY receivedAt DESC LIMIT 200")
    fun observeEmails(): Flow<List<SmsObservationEntity>>

    @Query("SELECT * FROM sms_observations WHERE sender NOT LIKE 'EMAIL:%' AND status = 'NEEDS_REVIEW' ORDER BY receivedAt DESC LIMIT 50")
    fun observePendingSmsOnly(): Flow<List<SmsObservationEntity>>

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

    @Query("SELECT * FROM sms_observations WHERE status = 'NEEDS_REVIEW' ORDER BY receivedAt ASC")
    suspend fun getAllPendingOldestFirst(): List<SmsObservationEntity>

    // ---- One-time re-mapping of auto-added SMS (user-checked ones are left alone) ----

    @Query(
        """
        DELETE FROM journal_lines WHERE journalEntryId IN (
            SELECT je.id FROM journal_entries je
            JOIN financial_transactions t ON t.id = je.transactionId
            JOIN sms_observations s ON s.id = t.sourceObservationId
            WHERE s.status = 'AUTO_POSTED'
        )
        """
    )
    suspend fun deleteAutoPostedLines(): Int

    @Query(
        """
        DELETE FROM journal_entries WHERE transactionId IN (
            SELECT t.id FROM financial_transactions t
            JOIN sms_observations s ON s.id = t.sourceObservationId
            WHERE s.status = 'AUTO_POSTED'
        )
        """
    )
    suspend fun deleteAutoPostedEntries(): Int

    @Query(
        """
        DELETE FROM financial_transactions WHERE sourceObservationId IN (
            SELECT id FROM sms_observations WHERE status = 'AUTO_POSTED'
        )
        """
    )
    suspend fun deleteAutoPostedTransactions(): Int

    @Query("UPDATE sms_observations SET status = 'NEEDS_REVIEW' WHERE status IN ('AUTO_POSTED', 'MERGED')")
    suspend fun resetAutoPostedStatus(): Int

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