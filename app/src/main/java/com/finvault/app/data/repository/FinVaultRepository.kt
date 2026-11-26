package com.finvault.app.data.repository

import androidx.room.withTransaction
import com.finvault.app.data.local.*
import com.finvault.app.domain.ledger.TransactionCommand
import com.finvault.app.sms.MerchantKey
import com.finvault.app.sms.SmsBodyCrypto
import com.finvault.app.sms.SmsIngestion
import com.finvault.app.sms.SmsParser
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
        const val BILLS_EXPENSE = "expense_bills"
        const val HEALTH_EXPENSE = "expense_health"
        const val ENTERTAINMENT_EXPENSE = "expense_entertainment"

        const val SALARY_INCOME = "income_salary"
        const val OTHER_INCOME = "income_other"

        const val OPENING_EQUITY = "equity_opening"

        const val INVESTMENTS = "account_investments"

        /**
         * Holding account for money sent to / received from the user's own name
         * when the other bank's SMS hasn't been matched yet. Its balance shows
         * transfers still waiting for their other half.
         */
        const val OTHER_OWN = "account_other_own"

        /** Account-holder names on UPI / IMPS SMS. Payments to / from these are self transfers. */
        val OWNER_NAMES = listOf("Ashutosh Verma")

        private const val SELF_TRANSFER_WINDOW = 30 * 60_000L
        private const val CARD_PAYMENT_WINDOW = 3 * 24 * 60 * 60_000L
        /** Two SMS quoting the same reference can arrive days apart (NEFT batches, delayed alerts). */
        private const val REFERENCE_WINDOW = 3 * 24 * 60 * 60_000L
        private const val CROSS_CHANNEL_WINDOW = 20 * 60_000L
        private const val SAME_PAYMENT_WINDOW = 7 * 24 * 60 * 60_000L
        private const val REFUND_LOOKBACK = 60L * 24 * 60 * 60_000L
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
     *
     * Before posting, the SMS is checked against what is already in the ledger:
     *  - a second alert for the same payment (same reference) is dropped;
     *  - the other half of a transfer between the user's own accounts is merged
     *    with its partner into one TRANSFER, so it is neither spending nor income;
     *  - learned rules (merchant -> category, payee -> own account) are applied.
     */
    suspend fun autoPostSms(item: SmsObservationEntity): Boolean {
        if (item.status != "NEEDS_REVIEW") return false
        val base = SmsTransactionMapper.posting(item, SmsBodyCrypto.decrypt(item.body)) ?: return false

        return runCatching {
            database.withTransaction {
                database.accountDao().insertAccounts(base.accounts)

                val posting = applyLearnedRules(resolveOwnCounterparty(base))

                if (isAlreadyRecorded(posting, item.id)) {
                    database.smsObservationDao().updateStatus(item.id, "MERGED")
                    return@withTransaction
                }

                val partner = findPartner(posting)
                val ownerTransfer = if (partner == null) ownerTransfer(posting) else null
                when {
                    partner != null -> mergeWithPartner(posting, partner, item.id)
                    ownerTransfer != null ->
                        if (completeWaitingTransfer(ownerTransfer, posting)) {
                            database.smsObservationDao().updateStatus(item.id, "MERGED")
                        } else {
                            recordInternal(ownerTransfer, item.id, "AUTO_POSTED")
                        }
                    else -> recordInternal(posting.command, item.id, "AUTO_POSTED")
                }
            }
        }.isSuccess
    }

    private data class Partner(val transaction: FinancialTransactionEntity, val referenceMatched: Boolean)

    /** Reference number quoted in the SMS a transaction was created from, if any. */
    private suspend fun referenceOf(transaction: FinancialTransactionEntity): String? {
        val smsId = transaction.sourceObservationId ?: return null
        val sms = database.smsObservationDao().getById(smsId) ?: return null
        return runCatching { SmsParser.parse(SmsBodyCrypto.decrypt(sms.body))?.referenceId }.getOrNull()
    }

    /**
     * Same reference, allowing for banks that print only the last digits.
     * Returns null when either side has no reference (unknown).
     */
    private fun referencesMatch(a: String?, b: String?): Boolean? {
        if (a == null || b == null) return null
        if (a == b) return true
        return a.length >= 10 && b.length >= 10 && a.takeLast(10) == b.takeLast(10)
    }

    private fun isOwnAsset(accountId: String?) = accountId == BANK || accountId?.startsWith("bank_") == true

    private fun isOwnSpecific(accountId: String?) =
        accountId?.startsWith("bank_") == true || accountId?.startsWith("card_") == true

    /**
     * If the SMS names a second masked account that the user already has
     * (e.g. "IMPS from A/c XX1234 to A/c XX5678"), it is a transfer between
     * the user's own accounts even when the other bank sends no SMS.
     */
    private suspend fun resolveOwnCounterparty(posting: SmsPosting): SmsPosting {
        if (posting.otherAccountHints.isEmpty()) return posting
        val command = posting.command
        val own = posting.ownAccountId
        val ownSuffix = own.substringAfterLast('_')
        val ownAccounts = database.accountDao().getOwnSmsAccounts()

        val other = posting.otherAccountHints
            .map { it.takeLast(4) }
            .filter { it != ownSuffix }
            .firstNotNullOfOrNull { hint ->
                ownAccounts.firstOrNull { it.id != own && it.id.substringAfterLast('_') == hint }
            } ?: return posting

        val transfer = when (command.type) {
            TransactionCommand.Type.EXPENSE -> if (isOwnAsset(own)) command.copy(
                type = TransactionCommand.Type.TRANSFER,
                title = if (other.id.startsWith("card_")) "Credit card bill payment" else "Self transfer",
                fromAccountId = own, toAccountId = other.id, categoryAccountId = null
            ) else null

            TransactionCommand.Type.INCOME -> if (other.id.startsWith("bank_")) command.copy(
                type = TransactionCommand.Type.TRANSFER, title = "Self transfer",
                fromAccountId = other.id, toAccountId = own, categoryAccountId = null
            ) else null

            // Bank-side SMS of a card bill payment: pin the specific card.
            TransactionCommand.Type.TRANSFER ->
                if (command.toAccountId == CREDIT_CARD && other.id.startsWith("card_")) {
                    command.copy(toAccountId = other.id)
                } else null

            TransactionCommand.Type.REFUND -> null
        } ?: return posting

        return posting.copy(command = transfer)
    }

    /** Applies what the user taught the app about this merchant / payee. */
    private suspend fun applyLearnedRules(posting: SmsPosting): SmsPosting {
        val command = posting.command
        val key = posting.merchantKey ?: MerchantKey.fromTitle(command.title)
        val rule = key?.let { database.merchantRuleDao().get(it) }

        if (rule?.kind == MerchantRuleEntity.SELF) {
            val target = database.accountDao().getAccount(rule.targetId)
            if (target != null && target.accountType in listOf("ASSET", "LIABILITY")) {
                val transfer = when (command.type) {
                    TransactionCommand.Type.EXPENSE ->
                        if (isOwnAsset(command.fromAccountId) || command.fromAccountId == CASH) {
                            command.copy(
                                type = TransactionCommand.Type.TRANSFER, title = "Self transfer",
                                toAccountId = target.id, categoryAccountId = null
                            )
                        } else null

                    TransactionCommand.Type.INCOME -> command.copy(
                        type = TransactionCommand.Type.TRANSFER, title = "Self transfer",
                        fromAccountId = target.id, categoryAccountId = null
                    )

                    else -> null
                }
                if (transfer != null && transfer.fromAccountId != transfer.toAccountId) {
                    return posting.copy(command = transfer)
                }
            }
        }

        val category = when {
            command.type != TransactionCommand.Type.EXPENSE && command.type != TransactionCommand.Type.REFUND -> null
            rule?.kind == MerchantRuleEntity.CATEGORY -> rule.targetId
            command.type == TransactionCommand.Type.REFUND -> refundCategory(posting)
            else -> null
        }
        if (category != null && database.accountDao().getAccount(category)?.accountType == "EXPENSE") {
            return posting.copy(command = command.copy(categoryAccountId = category))
        }
        return posting
    }

    /** A refund belongs to the same category as the purchase it reverses. */
    private suspend fun refundCategory(posting: SmsPosting): String? {
        val command = posting.command
        val recent = database.transactionDao().getExpensesBetween(command.occurredAt - REFUND_LOOKBACK, command.occurredAt)

        posting.referenceId?.let { ref ->
            recent.filter { it.amountMinor == command.amountMinor }
                .firstOrNull { referencesMatch(ref, referenceOf(it)) == true }
                ?.let { return it.categoryAccountId }
        }
        posting.merchantKey?.let { key ->
            recent.firstOrNull { MerchantKey.fromTitle(it.title) == key }?.let { return it.categoryAccountId }
        }
        return recent.firstOrNull {
            it.amountMinor == command.amountMinor &&
                    it.fromAccountId == command.toAccountId &&
                    it.occurredAt >= command.occurredAt - SAME_PAYMENT_WINDOW
        }?.categoryAccountId
    }

    /**
     * True when the movement in this SMS is already in the ledger:
     *  - another alert for the same payment (same type, amount and reference), or
     *  - a transfer between the user's own accounts that already covers this
     *    side (e.g. recorded from the other bank's SMS, by a learned rule, or by hand).
     */
    private suspend fun isAlreadyRecorded(posting: SmsPosting, smsId: String): Boolean {
        val command = posting.command
        val ref = posting.referenceId
        val window = if (ref != null) REFERENCE_WINDOW else SELF_TRANSFER_WINDOW
        val candidates = database.transactionDao().findByAmount(
            listOf(command.type.name, "TRANSFER"), command.amountMinor,
            command.occurredAt - window, command.occurredAt + window
        ).filter { it.sourceObservationId != smsId }

        val thisIsEmail = database.smsObservationDao().getById(smsId)?.sender?.startsWith("EMAIL:") == true

        for (candidate in candidates) {
            val sameRef = referencesMatch(ref, referenceOf(candidate))
            if (sameRef == false) continue

            // The same payment reported by both SMS and email: same type, amount and account, close in time.
            val candidateSender = candidate.sourceObservationId
                ?.let { database.smsObservationDao().getById(it)?.sender }
            val candidateIsEmail = candidateSender?.startsWith("EMAIL:") == true
            if (candidateSender != null && candidateIsEmail != thisIsEmail &&
                candidate.type == command.type.name &&
                kotlin.math.abs(candidate.occurredAt - command.occurredAt) <= CROSS_CHANNEL_WINDOW
            ) {
                val candidateOwn = when (command.type) {
                    TransactionCommand.Type.EXPENSE -> candidate.fromAccountId
                    else -> candidate.toAccountId
                }
                val sameAccount = candidateOwn == posting.ownAccountId ||
                        !isOwnSpecific(candidateOwn) || !isOwnSpecific(posting.ownAccountId)
                if (sameRef == true || sameAccount) return true
            }

            // Card bill payments have a bank-side and a card-side SMS; findPartner merges those.
            if (candidate.type == command.type.name && sameRef == true &&
                command.type != TransactionCommand.Type.TRANSFER
            ) return true

            if (candidate.type == "TRANSFER" && command.type != TransactionCommand.Type.TRANSFER) {
                val between = isOwnSpecific(candidate.fromAccountId) && isOwnSpecific(candidate.toAccountId)
                val closeInTime = kotlin.math.abs(candidate.occurredAt - command.occurredAt) <= SELF_TRANSFER_WINDOW
                if (!(sameRef == true || (between && closeInTime))) continue

                val covers = when (command.type) {
                    TransactionCommand.Type.EXPENSE -> candidate.fromAccountId == posting.ownAccountId
                    TransactionCommand.Type.INCOME, TransactionCommand.Type.REFUND ->
                        candidate.toAccountId == posting.ownAccountId
                    TransactionCommand.Type.TRANSFER -> false
                }
                if (covers && isOwnSpecific(posting.ownAccountId)) return true
            }
        }
        return false
    }

    /**
     * Finds the other half of a movement between the user's own accounts:
     *  - a debit from one own bank account + a credit of the same amount into
     *    another own bank account. With a matching UPI / IMPS / NEFT reference
     *    the two can be up to 3 days apart (NEFT batches, late SMS); without a
     *    reference they must be within 30 minutes and both on known accounts.
     *  - the bank-side and card-side SMS of one credit card bill payment.
     */
    private suspend fun findPartner(posting: SmsPosting): Partner? {
        val command = posting.command
        val own = posting.ownAccountId
        val ref = posting.referenceId
        val dao = database.transactionDao()

        return when (command.type) {
            TransactionCommand.Type.EXPENSE, TransactionCommand.Type.INCOME -> {
                if (!isOwnAsset(own)) return null
                val opposite = if (command.type == TransactionCommand.Type.EXPENSE) "INCOME" else "EXPENSE"
                val window = if (ref != null) REFERENCE_WINDOW else SELF_TRANSFER_WINDOW
                // Already-confirmed SMS are only paired on a matching reference.
                val statuses = if (ref != null) listOf("AUTO_POSTED", "POSTED") else listOf("AUTO_POSTED")

                dao.findSmsCandidates(
                    statuses, opposite, command.amountMinor,
                    command.occurredAt - window, command.occurredAt + window
                ).firstNotNullOfOrNull { candidate ->
                    val candidateOwn = if (opposite == "INCOME") candidate.toAccountId else candidate.fromAccountId
                    if (!isOwnAsset(candidateOwn) || candidateOwn == own) return@firstNotNullOfOrNull null

                    when (referencesMatch(ref, referenceOf(candidate))) {
                        true -> Partner(candidate, referenceMatched = true)
                        false -> null
                        null -> {
                            val status = candidate.sourceObservationId
                                ?.let { database.smsObservationDao().getById(it)?.status }
                            val close = kotlin.math.abs(candidate.occurredAt - command.occurredAt) <= SELF_TRANSFER_WINDOW
                            if (status == "AUTO_POSTED" && close &&
                                own.startsWith("bank_") && candidateOwn!!.startsWith("bank_")
                            ) Partner(candidate, referenceMatched = false) else null
                        }
                    }
                }
            }

            TransactionCommand.Type.TRANSFER -> {
                if (!isCard(command.toAccountId)) return null
                dao.findAutoPostedCandidates(
                    "TRANSFER", command.amountMinor,
                    command.occurredAt - CARD_PAYMENT_WINDOW, command.occurredAt + CARD_PAYMENT_WINDOW
                ).firstOrNull { isCard(it.toAccountId) }?.let { Partner(it, referenceMatched = false) }
            }

            TransactionCommand.Type.REFUND -> null
        }
    }

    private suspend fun mergeWithPartner(
        posting: SmsPosting,
        found: Partner,
        smsId: String
    ) {
        val partner = found.transaction
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
            TransactionCommand.Type.REFUND -> error("Refunds are not paired.")
        }

        // A reference-matched pair proves the payee / payer name is the user's own
        // account, so future payments to that name are transfers even without the
        // other bank's SMS.
        if (found.referenceMatched && command.type != TransactionCommand.Type.TRANSFER) {
            val (debitTitle, creditTitle) =
                if (command.type == TransactionCommand.Type.EXPENSE) command.title to partner.title
                else partner.title to command.title
            val debitKey = if (command.type == TransactionCommand.Type.EXPENSE) posting.merchantKey else null
            (debitKey ?: MerchantKey.fromTitle(debitTitle))?.let { learnSelf(it, to) }
            MerchantKey.fromTitle(creditTitle)?.let { learnSelf(it, from) }
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

    /** Money sent to / received from the user's own name is a transfer, not spending or income. */
    private suspend fun ownerTransfer(posting: SmsPosting): TransactionCommand? {
        val command = posting.command
        val payee = command.title.removePrefix("From ").trim()
        val isOwner = OWNER_NAMES.any { MerchantKey.isOwnerName(payee, it) } ||
                (posting.merchantKey ?: MerchantKey.fromTitle(command.title))
                    ?.let { database.merchantRuleDao().get(it)?.kind == MerchantRuleEntity.OWNER } == true
        if (!isOwner) return null
        return when (command.type) {
            TransactionCommand.Type.EXPENSE -> if (isOwnAsset(command.fromAccountId)) command.copy(
                type = TransactionCommand.Type.TRANSFER, title = "Self transfer",
                toAccountId = OTHER_OWN, categoryAccountId = null
            ) else null
            TransactionCommand.Type.INCOME -> command.copy(
                type = TransactionCommand.Type.TRANSFER, title = "Self transfer",
                fromAccountId = OTHER_OWN, categoryAccountId = null
            )
            else -> null
        }
    }

    /**
     * If the other half of this self transfer was already recorded against
     * "My other accounts", fill in the real account instead of adding a second
     * transfer. Matched on reference, or same amount within 30 minutes.
     */
    private suspend fun completeWaitingTransfer(transfer: TransactionCommand, posting: SmsPosting): Boolean {
        val sending = transfer.toAccountId == OTHER_OWN
        val own = if (sending) transfer.fromAccountId!! else transfer.toAccountId!!
        val window = if (posting.referenceId != null) REFERENCE_WINDOW else SELF_TRANSFER_WINDOW
        val waiting = database.transactionDao().findByAmount(
            listOf("TRANSFER"), transfer.amountMinor, transfer.occurredAt - window, transfer.occurredAt + window
        ).firstOrNull { candidate ->
            val waitingSide = if (sending) candidate.fromAccountId else candidate.toAccountId
            val otherSide = if (sending) candidate.toAccountId else candidate.fromAccountId
            if (waitingSide != OTHER_OWN || otherSide == own) return@firstOrNull false
            when (referencesMatch(posting.referenceId, referenceOf(candidate))) {
                true -> true
                false -> false
                null -> kotlin.math.abs(candidate.occurredAt - transfer.occurredAt) <= SELF_TRANSFER_WINDOW
            }
        } ?: return false

        database.ledgerDao().moveLine(waiting.id, OTHER_OWN, own)
        if (sending) {
            database.transactionDao().updateTransferAccounts(waiting.id, own, waiting.toAccountId!!)
        } else {
            database.transactionDao().updateTransferAccounts(waiting.id, waiting.fromAccountId!!, own)
        }
        return true
    }

    private suspend fun learnSelf(key: String, accountId: String) {
        // Never let a learned rule override a category the user picked for a real merchant.
        val existing = database.merchantRuleDao().get(key)
        if (existing != null && existing.kind != MerchantRuleEntity.SELF) return
        database.merchantRuleDao().upsert(MerchantRuleEntity(key, MerchantRuleEntity.SELF, accountId))
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
        // Refresh suggestions with the current parser (e.g. refunds that used to be manual-only).
        SmsIngestion.reclassifyPending(database)
        autoPostPendingSms()
    }

    /**
     * Re-checks SMS expenses sitting in "General" against learned rules and
     * the current merchant keywords, and moves them to a better category.
     * Expenses the user put in another category are left alone.
     */
    suspend fun recategorizeGeneralSmsExpenses(): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            var moved = 0
            database.transactionDao().getSmsExpensesInCategory(GENERAL_EXPENSE).forEach { transaction ->
                val sms = transaction.sourceObservationId?.let { database.smsObservationDao().getById(it) }
                val parsed = sms?.let { runCatching { SmsParser.parse(SmsBodyCrypto.decrypt(it.body)) }.getOrNull() }

                val key = MerchantKey.fromMerchant(parsed?.merchant) ?: MerchantKey.fromTitle(transaction.title)
                val rule = key?.let { database.merchantRuleDao().get(it) }
                val category = when {
                    rule?.kind == MerchantRuleEntity.CATEGORY -> rule.targetId
                    parsed != null -> parsed.suggestedCategoryId
                    else -> SmsParser.categoryFor(transaction.title)
                }
                if (category != null && category != GENERAL_EXPENSE &&
                    database.accountDao().getAccount(category)?.accountType == "EXPENSE"
                ) {
                    moveCategory(transaction, category)
                    moved++
                }
            }
            moved
        }
    }

    /**
     * Changes the category of an expense or refund. With [applyToMerchant],
     * every other transaction from the same merchant moves too and future
     * SMS from that merchant use this category.
     * Returns how many transactions changed.
     */
    suspend fun changeCategory(transactionId: String, categoryId: String, applyToMerchant: Boolean): Int =
        database.withTransaction {
            val transaction = database.transactionDao().getTransaction(transactionId) ?: error("Transaction not found.")
            require(transaction.type == "EXPENSE" || transaction.type == "REFUND") {
                "Only expenses and refunds have a spending category."
            }
            require(database.accountDao().getAccount(categoryId)?.accountType == "EXPENSE") {
                "Choose an expense category."
            }

            var changed = if (moveCategory(transaction, categoryId)) 1 else 0
            transaction.sourceObservationId?.let { markChecked(it) }

            val key = MerchantKey.fromTitle(transaction.title)
            if (applyToMerchant && key != null) {
                database.merchantRuleDao().upsert(MerchantRuleEntity(key, MerchantRuleEntity.CATEGORY, categoryId))
                database.transactionDao().getSpendingTransactions()
                    .filter { it.id != transaction.id && MerchantKey.fromTitle(it.title) == key }
                    .forEach { if (moveCategory(it, categoryId)) changed++ }
            }
            changed
        }

    /**
     * Re-records an expense as money sent to one of the user's own accounts,
     * or an income as money received from one. If the other bank's SMS was
     * already recorded as income / expense, it is absorbed into this transfer.
     * With [remember], future SMS to / from this payee become transfers too.
     */
    suspend fun convertToOwnTransfer(transactionId: String, otherAccountId: String, remember: Boolean) {
        database.withTransaction {
            val transaction = database.transactionDao().getTransaction(transactionId) ?: error("Transaction not found.")
            val other = database.accountDao().getAccount(otherAccountId) ?: error("Account not found.")
            require(other.accountType == "ASSET" || other.accountType == "LIABILITY") {
                "Choose one of your accounts."
            }

            val (from, to, oppositeType) = when (transaction.type) {
                "EXPENSE" -> Triple(transaction.fromAccountId!!, other.id, "INCOME")
                "INCOME" -> Triple(other.id, transaction.toAccountId!!, "EXPENSE")
                else -> error("Only expenses and income can be turned into a transfer.")
            }
            require(from != to) { "Choose a different account." }

            // The other half, if its SMS was recorded separately.
            val otherHalf = database.transactionDao().findByAmount(
                listOf(oppositeType), transaction.amountMinor,
                transaction.occurredAt - REFERENCE_WINDOW, transaction.occurredAt + REFERENCE_WINDOW
            ).firstOrNull { candidate ->
                candidate.id != transaction.id && candidate.sourceObservationId != null &&
                        (if (oppositeType == "INCOME") candidate.toAccountId == to else candidate.fromAccountId == from)
            }

            val key = MerchantKey.fromTitle(transaction.title)
            val smsId = transaction.sourceObservationId

            otherHalf?.let { half ->
                deleteTransaction(half.id)
                half.sourceObservationId?.let { database.smsObservationDao().updateStatus(it, "MERGED") }
            }
            deleteTransaction(transaction.id)

            recordInternal(
                TransactionCommand(
                    title = "Self transfer",
                    amountMinor = transaction.amountMinor,
                    type = TransactionCommand.Type.TRANSFER,
                    fromAccountId = from,
                    toAccountId = to,
                    note = listOf(transaction.note, "Between your own accounts").filter { it.isNotBlank() }.joinToString(" · "),
                    occurredAt = minOf(transaction.occurredAt, otherHalf?.occurredAt ?: transaction.occurredAt)
                ),
                smsId,
                "POSTED"
            )

            if (remember && key != null) {
                database.merchantRuleDao().upsert(MerchantRuleEntity(key, MerchantRuleEntity.SELF, other.id))
            }
        }
    }

    /**
     * Deletes a transaction the user removed. SMS-created ones are ignored so
     * the next import doesn't add them back.
     */
    suspend fun deleteTransactionByUser(transactionId: String) {
        val smsId = database.transactionDao().getTransaction(transactionId)?.sourceObservationId
        if (smsId != null) removeSmsTransaction(smsId) else deleteTransaction(transactionId)
    }

    private suspend fun moveCategory(transaction: FinancialTransactionEntity, categoryId: String): Boolean {
        val old = transaction.categoryAccountId ?: return false
        if (old == categoryId) return false
        database.ledgerDao().moveLine(transaction.id, old, categoryId)
        database.transactionDao().updateCategory(transaction.id, categoryId)
        return true
    }

    /** The user acted on this SMS transaction, so a future re-map must not overwrite it. */
    private suspend fun markChecked(smsId: String) {
        val sms = database.smsObservationDao().getById(smsId) ?: return
        if (sms.status == "AUTO_POSTED") database.smsObservationDao().updateStatus(smsId, "POSTED")
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
            val old = database.transactionDao().getTransactionIdForSms(smsId)
                ?.let { database.transactionDao().getTransaction(it) }
            old?.let { deleteTransaction(it.id) }
            recordInternal(command, smsId, "POSTED")
            old?.let { learnFromEdit(it, command) }
        }
    }

    /** Remembers a correction so the same merchant / payee is handled right next time. */
    private suspend fun learnFromEdit(old: FinancialTransactionEntity, new: TransactionCommand) {
        val key = MerchantKey.fromTitle(old.title) ?: MerchantKey.fromTitle(new.title) ?: return
        val rules = database.merchantRuleDao()
        when {
            (new.type == TransactionCommand.Type.EXPENSE || new.type == TransactionCommand.Type.REFUND) &&
                    new.categoryAccountId != null && new.categoryAccountId != old.categoryAccountId ->
                rules.upsert(MerchantRuleEntity(key, MerchantRuleEntity.CATEGORY, new.categoryAccountId))

            new.type == TransactionCommand.Type.TRANSFER && old.type == "EXPENSE" &&
                    new.fromAccountId == old.fromAccountId && new.toAccountId != null ->
                rules.upsert(MerchantRuleEntity(key, MerchantRuleEntity.SELF, new.toAccountId))

            new.type == TransactionCommand.Type.TRANSFER && old.type == "INCOME" &&
                    new.toAccountId == old.toAccountId && new.fromAccountId != null ->
                rules.upsert(MerchantRuleEntity(key, MerchantRuleEntity.SELF, new.fromAccountId))
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
                AccountEntity(BILLS_EXPENSE, "Bills & Utilities", "EXPENSE", isSystem = true),
                AccountEntity(HEALTH_EXPENSE, "Health & Fitness", "EXPENSE", isSystem = true),
                AccountEntity(ENTERTAINMENT_EXPENSE, "Entertainment & Subscriptions", "EXPENSE", isSystem = true),

                AccountEntity(SALARY_INCOME, "Salary", "INCOME", isSystem = true),
                AccountEntity(OTHER_INCOME, "Other Income", "INCOME", isSystem = true),

                AccountEntity(OPENING_EQUITY, "Opening Balance Equity", "EQUITY", isSystem = true),
                AccountEntity(INVESTMENTS, "Investments", "ASSET", isSystem = true),
                AccountEntity(OTHER_OWN, "My other accounts", "ASSET", isSystem = true)
            )
        )
        OWNER_NAMES.mapNotNull { MerchantKey.fromMerchant(it) }.forEach { key ->
            database.merchantRuleDao().upsert(MerchantRuleEntity(key, MerchantRuleEntity.OWNER, ""))
        }
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

                TransactionCommand.Type.REFUND -> {
                    require(
                        (to?.accountType == "ASSET" || to?.accountType == "LIABILITY") &&
                                category?.accountType == "EXPENSE"
                    ) {
                        "Choose the account the refund went to and its expense category."
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

                // Money back into the account, taken off the expense category.
                TransactionCommand.Type.REFUND -> listOf(
                    line(journalId, to!!.id, debit = command.amountMinor),
                    line(journalId, category!!.id, credit = command.amountMinor)
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