package com.finvault.app.email

import java.util.Locale

/** Turns a bank alert email into SMS-like text the SMS parser understands. */
object EmailText {

    private val tag = Regex("""(?is)<(script|style)[^>]*>.*?</\1>|<[^>]+>""")
    private val entity = mapOf("&nbsp;" to " ", "&amp;" to "&", "&#8377;" to "₹", "&rupee;" to "₹", "&lt;" to "<", "&gt;" to ">", "&#39;" to "'", "&quot;" to "\"")

    /** Footer / safety boilerplate that would trip the OTP or promotion filters. */
    private val noise = Regex(
        """(?i)(otp|one time password|never share|do not share|password|pin\b|offer|cashback|apply now|click here|unsubscribe|download|disclaimer|this is a system generated|do not reply|privacy)"""
    )

    private val numericEntity = Regex("""&#(x?)([0-9a-fA-F]+);""")
    private val moneyOrVerb = Regex(
        """(?i)(₹|\bINR\b|\bRs\.?\s*\d|debited|credited|spent|refund|reversed|withdrawn|received|sent|paid)"""
    )

    private val blockTag = Regex("""(?i)<\s*(br|/p|/div|/tr|/li|/td|/h[1-6]|/table)\b[^>]*>""")

    fun htmlToText(html: String): String {
        var text = blockTag.replace(html, "\n")
        text = tag.replace(text, " ")
        entity.forEach { (k, v) -> text = text.replace(k, v, ignoreCase = true) }
        text = numericEntity.replace(text) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
            code?.let { String(Character.toChars(it)) } ?: " "
        }
        return text
    }

    /**
     * The sentences that describe the transaction (plus one either side for
     * the account / reference), without headers, footers and safety text.
     */
    fun clean(subject: String, body: String): String {
        // Split on line / block breaks first, then sentences, so page headers and
        // navigation links don't get glued to the transaction sentence.
        val sentences = body
            .replace('\u00A0', ' ')
            .split('\n', '\r', '|')
            .flatMap { it.replace(Regex("""\s+"""), " ").split(Regex("""(?<=[.!?])\s+(?=[A-Z₹])""")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !noise.containsMatchIn(it) }
        val hits = sentences.indices.filter { moneyOrVerb.containsMatchIn(sentences[it]) }
        val keep = if (hits.isEmpty()) sentences.indices.toSet()
        else hits.flatMap { listOf(it - 1, it, it + 1) }.filter { it in sentences.indices }.toSortedSet()
        val text = keep.joinToString(" ") { sentences[it] }
        val cleanSubject = subject.takeUnless { noise.containsMatchIn(it) }.orEmpty()
        return "$cleanSubject. $text".trim().take(900)
    }

    /** "HDFC Bank InstaAlerts <alerts@hdfcbank.net>" -> "EMAIL:hdfcbank.net" */
    fun sender(fromHeader: String): String {
        val address = Regex("""<([^>]+)>""").find(fromHeader)?.groupValues?.get(1) ?: fromHeader
        val domain = address.substringAfter('@').trim().lowercase(Locale.ROOT)
            .removePrefix("mail.").removePrefix("email.").removePrefix("alerts.")
        return "$PREFIX$domain"
    }

    const val PREFIX = "EMAIL:"
}
