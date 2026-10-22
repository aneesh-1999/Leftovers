package com.leftovers.app.util.detection

import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.detection.CandidateStatus
import com.leftovers.app.data.detection.CandidateType
import com.leftovers.app.data.detection.DetectionConfidence
import com.leftovers.app.data.detection.TransactionCandidate
import java.util.regex.Pattern

object EsewaNotificationParser : PaymentNotificationParser {

    override fun canHandle(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        return pkg == "com.f1soft.esewa" || pkg == "com.esewa.mobile" || pkg.contains("esewa")
    }

    private val REJECT_PATTERNS = listOf(
        Pattern.compile("(?i)(otp|one[- ]time password|verification code)"),
        Pattern.compile("(?i)(request(ed)? (for )?money|requested npr)"),
        Pattern.compile("(?i)(flat \\d+%|cashback offer|promo code|campaign|bumper prize)"),
    )

    private val TXN_REF_REGEX = Pattern.compile("(?i)(?:txn(?:\\s*id)?|ref(?:erence)?(?:\\s*id|\\s*no)?|transaction code)[:\\s]+([A-Za-z0-9_-]{4,25})")

    // Match currency & amount: "NPR 1,500.00", "Rs. 250.00", "Rs 500", "1500.00 NPR"
    private val CURRENCY_AMOUNT_REGEX = Pattern.compile(
        "(?i)(NPR|NRs\\.?|Rs\\.?)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)|([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(NPR|NRs\\.?|Rs\\.?)"
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

        // 1. Safety Filter: Reject OTP, money requests, promotions
        for (pattern in REJECT_PATTERNS) {
            if (pattern.matcher(fullContent).find()) {
                return null
            }
        }

        // 2. Check for Declined / Failed status
        val isFailed = fullContent.contains("failed", ignoreCase = true) ||
            fullContent.contains("unsuccessful", ignoreCase = true) ||
            fullContent.contains("cancelled", ignoreCase = true)

        if (isFailed) {
            return TransactionCandidate(
                amountMinor = 0L,
                status = CandidateStatus.FAILED,
                confidence = DetectionConfidence.LOW,
                source = SmsSuggestion.SOURCE_NOTIFICATION,
                packageName = packageName,
                sender = "eSewa",
                timestamp = postTime,
                rawFingerprint = fingerprint,
            )
        }

        // 3. Extract Reference ID
        val refMatcher = TXN_REF_REGEX.matcher(fullContent)
        val referenceId = if (refMatcher.find()) refMatcher.group(1) else null

        // 4. Direction & Type Classification
        val isCashback = fullContent.contains("cashback", ignoreCase = true)
        val isReceived = fullContent.contains("received", ignoreCase = true) ||
            fullContent.contains("credited", ignoreCase = true)
        val isTransfer = fullContent.contains("fund transfer", ignoreCase = true) ||
            fullContent.contains("bank transfer", ignoreCase = true) ||
            fullContent.contains("transferred to bank", ignoreCase = true)

        val candidateType = when {
            isTransfer -> CandidateType.TRANSFER
            isCashback -> CandidateType.REFUND
            isReceived -> CandidateType.INCOME
            else -> CandidateType.EXPENSE
        }

        // 5. Extract Amount and Currency
        val amountMatcher = CURRENCY_AMOUNT_REGEX.matcher(fullContent)
        var amountMinor: Long? = null
        var currency = "NPR"

        if (amountMatcher.find()) {
            val numStr = amountMatcher.group(2) ?: amountMatcher.group(3)
            val currStr = amountMatcher.group(1) ?: amountMatcher.group(4)
            amountMinor = PaymentParserUtils.parseAmountToMinor(numStr.orEmpty())
            if (!currStr.isNullOrBlank()) {
                currency = PaymentParserUtils.normalizeCurrency(currStr)
            }
        }

        if (amountMinor == null || amountMinor <= 0L) {
            return null
        }

        // 6. Extract Merchant / Recipient
        val merchant = extractMerchant(fullContent, candidateType)

        // 7. Extract Account Digits if present (e.g. A/C ...1234)
        val digitsMatcher = Pattern.compile("(?i)(?:a/c|account)[:\\s.*]*(\\d{4})").matcher(fullContent)
        val accountDigits = if (digitsMatcher.find()) digitsMatcher.group(1) else null

        // 8. Confidence resolution
        val confidence = if (merchant.isNotBlank() && referenceId != null) {
            DetectionConfidence.HIGH
        } else if (merchant.isNotBlank()) {
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
            sender = "eSewa",
            referenceId = referenceId,
            rawFingerprint = fingerprint,
        )
    }

    private fun extractMerchant(fullContent: String, type: CandidateType): String {
        // "Payment of NPR 1,500.00 to Bhatbhateni Supermarket is successful" -> "Bhatbhateni Supermarket"
        // "NPR 500.00 sent to 9841234567 (Ram Bahadur) successfully" -> "Ram Bahadur"
        // "received NPR 2,500.00 from Hari Prasad" -> "Hari Prasad"
        // "paid for Internet Bill (WorldLink)" -> "WorldLink"

        val toMatcher = Pattern.compile("(?i)(?:to|for|from)\\s+([A-Za-z0-9&'’\\.\\-\\s\\(\\)]{2,40})").matcher(fullContent)
        if (toMatcher.find()) {
            var raw = toMatcher.group(1) ?: ""
            // Stop at known termination words
            val stopWords = listOf(" is successful", " successfully", " balance", " ref", " txn", " via", ".", ",")
            for (stop in stopWords) {
                val idx = raw.indexOf(stop, ignoreCase = true)
                if (idx > 0) {
                    raw = raw.substring(0, idx)
                }
            }

            // If it contains phone number + name like "9841234567 (Ram Bahadur)", pick the name in parenthesis
            val parenMatcher = Pattern.compile("\\((.+?)\\)").matcher(raw)
            if (parenMatcher.find()) {
                val inParen = parenMatcher.group(1)?.trim().orEmpty()
                if (inParen.isNotBlank()) return inParen
            }

            val cleaned = PaymentParserUtils.cleanMerchantName(raw)
            if (cleaned.isNotBlank() && !cleaned.equals("bank", ignoreCase = true)) {
                return cleaned
            }
        }

        return ""
    }
}
