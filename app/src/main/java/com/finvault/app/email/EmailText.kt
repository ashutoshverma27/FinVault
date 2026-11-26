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

    fun htmlToText(html: String): String {
        var text = tag.replace(html, " ")
        entity.forEach { (k, v) -> text = text.replace(k, v, ignoreCase = true) }
        return text
    }

    /** Subject + the first sentences of the body that describe the transaction. */
    fun clean(subject: String, body: String): String {
        val sentences = (subject + ". " + body)
            .replace(Regex("""\s+"""), " ")
            .split(Regex("""(?<=[.!?])\s+|\s{2,}"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !noise.containsMatchIn(it) }
        return sentences.joinToString(" ").take(700)
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
