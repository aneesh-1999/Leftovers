package com.leftovers.app.util.detection

import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.detection.CandidateStatus
import com.leftovers.app.data.detection.CandidateType
import com.leftovers.app.data.detection.DetectionConfidence
import com.leftovers.app.data.detection.TransactionCandidate
import java.util.regex.Pattern

object RevolutNotificationParser : PaymentNotificationParser {

    override fun canHandle(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        return pkg == "com.revolut.revolut" || pkg == "com.revolut.business" || pkg.startsWith("com.revolut.")
    }

    private val REJECT_PATTERNS = listOf(
        Pattern.compile("(?i)(security code|one-time passcode|otp|verification code|verify your identity)"),
        Pattern.compile("(?i)(card (has been )?frozen|card un-?frozen)"),
        Pattern.compile("(?i)(statement is ready|monthly statement)"),
        Pattern.compile("(?i)(crypto price alert|bitcoin|ethereum|market alert)"),
        Pattern.compile("(?i)(invite friends|earn cash back|referral)"),
    )

    // Match card digits: "...1234", "ending in 1234", "card 1234"
    private val DIGITS_REGEX = Pattern.compile("(?i)(?:ending in|card|\\.{2,}|\\*{2,})\\s*(\\d{4})")

    // Match currency & amount: "€14.50", "£8.20", "$25.00", "14.50 EUR", "USD 50"
    private val CURRENCY_AMOUNT_REGEX = Pattern.compile(
        "([€£$¥₹]|EUR|GBP|USD|CAD|AUD|CHF|JPY|PLN|RON|HUF|CZK|SEK|NOK|DKK)?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*([€£$¥₹]|EUR|GBP|USD|CAD|AUD|CHF|JPY|PLN|RON|HUF|CZK|SEK|NOK|DKK)?"
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

        // 1. Safety Filter: Reject security/crypto/promotional alerts
        for (pattern in REJECT_PATTERNS) {
            if (pattern.matcher(fullContent).find()) {
                return null
            }
        }

        // 2. Check for Declined / Failed status
        val isDeclined = fullContent.contains("declined", ignoreCase = true) ||
            fullContent.contains("failed", ignoreCase = true) ||
            fullContent.contains("blocked", ignoreCase = true)

        if (isDeclined) {
            return TransactionCandidate(
                amountMinor = 0L,
                status = CandidateStatus.DECLINED,
                confidence = DetectionConfidence.LOW,
                source = SmsSuggestion.SOURCE_NOTIFICATION,
                packageName = packageName,
                sender = "Revolut",
                timestamp = postTime,
                rawFingerprint = fingerprint,
            )
        }

        // 3. Extract Card / Account digits
        val digitsMatcher = DIGITS_REGEX.matcher(fullContent)
        val accountDigits = if (digitsMatcher.find()) digitsMatcher.group(1) else null

        // 4. Direction & Type Classification
        val isRefund = fullContent.contains("refund", ignoreCase = true)
        val isTransfer = fullContent.contains("vault", ignoreCase = true) ||
            fullContent.contains("pocket", ignoreCase = true) ||
            fullContent.contains("transferred to", ignoreCase = true)
        val isIncome = isRefund ||
            fullContent.contains("received", ignoreCase = true) ||
            fullContent.contains("sent you", ignoreCase = true)

        val candidateType = when {
            isTransfer -> CandidateType.TRANSFER
            isRefund -> CandidateType.REFUND
            isIncome -> CandidateType.INCOME
            else -> CandidateType.EXPENSE
        }

        // 5. Extract Amount and Currency
        val amountMatcher = CURRENCY_AMOUNT_REGEX.matcher(fullContent)
        var amountMinor: Long? = null
        var currency: String? = null

        while (amountMatcher.find()) {
            val currPrefix = amountMatcher.group(1)
            val numStr = amountMatcher.group(2)
            val currSuffix = amountMatcher.group(3)

            val parsedMinor = PaymentParserUtils.parseAmountToMinor(numStr.orEmpty())
            if (parsedMinor != null && parsedMinor > 0) {
                // Pick the first valid monetary amount
                amountMinor = parsedMinor
                val rawCurr = (currPrefix ?: currSuffix).orEmpty()
                if (rawCurr.isNotBlank()) {
                    currency = PaymentParserUtils.normalizeCurrency(rawCurr)
                }
                break
            }
        }

        if (amountMinor == null || amountMinor <= 0L) {
            return null
        }

        // 6. Extract Merchant / Counterparty
        var merchant = extractMerchant(title, text, candidateType)

        // 7. Confidence resolution
        val confidence = if (currency != null && merchant.isNotBlank()) {
            DetectionConfidence.HIGH
        } else {
            DetectionConfidence.MEDIUM
        }

        return TransactionCandidate(
            amountMinor = amountMinor,
            currency = currency,
            merchant = merchant,
            type = candidateType,
            status = CandidateStatus.COMPLETED,
            confidence = confidence,
            epochDay = PaymentParserUtils.toEpochDay(postTime),
            timestamp = postTime,
            accountDigits = accountDigits,
            source = SmsSuggestion.SOURCE_NOTIFICATION,
            packageName = packageName,
            sender = "Revolut",
            rawFingerprint = fingerprint,
        )
    }

