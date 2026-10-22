package com.leftovers.app.util.detection

import com.leftovers.app.data.detection.CandidateStatus
import com.leftovers.app.data.detection.CandidateType
import com.leftovers.app.data.detection.DetectionConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationParserTest {

    @Test
    fun revolutExpenseWithCardDigits() {
        val candidate = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Paid €14.50",
            text = "Paid €14.50 to Starbucks with card ...1234",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-1",
        )
        assertNotNull(candidate)
        assertEquals(1450L, candidate!!.amountMinor)
        assertEquals("EUR", candidate.currency)
        assertEquals("Starbucks", candidate.merchant)
        assertEquals("1234", candidate.accountDigits)
        assertEquals(CandidateType.EXPENSE, candidate.type)
        assertEquals(CandidateStatus.COMPLETED, candidate.status)
        assertEquals(DetectionConfidence.HIGH, candidate.confidence)
    }

    @Test
    fun revolutPoundExpense() {
        val candidate = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Revolut",
            text = "You spent £8.20 at Pret A Manger with your card",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-2",
        )
        assertNotNull(candidate)
        assertEquals(820L, candidate!!.amountMinor)
        assertEquals("GBP", candidate.currency)
        assertEquals("Pret A Manger", candidate.merchant)
        assertEquals(CandidateType.EXPENSE, candidate.type)
    }

    @Test
    fun revolutIncomingAndRefund() {
        val income = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Payment received",
            text = "Received €50.00 from Jane Doe",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-3",
        )
        assertNotNull(income)
        assertEquals(5000L, income!!.amountMinor)
        assertEquals("EUR", income.currency)
        assertEquals(CandidateType.INCOME, income.type)
        assertEquals("Jane Doe", income.merchant)

        val p2pIncome = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Pawan Kumar Ghising",
            text = "sent you €1. Tap to say thank you",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-pawan",
        )
        assertNotNull(p2pIncome)
        assertEquals(100L, p2pIncome!!.amountMinor)
        assertEquals("EUR", p2pIncome.currency)
        assertEquals(CandidateType.INCOME, p2pIncome.type)
        assertEquals("Pawan Kumar Ghising", p2pIncome.merchant)

        val refund = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Refund",
            text = "Amazon refunded you €19.99",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-4",
        )
        assertNotNull(refund)
        assertEquals(1999L, refund!!.amountMinor)
        assertEquals(CandidateType.REFUND, refund.type)
        assertEquals("Amazon", refund.merchant)
    }

    @Test
    fun revolutVaultTransfer() {
        val transfer = RevolutNotificationParser.parse(
            packageName = "com.revolut.revolut",
            title = "Revolut",
            text = "Transferred €100.00 to Savings Vault",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "revolut-fp-5",
        )
        assertNotNull(transfer)
        assertEquals(CandidateType.TRANSFER, transfer!!.type)
    }

    @Test
    fun revolutSafetyFiltersRejectNonFinancialAlerts() {
        assertNull(
            RevolutNotificationParser.parse(
                "com.revolut.revolut",
                "Security code",
                "Your one-time passcode is 432109",
                null, 0L, "f1"
            )
        )
        assertNull(
            RevolutNotificationParser.parse(
                "com.revolut.revolut",
                "Card frozen",
                "Your card ending in 1234 has been frozen",
                null, 0L, "f2"
            )
        )
        assertNull(
            RevolutNotificationParser.parse(
                "com.revolut.revolut",
                "Crypto Alert",
                "Bitcoin is up 5% today",
                null, 0L, "f3"
            )
        )
    }

    @Test
    fun esewaMerchantPaymentWithTxnId() {
        val candidate = EsewaNotificationParser.parse(
            packageName = "com.f1soft.esewa",
            title = "Payment Successful",
            text = "Payment of NPR 1,500.00 to Bhatbhateni Supermarket is successful. Txn ID: 987654321",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "esewa-fp-1",
        )
        assertNotNull(candidate)
        assertEquals(150_000L, candidate!!.amountMinor)
        assertEquals("NPR", candidate.currency)
        assertEquals("Bhatbhateni Supermarket", candidate.merchant)
        assertEquals("987654321", candidate.referenceId)
        assertEquals(CandidateType.EXPENSE, candidate.type)
        assertEquals(DetectionConfidence.HIGH, candidate.confidence)
    }

    @Test
    fun esewaFundTransferAndCashback() {
        val transfer = EsewaNotificationParser.parse(
            packageName = "com.f1soft.esewa",
            title = "Fund Transfer",
            text = "NPR 500.00 sent to 9841234567 (Ram Bahadur) successfully. Ref: 12345",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "esewa-fp-2",
        )
        assertNotNull(transfer)
        assertEquals(50_000L, transfer!!.amountMinor)
        assertEquals("Ram Bahadur", transfer.merchant)
        assertEquals("12345", transfer.referenceId)

        val cashback = EsewaNotificationParser.parse(
            packageName = "com.f1soft.esewa",
            title = "Cashback Received",
            text = "Cashback of NPR 50.00 credited to your wallet for Topup.",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "esewa-fp-3",
        )
        assertNotNull(cashback)
        assertEquals(5_000L, cashback!!.amountMinor)
        assertEquals(CandidateType.REFUND, cashback.type)
    }

    @Test
    fun esewaSafetyFilterRejectsOtpAndOffers() {
        assertNull(
            EsewaNotificationParser.parse(
                "com.f1soft.esewa",
                "eSewa OTP",
                "Your OTP for eSewa login is 987123. Do not share.",
                null, 0L, "f1"
            )
        )
        assertNull(
            EsewaNotificationParser.parse(
                "com.f1soft.esewa",
                "Special Offer",
                "Get flat 10% cashback on your next movie ticket!",
                null, 0L, "f2"
            )
        )
    }

    @Test
    fun genericPaymentNotificationParser() {
        val debit = GenericPaymentNotificationParser.parse(
            packageName = "com.f1soft.banksmart.siddhartha",
            title = "Debit Alert",
            text = "Rs 1,200.00 debited from A/C ...1234 for POS Purchase at Big Mart on 15-Oct",
            subText = null,
            postTime = 1_700_000_000_000L,
            fingerprint = "bank-fp-1",
        )
        assertNotNull(debit)
        assertEquals(120_000L, debit!!.amountMinor)
        assertEquals("NPR", debit.currency)
        assertEquals("1234", debit.accountDigits)
        assertEquals("Big Mart", debit.merchant)
        assertEquals(CandidateType.EXPENSE, debit.type)
    }
}
