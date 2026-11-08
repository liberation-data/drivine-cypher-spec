package org.drivine.cypher.number

/** The text of [name] in the directory the JVM wrote its answers to (`./gradlew jvmOracle`, DRIVINE_ORACLE). */
expect fun oracleFile(name: String): String

/** The target the tests run on, as a report names it. */
expect val target: String
