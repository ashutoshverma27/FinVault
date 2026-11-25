package com.finvault.app.sms

import java.util.Locale

/**
 * Normalises a merchant / payee name, or a transaction title derived from one,
 * into a stable key for learned rules: "Swiggy", "SWIGGY" and "swiggy" all
 * become "swiggy", and "Refund · Amazon" becomes "amazon".
 */
object MerchantKey {

    /** Titles the app generates when it doesn't know the merchant. */
    private val genericTitles = setOf(
        "upi payment", "payment", "card payment", "money received", "refund", "self transfer",
        "salary", "atm withdrawal", "credit card bill payment", "investment", "sms transaction",
        "failed payment reversed"
    )

    private val titlePrefixes = listOf("refund · ", "refund from ", "investment · ", "from ")

    fun fromTitle(title: String?): String? {
        var text = title?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (text in genericTitles) return null
        titlePrefixes.firstOrNull { text.startsWith(it) }?.let { text = text.removePrefix(it) }
        return normalise(text)
    }

    fun fromMerchant(merchant: String?): String? =
        merchant?.lowercase(Locale.ROOT)?.let(::normalise)

    private fun normalise(text: String): String? {
        val key = text.replace(Regex("[^a-z0-9]"), "")
        // Very short or mostly-numeric keys (QR codes, ref numbers) are not reliable names.
        if (key.length < 3) return null
        if (key.count { it.isDigit() } > key.length / 2) return null
        return key
    }
}
