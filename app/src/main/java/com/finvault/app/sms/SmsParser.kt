package com.finvault.app.sms

import com.finvault.app.data.repository.FinVaultRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class ParsedSms(
    val amountMinor: Long,
    val suggestedType: String,
    val title: String,
    val confidence: Int,
    val reviewNote: String,
    val manualOnly: Boolean,
    /** Merchant / payee / payer extracted from the message, if any. */
    val merchant: String? = null,
    /** Masked account or card suffix such as "1234". */
    val accountHint: String? = null,
    /** Best-guess category account id (expense or income category). */
    val suggestedCategoryId: String? = null,
    /** Best-guess source account (expense / transfer) or receiving account (income). */
    val suggestedAccountId: String = FinVaultRepository.BANK,
    /** Best-guess destination account for transfers. */
    val suggestedDestinationId: String? = null
)

/**
 * Heuristic parser for Indian bank / card / UPI transaction SMS.
 *
 * Only past, completed money movements are accepted. OTPs, promotions,
 * payment requests, reminders, failed transactions and balance-only
 * messages are rejected so that they never reach the review queue.
 */
object SmsParser {

    private val otpPattern = Regex(
        """(?i)\b(otp|one[\s-]?time[\s-]?password|verification code|security code|authentication code|passcode)\b"""
    )

    // A completed money movement verb. Required for every accepted message.
    private val transactionVerbPattern = Regex(
        """(?i)\b(debited|credited|withdrawn|deposited|spent|paid|purchased?|transferred|received|sent|deducted|charged|refunded|reversed|txn|transaction)\b"""
    )

    // A reference to an account / card / payment rail.
    private val instrumentPattern = Regex(
        """(?i)(\ba/?c\b|\bacct?\b|\baccount\b|\bcard\b|\bupi\b|\bvpa\b|\bwallet\b|\bneft\b|\bimps\b|\brtgs\b|\bref(?:\s*no)?\b|\butr\b|\batm\b|\bpos\b|[x*]{1,}\d{3,6})"""
    )

    private val maskedAccountPattern = Regex(
        """(?i)(?:a/?c|acct?|account|card|ending|no\.?)\s*(?:no\.?\s*)?(?:[x*.]+\s*)?(\d{3,6})\b|[x*]{2,}(\d{3,6})\b"""
    )

    private val promotionalPattern = Regex(
        """(?i)\b(offer|offers|cashback of upto|win|winner|congratulations|apply now|pre-?approved|eligible for|get upto|get up to|upto \d+% off|limited period|hurry|voucher|coupon|lucky draw|click here to claim|loan of|instant loan|credit limit increase|shop now|sale)\b"""
    )

    // Payment requests, reminders and scheduled debits are not completed transactions.
    private val requestOrFuturePattern = Regex(
        """(?i)(has requested|requested money|collect request|request(?:ed)? (?:of|for) (?:rs|inr|₹)|will be (?:debited|charged|deducted|auto[\s-]?debited)|is due (?:on|by)|due date|payment due|amount due|bill (?:is )?generated|statement (?:is )?generated|please pay|pay now|to be debited|scheduled for)"""
    )

    private val failedPattern = Regex(
        """(?i)\b(failed|declined|unsuccessful|was not successful|could not be processed|rejected|insufficient (?:funds|balance))\b"""
    )

    // ₹ / INR / Rs not preceded by a letter, so "Customers 500" is not an amount.
    private val currencyAmountPattern = Regex(
        """(?i)(?<![A-Za-z])(?:₹|INR|Rs\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )

    private val contextualAmountPattern = Regex(
        """(?i)\b(?:debited|credited|withdrawn|deposited|spent|paid|purchased|transferred|received|sent)\s+(?:by|with|of|for)?\s*(?:₹|INR|Rs\.?)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )

    // Words that, just before an amount, mean the amount is a balance / limit rather than the transaction.
    private val balanceContextPattern = Regex(
        """(?i)\b(bal|balance|avl|avail|available|limit|lmt|outstanding|o/s|due|min\.?)\W*(?:is|of|:|-)?\W*$"""
    )

    private val balanceKeywordPattern = Regex(
        """(?i)\b(avl|avail|available|bal|balance|limit|lmt|outstanding)\b"""
    )

    private val debitPattern = Regex(
        """(?i)\b(debited|withdrawn|spent|paid|purchased?|sent|deducted|charged|dr)\b"""
    )

    private val creditPattern = Regex(
        """(?i)\b(credited|received|deposited|cr)\b"""
    )

