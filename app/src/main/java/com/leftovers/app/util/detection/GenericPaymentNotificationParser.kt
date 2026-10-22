package com.leftovers.app.util.detection

import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.detection.CandidateStatus
import com.leftovers.app.data.detection.CandidateType
import com.leftovers.app.data.detection.DetectionConfidence
import com.leftovers.app.data.detection.TransactionCandidate
import java.util.regex.Pattern

object GenericPaymentNotificationParser : PaymentNotificationParser {

    override fun canHandle(packageName: String): Boolean = true // Fallback for all user-enabled apps

    private val REJECT_PATTERNS = listOf(
        Pattern.compile("(?i)(otp|verification code|one[- ]time password)"),
        Pattern.compile("(?i)(login alert|password changed|logged in from)"),
        Pattern.compile("(?i)(promo|discount|cashback offer|congratulations)"),
    )

    private val DEBIT_KEYWORDS = listOf("debited", "paid", "spent", "purchase", "sent", "withdrawn", "debit")
    private val CREDIT_KEYWORDS = listOf("credited", "received", "deposited", "cashback", "refund", "credit")

    private val DIGITS_REGEX = Pattern.compile("(?i)(?:a/c|acct|account|card)[^0-9]*([0-9]{3,4})")
    private val CURRENCY_AMOUNT_REGEX = Pattern.compile(
        "([€£$¥₹]|EUR|GBP|USD|CAD|AUD|CHF|JPY|NPR|Rs\\.?|INR)?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*([€£$¥₹]|EUR|GBP|USD|CAD|AUD|CHF|JPY|NPR|Rs\\.?|INR)?"
    )

    override fun parse(
        packageName: String,
        title: String,
        text: String,
        subText: String?,
        postTime: Long,
        fingerprint: String,
    ): TransactionCandidate? {
        val fullContent = "$title $text".trim()
        if (fullContent.isBlank()) return null

        for (pattern in REJECT_PATTERNS) {
            if (pattern.matcher(fullContent).find()) {
                return null
            }
        }

        // Determine if financial
        val lower = fullContent.lowercase()
        val isDebit = DEBIT_KEYWORDS.any { lower.contains(it) }
        val isCredit = CREDIT_KEYWORDS.any { lower.contains(it) }

        if (!isDebit && !isCredit) {
            // Not a recognized transaction statement
            return null
        }

        val type = if (isCredit) CandidateType.INCOME else CandidateType.EXPENSE

        // Extract amount & currency
        val amountMatcher = CURRENCY_AMOUNT_REGEX.matcher(fullContent)
        var amountMinor: Long? = null
        var currency: String? = null

        while (amountMatcher.find()) {
            val currPrefix = amountMatcher.group(1)
            val numStr = amountMatcher.group(2)
            val currSuffix = amountMatcher.group(3)

            val parsedMinor = PaymentParserUtils.parseAmountToMinor(numStr.orEmpty())
            if (parsedMinor != null && parsedMinor > 0) {
                amountMinor = parsedMinor
                val rawCurr = (currPrefix ?: currSuffix).orEmpty()
                if (rawCurr.isNotBlank()) {
                    currency = PaymentParserUtils.normalizeCurrency(rawCurr)
                }
                break
            }
        }

        if (amountMinor == null || amountMinor <= 0L) return null

        // Extract Account Digits
        val digitsMatcher = DIGITS_REGEX.matcher(fullContent)
        val digits = if (digitsMatcher.find()) digitsMatcher.group(1) else null

        // Extract merchant / info / narration
        val merchant = extractNarration(fullContent)

        val appLabel = SupportedPaymentApps.KNOWN_PRESETS.firstOrNull {
            it.packageName.equals(packageName, ignoreCase = true)
        }?.displayName ?: packageName

        return TransactionCandidate(
            amountMinor = amountMinor,
            currency = currency,
            merchant = merchant,
            type = type,
            status = CandidateStatus.COMPLETED,
            confidence = if (merchant.isNotBlank() && currency != null) DetectionConfidence.HIGH else DetectionConfidence.MEDIUM,
            epochDay = PaymentParserUtils.toEpochDay(postTime),
            timestamp = postTime,
            accountDigits = digits,
            source = SmsSuggestion.SOURCE_NOTIFICATION,
            packageName = packageName,
            sender = appLabel,
            rawFingerprint = fingerprint,
        )
    }

    private fun extractNarration(fullContent: String): String {
        val infoMatcher = Pattern.compile("(?i)(?:info|at|to|narration|towards)[:\\s]+([A-Za-z0-9&'’\\.\\-\\s]{2,40})").matcher(fullContent)
        if (infoMatcher.find()) {
            var raw = infoMatcher.group(1).orEmpty()
            val cutIndex = listOf(" on ", " with ", " dated ", " ref ", " txn ", " bal ", ".", ",").map { raw.indexOf(it, ignoreCase = true) }
                .filter { it > 0 }
                .minOrNull()
            if (cutIndex != null) {
                raw = raw.substring(0, cutIndex)
            }
            return PaymentParserUtils.cleanMerchantName(raw)
        }
        return ""
    }
}
