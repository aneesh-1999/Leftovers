package com.leftovers.app.util.detection

import com.leftovers.app.data.detection.TransactionCandidate

object PaymentNotificationParserRegistry {
    private val parsers = mutableListOf<PaymentNotificationParser>()

    init {
        // Parsers will register themselves or be added here
        register(RevolutNotificationParser)
        register(EsewaNotificationParser)
        register(GenericPaymentNotificationParser)
    }

    fun register(parser: PaymentNotificationParser) {
        if (!parsers.contains(parser)) {
            parsers.add(parser)
        }
    }

    /**
     * Finds the best matching parser and extracts a candidate, or returns null.
     */
    fun parse(
        packageName: String,
        title: String,
        text: String,
        subText: String?,
        postTime: Long,
        fingerprint: String,
    ): TransactionCandidate? {
        val parser = parsers.firstOrNull { it.canHandle(packageName) }
            ?: GenericPaymentNotificationParser

        return parser.parse(
            packageName = packageName,
            title = title,
            text = text,
            subText = subText,
            postTime = postTime,
            fingerprint = fingerprint,
        )
    }
}
