@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
)

package org.drivine.cypher.sqlite

import cnames.structs.sqlite3
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.AtomicInt
import kotlin.native.runtime.GC
import org.drivine.sqlite.ext.SQLITE_ERROR
import org.drivine.sqlite.ext.SQLITE_INTEGER
import org.drivine.sqlite.ext.SQLITE_OK
import org.drivine.sqlite.ext.sqlite3_api_routines
import platform.posix.memcpy

/*
 * The SQLite extension: the module's host functions, which compiled SQL calls on SQLite, in the same shared library as
 * drivine_compile, so a process has one Kotlin/Native runtime. SQLite derives the entry point's name from the library's
 * file name (libdrivinecypher → sqlite3_drivinecypher_init) and hands it the loading SQLite's routines, through which
 * every call into SQLite goes: the library links against no SQLite. See README.md, "The SQLite extension".
 */

/** The oldest SQLite the extension takes: 3.31.0 brought SQLITE_DIRECTONLY and SQLITE_INNOCUOUS, which it registers with. */
private const val OLDEST_SQLITE = 3_031_000

/** The extension's functions, registered on each connection it is loaded into. */
internal val hostFunctions: List<HostFunction> = listOf(
    // The module's version, as it was built: a host checks it loaded the library it meant to.
    HostFunction("drivine_extension_version", 0, deterministic = true) { _, _, result -> result.text(MODULE_VERSION) },
    // Collects now, for a host to call between statements: the collection the deferred floor puts off.
    HostFunction("drivine_gc_collect", 0, deterministic = false, directOnly = true) { _, _, result ->
        GC.collect()
        result.nullValue()
    },
    // The GC's floor, in bytes, as it now is; with an argument, set first (0 restores the runtime's own).
    HostFunction("drivine_gc_heap", 0, deterministic = false, directOnly = true) { _, _, result -> result.long(GcFloor.bytes) },
    HostFunction("drivine_gc_heap", 1, deterministic = false, directOnly = true) { args, _, result ->
        require(args.type(0) == SQLITE_INTEGER) { "the floor is a whole number of bytes." }
        result.long(GcFloor.set(args.long(0)))
    },
)

/**
 * The extension's entry point, which sqlite3_load_extension calls with the loading SQLite's routines, and which a host
 * linking SQLite statically registers with sqlite3_auto_extension (or calls on each connection itself). It registers
 * [hostFunctions] on [db]; the first time in the process, it binds the library to [routines] and defers collection
 * ([GcFloor]). It returns SQLite's result code, and where it fails, its message in [errorMessage], allocated by SQLite.
 */
@CName("sqlite3_drivinecypher_init")
fun drivinecypherInit(db: CPointer<sqlite3>?, errorMessage: CPointer<CPointerVar<ByteVar>>?, routines: CPointer<sqlite3_api_routines>?): Int {
    // Built with SQLITE_OMIT_LOAD_EXTENSION, a SQLite calls an auto-extension with no routines, so none can be called.
    if (routines == null) return SQLITE_ERROR
    return try {
        val version = routines.pointed.libversion_number!!()
        val refused = if (version < OLDEST_SQLITE) {
            "libdrivinecypher needs SQLite 3.31.0 or later, not ${version / 1_000_000}.${version / 1000 % 1000}.${version % 1000}."
        } else {
            Sqlite.bind(routines)
        }
        if (refused != null) return refuse(routines, errorMessage, refused)
        GcFloor.deferOnce()
        for (function in hostFunctions) {
            val rc = register(db, function)
            if (rc != SQLITE_OK) return refuse(routines, errorMessage, "libdrivinecypher couldn't register ${function.name} (SQLite's code $rc).", rc)
        }
        SQLITE_OK
    } catch (e: Throwable) {
        refuse(routines, errorMessage, "libdrivinecypher couldn't load: ${e.message ?: e::class.simpleName}")
    }
}

