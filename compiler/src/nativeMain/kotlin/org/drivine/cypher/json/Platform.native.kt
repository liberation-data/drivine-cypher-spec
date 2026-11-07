package org.drivine.cypher.json

import org.drivine.cypher.number.DoubleParse
import org.drivine.cypher.number.DoubleText
import org.drivine.cypher.sql.Temporal

actual fun platformTree(value: Any): JsonNode? = null

actual fun platformTemporal(value: Any): Temporal? = null

actual fun jvmDoubleText(value: Double): String = DoubleText.of(value)

actual fun jvmFloatText(value: Float): String = value.toString()

actual fun parseDoubleOrNull(text: String): Double? = DoubleParse.parseOrNull(text)
