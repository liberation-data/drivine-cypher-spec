@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.drivine.cypher.sqlite

import cnames.structs.sqlite3
import cnames.structs.sqlite3_context
import cnames.structs.sqlite3_value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.alloc
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlin.concurrent.AtomicReference
import org.drivine.sqlite.ext.SQLITE_DETERMINISTIC
import org.drivine.sqlite.ext.SQLITE_DIRECTONLY
import org.drivine.sqlite.ext.SQLITE_ERROR
import org.drivine.sqlite.ext.SQLITE_INNOCUOUS
import org.drivine.sqlite.ext.SQLITE_NULL
import org.drivine.sqlite.ext.SQLITE_UTF16
import org.drivine.sqlite.ext.SQLITE_UTF8
import org.drivine.sqlite.ext.sqlite3_api_routines

/*
 * The registration of host functions, which every host function of the extension goes through: its arity and flags, a
 * callback that no Throwable escapes, and its arguments and result read and written through the loading SQLite's
 * routines. An exception escaping a staticCFunction into SQLite terminates the host process, so each call catches every
 * Throwable and fails the statement with its message instead.
 */

/** The SQLite the library is bound to: the routines of the first SQLite that initialised the extension in the process. */
internal object Sqlite {
    private val bound = AtomicReference<sqlite3_api_routines?>(null)

    /**
     * Binds the library to [routines], the first time; null where it is bound to them, else why it can't be. One process
     * has one SQLite that loads the extension: each call goes through the routines of the one it was bound to, so a
     * second SQLite (another copy of the library, of another version) is refused rather than called through the first.
     */
    fun bind(routines: CPointer<sqlite3_api_routines>): String? {
        val given = routines.pointed
        if (bound.compareAndSet(null, given)) return null
        return if (bound.value!!.rawPtr == given.rawPtr) {
            null
        } else {
            "libdrivinecypher is already bound to another SQLite in this process, and can serve only one."
        }
    }

    /** The bound routines. A host function runs only once they are bound, as it is registered through them. */
    val api: sqlite3_api_routines get() = bound.value!!
}

/** The text encoding a host function prefers its text arguments in, which SQLite converts them to before the call. */
internal enum class TextEncoding(val flag: Int) { UTF8(SQLITE_UTF8), UTF16(SQLITE_UTF16) }

/**
 * A call's arguments, read in place through SQLite's routines: valid only within the call. Each text or blob read gives
 * a copy (the only allocation a read makes); the numeric reads allocate nothing.
 */
internal value class SqlArgs(private val argv: CPointer<CPointerVar<sqlite3_value>>?) {
    private fun value(i: Int): CPointer<sqlite3_value>? = argv!![i]

    /** The argument's fundamental type: SQLITE_INTEGER, SQLITE_FLOAT, SQLITE_TEXT, SQLITE_BLOB or SQLITE_NULL. */
    fun type(i: Int): Int = Sqlite.api.value_type!!(value(i))

    fun isNull(i: Int): Boolean = type(i) == SQLITE_NULL

    fun long(i: Int): Long = Sqlite.api.value_int64!!(value(i))

    fun int(i: Int): Int = Sqlite.api.value_int!!(value(i))

    fun double(i: Int): Double = Sqlite.api.value_double!!(value(i))

    /** The argument as UTF-8 text, decoded (an invalid sequence as U+FFFD); null where it is NULL. */
    fun text(i: Int): String? {
        val a = Sqlite.api
        val v = value(i)
        val chars = a.value_text!!(v) ?: return null
        val n = a.value_bytes!!(v)
        return if (n == 0) "" else chars.reinterpret<ByteVar>().readBytes(n).decodeToString()
    }

    /** The argument as UTF-16 text, its code units as they are (a lone surrogate kept); null where it is NULL. */
    fun text16(i: Int): String? {
        val a = Sqlite.api
        val v = value(i)
        val units = a.value_text16!!(v)?.reinterpret<UShortVar>() ?: return null
        val n = a.value_bytes16!!(v) / 2
        return CharArray(n) { Char(units[it]) }.concatToString()
    }

    /** The argument as bytes (a text's UTF-8); null where it is NULL. */
    fun blob(i: Int): ByteArray? {
        val a = Sqlite.api
        val v = value(i)
        if (a.value_type!!(v) == SQLITE_NULL) return null
        val bytes = a.value_blob!!(v)
        val n = a.value_bytes!!(v)
        return if (n == 0 || bytes == null) ByteArray(0) else bytes.reinterpret<ByteVar>().readBytes(n)
    }
}

/** SQLITE_TRANSIENT: SQLite copies the text or blob before the result call returns. */
private val transient = (-1L).toCPointer<kotlinx.cinterop.CFunction<(COpaquePointer?) -> Unit>>()

/** A byte to point at for an empty text or blob: a NULL pointer would give SQL NULL. Never freed, as it is the process's. */
private val empty = nativeHeap.alloc<ByteVar>().ptr