    private fun extractMerchant(title: String, text: String, type: CandidateType): String {
        // Strip out notification action callouts like "Tap to say thank you", "Say thanks", "Tap to view"
        val cleanText = text
            .replace(Regex("(?i)[.·•]?\\s*tap to say thank you.*"), "")
            .replace(Regex("(?i)[.·•]?\\s*say thank you.*"), "")
            .replace(Regex("(?i)[.·•]?\\s*say thanks.*"), "")
            .replace(Regex("(?i)[.·•]?\\s*tap to (?:view|reply|respond|see).*"), "")
            .trim()

        val cleanTitle = title
            .replace(Regex("(?i)[.·•]?\\s*tap to say thank you.*"), "")
            .trim()

        // 1. Check if title itself is a person's name or merchant name
        // (e.g. Title: "Pawan Kumar Ghising", Text: "sent you €1. Tap to say thank you")
        val isSystemTitle = cleanTitle.equals("Revolut", ignoreCase = true) ||
            cleanTitle.startsWith("Payment", ignoreCase = true) ||
            cleanTitle.startsWith("Paid", ignoreCase = true) ||
            cleanTitle.startsWith("Spent", ignoreCase = true) ||
            cleanTitle.startsWith("Transferred", ignoreCase = true) ||
            cleanTitle.startsWith("Refund", ignoreCase = true) ||
            cleanTitle.startsWith("Security", ignoreCase = true)

        val isP2pSentToYou = cleanText.contains("sent you", ignoreCase = true) ||
            cleanText.contains("paid you", ignoreCase = true)

        if (isP2pSentToYou) {
            // Check if sender name is in text before "sent you", e.g. "Pawan Kumar Ghising sent you €1"
            val beforeSentYou = cleanText.substringBefore("sent you", "").substringBefore("paid you", "").trim()
            val candidateFromText = PaymentParserUtils.cleanMerchantName(beforeSentYou)
            if (candidateFromText.isNotBlank() && !isActionPhrase(candidateFromText)) {
                return candidateFromText
            }
            // If text starts with "sent you", title contains the sender's name
            if (!isSystemTitle && cleanTitle.isNotBlank()) {
                return PaymentParserUtils.cleanMerchantName(cleanTitle)
            }
        }

        // 2. Check "refunded you" (e.g. "Amazon refunded you €19.99")
        val refundMatcher = Pattern.compile("(?i)(?:^|[.\\n])\\s*([A-Za-z0-9&'’\\-\\s]+?)\\s+refunded you")
            .matcher(if (cleanText.isNotBlank()) cleanText else cleanTitle)
        if (refundMatcher.find()) {
            val candidate = PaymentParserUtils.cleanMerchantName(refundMatcher.group(1) ?: "")
            if (candidate.isNotBlank() && !isActionPhrase(candidate)) return candidate
        }

        // 3. Check "refund ... from <Merchant>"
        val refundFromMatcher = Pattern.compile("(?i)refund(?:\\s+of[^\\n]+)?\\s+from\\s+([A-Za-z0-9&'’\\-\\s]+)").matcher("$cleanTitle. $cleanText")
        if (refundFromMatcher.find()) {
            val candidate = PaymentParserUtils.cleanMerchantName(refundFromMatcher.group(1) ?: "")
            if (candidate.isNotBlank() && !isActionPhrase(candidate)) return candidate
        }

        // 4. Check "to <Merchant>" or "at <Merchant>"
        val combined = "$cleanTitle. $cleanText"
        val atToMatcher = Pattern.compile("(?i)(?:to|at|from)\\s+([A-Za-z0-9&'’\\.\\-\\s]{2,40})").matcher(combined)
        while (atToMatcher.find()) {
            var raw = atToMatcher.group(1) ?: ""
            val cutIndex = listOf(" with ", " ending ", " on ", ".", ",", " tap ").map { raw.indexOf(it, ignoreCase = true) }
                .filter { it > 0 }
                .minOrNull()
            if (cutIndex != null) {
                raw = raw.substring(0, cutIndex)
            }
            val cleaned = PaymentParserUtils.cleanMerchantName(raw)
            if (cleaned.isNotBlank() && !isActionPhrase(cleaned)) {
                return cleaned
            }
        }

        // 5. If title is "Payment to Starbucks"
        if (cleanTitle.startsWith("Payment to ", ignoreCase = true)) {
            val candidate = PaymentParserUtils.cleanMerchantName(cleanTitle.substring(11))
            if (candidate.isNotBlank() && !isActionPhrase(candidate)) return candidate
        }

        // 6. If title is not system title and text indicates expense (e.g. Title: "Starbucks", Text: "Paid €4.50 with card ...1234")
        if (!isSystemTitle && cleanTitle.isNotBlank() && !isActionPhrase(cleanTitle)) {
            return PaymentParserUtils.cleanMerchantName(cleanTitle)
        }

        return ""
    }

    private fun isActionPhrase(name: String): Boolean {
        val lower = name.trim().lowercase(java.util.Locale.ROOT)
        return lower in setOf(
            "say thank you", "thank you", "say thanks", "thanks", "view", "see details",
            "tap to", "respond", "reply", "bank payment", "payment",
        ) || lower.startsWith("say thank") || lower.startsWith("tap to")
    }
}
