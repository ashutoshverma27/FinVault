package com.finvault.app.sms

import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import java.util.Locale

/** A ledger command for an SMS plus any per-bank accounts it needs. */
data class SmsPosting(
    val command: TransactionCommand,
    val accounts: List<AccountEntity>,
    /** The user's own account this SMS is about (the masked a/c or card). */
    val ownAccountId: String,
    /** Normalised merchant / payee name, used for learned rules. */
    val merchantKey: String? = null,
    /** UPI / IMPS / NEFT reference. Both sides of a transfer quote the same one. */
    val referenceId: String? = null,
    /** Other masked account numbers in the SMS (e.g. the account the money went to). */
    val otherAccountHints: List<String> = emptyList()
)

/** Turns a detected SMS into a ledger command using the parser's suggestions. */
object SmsTransactionMapper {

    private val bankNames = linkedMapOf(
        "HDFC" to "HDFC", "ICICI" to "ICICI", "SBI" to "SBI", "AXIS" to "Axis", "KOTAK" to "Kotak",
        "KMB" to "Kotak", "YES" to "Yes Bank", "IDFC" to "IDFC First", "INDUS" to "IndusInd",
        "PNB" to "PNB", "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda", "CANARA" to "Canara",
        "CANBNK" to "Canara", "UNION" to "Union Bank", "UBI" to "Union Bank", "FEDERAL" to "Federal",
        "FEDBNK" to "Federal", "RBL" to "RBL", "AUBANK" to "AU Bank", "IDBI" to "IDBI", "CITI" to "Citi",
        "SCB" to "Standard Chartered", "HSBC" to "HSBC", "PAYTM" to "Paytm Bank", "AIRTEL" to "Airtel Bank",
        "AMEX" to "Amex", "ONECARD" to "OneCard", "SLICE" to "Slice", "JUPITER" to "Jupiter", "FI" to "Fi"
    )

    fun command(item: SmsObservationEntity, plainBody: String): TransactionCommand? =
        posting(item, plainBody)?.command

    fun posting(item: SmsObservationEntity, plainBody: String): SmsPosting? {
        if (item.manualOnly) return null
        val parsed = SmsParser.parse(plainBody) ?: return null

        val type = runCatching { TransactionCommand.Type.valueOf(item.suggestedType) }
            .getOrDefault(TransactionCommand.Type.EXPENSE)
        val title = item.suggestedTitle.takeUnless { it == "SMS transaction" } ?: parsed.title
        val bank = bankName(item.sender)
        val note = "From SMS · $bank"
        val category = parsed.suggestedCategoryId
        val newAccounts = mutableListOf<AccountEntity>()

        /** Replace a generic bank / card account with the specific masked one, if known. */
        fun own(genericId: String): String {
            val hint = parsed.accountHint?.takeLast(4) ?: return genericId
            val key = bank.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            return when (genericId) {
                FinVaultRepository.BANK -> {
                    val id = "bank_${key}_$hint"
                    newAccounts += AccountEntity(id, "$bank ••$hint", "ASSET")
                    id
                }
                FinVaultRepository.CREDIT_CARD -> {
                    val id = "card_${key}_$hint"
                    newAccounts += AccountEntity(id, "$bank Card ••$hint", "LIABILITY")
                    id
                }
                else -> genericId
            }
        }

        val command: TransactionCommand
        val ownAccount: String

        when (type) {
            TransactionCommand.Type.EXPENSE -> {
                ownAccount = own(parsed.suggestedAccountId)
                command = TransactionCommand(
                    title = title,
                    amountMinor = item.suggestedAmountMinor,
                    type = type,
                    fromAccountId = ownAccount,
                    categoryAccountId = category?.takeIf { it.startsWith("expense_") }
                        ?: FinVaultRepository.GENERAL_EXPENSE,
                    note = note,
                    occurredAt = item.receivedAt
                )
            }

            TransactionCommand.Type.INCOME -> {
                // Income can only land in an asset account.
                val generic = parsed.suggestedAccountId.takeUnless { it == FinVaultRepository.CREDIT_CARD }
                    ?: FinVaultRepository.BANK
                ownAccount = own(generic)
                command = TransactionCommand(
                    title = title,
                    amountMinor = item.suggestedAmountMinor,
                    type = type,
                    toAccountId = ownAccount,
                    categoryAccountId = category?.takeIf { it.startsWith("income_") }
                        ?: FinVaultRepository.OTHER_INCOME,
                    note = note,
                    occurredAt = item.receivedAt
                )
            }

            TransactionCommand.Type.REFUND -> {
                ownAccount = own(parsed.suggestedAccountId)
                command = TransactionCommand(
                    title = title,
                    amountMinor = item.suggestedAmountMinor,
                    type = type,
                    toAccountId = ownAccount,
                    categoryAccountId = category?.takeIf { it.startsWith("expense_") }
                        ?: FinVaultRepository.GENERAL_EXPENSE,
                    note = note,
                    occurredAt = item.receivedAt
                )
            }

            TransactionCommand.Type.TRANSFER -> {
                val destination = parsed.suggestedDestinationId
                // A card "payment received" SMS carries the card's number; a bank debit carries the bank's.
                val isCardSide = destination == FinVaultRepository.CREDIT_CARD &&
                        Regex("""(?i)(received|credited|thank you)""").containsMatchIn(plainBody) &&
                        !Regex("""(?i)\b(debited|deducted)\b""").containsMatchIn(plainBody)
                val from: String
                val to: String
                if (isCardSide) {
                    from = FinVaultRepository.BANK
                    to = own(FinVaultRepository.CREDIT_CARD)
                    ownAccount = to
                } else {
                    from = own(parsed.suggestedAccountId)
                    to = destination?.takeUnless { it == from }
                        ?: if (from == FinVaultRepository.CASH) FinVaultRepository.BANK else FinVaultRepository.CASH
                    ownAccount = from
                }
                command = TransactionCommand(
                    title = title,
                    amountMinor = item.suggestedAmountMinor,
                    type = type,
                    fromAccountId = from,
                    toAccountId = if (to == from) FinVaultRepository.CASH else to,
                    note = note,
                    occurredAt = item.receivedAt
                )
            }
        }

        return SmsPosting(
            command = command,
            accounts = newAccounts,
            ownAccountId = ownAccount,
            merchantKey = MerchantKey.fromMerchant(parsed.merchant),
            referenceId = parsed.referenceId,
            otherAccountHints = parsed.otherAccountHints
        )
    }

    /** "VM-HDFCBK-S" -> "HDFC"; unknown senders fall back to the header itself. */
    fun bankName(sender: String): String {
        val parts = sender.trim().uppercase(Locale.ROOT).split('-')
        val header = if (parts.size >= 2 && parts[0].length == 2) parts[1] else parts.first()
        bankNames.entries.firstOrNull { header.startsWith(it.key) || (it.key.length >= 4 && header.contains(it.key)) }?.let { return it.value }
        val letters = header.filter { it.isLetter() }
        return if (letters.length >= 3) letters.take(6).lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        else "Bank"
    }
}
