package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.TemporalArithmeticFunction

/**
 * How a library finds which of the graph's Oracle functions its session may call, so it compiles a statement to call only
 * those (liberation-data/drivine4j#808, #811): one statement for each of the compiler's flags, which calls every function the
 * flag lets the compiler call, and the errors that mean the session has no such function to call. A store an earlier version
 * made may have some and not others, and another user's may stand behind a synonym, so each is found by calling it, not by
 * looking in the catalog. drivine4j's `OracleStoredFunctions` runs these; TypeScript and Python read them through the JSON entry
 * (`storeStatements`), so the three probe alike.
 */
@DrivineInternalApi
object OracleFunctionProbes {

    /**
     * The statement that calls every function the compiler flag names calls, by the flag's name: `nullsEqualStored`,
     * `eraStored` (the era functions and the temporal key, which the compiler calls on the one flag, drivine4j#808) and
     * `temporalArithmeticStored` (which calls the era functions and the key in turn, so where they are missing it is there
     * but not valid, drivine4j#811). Each gives a row where the session may call them all.
     */
    fun probes(graph: String): Map<String, String> {
        val era = OracleCypherCompiler.eraName(graph)
        val eraDate = OracleCypherCompiler.eraDateName(graph)
        val key = OracleCypherCompiler.temporalKeyName(graph)
        return linkedMapOf(
            "nullsEqualStored" to "SELECT ${OracleCypherCompiler.nullsEqualName(graph)}(JSON('[1]'), JSON('[1]')) FROM DUAL",
            "eraStored" to "SELECT $era('0000-01-01'), $eraDate('-0001-01-01'), $key('date', '2024-01-01') FROM DUAL",
            "temporalArithmeticStored" to "SELECT JSON_SERIALIZE(${TemporalArithmeticFunction.name(graph)}('+', " +
                "JSON('{\"\$t\":\"date\",\"v\":\"2024-01-01\"}'), JSON('{\"\$t\":\"duration\",\"v\":\"P1D\"}'))) FROM DUAL",
        )
    }

    /**
     * The Oracle error numbers a probe that finds no function to call fails with: ORA-00904 where there is none, or none the
     * session may call (and of a synonym for one that is gone), ORA-06575 where it is not valid, and ORA-00942 or ORA-01031
     * where something it reads is not granted. Any other error, a connection lost among them, is the statement's to report.
     */
    val NO_FUNCTION: List<Int> = listOf(904, 942, 1031, 6575)
}
