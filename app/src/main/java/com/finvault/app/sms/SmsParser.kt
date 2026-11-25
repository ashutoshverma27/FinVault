package com.finvault.app.sms

import java.math.BigDecimal
import java.math.RoundingMode

data class ParsedSms(
    val amountMinor: Long,
    val suggestedType: String,
    val title: String,
    val confidence: Int,
    val reviewNote: String,
    val manualOnly: Boolean
)

object SmsParser {

    private val otpPattern = Regex(
        """(?i)\b(otp|one.time.password|verification code|security code)\b"""
    )

    private val financialSignalPattern = Regex(
        """(?i)\b(debited|credited|withdrawn|deposited|spent|paid|purchase|purchased|transferred|transfer|received|refund|reversal|reversed|chargeback|upi|neft|imps|rtgs|pos|atm|transaction|txn)\b"""
    )

    private val currencyAmountPattern = Regex(
        """(?i)(?:₹|INR|Rs\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )

    private val contextualAmountPattern = Regex(
        """(?i)\b(?:debited|credited|withdrawn|deposited|spent|paid|purchased|transferred|received)\s+(?:by|with|of|for)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )

    private val debitPattern = Regex(
        """(?i)\b(debited|withdrawn|spent|paid|purchased|sent)\b"""
    )

    private val creditPattern = Regex(
        """(?i)\b(credited|received|deposited|salary)\b"""
    )

    private val refundPattern = Regex(
        """(?i)\b(refund|refunded|reversal|reversed|chargeback)\b"""
    )

    private val transferPattern = Regex(
        """(?i)\b(transferred|transfer|NEFT|IMPS|RTGS)\b"""
    )

    fun parse(body: String): ParsedSms? {

        val message = body.trim()

        if (message.isBlank()) return null

        // Do not store OTP or verification messages.
        if (otpPattern.containsMatchIn(message)) return null

        // Require at least one transaction-related signal.
        if (!financialSignalPattern.containsMatchIn(message)) return null

        val amountText =
            currencyAmountPattern.find(message)?.groupValues?.get(1)
                ?: contextualAmountPattern.find(message)?.groupValues?.get(1)
                ?: return null

        val amountMinor = try {
            BigDecimal(amountText.replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        } catch (_: Exception) {
            return null
        }

        if (amountMinor <= 0) return null

        val isRefund = refundPattern.containsMatchIn(message)
        val isDebit = debitPattern.containsMatchIn(message)
        val isCredit = creditPattern.containsMatchIn(message)
        val isPossibleTransfer = transferPattern.containsMatchIn(message)

        val suggestedType = when {
            isCredit && !isDebit -> "INCOME"
            else -> "EXPENSE"
        }

        val reviewNote = when {
            isRefund ->
                "Possible refund or reversal. The current ledger does not yet support correct refund accounting. Add this manually instead."

            isPossibleTransfer ->
                "This may be a transfer between your own accounts. Choose Transfer during review if appropriate."

            isCredit && !isDebit ->
                "Possible incoming payment. Confirm whether this is salary, income or a transfer."

            else ->
                "Possible outgoing payment. Confirm the payment account and category."
        }

        return ParsedSms(
            amountMinor = amountMinor,
            suggestedType = suggestedType,
            title = "SMS transaction",
            confidence = if (currencyAmountPattern.containsMatchIn(message)) 85 else 65,
            reviewNote = reviewNote,
            manualOnly = isRefund
        )
    }
}