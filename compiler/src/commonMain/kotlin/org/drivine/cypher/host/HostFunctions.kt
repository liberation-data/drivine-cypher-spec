package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.number.DoubleText
import org.drivine.cypher.store.SqliteFunctions

/**
 * The host functions of [SqliteFunctions] that the module implements, which each host registers on SQLite as they are:
 * the native library as a SQLite extension, a host on the JVM from its own registrations. They are ported from
 * drivine4j's in batches (#108); the spec's host.yaml pins what each gives, and HostSpecTest checks each one here against
 * its cases, and lists those not yet ported.
 */
@DrivineInternalApi
object HostFunctions {

    /**
     * `drivine_float_text(real)`: the float as Java writes a double, as Cypher's `toString` gives it on a graph engine
     * (`2.0`, `1.0E7`, `0.3333333333333333`, an infinity as `Infinity`), where SQLite's own text of a real has 17
     * digits. Null of null; any other argument is read as SQLite reads it as a float.
     */
    val floatText = HostFunction(SqliteFunctions.FLOAT_TEXT, 1, deterministic = true) { args, _, result ->
        if (args.isNull(0)) result.nullValue() else result.text(DoubleText.of(args.double(0)))
    }

    /** Every host function the module implements, in the order each host registers them. */
    val all: List<HostFunction> = listOf(floatText)
}