/** A call's result, written through SQLite's routines. The last one written is the call's. */
internal value class SqlResult(private val ctx: CPointer<sqlite3_context>?) {
    fun nullValue() = Sqlite.api.result_null!!(ctx)

    fun long(value: Long) = Sqlite.api.result_int64!!(ctx, value)

    fun int(value: Int) = Sqlite.api.result_int!!(ctx, value)

    fun double(value: Double) = Sqlite.api.result_double!!(ctx, value)

    /** The text, as UTF-8 (a lone surrogate as U+FFFD, as Kotlin encodes it). */
    fun text(value: String) {
        val bytes = value.encodeToByteArray()
        if (bytes.isEmpty()) return Sqlite.api.result_text!!(ctx, empty, 0, transient)
        bytes.usePinned { Sqlite.api.result_text!!(ctx, it.addressOf(0), bytes.size, transient) }
    }

    /** The text, as UTF-16 in the machine's byte order: its code units as they are. */
    fun text16(value: String) {
        if (value.isEmpty()) return Sqlite.api.result_text16!!(ctx, empty, 0, transient)
        val chars = value.toCharArray()
        chars.usePinned { Sqlite.api.result_text16!!(ctx, it.addressOf(0), chars.size * 2, transient) }
    }

    fun blob(value: ByteArray) {
        if (value.isEmpty()) return Sqlite.api.result_blob!!(ctx, empty, 0, transient)
        value.usePinned { Sqlite.api.result_blob!!(ctx, it.addressOf(0), value.size, transient) }
    }

    /** Fails the statement with [message]. SQLite copies it. */
    fun error(message: String) {
        val bytes = message.encodeToByteArray()
        if (bytes.isEmpty()) return Sqlite.api.result_error!!(ctx, empty, 0)
        bytes.usePinned { Sqlite.api.result_error!!(ctx, it.addressOf(0), bytes.size) }
    }
}

/** A host function's body: it reads [count] arguments from [args] and writes its result to [result]. */
internal fun interface ScalarBody {
    fun call(args: SqlArgs, count: Int, result: SqlResult)
}

/**
 * A scalar host function, as SQL calls it: [name], taking [arity] arguments (-1 for any number). [deterministic] where
 * it gives the same result for the same arguments within a statement, so SQLite may use it in an index or factor it out;
 * [innocuous] where it has no side effects and reads nothing outside its arguments, so a trigger or view of an
 * untrusted schema may call it; [directOnly] where it changes state, so only a statement of the host's own may call it.
 */
internal class HostFunction(
    val name: String,
    val arity: Int,
    val deterministic: Boolean,
    val innocuous: Boolean = deterministic,
    val directOnly: Boolean = false,
    val encoding: TextEncoding = TextEncoding.UTF8,
    val body: ScalarBody,
) {
    init {
        require(!(innocuous && directOnly)) { "$name can't be both innocuous and direct-only." }
    }

    val flags: Int = encoding.flag or
        (if (deterministic) SQLITE_DETERMINISTIC else 0) or
        (if (innocuous) SQLITE_INNOCUOUS else 0) or
        (if (directOnly) SQLITE_DIRECTONLY else 0)
}

/** The one callback SQLite calls for every host function: the function is the registration's user data. */
private val trampoline = staticCFunction { ctx: CPointer<sqlite3_context>?, count: Int, argv: CPointer<CPointerVar<sqlite3_value>>? ->
    dispatch(ctx, count, argv)
}

private val disposeFunction = staticCFunction { p: COpaquePointer? -> p?.asStableRef<HostFunction>()?.dispose(); Unit }

/** Runs the host function [ctx] is a call of. No Throwable leaves it: one would terminate the host process. */
internal fun dispatch(ctx: CPointer<sqlite3_context>?, count: Int, argv: CPointer<CPointerVar<sqlite3_value>>?) {
    var name = "drivine"
    try {
        val function = Sqlite.api.user_data!!(ctx)!!.asStableRef<HostFunction>().get()
        name = function.name
        function.body.call(SqlArgs(argv), count, SqlResult(ctx))
    } catch (e: Throwable) {
        fail(ctx, name, e)
    }
}

/** Fails the call with the Throwable's message; with SQLITE_ERROR alone where even the message can't be written. */
private fun fail(ctx: CPointer<sqlite3_context>?, name: String, e: Throwable) {
    try {
        SqlResult(ctx).error("$name: ${e.message ?: e::class.simpleName ?: "failed"}")
    } catch (_: Throwable) {
        try {
            Sqlite.api.result_error_code!!(ctx, SQLITE_ERROR)
        } catch (_: Throwable) {
            // Nothing is bound to report through: the call gives SQL NULL.
        }
    }
}

/**
 * Registers [function] on the connection [db], through the bound routines; SQLite's result code. The registration holds
 * a reference to the function, which SQLite releases when the connection closes, the function is replaced, or the
 * registration fails.
 */
internal fun register(db: CPointer<sqlite3>?, function: HostFunction): Int = memScoped {
    val ref = StableRef.create(function)
    Sqlite.api.create_function_v2!!(
        db, function.name.cstr.ptr, function.arity, function.flags, ref.asCPointer(), trampoline, null, null, disposeFunction,
    )
}
