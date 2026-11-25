package com.finvault.app.sms

import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand

/** Turns a detected SMS into a ledger command using the parser's suggestions. */
object SmsTransactionMapper {

    fun command(item: SmsObservationEntity, plainBody: String): TransactionCommand? {
        if (item.manualOnly) return null
        val parsed = SmsParser.parse(plainBody) ?: return null

        val type = runCatching { TransactionCommand.Type.valueOf(item.suggestedType) }
            .getOrDefault(TransactionCommand.Type.EXPENSE)
        val title = item.suggestedTitle.takeUnless { it == "SMS transaction" } ?: parsed.title
        val note = "From SMS · ${senderLabel(item.sender)}"
        val category = parsed.suggestedCategoryId

        return when (type) {
            TransactionCommand.Type.EXPENSE -> TransactionCommand(
                title = title,
                amountMinor = item.suggestedAmountMinor,
                type = type,
                fromAccountId = parsed.suggestedAccountId,
                categoryAccountId = category?.takeIf { it.startsWith("expense_") }
                    ?: FinVaultRepository.GENERAL_EXPENSE,
                note = note,
                occurredAt = item.receivedAt
            )

            TransactionCommand.Type.INCOME -> TransactionCommand(
                title = title,
                amountMinor = item.suggestedAmountMinor,
                type = type,
                // Income can only land in an asset account.
                toAccountId = parsed.suggestedAccountId.takeUnless { it == FinVaultRepository.CREDIT_CARD }
                    ?: FinVaultRepository.BANK,
                categoryAccountId = category?.takeIf { it.startsWith("income_") }
                    ?: FinVaultRepository.OTHER_INCOME,
                note = note,
                occurredAt = item.receivedAt
            )

            TransactionCommand.Type.TRANSFER -> {
                val from = parsed.suggestedAccountId
                val to = parsed.suggestedDestinationId?.takeUnless { it == from }
                    ?: if (from == FinVaultRepository.CASH) FinVaultRepository.BANK else FinVaultRepository.CASH
                TransactionCommand(
                    title = title,
                    amountMinor = item.suggestedAmountMinor,
                    type = type,
                    fromAccountId = from,
                    toAccountId = to,
                    note = note,
                    occurredAt = item.receivedAt
                )
            }
        }
    }

    private fun senderLabel(sender: String): String {
        val parts = sender.trim().split('-')
        return if (parts.size >= 2 && parts[0].length == 2 && parts[1].any { it.isLetter() }) parts[1] else sender.trim()
    }
}
