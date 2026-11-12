package org.drivine.cypher.host.regex

import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.native.concurrent.ThreadLocal
import org.drivine.sqlite.ext.drivine_stack_here
import org.drivine.sqlite.ext.drivine_stack_low

/** kotlin.text.Regex, the pattern rewritten to match as Java's does: see [HarmonyRewrite]. */
internal actual fun compileJavaPattern(expression: String): JavaPattern = HarmonyPattern(expression)

/** A spin lock: held for a few map operations alone, as SQLite may call a function from any of a host's threads. */
internal actual class PatternLock actual constructor() {
    private val held = AtomicInt(0)

    actual fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) { /* held for a map's get or put */ }
        try {
            return block()
        } finally {
            held.value = 0
        }
    }
}

/** This thread's, read once a thread: on Linux, the main thread's is read from /proc. -1 until read. */
@ThreadLocal
private var low = -1L

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun stackLow(): Long {
    if (low < 0) low = drivine_stack_low().toLong()
    return low
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun stackHere(): Long = drivine_stack_here().toLong()

/** Measured: see README.md, "Regular expressions". A debug binary's frames are about five times a release's. */
@OptIn(ExperimentalNativeApi::class)
internal actual fun stackBytesPerNode(): Long = if (Platform.isDebugBinary) 4096 else 1024
