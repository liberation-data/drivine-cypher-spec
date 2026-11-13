package org.drivine.cypher.sql

import org.drivine.cypher.json.TemporalValue

/**
 * The function each SQL store keeps, or registers, that works out `+`, `-`, `*` and `/` where a side is a temporal value read
 * from the store, a tag whose kind the statement doesn't know (drivine4j#418). A compiled statement calls it where a side may
 * be such a tag (`SqlCypherCompiler.temporalRefused`): `<function>(operator, x, y)`, the operator as `+`, `-`, `*` or `/`, and
 * x and y as the JSON they are. It gives the tag the arithmetic makes, and fails by name for a pair of kinds Cypher doesn't add
 * or take away, as Neo4j does (`date + date`).
 *
 * Its body on Postgres and Oracle is the compiler's own temporal arithmetic: for each pair of kinds, the statement it compiles
 * for `$x <op> $y` where x and y are given as values of those kinds, read over the function's arguments. A statement whose
 * sides the statement knows to be temporal is compiled to that same SQL inline, so the two give the same answers by
 * construction, and a fix to the one is a fix to the other. The SQL depends on the kinds alone, not the values given, so one
 * body serves each pair. A pair whose duration is first (`duration + date`, `2 * duration`) is the other way round.
 *
 * A body changes wherever the inline arithmetic changes. A store made before keeps the function it was made with until it is
 * made again: on Postgres when `PostgresDdl.VERSION` changes, on Oracle when the function's version, a hash of its DDL,
 * changes (`OracleDdl.ensuring`).
 */
object TemporalArithmeticFunction {

    /** The SQLite host function each library registers on its connections, as it does the module's others. */
    const val HOST = "drivine_temporal_arithmetic"

    /** The function the store of the graph [graph] keeps. */
    fun name(graph: String) = "${graph}_temporal_arithmetic"

    /** A value of each kind, given where a body is compiled: the SQL depends on its kind, not on it. */
    private val SAMPLE = mapOf(
        Temporal.DATE to "2024-01-31", Temporal.LOCAL_TIME to "10:00:00", Temporal.TIME to "10:00:00Z",
        Temporal.LOCAL_DATE_TIME to "2024-01-01T10:00:00", Temporal.DATE_TIME to "2024-01-01T10:00:00Z", Temporal.DURATION to "P1D",
    )

    /** One body: the operator, the kind of the side first, and the kind of the second, or null for a number. */
    private data class Pair(val operator: String, val x: Temporal, val y: Temporal?)

    /**
     * Each body the function holds, in the order it tests them: a moment and a duration added or taken away, two durations, and
     * a duration multiplied or divided by a number.
     */
    private val PAIRS: List<Pair> = (Temporal.entries - Temporal.DURATION).flatMap { moment ->
        listOf(Pair("+", moment, Temporal.DURATION), Pair("-", moment, Temporal.DURATION))
    } + listOf(
        Pair("+", Temporal.DURATION, Temporal.DURATION), Pair("-", Temporal.DURATION, Temporal.DURATION),
        Pair("*", Temporal.DURATION, null), Pair("/", Temporal.DURATION, null),
    )

    /** The statement [compiler], made anew so its aliases start again, compiles for [pair]. */
    private fun compiled(compiler: () -> SqlCypherCompiler, pair: Pair): String {
        val y: Any = pair.y?.let { TemporalValue(it, SAMPLE.getValue(it)) } ?: 2L
        return compiler().compile("RETURN \$x ${pair.operator} \$y AS r", mapOf("x" to TemporalValue(pair.x, SAMPLE.getValue(pair.x)), "y" to y)).sql
    }

    /**
     * Each test and body, in [PAIRS]' order: [test] of the operator and the kinds, the kind's word or null for a number, and the
     * body [body] makes of the compiled statement with x and y as [x] and [y]. A plus of a duration and a moment, and a times
     * of a number and a duration, are their pair's body with the sides swapped.
     */
    private fun branches(
        compiler: () -> SqlCypherCompiler, x: String, y: String,
        test: (operator: String, x: String?, y: String?) -> String, body: (String) -> String,
    ): List<kotlin.Pair<String, String>> = PAIRS.flatMap { pair ->
        val sql = compiled(compiler, pair)
        fun read(first: String, second: String) = body(sql.replace("\$_cy_x", first).replace("\$_cy_y", second))
        val here = test(pair.operator, pair.x.function, pair.y?.function) to read(x, y)
        val swapped = when {
            pair.operator == "+" && pair.y == Temporal.DURATION && pair.x != Temporal.DURATION -> test("+", Temporal.DURATION.function, pair.x.function) to read(y, x)
            pair.operator == "*" -> test("*", null, Temporal.DURATION.function) to read(y, x)
            else -> null
        }
        listOfNotNull(here, swapped)
    }