    private val refundPattern = Regex(
        """(?i)\b(refund|refunded|reversal|reversed|chargeback)\b"""
    )

    private val salaryPattern = Regex("""(?i)\b(salary|sal cr|payroll)\b""")

    private val atmPattern = Regex("""(?i)\b(atm|cash withdrawal|withdrawn at|cash wdl)\b""")

    private val creditCardPattern = Regex("""(?i)\b(credit card|cc\b|card (?:no\.? )?(?:ending|xx|\*{2}))""")

    private val debitCardPattern = Regex("""(?i)\bdebit card\b""")

    private val cardPattern = Regex("""(?i)\bcard\b""")

    private val cardBillPaymentPattern = Regex(
        """(?i)(payment (?:of .{0,30})?(?:has been )?received|received (?:a )?payment|payment .{0,20}credited|thank you for (?:your )?payment).{0,80}card|card.{0,80}(payment (?:of .{0,30})?(?:has been )?received|thank you for (?:your )?payment)"""
    )

    private val investmentPattern = Regex(
        """(?i)\b(mutual fund|mf\b|sip\b|zerodha|groww|upstox|kuvera|coin by|indmoney|paytm money|nps\b|ppf\b|etmoney|angel ?one|smallcase|bse star|nse clearing|iccl|cams|kfin)"""
    )

    private val selfTransferPattern = Regex(
        """(?i)\b(self transfer|own account|to self|transfer to your)\b"""
    )

    private val merchantPatterns = listOf(
        Regex("""(?i)\bat\s+([A-Za-z0-9&'._\- ]{2,40}?)(?=\s+on\b|\s+for\b|\s+via\b|\s+ref|\s+txn|\s+avl|\s*[.,;(]|\s+\d{1,2}[-/]|$)"""),
        Regex("""(?i)\b(?:trf to|paid to|sent to|transferred to|towards|to vpa|to)\s+(?:vpa\s+)?([A-Za-z0-9@&'._\- ]{2,40}?)(?=\s+on\b|\s+ref|\s+upi|\s+via\b|\s+avl|\s+not you|\s*[.,;(]|\s+\d{1,2}[-/]|$)"""),
        Regex("""(?i)\b(?:from|by)\s+(?:vpa\s+)?([A-Za-z0-9@&'._\- ]{2,40}?)(?=\s+on\b|\s+ref|\s+upi|\s+via\b|\s+avl|\s*[.,;(]|\s+\d{1,2}[-/]|$)"""),
        Regex("""(?i)\binfo[:\s]+([A-Za-z0-9&'._\-/ ]{2,40}?)(?=\s*[.,;]|\s+avl|$)""")
    )

    // Phrases that look like a merchant capture but are actually the user's own account or boilerplate.
    private val merchantStopWords = Regex(
        """(?i)^(your|you|a/?c\b|acct?\b|account|bank\b|card|the\b|ur\b|my\b|self\b|beneficiary|upi\b|imps|neft|rtgs|atm\b|pos\b|xx|rs\b|inr\b|block|report|dispute|stop|unsubscribe|know|check|view|avoid|call|sms\b|customer|help|reach|contact|ensure|protect|secure|\d)"""
    )

    private data class CategoryRule(val categoryId: String, val pattern: Regex)

    private val categoryRules = listOf(
        CategoryRule(
            FinVaultRepository.FOOD_EXPENSE,
            Regex("""(?i)\b(swiggy|zomato|restaurant|cafe|caf[eé]|dominos|domino's|pizza|mcdonald|kfc|burger|starbucks|chaayos|food|eats|dining|bakery|blinkit|zepto|instamart|bigbasket|grofers|dmart|grocer|supermarket|kitchen)""")
        ),
        CategoryRule(
            FinVaultRepository.TRANSPORT_EXPENSE,
            Regex("""(?i)\b(uber|ola\b|olacabs|rapido|metro|irctc|railway|fuel|petrol|diesel|hpcl|iocl|bpcl|indian oil|bharat petroleum|shell|fastag|parking|redbus|indigo|air india|vistara|akasa|makemytrip|goibibo|cleartrip|yulu|blusmart)""")
        ),
        CategoryRule(
            FinVaultRepository.SHOPPING_EXPENSE,
            Regex("""(?i)\b(amazon|amzn|flipkart|myntra|ajio|nykaa|meesho|croma|reliance digital|vijay sales|decathlon|ikea|lifestyle|westside|pantaloons|tata cliq|snapdeal|lenskart|mall|store|mart\b)""")
        ),
        CategoryRule(
            FinVaultRepository.HOUSING_EXPENSE,
            Regex("""(?i)\b(rent\b|electricity|bescom|msedcl|tata power|adani electricity|bses|water bill|maintenance|broadband|jio ?fiber|airtel|act fibernet|piped gas|igl|mgl|society|nobroker|housing)""")
        )
    )

