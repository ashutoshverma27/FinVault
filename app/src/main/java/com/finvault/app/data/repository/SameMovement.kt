package com.finvault.app.data.repository

import com.finvault.app.data.repository.FinVaultRepository.Companion.BANK
import com.finvault.app.data.repository.FinVaultRepository.Companion.CREDIT_CARD

/**
 * Decides whether an SMS and an email describe the same payment, so it is
 * counted once. Type is not compared because the two are often worded
 * differently (or one was already turned into a transfer); what must agree is
 * the amount, the direction and the account, close in time.
 */
object SameMovement {

    data class Movement(
        val type: String,
        val amountMinor: Long,
        val occurredAt: Long,
        /** Account money left (bank / card), if any. */
        val from: String?,
        /** Account money arrived in, if any. */
        val to: String?,
        val reference: String?,
        val isEmail: Boolean
    )

    /** Bank emails can lag the SMS for the same payment by a few hours. */
    const val WINDOW = 3 * 60 * 60_000L
    /** Same reference: can be days apart (delayed statement-style alerts). */
    const val REFERENCE_WINDOW = 3 * 24 * 60 * 60_000L
    /** Emails often quote a different id than the SMS; then they must be this close. */
    const val CONFLICTING_REFERENCE_WINDOW = 15 * 60_000L
    private const val CLOSE = 30 * 60_000L

    fun matches(a: Movement, b: Movement): Boolean {
        if (a.amountMinor != b.amountMinor) return false
        val gap = kotlin.math.abs(a.occurredAt - b.occurredAt)
        val maxGap = when (references(a.reference, b.reference)) {
            true -> REFERENCE_WINDOW
            false -> CONFLICTING_REFERENCE_WINDOW
            null -> WINDOW
        }
        if (gap > maxGap) return false
        val close = gap <= CLOSE
        val aOut = a.type == "EXPENSE" || a.type == "TRANSFER"
        val bOut = b.type == "EXPENSE" || b.type == "TRANSFER"
        val outgoing = aOut && bOut && sameAccount(a.from, b.from, close)
        val incoming = a.type != "EXPENSE" && b.type != "EXPENSE" && sameAccount(a.to, b.to, close)
        return outgoing || incoming
    }

    private fun references(a: String?, b: String?): Boolean? {
        if (a == null || b == null) return null
        if (a == b) return true
        return a.length >= 10 && b.length >= 10 && a.takeLast(10) == b.takeLast(10)
    }

    private fun specific(id: String) = id.startsWith("bank_") || id.startsWith("card_")

    private fun kind(id: String) = when {
        id == BANK || id.startsWith("bank_") -> "bank"
        id == CREDIT_CARD || id.startsWith("card_") -> "card"
        else -> null
    }

    private fun sameAccount(a: String?, b: String?, close: Boolean): Boolean {
        if (a == null || b == null) return false
        if (a == b) return specific(a) || ((a == BANK || a == CREDIT_CARD) && close)
        // One alert named the account, the other didn't: same kind of account, close in time.
        val generic = a == BANK || a == CREDIT_CARD || b == BANK || b == CREDIT_CARD
        return generic && close && kind(a) != null && kind(a) == kind(b)
    }
}
