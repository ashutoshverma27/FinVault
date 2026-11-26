package com.finvault.app.email

import com.finvault.app.sms.SmsParser
import com.finvault.app.sms.SmsTransactionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EmailTextTest {

    @Test
    fun hdfcAlertEmail_parsesLikeSms_despiteOtpFooter() {
        val html = """
            <html><body><p>Dear Customer,</p>
            <p>Rs.1,499.00 has been debited from your HDFC Bank Credit Card ending 4321 towards MYNTRA on 25 Nov, 2026 at 10:15:22.</p>
            <p>Never share your OTP, PIN or password with anyone.</p>
            <p>Get 10% cashback offer - click here</p></body></html>
        """.trimIndent()
        val text = EmailText.clean("Alert : Update on your HDFC Bank Credit Card", EmailText.htmlToText(html))
        val parsed = SmsParser.parse(text)
        assertNotNull(text, parsed)
        assertEquals(149_900L, parsed!!.amountMinor)
        assertEquals("EXPENSE", parsed.suggestedType)
        assertEquals("4321", parsed.accountHint)
    }

    @Test
    fun senderDomain_mapsToBank() {
        val sender = EmailText.sender("HDFC Bank InstaAlerts <alerts@hdfcbank.net>")
        assertEquals("EMAIL:hdfcbank.net", sender)
        assertEquals("HDFC", SmsTransactionMapper.bankName(sender))
        assertEquals("SBI", SmsTransactionMapper.bankName(EmailText.sender("SBI <donotreply.sbiatm@alerts.sbi.co.in>")))
        assertEquals("Kotak", SmsTransactionMapper.bankName(EmailText.sender("<BankAlerts@kotak.com>")))
    }
}
