package com.finvault.app.data.repository

import com.finvault.app.data.repository.SameMovement.Movement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameMovementTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L
    private val hdfc = "bank_hdfc_2397"

    private fun sms(type: String, from: String? = null, to: String? = null, at: Long = t0, ref: String? = null, amount: Long = 210_000) =
        Movement(type, amount, at, from, to, ref, isEmail = false)

    private fun email(type: String, from: String? = null, to: String? = null, at: Long = t0, ref: String? = null, amount: Long = 210_000) =
        Movement(type, amount, at, from, to, ref, isEmail = true)

    @Test fun sameExpense_emailTwoHoursLater_isDuplicate() =
        assertTrue(SameMovement.matches(sms("EXPENSE", from = hdfc), email("EXPENSE", from = hdfc, at = t0 + 120 * min)))

    @Test fun smsBecameSelfTransfer_emailIsExpense_isDuplicate() =
        assertTrue(SameMovement.matches(sms("TRANSFER", from = hdfc, to = FinVaultRepository.OTHER_OWN), email("EXPENSE", from = hdfc)))

    @Test fun differentReferenceButWithinMinutes_isDuplicate() =
        assertTrue(SameMovement.matches(sms("EXPENSE", from = hdfc, ref = "544462711967"), email("EXPENSE", from = hdfc, at = t0 + 5 * min, ref = "HDFC00012345")))

    @Test fun emailWithoutAccountNumber_closeInTime_isDuplicate() =
        assertTrue(SameMovement.matches(sms("EXPENSE", from = hdfc), email("EXPENSE", from = FinVaultRepository.BANK, at = t0 + 10 * min)))

    @Test fun cardSpend_bothChannels_isDuplicate() =
        assertTrue(SameMovement.matches(sms("EXPENSE", from = "card_hdfc_4321"), email("EXPENSE", from = "card_hdfc_4321", at = t0 + 40 * min)))

    @Test fun incomeAndRefundSameAccount_isDuplicate() =
        assertTrue(SameMovement.matches(sms("INCOME", to = hdfc), email("INCOME", to = hdfc, at = t0 + 30 * min)))

    @Test fun differentAccount_isNotDuplicate() =
        assertFalse(SameMovement.matches(sms("EXPENSE", from = hdfc), email("EXPENSE", from = "bank_icici_1111")))

    @Test fun differentAmount_isNotDuplicate() =
        assertFalse(SameMovement.matches(sms("EXPENSE", from = hdfc), email("EXPENSE", from = hdfc, amount = 210_001)))

    @Test fun oppositeDirection_isNotDuplicate() =
        assertFalse(SameMovement.matches(sms("EXPENSE", from = hdfc), email("INCOME", to = hdfc)))

    @Test fun tooFarApart_isNotDuplicate() =
        assertFalse(SameMovement.matches(sms("EXPENSE", from = hdfc), email("EXPENSE", from = hdfc, at = t0 + 4 * 60 * min)))

    @Test fun sameReferenceDaysApart_isDuplicate() =
        assertTrue(SameMovement.matches(sms("EXPENSE", from = hdfc, ref = "544462711967"), email("EXPENSE", from = hdfc, at = t0 + 30 * 60 * min, ref = "544462711967")))

    @Test fun genericBankVsCard_isNotDuplicate() =
        assertFalse(SameMovement.matches(sms("EXPENSE", from = "card_hdfc_4321"), email("EXPENSE", from = FinVaultRepository.BANK)))
}
