package com.finvault.app.data.repository

import androidx.room.withTransaction
import com.finvault.app.data.local.*
import com.finvault.app.domain.ledger.TransactionCommand
import com.finvault.app.sms.SmsBodyCrypto
import com.finvault.app.sms.SmsPosting
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

        private const val SELF_TRANSFER_WINDOW = 30 * 60_000L
        private const val CARD_PAYMENT_WINDOW = 3 * 24 * 60 * 60_000L
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
        val posting = SmsTransactionMapper.posting(item, SmsBodyCrypto.decrypt(item.body)) ?: return false

        return runCatching {
            database.withTransaction {
                database.accountDao().insertAccounts(posting.accounts)

                val partner = findPartner(posting)
                if (partner == null) {
                    recordInternal(posting.command, item.id, "AUTO_POSTED")
                } else {
                    mergeWithPartner(posting, partner, item.id)
                }
            }
        }.isSuccess
    }

    /**
     * Finds the other half of a movement between the user's own accounts:
     *  - a debit from one own bank account + a credit of the same amount into
     *    another own bank account within 30 minutes (self transfer), or
     *  - the bank-side and card-side SMS of one credit card bill payment.
     */
    private suspend fun findPartner(posting: SmsPosting): FinancialTransactionEntity? {
        val command = posting.command
        val own = posting.ownAccountId
        val dao = database.transactionDao()

        return when (command.type) {
            TransactionCommand.Type.EXPENSE, TransactionCommand.Type.INCOME -> {
                if (!own.startsWith("bank_")) return null
                val opposite = if (command.type == TransactionCommand.Type.EXPENSE) "INCOME" else "EXPENSE"
                dao.findAutoPostedCandidates(
                    opposite, command.amountMinor,
                    command.occurredAt - SELF_TRANSFER_WINDOW, command.occurredAt + SELF_TRANSFER_WINDOW
                ).firstOrNull { candidate ->
                    val candidateOwn = if (opposite == "INCOME") candidate.toAccountId else candidate.fromAccountId
                    candidateOwn != null && candidateOwn.startsWith("bank_") && candidateOwn != own
                }
            }

            TransactionCommand.Type.TRANSFER -> {
                if (!isCard(command.toAccountId)) return null
                dao.findAutoPostedCandidates(
                    "TRANSFER", command.amountMinor,
                    command.occurredAt - CARD_PAYMENT_WINDOW, command.occurredAt + CARD_PAYMENT_WINDOW
                ).firstOrNull { isCard(it.toAccountId) }
            }
        }
    }

    private suspend fun mergeWithPartner(
        posting: SmsPosting,
        partner: FinancialTransactionEntity,
        smsId: String
    ) {
        val command = posting.command
        val (from, to, title) = when (command.type) {
            TransactionCommand.Type.EXPENSE -> Triple(posting.ownAccountId, partner.toAccountId!!, "Self transfer")
            TransactionCommand.Type.INCOME -> Triple(partner.fromAccountId!!, posting.ownAccountId, "Self transfer")
            TransactionCommand.Type.TRANSFER -> Triple(
                // Prefer the specific bank (bank-side SMS) and specific card (card-side SMS).
                listOfNotNull(command.fromAccountId, partner.fromAccountId).firstOrNull { it.startsWith("bank_") }
                    ?: command.fromAccountId!!,
                listOfNotNull(command.toAccountId, partner.toAccountId).firstOrNull { it.startsWith("card_") }
                    ?: command.toAccountId!!,
                "Credit card bill payment"
            )
        }

        val partnerSmsId = partner.sourceObservationId
        deleteTransaction(partner.id)
        partnerSmsId?.let { database.smsObservationDao().updateStatus(it, "MERGED") }

        recordInternal(
            TransactionCommand(
                title = title,
                amountMinor = command.amountMinor,
                type = TransactionCommand.Type.TRANSFER,
                fromAccountId = from,
                toAccountId = if (to == from) CASH else to,
                note = "Between your own accounts · from 2 SMS",
                occurredAt = minOf(command.occurredAt, partner.occurredAt)
            ),
            smsId,
            "AUTO_POSTED"
        )
    }

    private fun isCard(accountId: String?) =
        accountId == CREDIT_CARD || accountId?.startsWith("card_") == true

    /** Auto-adds every SMS still waiting in the review queue, oldest first so transfer pairs match. */
    suspend fun autoPostPendingSms(): Int = withContext(Dispatchers.IO) {
        database.smsObservationDao().getAllPendingOldestFirst().count { autoPostSms(it) }
    }

    /**
     * Re-posts every auto-added (not yet user-checked) SMS with the current
     * mapping: per-bank accounts, self-transfer pairing and card bill merging.
     */
    suspend fun remapAutoPostedSms(): Int = withContext(Dispatchers.IO) {
        val dao = database.smsObservationDao()
        database.withTransaction {
            dao.deleteAutoPostedLines()
            dao.deleteAutoPostedEntries()
            dao.deleteAutoPostedTransactions()
            dao.resetAutoPostedStatus()
        }
        autoPostPendingSms()
    }

    /**
     * Sets an account's balance to what the user sees in their bank app by
     * posting an adjustment against Opening Balance Equity. Adjustments are
     * not income or spending, so monthly totals are unaffected.
     */
    suspend fun setAccountBalance(accountId: String, targetMinor: Long) {
        database.withTransaction {
            val account = database.accountDao().getAccount(accountId) ?: error("Account not found.")
            val current = database.accountDao().getBalance(accountId)
            val diff = targetMinor - current
            if (diff == 0L) return@withTransaction

            val amount = kotlin.math.abs(diff)
            // ASSET balance = debits - credits; LIABILITY balance = credits - debits.
            val debitAccount = when {
                account.accountType == "ASSET" && diff > 0 -> accountId
                account.accountType == "LIABILITY" && diff < 0 -> accountId
                else -> OPENING_EQUITY
            }
            val creditAccount = if (debitAccount == accountId) OPENING_EQUITY else accountId

            val transactionId = UUID.randomUUID().toString()
            val journalId = UUID.randomUUID().toString()
            database.transactionDao().insertTransaction(
                FinancialTransactionEntity(
                    id = transactionId,
                    title = "Balance adjustment · ${account.name}",
                    amountMinor = amount,
                    type = "ADJUSTMENT",
                    fromAccountId = OPENING_EQUITY,
                    toAccountId = accountId,
                    categoryAccountId = null,
                    occurredAt = System.currentTimeMillis(),
                    note = "Balance set manually"
                )
            )
            database.ledgerDao().insertJournalEntry(
                JournalEntryEntity(journalId, transactionId, "Balance adjustment")
            )
            database.ledgerDao().insertJournalLines(
                listOf(
                    line(journalId, debitAccount, debit = amount),
                    line(journalId, creditAccount, credit = amount)
                )
            )
        }
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