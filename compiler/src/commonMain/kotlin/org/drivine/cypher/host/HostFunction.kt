package org.drivine.cypher.host

import org.drivine.DrivineInternalApi

/*
 * The functions compiled SQL calls on SQLite (see SqliteFunctions), written once, here, for every host: the native
 * library registers them as a SQLite extension (org.drivine.cypher.sqlite), and a host on the JVM calls them from its
 * own registrations. The spec's host.yaml pins what each gives (HostSpecTest).
 */

/** A SQL value's fundamental type, as SQLite gives an argument's. */
@DrivineInternalApi
enum class SqlType { INTEGER, FLOAT, TEXT, BLOB, NULL }

/** The text encoding a host function prefers its text arguments in, which SQLite converts them to before the call. */
@DrivineInternalApi
enum class TextEncoding { UTF8, UTF16 }

/**
 * A call's arguments, as the host reads them: valid only within the call. A read of a type other than the argument's
 * converts it as SQLite does (`sqlite3_value_double` of a text, for example).
 */
@DrivineInternalApi
interface HostArgs {
    fun type(i: Int): SqlType

    fun isNull(i: Int): Boolean = type(i) == SqlType.NULL

    fun long(i: Int): Long

    fun int(i: Int): Int

    fun double(i: Int): Double

    /** The argument as UTF-8 text, decoded (an invalid sequence as U+FFFD); null where it is NULL. */
    fun text(i: Int): String?

    /** The argument as UTF-16 text, its code units as they are (a lone surrogate kept); null where it is NULL. */
    fun text16(i: Int): String?

    /** The argument as bytes (a text's UTF-8); null where it is NULL. */
    fun blob(i: Int): ByteArray?
}

/** A call's result, as the host writes it. The last one written is the call's. */
@DrivineInternalApi
interface HostResult {
    fun nullValue()

    fun long(value: Long)

    fun int(value: Int)

    fun double(value: Double)

    /** The text, as UTF-8 (a lone surrogate as U+FFFD). */
    fun text(value: String)

    /** The text, as UTF-16: its code units as they are. */
    fun text16(value: String)

    fun blob(value: ByteArray)

    /** Fails the statement with [message]. */
    fun error(message: String)
}

/** A host function's body: it reads [count] arguments from [args] and writes its result to [result]. */
@DrivineInternalApi
fun interface ScalarBody {
    fun call(args: HostArgs, count: Int, result: HostResult)
}

/**
 * A scalar host function, as SQL calls it: [name], taking [arity] arguments (-1 for any number). [deterministic] where
 * it gives the same result for the same arguments within a statement, so SQLite may use it in an index or factor it out;
 * [innocuous] where it has no side effects and reads nothing outside its arguments, so a trigger or view of an
 * untrusted schema may call it; [directOnly] where it changes state, so only a statement of the host's own may call it.
 * [encoding] is the encoding SQLite converts its text arguments to. It can't be both innocuous and direct-only.
 */
@DrivineInternalApi
class HostFunction(
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
}