    /**
     * The Postgres function of the graph [graph], as `CREATE OR REPLACE FUNCTION`: PL/pgSQL, its body a text in quotes, not
     * dollars, so the block `PostgresDdl.ensuring` makes the store in reads it as one statement.
     */
    fun postgres(graph: String): String {
        val tests = branches({ PostgresCypherCompiler(graph) }, "cy_x", "cy_y", { op, x, y ->
            "cy_op = '$op' AND ${x?.let { "kx = '$it'" } ?: "nx"} AND ${y?.let { "ky = '$it'" } ?: "ny"}"
        }) { "RETURN ($it);" }
        val body = buildString {
            appendLine("DECLARE")
            appendLine("    kx text := CASE WHEN jsonb_typeof(cy_x) = 'object' THEN cy_x ->> '\$t' END;")
            appendLine("    ky text := CASE WHEN jsonb_typeof(cy_y) = 'object' THEN cy_y ->> '\$t' END;")
            appendLine("    nx boolean := jsonb_typeof(cy_x) = 'number';")
            appendLine("    ny boolean := jsonb_typeof(cy_y) = 'number';")
            appendLine("BEGIN")
            appendLine("    IF cy_x IS NULL OR cy_y IS NULL OR jsonb_typeof(cy_x) = 'null' OR jsonb_typeof(cy_y) = 'null' THEN")
            appendLine("        RETURN NULL;")
            appendLine("    END IF;")
            tests.forEach { (test, read) -> appendLine("    IF $test THEN\n        $read\n    END IF;") }
            appendLine("    RAISE EXCEPTION '$UNDEFINED %, % and %', cy_op, COALESCE(kx, jsonb_typeof(cy_x)), COALESCE(ky, jsonb_typeof(cy_y));")
            append("END;")
        }
        return "CREATE OR REPLACE FUNCTION ${name(graph)}(cy_op text, cy_x jsonb, cy_y jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS '" +
            body.replace("'", "''") + "'"
    }

    /**
     * The Oracle function of the graph [graph], with no `CREATE OR REPLACE`, as `OracleDdl` keeps its functions: PL/SQL, each
     * body selected into the tag it gives. It calls the graph's era functions and `_temporal_key`, made before it: where the
     * store has not those, it is not valid, and a statement calls none of it (`GraphOptions.temporalArithmeticStored`).
     */
    fun oracle(graph: String): String {
        val number = "IN ('number', 'double', 'float')"
        val tests = branches({ OracleCypherCompiler(graph, true, true) }, "JSON_SERIALIZE(cy_x)", "JSON_SERIALIZE(cy_y)", { op, x, y ->
            "cy_op = '$op' AND ${x?.let { "kx = '$it'" } ?: "tx $number"} AND ${y?.let { "ky = '$it'" } ?: "ty $number"}"
        }) { "SELECT ($it) INTO r FROM DUAL;\n        RETURN r;" }
        return buildString {
            appendLine("FUNCTION ${name(graph)}(cy_op VARCHAR2, cy_x JSON, cy_y JSON) RETURN JSON IS")
            appendLine("    r JSON;")
            appendLine("    kx VARCHAR2(20);")
            appendLine("    ky VARCHAR2(20);")
            appendLine("    tx VARCHAR2(20);")
            appendLine("    ty VARCHAR2(20);")
            appendLine("BEGIN")
            appendLine("    SELECT CASE WHEN JSON_VALUE(cy_x, '\$.type()') = 'object' THEN JSON_VALUE(cy_x, '\$.\"\$t\"') END,")
            appendLine("        CASE WHEN JSON_VALUE(cy_y, '\$.type()') = 'object' THEN JSON_VALUE(cy_y, '\$.\"\$t\"') END,")
            appendLine("        JSON_VALUE(cy_x, '\$.type()'), JSON_VALUE(cy_y, '\$.type()') INTO kx, ky, tx, ty FROM DUAL;")
            appendLine("    IF tx IS NULL OR ty IS NULL OR tx = 'null' OR ty = 'null' THEN")
            appendLine("        RETURN NULL;")
            appendLine("    END IF;")
            tests.forEach { (test, read) -> appendLine("    IF $test THEN\n        $read\n    END IF;") }
            appendLine("    RAISE_APPLICATION_ERROR(-20418, '$UNDEFINED ' || cy_op || ', ' || COALESCE(kx, tx) || ' and ' || COALESCE(ky, ty));")
            append("END;")
        }
    }

    /** What the function fails with for a pair of kinds Cypher doesn't add, take away, multiply or divide, as Neo4j fails it. */
    const val UNDEFINED = "Cypher has no temporal arithmetic of the operator, kinds or values:"
}
