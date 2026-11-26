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

    private val honorific = Regex("""^(?:mr|mrs|ms|miss|dr|shri|smt)\.?\s+""", RegexOption.IGNORE_CASE)

    /**
     * True if a payee / payer name on an SMS is the account holder. Banks print
     * names differently: "Mr ASHUTOSH VERMA", "ASHUTOSH V", "VERMA ASHUTOSH",
     * "ASHUTOSH VERM" (truncated), "ashutoshverma" (UPI handle).
     */
    fun isOwnerName(payee: String?, ownerName: String): Boolean {
        if (payee.isNullOrBlank()) return false
        val words = ownerName.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val first = words.first()
        val last = words.last()
        val name = honorific.replace(payee.trim(), "").substringBefore('@')
        val key = name.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        if (key.length < 3) return false
        val full = words.joinToString("")
        if (key == full || key == words.reversed().joinToString("")) return true
        if (words.size == 1) return key == first
        // First name + all or part of the surname ("ashutoshv", "ashutoshverm"), or surname + first name.
        if (key.startsWith(first) && key.length > first.length && last.startsWith(key.removePrefix(first))) return true
        if (key.startsWith(last) && key.length > last.length && first.startsWith(key.removePrefix(last))) return true
        return false
    }

    private fun normalise(text: String): String? {
        val key = text.replace(Regex("[^a-z0-9]"), "")
        // Very short or mostly-numeric keys (QR codes, ref numbers) are not reliable names.
        if (key.length < 3) return null
        if (key.count { it.isDigit() } > key.length / 2) return null
        return key
    }
}
