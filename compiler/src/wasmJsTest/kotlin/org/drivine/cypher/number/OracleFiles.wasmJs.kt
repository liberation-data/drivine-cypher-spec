@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.drivine.cypher.number

private fun oracleDirectory(): String = js("globalThis.process.env.DRIVINE_ORACLE || ''")

private fun readText(path: String): String = js("globalThis.process.getBuiltinModule('node:fs').readFileSync(path, 'utf8')")

actual fun oracleFile(name: String): String {
    val directory = oracleDirectory()
    require(directory.isNotEmpty()) { "DRIVINE_ORACLE names no directory: run ./gradlew jvmOracle" }
    return readText("$directory/$name")
}

actual val target: String = "wasmJs"