    fun parse(body: String): ParsedSms? {

        val message = body.trim().replace(Regex("""\s+"""), " ")

        if (message.isBlank()) return null

        // Never store OTP or verification messages.
        if (otpPattern.containsMatchIn(message)) return null

        if (!transactionVerbPattern.containsMatchIn(message)) return null
        if (!instrumentPattern.containsMatchIn(message)) return null

        if (requestOrFuturePattern.containsMatchIn(message)) return null
        if (failedPattern.containsMatchIn(message)) return null

        val hasMaskedAccount = maskedAccountPattern.containsMatchIn(message)

        // Marketing messages rarely reference a masked account number.
        if (!hasMaskedAccount && promotionalPattern.containsMatchIn(message)) return null

        val (amountMinor, fromCurrencySymbol) = extractAmount(message) ?: return null

        val isRefund = refundPattern.containsMatchIn(message)
        val direction = detectDirection(message)
        val isCredit = direction == Direction.CREDIT
        val isDebit = direction == Direction.DEBIT

        val accountHint = maskedAccountPattern.find(message)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        }

        val isCreditCard = creditCardPattern.containsMatchIn(message) ||
                (cardPattern.containsMatchIn(message) &&
                        !debitCardPattern.containsMatchIn(message) &&
                        Regex("""(?i)\bspent\b""").containsMatchIn(message))

        val merchant = extractMerchant(message)
        val categoryId = suggestCategory(message, merchant, isCredit)

        val isCardBillPayment = isCredit && cardBillPaymentPattern.containsMatchIn(message)
        val isAtm = isDebit && atmPattern.containsMatchIn(message)
        val isInvestment = isDebit && investmentPattern.containsMatchIn(message)
        val isSelfTransfer = selfTransferPattern.containsMatchIn(message)
        val isSalary = isCredit && salaryPattern.containsMatchIn(message)

        val type: String
        val accountId: String
        var destinationId: String? = null
        val title: String
        val note: String

        when {
            isRefund -> {
                type = "INCOME"
                accountId = if (isCreditCard) FinVaultRepository.CREDIT_CARD else FinVaultRepository.BANK
                title = merchant?.let { "Refund from $it" } ?: "Refund"
                note = "Looks like a refund or reversal. Refunds can't be posted from SMS yet — " +
                        "add it manually or ignore it."
            }

            isCardBillPayment -> {
                type = "TRANSFER"
                accountId = FinVaultRepository.BANK
                destinationId = FinVaultRepository.CREDIT_CARD
                title = "Credit card bill payment"
                note = "Looks like a credit card bill payment — recorded as a transfer from your bank to the card."
            }

            isAtm -> {
                type = "TRANSFER"
                accountId = FinVaultRepository.BANK
                destinationId = FinVaultRepository.CASH
                title = "ATM withdrawal"
                note = "Cash withdrawal — recorded as a transfer from your bank to your cash wallet."
            }

            isInvestment -> {
                type = "TRANSFER"
                accountId = FinVaultRepository.BANK
                destinationId = FinVaultRepository.INVESTMENTS
                title = merchant?.let { "Investment · $it" } ?: "Investment"
                note = "Looks like an investment — recorded as a transfer to Investments so it shows under 'Invested'."
            }

            isSelfTransfer -> {
                type = "TRANSFER"
                accountId = FinVaultRepository.BANK
                destinationId = FinVaultRepository.CASH
                title = "Self transfer"
                note = "Looks like a transfer between your own accounts. Pick the right accounts."
            }

            isCredit -> {
                type = "INCOME"
                accountId = FinVaultRepository.BANK
                title = when {
                    isSalary -> "Salary"
                    merchant != null -> "From $merchant"
                    else -> "Money received"
                }
                note = if (isSalary) {
                    "Salary credit detected."
                } else {
                    "Incoming payment. Confirm whether this is income or a transfer from your own account."
                }
            }

            else -> {
                type = "EXPENSE"
                accountId = if (isCreditCard) FinVaultRepository.CREDIT_CARD else FinVaultRepository.BANK
                title = merchant ?: when {
                    isCreditCard -> "Card payment"
                    Regex("""(?i)\bupi\b""").containsMatchIn(message) -> "UPI payment"
                    else -> "Payment"
                }
                note = "Outgoing payment. Check the account and category before adding it."
            }
        }

