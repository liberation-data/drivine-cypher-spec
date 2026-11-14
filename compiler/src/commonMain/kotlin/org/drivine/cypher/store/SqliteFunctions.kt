package org.drivine.cypher.store

import org.drivine.DrivineInternalApi

/**
 * The functions Drivine registers on each connection to a SQLite store, which its statements call:
 * SQLite keeps no function of its own a statement could, and runs one written in the application's
 * language as readily as its own. Each is registered by [register], on a connection as it is opened
 * (see [SqliteDataSource]).
 *
 * - [WRITE] carries out a plan of steps: see [SqliteWriteFunction].
 * - [DELETE] deletes the nodes a statement selected, and counts them: see [SqliteDeleteFunction].
 * - [LOWER_COPIES] fills in the lowercased copies of a property, and counts the nodes it wrote: see
 *   [SqliteLowerCopiesFunction].
 * - [SIMILARITY] scores an embedding against a vector: see [SqliteSimilarityFunction].
 * - [REGEXP_COUNT] counts the matches of a regular expression, and [REGEXP] is SQLite's `REGEXP`
 *   operator: see [SqliteRegexpCountFunction] and [SqliteRegexpFunction].
 * - [LOWER] lowers text as Unicode does: see [SqliteLowerFunction].
 * - [EPOCH] reads a moment as nanoseconds since the epoch: see [SqliteEpochFunction].
 *
 * Compiled Cypher calls the rest, for what Cypher does with its values, JSON text each, that SQLite does not:
 *
 * - [EQUAL], [CANONICAL] and [ADD] compare, group and add them: see [SqliteEqualFunction],
 *   [SqliteCanonicalFunction] and [SqliteAddFunction].
 * - [UPPER], [SPLIT], [REVERSE] and [UUID] are Cypher's `toUpper`, `split`, `reverse` and `randomUUID`: see
 *   [SqliteUpperFunction], [SqliteSplitFunction], [SqliteReverseFunction] and [SqliteUuidFunction].
 * - [MATH] and [ROUND] work out Cypher's functions of numbers, `exp`, `log`, `sin` …, and `round` by a mode, as
 *   Neo4j does: see [SqliteMathFunction] and [SqliteRoundFunction].
 * - [REGEX_GROUP] and [REGEX_REPLACE] read and replace what a regular expression matches: see
 *   [SqliteRegexGroupFunction] and [SqliteRegexReplaceFunction].
 * - [FLOAT_TEXT] writes a float as Java does, for `toString`, [SORT_KEY] orders a list as Cypher does, and [LIST_ORDER]
 *   compares two lists as Cypher does: see [SqliteFloatTextFunction], [SqliteSortKeyFunction] and [SqliteListOrderFunction].
 * - [TRUNC], [TO_CHAR] and [TEMPORAL] work out temporal values: see [SqliteTruncFunction], [SqliteToCharFunction]
 *   and [SqliteTemporalFunction]. The module implements them in common code (`org.drivine.cypher.host.HostFunctions`),
 *   in the forms the compiler's SQL calls: `drivine_temporal`'s `add_time(timestamp, days, seconds, nanos)`, whole
 *   numbers each; `seconds_between(from, to)`, whole seconds, floored, and `nanos_between(from, to)`, the nanoseconds past
 *   them; and `extract(timestamp, 'NANOSECOND_OF_MINUTE')`, where `SECOND` is no field.
 * - `drivine_temporal_arithmetic` (`TemporalArithmeticFunction.HOST`) works out `+`, `-`, `*` and `/` of a temporal value
 *   read from the store, as Postgres and Oracle do by the function they keep: the module's alone
 *   (`org.drivine.cypher.host.HostFunctions.temporalArithmetic`, drivine4j#418).
 * - [HOST_VERSION_FUNCTION] gives [HOST_VERSION], the version of those forms, which a host checks once on each
 *   connection, as `org.drivine.cypher.host.HostCheck` words it.
 * - [CYPHER] carries out the procedure a statement that writes compiles to: see [SqliteCypherFunction].
 * - [FAIL] fails the statement with its text, where Neo4j fails on a value it reads: see [SqliteFailFunction].
 *
 * A function that reads only its arguments is registered as deterministic, so SQLite may index an
 * expression over it.
 */
@DrivineInternalApi
object SqliteFunctions {

    const val WRITE = "drivine_write"
    const val DELETE = "drivine_delete"
    const val LOWER_COPIES = "drivine_lower_copies"
    const val SIMILARITY = "drivine_similarity"
    const val REGEXP_COUNT = "drivine_regexp_count"
    const val LOWER = "drivine_lower"
    const val EPOCH = "drivine_epoch"
    const val EQUAL = "drivine_equal"
    const val ADD = "drivine_add"
    const val CANONICAL = "drivine_canonical"
    const val CYPHER = "drivine_cypher"
    const val UPPER = "drivine_upper"
    const val SPLIT = "drivine_split"
    const val REVERSE = "drivine_reverse"
    const val UUID = "drivine_uuid"
    const val REGEX_GROUP = "drivine_regex_group"
    const val REGEX_REPLACE = "drivine_regexp_replace"
    const val TRUNC = "drivine_trunc"
    const val TO_CHAR = "drivine_to_char"
    const val TEMPORAL = "drivine_temporal"
    const val FLOAT_TEXT = "drivine_float_text"
    const val SORT_KEY = "drivine_sort_key"
    const val LIST_ORDER = "drivine_list_order"
    const val MATH = "drivine_math"
    const val ROUND = "drivine_round"
    const val FAIL = "drivine_fail"
    const val REGEXP = "regexp"
    const val HOST_VERSION_FUNCTION = "drivine_host_version"

    /**
     * The version of the host functions' contract: the forms of their arguments and results the compiler's SQL calls them
     * with, which the spec's host.yaml pins. A change to a function's form bumps it, in the same change as its host.yaml
     * steps, so a host that registers older functions fails on connecting, not mid-statement. 1 is the first a function
     * reports; functions with no [HOST_VERSION_FUNCTION] are drivine4j's older forms. 2 adds `drivine_temporal_arithmetic`
     * (drivine4j#418), which the compiler's SQL calls on SQLite.
     */
    const val HOST_VERSION = 2
}
