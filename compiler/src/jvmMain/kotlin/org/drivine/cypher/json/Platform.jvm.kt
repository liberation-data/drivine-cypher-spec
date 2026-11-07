package org.drivine.cypher.json

import org.drivine.cypher.sql.Temporal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

/** As drivine4j's Jackson mapper (JavaTimeModule, timestamps off) writes the JVM's own types. */
actual fun platformTree(value: Any): JsonNode? = when (value) {
    is java.math.BigDecimal -> DecimalNode(
        com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString(value.toString()), value.toPlainString(), value.toString(),
    )
    is java.math.BigInteger -> BigIntegerNode(com.ionspin.kotlin.bignum.integer.BigInteger.parseString(value.toString()))
    is LocalDate, is LocalTime, is LocalDateTime, is OffsetTime, is Duration, is Period, is Instant -> TextNode(value.toString())
    is OffsetDateTime -> TextNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value))
    is ZonedDateTime -> TextNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value))
    is Date -> TextNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.toInstant().atOffset(java.time.ZoneOffset.UTC)))
    is UUID -> TextNode(value.toString())
    else -> null
}

actual fun platformTemporal(value: Any): Temporal? = when (value) {
    is LocalDate -> Temporal.DATE
    is LocalTime -> Temporal.LOCAL_TIME
    is OffsetTime -> Temporal.TIME
    is LocalDateTime -> Temporal.LOCAL_DATE_TIME
    is Instant, is ZonedDateTime, is OffsetDateTime, is Date -> Temporal.DATE_TIME
    is Duration, is Period -> Temporal.DURATION
    else -> null
}

actual fun jvmDoubleText(value: Double): String = value.toString()
actual fun jvmFloatText(value: Float): String = value.toString()

actual fun parseDoubleOrNull(text: String): Double? = text.toDoubleOrNull()