        var confidence = 45
        if (fromCurrencySymbol) confidence += 20
        if (hasMaskedAccount) confidence += 15
        if (direction != Direction.UNKNOWN) confidence += 10
        if (merchant != null) confidence += 5

        return ParsedSms(
            amountMinor = amountMinor,
            suggestedType = type,
            title = title.take(60),
            confidence = confidence.coerceAtMost(95),
            reviewNote = note,
            manualOnly = isRefund,
            merchant = merchant,
            accountHint = accountHint,
            suggestedCategoryId = when (type) {
                "INCOME" -> if (isSalary) FinVaultRepository.SALARY_INCOME else FinVaultRepository.OTHER_INCOME
                "EXPENSE" -> categoryId
                else -> null
            },
            suggestedAccountId = accountId,
            suggestedDestinationId = destinationId
        )
    }

    private enum class Direction { DEBIT, CREDIT, UNKNOWN }

    /**
     * Uses whichever direction keyword appears first. Messages such as
     * "A/c XX12 debited for Rs 250; JOHN credited" mention both, and the
     * first keyword describes what happened to the user's account.
     */
    private fun detectDirection(message: String): Direction {
        val debit = debitPattern.find(message)?.range?.first
        val credit = creditPattern.find(message)?.range?.first
        return when {
            debit == null && credit == null -> Direction.UNKNOWN
            debit == null -> Direction.CREDIT
            credit == null -> Direction.DEBIT
            debit <= credit -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    /** Returns the transaction amount and whether it was introduced by a currency symbol. */
    private fun extractAmount(message: String): Pair<Long, Boolean>? {
        for (match in currencyAmountPattern.findAll(message)) {
            if (isBalanceAmount(message, match.range.first)) continue
            val minor = toMinor(match.groupValues[1]) ?: continue
            if (minor > 0) return minor to true
        }
        val contextual = contextualAmountPattern.find(message)?.groupValues?.get(1) ?: return null
        val minor = toMinor(contextual) ?: return null
        return if (minor > 0) minor to false else null
    }

    /**
     * True when the amount belongs to a balance / limit / due clause, e.g.
     * "Avl Bal: Rs 500" or "Avl Bal in your A/c XX12 is INR 500".
     */
    private fun isBalanceAmount(message: String, amountStart: Int): Boolean {
        val before = message.substring(maxOf(0, amountStart - 48), amountStart)
        if (balanceContextPattern.containsMatchIn(before)) return true
        val keyword = balanceKeywordPattern.findAll(before).lastOrNull() ?: return false
        val afterKeyword = before.substring(keyword.range.last + 1)
        return !debitPattern.containsMatchIn(afterKeyword) &&
                !creditPattern.containsMatchIn(afterKeyword) &&
                !afterKeyword.contains('.') &&
                !afterKeyword.contains(';')
    }

    private fun toMinor(text: String): Long? = try {
        BigDecimal(text.replace(",", "").trimEnd('.'))
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    } catch (_: Exception) {
        null
    }

    internal fun extractMerchant(message: String): String? {
        for (pattern in merchantPatterns) {
            for (match in pattern.findAll(message)) {
                val cleaned = cleanMerchant(match.groupValues[1]) ?: continue
                return cleaned
            }
        }
        return null
    }

    private fun cleanMerchant(raw: String): String? {
        var value = raw.trim().trim('.', '-', ':', ',', '/')
        if (value.contains('@')) {
            // UPI VPA such as "swiggy.instamart@icici" -> "swiggy.instamart"
            value = value.substringBefore('@')
        }
        value = value.replace(Regex("""\s+"""), " ")
        if (value.length < 2) return null
        if (merchantStopWords.containsMatchIn(value)) return null
        if (value.count { it.isDigit() } > value.length / 2) return null
        return prettify(value)
    }

    private fun prettify(value: String): String {
        val isShouting = value == value.uppercase(Locale.ROOT)
        val text = if (isShouting) {
            value.lowercase(Locale.ROOT).split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        } else {
            value
        }
        return text.take(32)
    }

    private fun suggestCategory(message: String, merchant: String?, isCredit: Boolean): String {
        if (isCredit) return FinVaultRepository.OTHER_INCOME
        val haystack = listOfNotNull(merchant, message).joinToString(" ")
        return categoryRules.firstOrNull { it.pattern.containsMatchIn(haystack) }?.categoryId
            ?: FinVaultRepository.GENERAL_EXPENSE
    }
}
