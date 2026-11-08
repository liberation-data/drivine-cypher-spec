package org.drivine.cypher.time

/**
 * Whether the pinned JVM's `ZoneId.of` takes [zone] (see JVM_ZONE_IDS, which the build writes): not the platform's own
 * zone database, which is the OS's on Kotlin/Native (and missing on some slim Linux images) and Node's ICU on wasmJs,
 * neither of them the JVM's tzdb release, so that every target compiles a statement alike.
 */
actual fun knownRegion(zone: String): Boolean = zone in JVM_ZONE_IDS
