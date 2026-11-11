@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class)

package org.drivine.cypher.sqlite

import cnames.structs.sqlite3
import cnames.structs.sqlite3_context
import cnames.structs.sqlite3_value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlin.native.runtime.GC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.sqlite.ext.SQLITE_DETERMINISTIC
import org.drivine.sqlite.ext.SQLITE_DIRECTONLY
import org.drivine.sqlite.ext.SQLITE_ERROR
import org.drivine.sqlite.ext.SQLITE_INNOCUOUS
import org.drivine.sqlite.ext.SQLITE_INTEGER
import org.drivine.sqlite.ext.SQLITE_OK
import org.drivine.sqlite.ext.SQLITE_UTF8
import org.drivine.sqlite.ext.sqlite3_api_routines
import platform.posix.memset

/** A registration the fake SQLite was given: the function's name, arity, flags, user data, callback and destructor. */
private class Registered(
    val name: String,
    val arity: Int,
    val flags: Int,
    val app: COpaquePointer?,
    val call: CPointer<CFunction<(CPointer<sqlite3_context>?, Int, CPointer<CPointerVar<sqlite3_value>>?) -> Unit>>?,
    val destroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>?,
)

private val registered = mutableListOf<Registered>()

/** What the last call wrote as its result: its kind and value. */
private var result: Pair<String, Any?>? = null

/**
 * A SQLite of the extension's routines alone, as much as the extension calls: a registration is recorded, a call's
 * context is the registration's user data itself, a value is a pointer to a Long, and a result is recorded.
 */
private fun fakeRoutines(version: Int): CPointer<sqlite3_api_routines> {
    val routines = nativeHeap.alloc<sqlite3_api_routines>()
    memset(routines.ptr, 0, sizeOf<sqlite3_api_routines>().convert())
    routines.libversion_number = if (version >= 3_031_000) {
        staticCFunction<Int> { 3_054_001 }
    } else {
        staticCFunction<Int> { 3_030_001 }
    }
    routines.malloc = staticCFunction { n: Int -> platform.posix.malloc(n.convert()) }
    routines.create_function_v2 = staticCFunction { _: CPointer<sqlite3>?, name: CPointer<ByteVar>?, arity: Int, flags: Int, app: COpaquePointer?,
                                                    call: CPointer<CFunction<(CPointer<sqlite3_context>?, Int, CPointer<CPointerVar<sqlite3_value>>?) -> Unit>>?,
                                                    _: CPointer<CFunction<(CPointer<sqlite3_context>?, Int, CPointer<CPointerVar<sqlite3_value>>?) -> Unit>>?,
                                                    _: CPointer<CFunction<(CPointer<sqlite3_context>?) -> Unit>>?,
                                                    destroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>? ->
        registered += Registered(name!!.toKString(), arity, flags, app, call, destroy)
        SQLITE_OK
    }
    routines.user_data = staticCFunction { ctx: CPointer<sqlite3_context>? -> ctx?.reinterpret<ByteVar>() }
    routines.value_type = staticCFunction { _: CPointer<sqlite3_value>? -> SQLITE_INTEGER }
    routines.value_int64 = staticCFunction { v: CPointer<sqlite3_value>? -> v!!.reinterpret<LongVar>().pointed.value }
    routines.result_text = staticCFunction { _: CPointer<sqlite3_context>?, text: CPointer<ByteVar>?, n: Int, _: CPointer<CFunction<(COpaquePointer?) -> Unit>>? ->
        result = "text" to text!!.readBytes(n).decodeToString()
    }
    routines.result_error = staticCFunction { _: CPointer<sqlite3_context>?, text: CPointer<ByteVar>?, n: Int ->
        result = "error" to text!!.readBytes(n).decodeToString()
    }
    routines.result_error_code = staticCFunction { _: CPointer<sqlite3_context>?, code: Int -> result = "code" to code }
    routines.result_null = staticCFunction { _: CPointer<sqlite3_context>? -> result = "null" to null }
    routines.result_int64 = staticCFunction { _: CPointer<sqlite3_context>?, value: Long -> result = "long" to value }
    return routines.ptr
}

/** The one SQLite the test process binds the extension to. */
private val sqlite: CPointer<sqlite3_api_routines> by lazy { fakeRoutines(3_054_001) }

/** A connection the fake SQLite never reads. */
private val db = 1L.toCPointer<sqlite3>()

/** Loads the extension into the fake SQLite, as sqlite3_load_extension does; its result code and message. */
private fun load(routines: CPointer<sqlite3_api_routines>?): Pair<Int, String?> = memScoped {
    val message = alloc<CPointerVar<ByteVar>>()
    val rc = drivinecypherInit(db, message.ptr, routines)
    val text = message.value?.toKString()
    message.value?.let { platform.posix.free(it) }
    rc to text
}

