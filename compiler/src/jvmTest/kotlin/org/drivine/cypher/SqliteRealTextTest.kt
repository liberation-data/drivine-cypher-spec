package org.drivine.cypher

import org.drivine.cypher.number.SqliteRealText
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * [SqliteRealText] against SQLite's own text of a REAL, as `CAST(x AS TEXT)` gives it on the JVM's SQLite, value by value: what
 * a host function the wasm build calls reads a REAL as (liberation-data/drivine#757). Every text reads back as SQLite's does;
 * where SQLite's has at most 15 significant digits, it is SQLite's, letter for letter. SQLite's 17 digits, where 15 don't read
 * back, depend on the platform's long double, and a subnormal's on SQLite's own reading (see [SqliteRealText]), so only
 * their value is pinned.
 */
class SqliteRealTextTest {

    @Test
    fun eachRealIsWrittenAsSqliteWritesIt() {
        val random = Random(757)
        val edges = listOf(
            0.1, 1.0, -1.0, 2.5, 100.0, 1e14, 1e15, 1e16, 123456789012345.0, 1234567890123456.0, 1e-4, 1e-5, 0.00012345,
            1.5e-5, 1e20, -1e20, 1e300, 1e-300, Double.MAX_VALUE, Double.MIN_VALUE, 4.9e-324, 0.1 + 0.2, 1.0 / 3, 2.0 / 3,
            999999999999999.5, 0.30000000000000004, 1.0000000000000002, 9.999999999999999e22, 5e-324, -0.5, 1e-7, 3.14159,
            123456.7890123456789, 0.000999999999999999, 2.2250738585072014E-308, 2.225073858507201E-308, 1e-310, -3e-320,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0,
        )
        val subnormals = List(500) { Double.fromBits(random.nextLong(1L, 1L shl 52)) }
        val values = edges + subnormals + List(20_000) {
            when (it % 4) {
                0 -> random.nextDouble()
                1 -> random.nextDouble(-1e6, 1e6)
                2 -> Double.fromBits(random.nextLong()).takeIf { d -> d.isFinite() } ?: 1.0
                else -> random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L) / 1000.0
            }
        }
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.prepareStatement("SELECT CAST(? AS TEXT)").use { s ->
                for (value in values) {
                    s.setDouble(1, value)
                    val sqlite = s.executeQuery().use { r -> r.next(); r.getString(1) }
                    val ours = SqliteRealText.of(value)
                    val where = "$value (${value.toRawBits()}): SQLite wrote $sqlite, this $ours"
                    // An infinity and minus zero, letter for letter: SQLite's Inf reads as no Double.
                    if (!value.isFinite() || value == 0.0) { assertEquals(sqlite, ours, where); continue }
                    assertEquals(sqlite.toDouble(), ours.toDouble(), where)
                    if (significant(sqlite) <= 15 && kotlin.math.abs(value) >= java.lang.Double.MIN_NORMAL) assertEquals(sqlite, ours, where)
                }
            }
        }
    }

    /** The significant digits of the number [text] writes. */
    private fun significant(text: String) =
        text.substringBefore('e').filter(Char::isDigit).trimStart('0').trimEnd('0').length
}
