package org.drivine.cypher.time

actual fun knownRegion(zone: String): Boolean = runCatching { java.time.ZoneId.of(zone) }.getOrNull().let { it != null && it !is java.time.ZoneOffset }