/** Calls the function last registered as [name] with [arity], with these whole-number arguments; what it wrote. */
private fun call(name: String, arity: Int, vararg args: Long): Pair<String, Any?>? = memScoped {
    val function = registered.last { it.name == name && it.arity == arity }
    val values = args.map { alloc<LongVar>().apply { value = it }.ptr }
    val argv = allocArray<CPointerVar<sqlite3_value>>(args.size.coerceAtLeast(1))
    values.forEachIndexed { i, v -> argv[i] = v.reinterpret() }
    result = null
    function.call!!(function.app!!.reinterpret(), args.size, argv)
    result
}

/** The extension's entry point and its host functions, against a fake SQLite's routines. */
class SqliteExtensionTest {

    @Test
    fun itRegistersItsFunctionsThroughTheLoadingSqlitesRoutines() {
        registered.clear()
        assertEquals(SQLITE_OK to null, load(sqlite))
        assertEquals(
            listOf("drivine_extension_version/0", "drivine_gc_collect/0", "drivine_gc_heap/0", "drivine_gc_heap/1"),
            registered.map { "${it.name}/${it.arity}" },
        )
        val version = registered.single { it.name == "drivine_extension_version" }
        assertEquals(SQLITE_UTF8 or SQLITE_DETERMINISTIC or SQLITE_INNOCUOUS, version.flags)
        assertTrue(registered.filter { it.name.startsWith("drivine_gc_") }.all { it.flags == SQLITE_UTF8 or SQLITE_DIRECTONLY })
        assertEquals("text" to MODULE_VERSION, call("drivine_extension_version", 0))
        assertEquals("null" to null, call("drivine_gc_collect", 0))
        // Each registration's reference to its function is released by its destructor, as SQLite calls it on close.
        registered.forEach { it.destroy!!(it.app) }
    }

    @Test
    fun aThrowableInAFunctionFailsTheCallAndNeverReachesSqlite() {
        load(sqlite)
        registered.clear()
        // An Error, not an Exception: everything a body throws is caught.
        register(db, HostFunction("drivine_test_throws", 0, deterministic = true) { _, _, _ -> throw Error("boom") })
        assertEquals("error" to "drivine_test_throws: boom", call("drivine_test_throws", 0))
        register(db, HostFunction("drivine_test_throws_bare", 0, deterministic = true) { _, _, _ -> throw IllegalStateException() })
        assertEquals("error" to "drivine_test_throws_bare: IllegalStateException", call("drivine_test_throws_bare", 0))
        registered.forEach { it.destroy!!(it.app) }
    }

    @Test
    fun theGcFloorIsRaisedOnLoadAndSettable() {
        registered.clear()
        load(sqlite)
        assertEquals(GcFloor.DEFAULT_BYTES, GC.minHeapBytes)
        assertEquals("long" to GcFloor.DEFAULT_BYTES, call("drivine_gc_heap", 0))
        assertEquals("long" to 128L * 1024 * 1024, call("drivine_gc_heap", 1, 128L * 1024 * 1024))
        assertEquals(128L * 1024 * 1024, GC.minHeapBytes)
        assertEquals(128L * 1024 * 1024, GC.targetHeapBytes)
        // A second connection's load leaves the host's setting be.
        load(sqlite)
        assertEquals(128L * 1024 * 1024, GC.minHeapBytes)
        // 0 restores the runtime's own floor, which is lower.
        val restored = call("drivine_gc_heap", 1, 0)!!.second as Long
        assertTrue(restored < GcFloor.DEFAULT_BYTES, "$restored")
        assertEquals(restored, GC.minHeapBytes)
        assertEquals("error" to "drivine_gc_heap: the floor is 0 bytes or more, not -1.", call("drivine_gc_heap", 1, -1))
        assertEquals(restored, GC.minHeapBytes)
        assertEquals("long" to GcFloor.DEFAULT_BYTES, call("drivine_gc_heap", 1, GcFloor.DEFAULT_BYTES))
        drivineGcCollect()
        registered.forEach { it.destroy!!(it.app) }
    }

    @Test
    fun anotherSqliteNoSqliteAndAnOldSqliteAreRefused() {
        load(sqlite)
        val other = fakeRoutines(3_054_001)
        val (rc, message) = load(other)
        assertEquals(SQLITE_ERROR, rc)
        assertEquals("libdrivinecypher is already bound to another SQLite in this process, and can serve only one.", message)
        assertEquals(SQLITE_ERROR to null, load(null))
        assertEquals(SQLITE_ERROR to "libdrivinecypher needs SQLite 3.31.0 or later, not 3.30.1.", load(fakeRoutines(3_030_001)))
        assertNotEquals(other.toLong(), sqlite.toLong())
        nativeHeap.free(other)
    }

    @Test
    fun theVersionIsTheBuildsVersion() {
        assertTrue(Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*").matches(MODULE_VERSION), MODULE_VERSION)
        assertNull(Sqlite.bind(sqlite))
    }
}
