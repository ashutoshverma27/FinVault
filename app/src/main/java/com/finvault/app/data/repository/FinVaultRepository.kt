package com.finvault.app.data.repository

import androidx.room.withTransaction
import com.finvault.app.data.local.*
import com.finvault.app.domain.ledger.TransactionCommand
import com.finvault.app.sms.SmsBodyCrypto
import com.finvault.app.sms.SmsTransactionMapper
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FinVaultRepository(private val database: FinVaultDatabase) {

    companion object {
        const val CASH = "account_cash"
        const val BANK = "account_primary_bank"
        const val CREDIT_CARD = "account_credit_card"

        const val GENERAL_EXPENSE = "expense_general"
        const val FOOD_EXPENSE = "expense_food"
        const val HOUSING_EXPENSE = "expense_housing"
        const val TRANSPORT_EXPENSE = "expense_transport"
        const val SHOPPING_EXPENSE = "expense_shopping"

        const val SALARY_INCOME = "income_salary"
        const val OTHER_INCOME = "income_other"

        const val OPENING_EQUITY = "equity_opening"

        const val INVESTMENTS = "account_investments"
    }

    fun observeBalances(): Flow<List<AccountBalance>> =
        database.accountDao().observeAccountBalances()

    fun observeTransactions(): Flow<List<FinancialTransactionEntity>> =
        database.transactionDao().observeTransactions()

    fun observeAccounts(): Flow<List<AccountEntity>> =
        database.accountDao().observeAccounts()

    fun observePendingSms(): Flow<List<SmsObservationEntity>> =
        database.smsObservationDao().observePending()

    fun observeAutoPostedSms(): Flow<List<SmsObservationEntity>> =
        database.smsObservationDao().observeAutoPosted()

    fun observeAutoPostedSmsCount(): Flow<Int> =
        database.smsObservationDao().observeAutoPostedCount()

    /**
     * Adds a detected SMS straight to the ledger using the parser's suggestions.
     * It stays in the "auto-added" list until the user confirms, edits or removes it.
     * Returns false if the SMS needs manual input (e.g. refunds).
     */
    suspend fun autoPostSms(item: SmsObservationEntity): Boolean {
        if (item.status != "NEEDS_REVIEW") return false
        val command = SmsTransactionMapper.command(item, SmsBodyCrypto.decrypt(item.body)) ?: return false
        return runCatching { recordInternal(command, item.id, "AUTO_POSTED") }.isSuccess
    }

    /** Auto-adds every SMS still waiting in the review queue. */
    suspend fun autoPostPendingSms(): Int = withContext(Dispatchers.IO) {
        database.smsObservationDao().getAllPending().count { autoPostSms(it) }
    }

    suspend fun autoPostSmsById(id: String): Boolean {
        val item = database.smsObservationDao().getById(id) ?: return false
        return autoPostSms(item)
    }

    /** User checked an auto-added SMS and it's correct. */
    suspend fun confirmSms(id: String) {
        database.smsObservationDao().updateStatus(id, "POSTED")
    }

    suspend fun confirmAllAutoPosted() {
        database.smsObservationDao().markAllAutoPostedReviewed()
    }

    /** Removes the ledger entry created from an SMS and ignores the SMS. */
    suspend fun removeSmsTransaction(smsId: String) {
        database.withTransaction {
            database.transactionDao().getTransactionIdForSms(smsId)?.let { deleteTransaction(it) }
            database.smsObservationDao().updateStatus(smsId, "IGNORED")
        }
    }

    /** Replaces the ledger entry created from an SMS with an edited one. */
    suspend fun replaceSmsTransaction(smsId: String, command: TransactionCommand) {
        database.withTransaction {
            database.transactionDao().getTransactionIdForSms(smsId)?.let { deleteTransaction(it) }
            recordInternal(command, smsId, "POSTED")
        }
    }

    fun observePendingSmsCount(): Flow<Int> =
        database.smsObservationDao().observePendingCount()

    fun observeTransactionItems(): Flow<List<TransactionListItem>> =
        database.transactionDao().observeTransactionItems(0L, Long.MAX_VALUE)

    fun observeTransactionItems(startMillis: Long, endMillis: Long): Flow<List<TransactionListItem>> =
        database.transactionDao().observeTransactionItems(startMillis, endMillis)

    /**
     * Removes a transaction and its journal entry. If it was created from an
     * SMS, that SMS goes back to the review queue.
     */
    suspend fun deleteTransaction(transactionId: String) {
        database.withTransaction {
            val transactionDao = database.transactionDao()
            val ledgerDao = database.ledgerDao()

            val transaction = transactionDao.getTransaction(transactionId)
                ?: error("Transaction not found.")

            ledgerDao.deleteLinesForTransaction(transactionId)
            ledgerDao.deleteEntryForTransaction(transactionId)

            check(transactionDao.deleteTransaction(transactionId) == 1) {
                "Could not delete the transaction."
            }

            transaction.sourceObservationId?.let { smsId ->
                database.smsObservationDao().updateStatus(smsId, "NEEDS_REVIEW")
            }
        }
    }

    /** Undo for [markSmsIgnored]. */
    suspend fun restoreSms(id: String) {
        database.smsObservationDao().updateStatus(id, "NEEDS_REVIEW")
    }

    suspend fun seedDefaults() {
        database.accountDao().insertAccounts(
            listOf(
                AccountEntity(CASH, "Cash Wallet", "ASSET", isSystem = true),
                AccountEntity(BANK, "Primary Bank", "ASSET", isSystem = true),
                AccountEntity(CREDIT_CARD, "Credit Card", "LIABILITY", isSystem = true),

                AccountEntity(GENERAL_EXPENSE, "General Expense", "EXPENSE", isSystem = true),
                AccountEntity(FOOD_EXPENSE, "Food & Dining", "EXPENSE", isSystem = true),
                AccountEntity(HOUSING_EXPENSE, "Housing", "EXPENSE", isSystem = true),
                AccountEntity(TRANSPORT_EXPENSE, "Transport", "EXPENSE", isSystem = true),
                AccountEntity(SHOPPING_EXPENSE, "Shopping", "EXPENSE", isSystem = true),

                AccountEntity(SALARY_INCOME, "Salary", "INCOME", isSystem = true),
                AccountEntity(OTHER_INCOME, "Other Income", "INCOME", isSystem = true),

                AccountEntity(OPENING_EQUITY, "Opening Balance Equity", "EQUITY", isSystem = true),
                AccountEntity(INVESTMENTS, "Investments", "ASSET", isSystem = true)
            )
        )
    }

    suspend fun record(command: TransactionCommand) {
        recordInternal(command, null, "POSTED")
    }

    /**
     * Posts the ledger transaction and marks the SMS as POSTED
     * in the same database transaction.
     */
    suspend fun recordFromSms(
        command: TransactionCommand,
        smsObservationId: String
    ) {
        recordInternal(command, smsObservationId, "POSTED")
    }

    suspend fun markSmsIgnored(id: String) {
        val updated = database.smsObservationDao()
            .updateStatus(id, "IGNORED")

        check(updated == 1) {
            "Could not update the SMS review status."
        }
    }

    private suspend fun recordInternal(
        command: TransactionCommand,
        smsObservationId: String?,
        smsStatus: String
    ) {
        require(command.title.isNotBlank()) {
            "Description is required."
        }

        require(command.amountMinor > 0) {
            "Amount must be greater than zero."
        }

        val accountDao = database.accountDao()
        val transactionDao = database.transactionDao()
        val ledgerDao = database.ledgerDao()
        val smsDao = database.smsObservationDao()

        database.withTransaction {

            val from = command.fromAccountId?.let {
                accountDao.getAccount(it) ?: error("Source account not found.")
            }

            val to = command.toAccountId?.let {
                accountDao.getAccount(it) ?: error("Destination account not found.")
            }

            val category = command.categoryAccountId?.let {
                accountDao.getAccount(it) ?: error("Category account not found.")
            }

            when (command.type) {

                TransactionCommand.Type.EXPENSE -> {
                    require(from != null && category?.accountType == "EXPENSE") {
                        "Choose a payment account and expense category."
                    }

                    require(
                        from.accountType == "ASSET" ||
                                from.accountType == "LIABILITY"
                    ) {
                        "Payment must come from an asset or credit-card account."
                    }
                }

                TransactionCommand.Type.INCOME -> {
                    require(
                        to?.accountType == "ASSET" &&
                                category?.accountType == "INCOME"
                    ) {
                        "Choose a receiving asset account and income category."
                    }
                }

                TransactionCommand.Type.TRANSFER -> {
                    require(from != null && to != null) {
                        "Choose both transfer accounts."
                    }

                    require(from.id != to.id) {
                        "Transfer accounts must be different."
                    }

                    require(
                        from.accountType in listOf("ASSET", "LIABILITY") &&
                                to.accountType in listOf("ASSET", "LIABILITY")
                    ) {
                        "Transfers require asset or liability accounts."
                    }
                }
            }

            val transactionId = UUID.randomUUID().toString()
            val journalId = UUID.randomUUID().toString()

            transactionDao.insertTransaction(
                FinancialTransactionEntity(
                    id = transactionId,
                    title = command.title.trim(),
                    amountMinor = command.amountMinor,
                    type = command.type.name,
                    fromAccountId = command.fromAccountId,
                    toAccountId = command.toAccountId,
                    categoryAccountId = command.categoryAccountId,
                    occurredAt = command.occurredAt,
                    note = command.note.trim(),
                    sourceObservationId = smsObservationId
                )
            )

            ledgerDao.insertJournalEntry(
                JournalEntryEntity(
                    id = journalId,
                    transactionId = transactionId,
                    description = command.title.trim()
                )
            )

            val lines = when (command.type) {

                TransactionCommand.Type.EXPENSE -> listOf(
                    line(journalId, category!!.id, debit = command.amountMinor),
                    line(journalId, from!!.id, credit = command.amountMinor)
                )

                TransactionCommand.Type.INCOME -> listOf(
                    line(journalId, to!!.id, debit = command.amountMinor),
                    line(journalId, category!!.id, credit = command.amountMinor)
                )

                TransactionCommand.Type.TRANSFER -> listOf(
                    line(journalId, to!!.id, debit = command.amountMinor),
                    line(journalId, from!!.id, credit = command.amountMinor)
                )
            }

            ledgerDao.insertJournalLines(lines)

            check(ledgerDao.isEntryBalanced(journalId)) {
                "Ledger validation failed: journal entry is not balanced."
            }

            if (smsObservationId != null) {
                val updated = smsDao.updateStatus(
                    id = smsObservationId,
                    status = smsStatus
                )

                check(updated == 1) {
                    "SMS was not found."
                }
            }
        }
    }

    private fun line(
        journalId: String,
        accountId: String,
        debit: Long = 0,
        credit: Long = 0
    ) = JournalLineEntity(
        id = UUID.randomUUID().toString(),
        journalEntryId = journalId,
        accountId = accountId,
        debitMinor = debit,
        creditMinor = credit
    )
}