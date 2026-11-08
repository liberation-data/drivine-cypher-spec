@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.drivine.cypher.number

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv

actual fun oracleFile(name: String): String {
    val directory = getenv("DRIVINE_ORACLE")?.toKString()
    require(!directory.isNullOrEmpty()) { "DRIVINE_ORACLE names no directory: run ./gradlew jvmOracle" }
    val file = fopen("$directory/$name", "rb") ?: error("No file $directory/$name")
    try {
        val out = ByteArrayBuilder()
        memScoped {
            val buffer = allocArray<ByteVar>(1 shl 16)
            while (true) {
                val read = fread(buffer, 1.convert(), (1 shl 16).convert(), file).toInt()
                if (read <= 0) break
                out.append(buffer.readBytes(read))
            }
        }
        return out.bytes().decodeToString()
    } finally {
        fclose(file)
    }
}

private class ByteArrayBuilder {
    private val chunks = mutableListOf<ByteArray>()
    fun append(bytes: ByteArray) { chunks += bytes }
    fun bytes(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }
}

actual val target: String = "native ${kotlin.native.Platform.osFamily.name.lowercase()} ${kotlin.native.Platform.cpuArchitecture.name.lowercase()}"