/** Fails the init with [message], copied into memory of the loading SQLite's, which frees it. No Throwable leaves it. */
private fun refuse(routines: CPointer<sqlite3_api_routines>, errorMessage: CPointer<CPointerVar<ByteVar>>?, message: String, rc: Int = SQLITE_ERROR): Int {
    try {
        if (errorMessage != null) {
            val bytes = message.encodeToByteArray()
            val out = routines.pointed.malloc!!(bytes.size + 1)?.reinterpret<ByteVar>()
            if (out != null) {
                if (bytes.isNotEmpty()) bytes.usePinned { memcpy(out, it.addressOf(0), bytes.size.convert()) }
                out[bytes.size] = 0
            }
            errorMessage.pointed.value = out
        }
    } catch (_: Throwable) {
        // The code alone, then.
    }
    return rc
}

/**
 * Collects now, and waits: for a host to call between statements, where collection is deferred ([GcFloor]). It may be
 * called from any thread, and before the extension is loaded.
 */
@CName("drivine_gc_collect")
fun drivineGcCollect() = GC.collect()

/**
 * Kotlin/Native's GC floor, raised so a statement's garbage doesn't set off a collection mid-statement. Its collector
 * schedules a collection when the heap reaches targetHeapBytes, which autotune sets after each collection from the live
 * heap, but never below minHeapBytes, 5 MiB by default: with a small live heap, that is a collection every ~5 MiB
 * allocated (60-65 in a 1M-row regexp statement, #108). Raising targetHeapBytes alone doesn't hold, as autotune lowers it
 * again; raising the floor does. The floor stays the backstop: past it, a collection runs as before, so a statement that
 * makes more garbage, or a host that never collects, holds at most about this much.
 */
internal object GcFloor {
    /** 64 MiB: about 200k rows of the regexp prototype's garbage (~300 bytes a row), against ~16k at 5 MiB. */
    const val DEFAULT_BYTES: Long = 64L * 1024 * 1024

    /**
     * The runtime's own floor and target, as they were before the extension first raised them: what 0 restores; -1 until
     * then. Read and written under [lock], as two connections' inits, or an init and a drivine_gc_heap, may run at once
     * on two threads: unlocked, a host's floor could be overwritten by another init's first raise, or 0 restore a target
     * not yet read.
     */
    private var runtimeFloor = -1L
    private var runtimeTarget = -1L
    private val lock = AtomicInt(0)

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(0, 1)) { /* held for a few field writes alone */ }
        try {
            return block()
        } finally {
            lock.value = 0
        }
    }

    /** Raises the floor to [DEFAULT_BYTES], once in the process: a later connection's init leaves a host's setting be. */
    fun deferOnce() = locked { deferOnceLocked() }

    private fun deferOnceLocked() {
        if (runtimeFloor == -1L) {
            // Recorded after the raise, so a setter that throws leaves the process as it was, for the next init to try again.
            val floor = GC.minHeapBytes
            val target = GC.targetHeapBytes
            raise(DEFAULT_BYTES)
            runtimeFloor = floor
            runtimeTarget = target
        }
    }

    val bytes: Long get() = GC.minHeapBytes

    /** Sets the floor to [bytes], or restores the runtime's own with 0; the floor now. */
    fun set(bytes: Long): Long {
        require(bytes >= 0) { "the floor is 0 bytes or more, not $bytes." }
        require(bytes <= GC.maxHeapBytes) { "the floor is at most the heap's maximum, ${GC.maxHeapBytes} bytes, not $bytes." }
        return locked {
            deferOnceLocked()
            if (bytes == 0L) {
                GC.minHeapBytes = runtimeFloor
                GC.targetHeapBytes = runtimeTarget
            } else {
                raise(bytes)
            }
            GC.minHeapBytes
        }
    }

    /** The target too, as the one in force until the next collection is the runtime's, set before the floor moved. */
    private fun raise(bytes: Long) {
        GC.minHeapBytes = bytes
        GC.targetHeapBytes = bytes
    }
}
