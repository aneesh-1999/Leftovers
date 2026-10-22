package com.leftovers.app.util.detection

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PaymentParserUtils {

    /**
     * Converts a string amount like "14.50", "1,500.00", "25", "1200.5" into minor units (cents/paise)
     * without using floating-point arithmetic.
     */
    fun parseAmountToMinor(rawAmount: String): Long? {
        val clean = rawAmount.replace(",", "").trim()
        if (clean.isBlank()) return null

        val parts = clean.split(".")
        return when (parts.size) {
            1 -> {
                val major = parts[0].toLongOrNull() ?: return null
                major * 100L
            }
            2 -> {
                val major = parts[0].toLongOrNull() ?: return null
                val decStr = parts[1]
                val minorPart = when {
                    decStr.length >= 2 -> decStr.substring(0, 2).toIntOrNull() ?: return null
                    decStr.length == 1 -> (decStr + "0").toIntOrNull() ?: return null
                    else -> 0
                }
                major * 100L + minorPart
            }
            else -> null
        }
    }

    /**
     * Resolves epochDay from timestamp.
     */
    fun toEpochDay(timestamp: Long): Long =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /**
     * Normalizes currency symbol to 3-letter ISO code where possible.
     */
    fun normalizeCurrency(raw: String): String = when (raw.trim().uppercase()) {
        "€", "EUR" -> "EUR"
        "£", "GBP" -> "GBP"
        "$", "USD" -> "USD"
        "NPR", "RS.", "RS", "NRs", "NRs." -> "NPR"
        "INR", "₹" -> "INR"
        "AUD" -> "AUD"
        "CAD" -> "CAD"
        "CHF" -> "CHF"
        "JPY", "¥" -> "JPY"
        else -> raw.trim().uppercase()
    }

    /**
     * Cleans up merchant name by stripping common prefixes/suffixes and punctuation.
     */
    fun cleanMerchantName(raw: String): String {
        return raw.trim()
            .removePrefix("to ")
            .removePrefix("at ")
            .removePrefix("with ")
            .removePrefix("from ")
            .removePrefix("for ")
            .trimEnd('.', ',', '!', ':', ';', '-')
            .trim()
    }
}
