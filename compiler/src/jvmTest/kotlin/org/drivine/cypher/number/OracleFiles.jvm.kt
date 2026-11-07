package org.drivine.cypher.number

actual fun oracleFile(name: String): String =
    java.io.File(requireNotNull(System.getenv("DRIVINE_ORACLE")) { "DRIVINE_ORACLE names no directory: run ./gradlew jvmOracle" }, name).readText()

actual val target: String = "jvm"
