package com.finvault.app.sms

import com.finvault.app.data.repository.FinVaultRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {

    private fun parsed(body: String): ParsedSms {
        val result = SmsParser.parse(body)
        assertNotNull("Expected a transaction for: $body", result)
        return result!!
    }

    // ---- Accepted transactions -------------------------------------------------

    @Test
    fun creditCardSpend_usesCardAccountMerchantAndCategory() {
        val sms = parsed(
            "Rs.1,250.00 spent on HDFC Bank Card x1234 at SWIGGY on 2026-11-25:16:05:00. " +
                    "Avl Lmt: Rs 48,750.00. Not you? Call 18002586161"
        )
        assertEquals(125_000L, sms.amountMinor)
        assertEquals("EXPENSE", sms.suggestedType)
        assertEquals("Swiggy", sms.merchant)
        assertEquals(FinVaultRepository.CREDIT_CARD, sms.suggestedAccountId)
        assertEquals(FinVaultRepository.FOOD_EXPENSE, sms.suggestedCategoryId)
        assertEquals("1234", sms.accountHint)
    }

    @Test
    fun upiDebit_withPayeeCreditedLater_isExpenseFromBank() {
        val sms = parsed(
            "ICICI Bank Acct XX123 debited for Rs 250.00 on 12-Nov-26; UBER INDIA credited. " +
                    "UPI:432112345678. Call 18002662 for dispute."
        )
        assertEquals(25_000L, sms.amountMinor)
        assertEquals("EXPENSE", sms.suggestedType)
        assertEquals(FinVaultRepository.BANK, sms.suggestedAccountId)
        assertEquals(FinVaultRepository.TRANSPORT_EXPENSE, sms.suggestedCategoryId)
    }

    @Test
    fun sbiUpiDebit_withoutCurrencySymbol_usesContextualAmount() {
        val sms = parsed(
            "Dear UPI user A/C X1234 debited by 250.0 on date 12Nov26 trf to AMAZON PAY Refno 432112345678. " +
                    "If not u? call 1800111109. -SBI"
        )
        assertEquals(25_000L, sms.amountMinor)
        assertEquals("EXPENSE", sms.suggestedType)
        assertEquals("Amazon Pay", sms.merchant)
        assertEquals(FinVaultRepository.SHOPPING_EXPENSE, sms.suggestedCategoryId)
    }

    @Test
    fun hdfcSentUpi_extractsVpaPayee() {
        val sms = parsed(
            "Sent Rs.499.00 From HDFC Bank A/C *1234 To zomato@hdfcbank On 12/11/26 Ref 432112345678 " +
                    "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
        )
        assertEquals(49_900L, sms.amountMinor)
        assertEquals("EXPENSE", sms.suggestedType)
        assertEquals("zomato", sms.merchant)
        assertEquals(FinVaultRepository.FOOD_EXPENSE, sms.suggestedCategoryId)
    }

    @Test
    fun balanceBeforeAmount_isNotUsedAsTransactionAmount() {
        val sms = parsed(
            "Your A/c XX9876 has Avl Bal of INR 10,000.00. INR 750.00 debited on 12-11-26 via UPI Ref 1234567."
        )
        assertEquals(75_000L, sms.amountMinor)
    }

    @Test
    fun incomingUpiCredit_isIncome() {
        val sms = parsed(
            "Rs.2,000.00 credited to your A/c XX1234 on 12-11-26 by A/c linked to VPA john.doe@okaxis (UPI Ref No 432112345678)."
        )
        assertEquals(200_000L, sms.amountMinor)
        assertEquals("INCOME", sms.suggestedType)
        assertEquals(FinVaultRepository.OTHER_INCOME, sms.suggestedCategoryId)
        assertEquals(FinVaultRepository.BANK, sms.suggestedAccountId)
    }

    @Test
    fun salaryCredit_suggestsSalaryCategory() {
        val sms = parsed(
            "INR 85,000.00 credited to A/c no. XX4321 on 01-11-26 by NEFT from ACME CORP - SALARY NOV. Avl Bal INR 92,100.50"
        )
        assertEquals(8_500_000L, sms.amountMinor)
        assertEquals("INCOME", sms.suggestedType)
        assertEquals(FinVaultRepository.SALARY_INCOME, sms.suggestedCategoryId)
        assertEquals("Salary", sms.title)
    }

    @Test
    fun atmWithdrawal_isTransferToCash() {
        val sms = parsed(
            "Rs 5,000.00 withdrawn at ATM from A/c XX1234 on 12-11-26. Avl Bal Rs 20,000.00"
        )
        assertEquals("TRANSFER", sms.suggestedType)
        assertEquals(FinVaultRepository.BANK, sms.suggestedAccountId)
        assertEquals(FinVaultRepository.CASH, sms.suggestedDestinationId)
    }

    @Test
    fun creditCardBillPayment_isTransferFromBankToCard() {
        val sms = parsed(
            "Payment of Rs 15,000.00 has been received on your HDFC Bank Credit Card ending 1234. Thank you."
        )
        assertEquals("TRANSFER", sms.suggestedType)
        assertEquals(FinVaultRepository.BANK, sms.suggestedAccountId)
        assertEquals(FinVaultRepository.CREDIT_CARD, sms.suggestedDestinationId)
    }

    @Test
    fun mutualFundSip_isTransferToInvestments() {
        val sms = parsed(
            "Rs.5,000.00 debited from A/c XX1234 towards SIP for Groww mutual fund on 05-11-26. Ref 998877."
        )
        assertEquals("TRANSFER", sms.suggestedType)
        assertEquals(FinVaultRepository.INVESTMENTS, sms.suggestedDestinationId)
    }

    @Test
    fun refund_isManualOnly() {
        val sms = parsed(
            "Refund of Rs 799.00 from AMAZON has been credited to your card XX1234 on 12-11-26."
        )
        assertTrue(sms.manualOnly)
    }

    @Test
    fun rentKeywordDoesNotMatchInsideOtherWords() {
        val sms = parsed(
            "Rs 300.00 debited from your current a/c XX1234 at PARENT SHOP on 12-11-26. Ref 12345"
        )
        assertEquals(FinVaultRepository.GENERAL_EXPENSE, sms.suggestedCategoryId)
    }

    // ---- Rejected messages -----------------------------------------------------

    @Test
    fun otpMessage_isRejected() {
        assertNull(
            SmsParser.parse(
                "123456 is your OTP for txn of INR 2,500.00 at AMAZON on HDFC Bank card ending 1234. Do not share."
            )
        )
    }

    @Test
    fun promotionalMessage_isRejected() {
        assertNull(
            SmsParser.parse(
                "Congratulations! You are eligible for a pre-approved loan of Rs 5,00,000 credited instantly to your account. Apply now: http://x.y"
            )
        )
    }

    @Test
    fun wordEndingInRs_isNotAnAmount() {
        assertNull(SmsParser.parse("Dear customers 500 upi payments were processed today"))
    }

    @Test
    fun collectRequest_isRejected() {
        assertNull(
            SmsParser.parse(
                "JOHN has requested money from you on Google Pay. On approving, INR 500.00 will be debited from your A/c XX1234."
            )
        )
    }

    @Test
    fun upcomingAutoDebit_isRejected() {
        assertNull(
            SmsParser.parse(
                "Rs 649.00 will be debited from your A/c XX1234 on 15-11-26 towards NETFLIX mandate."
            )
        )
    }

    @Test
    fun cardStatementReminder_isRejected() {
        assertNull(
            SmsParser.parse(
                "Your HDFC Bank Credit Card XX1234 statement is generated. Total amount due Rs 12,345.00, payment due by 05-12-26."
            )
        )
    }

    @Test
    fun failedTransaction_isRejected() {
        assertNull(
            SmsParser.parse(
                "Your transaction of Rs 1,200.00 on card XX1234 at FLIPKART was declined due to insufficient balance."
            )
        )
    }

    @Test
    fun balanceOnlyMessage_isRejected() {
        assertNull(
            SmsParser.parse("Avl Bal in your A/c XX1234 is INR 12,000.00 as on 12-11-26. Txn details on the app.")
        )
    }

    @Test
    fun casualChat_isRejected() {
        assertNull(SmsParser.parse("I paid 500 for the movie tickets, send me later"))
    }
}
