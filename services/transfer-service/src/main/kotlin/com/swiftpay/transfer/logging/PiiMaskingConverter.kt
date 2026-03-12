package com.swiftpay.transfer.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Logback converter that masks PII patterns (email, phone, document numbers)
 * in log messages. Registered as %piiMask in logback-spring.xml.
 *
 * Usage in pattern: %piiMask(%msg)
 */
class PiiMaskingConverter : ClassicConverter() {

    companion object {
        private val EMAIL_PATTERN = Regex(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
        )
        private val PHONE_PATTERN = Regex(
            "(?<![a-fA-F0-9-])\\+?\\d[\\d\\-\\s]{8,14}\\d(?![a-fA-F0-9-])"
        )
        private val SSN_PATTERN = Regex(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b"
        )
        private val CARD_PATTERN = Regex(
            "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"
        )

        fun mask(message: String): String {
            var result = message
            result = EMAIL_PATTERN.replace(result) { match ->
                val email = match.value
                val atIndex = email.indexOf('@')
                if (atIndex > 1) {
                    "${email[0]}***@${email.substring(atIndex + 1)}"
                } else {
                    "***@${email.substring(atIndex + 1)}"
                }
            }
            result = CARD_PATTERN.replace(result) { match ->
                val digits = match.value.replace(Regex("[\\s-]"), "")
                "****-****-****-${digits.takeLast(4)}"
            }
            result = SSN_PATTERN.replace(result, "***-**-****")
            result = PHONE_PATTERN.replace(result) { match ->
                val digits = match.value.replace(Regex("[^\\d]"), "")
                "***${digits.takeLast(4)}"
            }
            return result
        }
    }

    override fun convert(event: ILoggingEvent): String {
        return mask(event.formattedMessage)
    }
}
