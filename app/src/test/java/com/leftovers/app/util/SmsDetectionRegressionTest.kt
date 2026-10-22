package com.leftovers.app.util

import com.leftovers.app.data.SmsSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDetectionRegressionTest {

    @Test
    fun smsParserPreservesExistingBehavior() {
        val sms = "Rs.450.00 debited from A/c XX9876 to VPA zomato@icici on 15-10-26"
        val check = SmsParser.check(sms)
        assertNotNull(check.parsed)
        assertEquals(45_000L, check.parsed!!.amountMinor)
        assertEquals("Zomato", check.parsed!!.merchant)
        assertEquals("9876", SmsParser.accountDigits(sms))
    }

    @Test
    fun smsSuggestionSupportsAllSources() {
        val smsSuggestion = SmsSuggestion(
            amountMinor = 1000L,
            merchant = "Shop",
            sender = "BANK",
            epochDay = 20000L,
            body = "hash1",
            source = SmsSuggestion.SOURCE_SMS,
        )
        assertEquals(SmsSuggestion.SOURCE_SMS, smsSuggestion.source)

        val notifSuggestion = SmsSuggestion(
            amountMinor = 2000L,
            merchant = "Revolut Shop",
            sender = "Revolut",
            epochDay = 20000L,
            body = "hash2",
            source = SmsSuggestion.SOURCE_NOTIFICATION,
        )
        assertEquals(SmsSuggestion.SOURCE_NOTIFICATION, notifSuggestion.source)

        val emailSuggestion = SmsSuggestion(
            amountMinor = 3000L,
            merchant = "Amazon",
            sender = "alerts@bank.com",
            epochDay = 20000L,
            body = "hash3",
            source = SmsSuggestion.SOURCE_EMAIL,
        )
        assertEquals(SmsSuggestion.SOURCE_EMAIL, emailSuggestion.source)
    }

    @Test
    fun legacyDuplicatePaymentsPreserved() {
        val now = System.currentTimeMillis()
        val seen = listOf(
            com.leftovers.app.data.AmountAt(5000L, now - 1000L),
        )
        assertTrue(com.leftovers.app.data.DuplicatePayments.isDuplicate(5000L, now, seen))
    }
}
