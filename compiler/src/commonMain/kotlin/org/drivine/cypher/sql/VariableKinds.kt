package org.drivine.cypher.sql

import org.drivine.cypher.CompilerException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.ast.SetItem

/**
 * The kind of each variable of a statement, as Neo4j works it out when it plans the statement, and the statements it
 * refuses for them: a variable used as two kinds, `MATCH (a)-[a]->()` (Neo4j's VariableTypeConflict), and one bound
 * already where a pattern makes it, `MATCH (a) CREATE (a)` (VariableAlreadyBound). Read from the statement alone, before
 * it is compiled, so a statement that compiles is compiled as it was, and one refused here is refused for what is wrong
 * with it, not for what the compiler does not take yet.
 *
 * Only a kind the statement fixes is known: a pattern's node, relationship, variable-length relationship or path, a value
 * written, and a variable a `WITH` passes on as it is; and, for `size()` alone, an item an `UNWIND` gives of a list
 * written of maps, and a map projection a `WITH` binds (see [Kind.SIZED_MAP]). Any other value (a property, a parameter, any other unwound item, a
 * function's result) may be of any kind, and is never refused here as being of another: what it is used as is left to
 * the compiler, as before.
 *
 * [aggregates] is the compiler's test of whether an expression aggregates, outside a nested query of its own, passed in
 * as [Aggregates] has it: after a `WITH` or a `RETURN` that groups, what its `ORDER BY` reads is not what it reads after one
 * that does not.
 */
internal class VariableKinds(private val aggregates: (Expression) -> Boolean) {

    /** A kind of variable: see [VariableKinds]. [noun] names a variable of it, as a message does; a value's kind is named as in [Value.Known]. */
    private enum class Kind(val noun: String) {
        NODE("a node"), RELATIONSHIP("a relationship"), RELATIONSHIPS("a list of relationships"), PATH("a path"),
        INTEGER("an integer"), FLOAT("a float"), TEXT("a text"), BOOLEAN("a truth value"), LIST("a list"), MAP("a map"),

        /**
         * A map Neo4j types a map when it compiles the statement for `size()` alone (#364): one an `UNWIND` gives of a list
         * written of maps, `UNWIND [{a: 1}] AS m`, or a map projection a `WITH` binds, `WITH n {.a} AS m`. A write of it as a
         * property, or a pattern that reads it as a node, fails only as each row runs, and not over no rows, so it is any
         * value to all else ([fits], [stored]).
         */
        SIZED_MAP("a map"),

        /** Any kind at all: a value whose kind the statement leaves open. */
        ANY("a value"),
    }

    /**
     * The variables in scope, by name, each of its kind. Of them, those a `WITH` passes on not, read in its `WHERE` ([dropped]):
     * a value there may read them, but a pattern names none of them (see [use]), in a subquery's too. One bound again within, as a
     * comprehension's variable, is no longer one of them. Of those, the ones a `WITH` that neither groups nor is `DISTINCT`
     * drops that no `WITH` before it had dropped ([shadowing]): Neo4j still reads them there, so a quantified path pattern's group names none of them as its own
     * (see [grouped]). [shadowing] holds the names a subquery has from outside it too, those a `CALL`'s brackets import and those in
     * scope for an `EXISTS { … }` or a `COUNT { … }`: within it, a `WITH`'s or an `UNWIND`'s alias, or a `CALL`'s item, binds
     * none of them again, nor, in an `EXISTS { … }` or a `COUNT { … }`, its `RETURN`'s alias, as Neo4j refuses (its 42N07,
     * drivine4j#819; see [rebinding]); a `WITH` that passes one on as it is keeps it so. A `CALL () { … }` within, which imports
     * none, binds them as its own, but they stay [outer] in it, at any depth, and a `CALL`'s item there names none of them
     * either: Neo4j has it so within an `EXISTS { … }` or a `COUNT { … }`, and the names a `CALL` imports are taken alike.
     *
     * Within the `WHERE` or the order of a `WITH` or a `RETURN` that groups or is DISTINCT, in a subquery there at any depth
     * too, [horizon] holds the names that projection can read, its items and what a subquery reads from outside it: Neo4j
     * reads the brackets of a `CALL (a) { … }` within as of that projection, and refuses one that names any other (its
     * 42N44), though it is the subquery's own (see [called]). Null outside any such projection. A projection that groups
     * within it, in a subquery, holds a horizon of its own there, its items and what its subquery reads from outside it. A
     * `CALL (*) { … }` within imports what its horizon doesn't hold too ([hidden]), but Neo4j refuses a read of it there
     * (its 42N44 too), in a pattern or as a value; a procedure's call, `CALL db.labels() YIELD label WHERE …`, does not hide
     * it. A subquery within, `EXISTS { … }` or `COUNT { … }`, reads as of where it is: what is in scope
     * there joins its horizon, a comprehension's variable or a subquery's own, but not what a `CALL (*)` hides (see [query]).
     */
    private class Scope(
        val kinds: MutableMap<String, Kind> = LinkedHashMap(),
        val dropped: MutableSet<String> = LinkedHashSet(),
        val shadowing: MutableSet<String> = LinkedHashSet(),
        var horizon: Set<String>? = null,
        val hidden: MutableSet<String> = LinkedHashSet(),
        val outer: MutableSet<String> = LinkedHashSet(),
    ) {
        fun copy() = Scope(LinkedHashMap(kinds), LinkedHashSet(dropped), LinkedHashSet(shadowing), horizon, LinkedHashSet(hidden), LinkedHashSet(outer))

        /** A copy, with [names] bound within an expression, as a comprehension's variable, of any kind. */
        fun binding(vararg names: String) = copy().also { scope -> names.forEach { scope[it] = Kind.ANY } }
        operator fun get(name: String) = kinds[name]
        operator fun set(name: String, kind: Kind) {
            kinds[name] = kind
            dropped -= name
            shadowing -= name
            hidden -= name
            outer -= name
        }
    }

    /**
     * Refuses [query] where a variable of it is used as two kinds, or made where it is bound already; where a pattern in a
     * `WITH`'s `WHERE` names a variable the `WITH` drops (see [Scope]), or in the `ORDER BY` of one that groups (see
     * [projected]); where a `WITH` or a `RETURN` names two items alike; where a pattern is used as a value, not as a
     * condition (see [valued]); and where a pattern or a subquery is in a property a `CREATE` or a `MERGE` makes, or a value
     * a `MERGE` sets, where Neo4j refuses it (see [made] and [merged]), and as not compiled yet where one is in the properties
     * a `CREATE` or a `MERGE` makes (see [timed]).
     */
    fun check(query: Query): CompilerException? {
        query(query, Scope())
        return inaccessible
    }

    /**
     * The first `CALL (a) { … }` whose brackets name a variable the projection that groups it is within can't read (see
     * [Scope.horizon]): given back by [check], not thrown, as Neo4j refuses a variable not defined anywhere in the statement
     * before it (its 42N62), and the compiler finds those as it compiles.
     */
    private var inaccessible: CompilerException? = null

    /** [query]'s variables, from those of [outer] it reads: what each part of it returns. */
    private fun query(query: Query, outer: Scope): List<Map<String, Kind>> {
        // Those of [outer] stay in scope after each WITH within, as Neo4j has a subquery read them, but those a WITH outside drops.
        val kept = outer.kinds.filterKeys { name -> name !in outer.dropped }
        // Within a horizon, what [outer] reads joins it, but what a CALL (*) hides, as Neo4j reads a CALL's brackets as of where the subquery is (drivine4j#681).
        val horizon = outer.horizon?.plus(kept.keys - outer.hidden)
        // What it reads from outside, it binds not again (drivine4j#819).
        return (listOf(query.clauses) + query.unions.map { it.clauses }).map { part ->
            clauses(part, outer.copy().also { it.horizon = horizon; it.shadowing += kept.keys }, kept = kept, returning = true)
        }
    }

    /**
     * [clauses] in turn, over [scope]: what their `RETURN` returns, of the kinds it returns. Within a `FOREACH`, [foreach],
     * a `CREATE` or a `MERGE` may name a variable bound already, as Neo4j takes it there. [kept] stay in scope after each
     * `WITH` that does not name them, as what a `CALL`'s brackets import does, and a subquery's variables from outside it.
     * Those of an `EXISTS { … }` or a `COUNT { … }`, [returning], bind with their `RETURN`'s aliases too; and an `UNWIND` binds
     * no variable in scope again (see [redeclared]).
     */
    private fun clauses(
        clauses: List<Clause>, scope: Scope, foreach: Boolean = false, kept: Map<String, Kind> = emptyMap(), returning: Boolean = false,
    ): Map<String, Kind> {
        var current = scope
        var returned = emptyMap<String, Kind>()
        for (clause in clauses) {
            when (clause) {
                is Clause.Match -> {
                    clause.patterns.forEach { matched(it, current) }
                    clause.where?.let { expression(it, current) }
                }
                is Clause.Unwind -> {
                    expression(clause.list, current)
                    valued(clause.list, condition = false)
                    rebinding(clause.variable, current.shadowing)
                    if (clause.variable !in current.shadowing && current[clause.variable] != null && clause.variable !in current.dropped) throw redeclared(clause.variable)
                    current[clause.variable] = (clause.list as? Expression.ListLiteral)?.items
                        ?.takeIf { items -> items.isNotEmpty() && items.all { it is Expression.MapLiteral || it is Expression.MapProjection } }
                        ?.let { Kind.SIZED_MAP } ?: Kind.ANY
                }
                is Clause.With -> {
                    // An item that passes a variable on as it is, WITH m1, binds nothing again.
                    clause.projection.items.forEach { if ((it.expression as? Expression.Variable)?.name != it.name) rebinding(it.name, current.shadowing) }
                    current = projected(clause.projection, current, clause.where, kept.keys).also { next ->
                        // Into its kinds alone, so one an outer WITH's WHERE still reads, kept in a subquery of an order there, stays shadowing (drivine4j#681).
                        kept.forEach { (name, kind) -> if (next[name] == null) next.kinds[name] = kind }
                    }
                }
                is Clause.Return -> {
                    // EXISTS { RETURN 'x' AS m1 } binds m1 again, as Neo4j has it (drivine4j#819).
                    if (returning) clause.projection.items.forEach { if ((it.expression as? Expression.Variable)?.name != it.name) rebinding(it.name, current.shadowing) }
                    returned = projected(clause.projection, current, null, kept.keys).kinds
                }
                is Clause.Create -> {
                    val made = mutableSetOf<String>()
                    val before = current.copy()
                    val own = madeBy(clause.patterns, before)
                    clause.patterns.forEach { made(it, current, before, own, made, merging = false, foreach) }
                    clause.patterns.forEach { pattern -> (pattern.nodes.mapNotNull { it.properties } + pattern.relationships.mapNotNull { it.properties }).forEach(::timed) }
                }
                is Clause.Merge -> {
                    merged(clause, current)
                    made(clause.pattern, current, current.copy(), madeBy(listOf(clause.pattern), current), mutableSetOf(), merging = true, foreach)
                    (clause.onCreate + clause.onMatch).forEach { set(it, current) }
                    (clause.pattern.nodes.mapNotNull { it.properties } + clause.pattern.relationships.mapNotNull { it.properties }).forEach(::timed)
                }
                is Clause.Set -> clause.items.forEach { set(it, current) }
                is Clause.Remove -> {}
                is Clause.Delete -> clause.expressions.forEach { expression(it, current) }
                is Clause.VectorSearch -> {
                    expression(clause.k, current)
                    expression(clause.vector, current)
                    clause.node?.let { current[it] = Kind.NODE }
                    clause.score?.let { current[it] = Kind.FLOAT }
                    clause.where?.let { expression(it, current) }
                }
                is Clause.Foreach -> {
                    expression(clause.list, current)
                    valued(clause.list, condition = false)
                    val inner = current.copy()
                    inner[clause.variable] = Kind.ANY
                    clauses(clause.clauses, inner, foreach = true)
                }
                is Clause.Call -> called(clause, current).forEach { (name, kind) ->
                    rebinding(name, current.shadowing + current.outer)
                    current[name] = kind
                }
            }
        }
        return returned
    }

    /**
     * What [call] returns, of the kinds each of its queries returns, read from the row [scope] holds: only a variable its
     * brackets import, `CALL (a) { … }` or `CALL (*) { … }`, or where it has none its leading `WITH` imports as it is, or
     * with `*`, of the kind it is; any other of the row is a new variable there, as in Neo4j.
     */
    private fun called(call: Clause.Call, scope: Scope): Map<String, Kind> {
        // In the WHERE or the order of a projection that groups, Neo4j reads the brackets as of that projection (drivine4j#681).
        scope.horizon?.let { horizon ->
            call.imports?.firstOrNull { it !in horizon }?.let {
                inaccessible = inaccessible ?: CompilerException("$it is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            }
        }
        val query = call.query
        val parts = (listOf(query.clauses) + query.unions.map { it.clauses }).map { clauses ->
            val imported = imports(call, clauses, scope.kinds.keys)
            val inner = scope.kinds.filterTo(LinkedHashMap()) { (name, _) -> name in imported }
            // What a WITH outside drops, imported by a CALL within its WHERE, CALL (*) { … }, stays dropped in the call (drivine4j#504).
            val dropped = scope.dropped.filterTo(LinkedHashSet()) { it in imported }
            val shadowing = scope.shadowing.filterTo(LinkedHashSet()) { it in imported }
            // What CALL (*) imports there that the projection can't read, is refused where it is read (drivine4j#681); not a procedure's.
            val hidden = imported.filterTo(LinkedHashSet()) { it in scope.hidden || (call.importsAll && !call.procedure && scope.horizon?.contains(it) == false) }
            // What its brackets import, it binds not again; and what a subquery around it has from outside stays outer within it (drivine4j#819).
            // What a leading WITH imports, Neo4j has declared in the subquery's own scope, as at the top (see [redeclared]).
            // Not a procedure's: the WITH of its WHERE yields a variable as its item, and one bound already is refused as such, as without one.
            val bracketed = call.imports != null || call.importsAll
            if (bracketed && !call.procedure) shadowing += imported - dropped
            val outer = LinkedHashSet(scope.outer).apply { addAll(scope.shadowing) }
            clauses(clauses, Scope(inner, dropped, shadowing, scope.horizon, hidden, outer), kept = if (bracketed) inner.filterKeys { it !in dropped } else emptyMap())
        }
        return parts.first().mapValues { (name, kind) -> if (parts.all { it[name] == kind }) kind else Kind.ANY }
    }

    companion object {
        /**
         * The variables of the row, of [names], that the query [clauses] of [call] imports, as Neo4j has it: those its brackets
         * name, `CALL (a, b) { … }`, or each with `CALL (*) { … }`; with no brackets, those its leading `WITH` names as they are,
         * or each with `WITH *`; none where it has neither, `CALL () { … }` or `CALL { MATCH … }`. The compiler reads a call's
         * scope from it too, so the two agree. Refuses a bracketed name not of the row, and, as Neo4j does (its 42I28), a leading
         * `WITH` that names a variable of the row, or `*`, with anything more: such a `WITH` imports, and one that names none is
         * any `WITH`, `CALL { WITH 1 AS one WHERE … }`.
         */
        fun imports(call: Clause.Call, clauses: List<Clause>, names: Collection<String>): Set<String> {
            val all = names.toSet()
            if (call.importsAll) return all
            call.imports?.let { imports ->
                imports.firstOrNull { it !in all }?.let { throw CompilerException("$it is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.") }
                return imports.toSet()
            }
            val with = clauses.firstOrNull() as? Clause.With ?: return emptySet()
            val leading = with.projection
            if (!leading.star && leading.items.none { (it.expression as? Expression.Variable)?.name in all }) return emptySet()
            val plain = leading.items.all { (it.expression as? Expression.Variable)?.name == it.name }
            if (!plain || with.where != null || leading.distinct || leading.orderBy.isNotEmpty() || leading.skip != null || leading.limit != null) {
                throw CompilerException(
                    "The importing WITH of a CALL { … } names variables of the row alone, as in Cypher: no alias, expression, DISTINCT, WHERE, ORDER BY, SKIP " +
                        "or LIMIT. Import them with CALL (a, b) { … }, and work on them in a WITH after."
                )
            }
            return if (leading.star) all else all intersect leading.items.map { it.name }.toSet()
        }
    }

    /**
     * The scope after a `WITH` or a `RETURN` of [projection] over [scope]: each item of the kind its expression is, and with
     * `*` each variable in scope. Its `WHERE`, [where], and its order read the items, and a variable before it as of any kind;
     * but a pattern in the `WHERE` names only what the `WITH` passes on, and what a `CALL`'s brackets import or a subquery
     * reads from outside it, [kept], as Neo4j has it after a `WITH` that groups or is DISTINCT (drivine4j#504). After one
     * that neither groups nor is DISTINCT, Neo4j reads in the `WHERE` what it drops, but what a `WITH` before had dropped already, so a quantified path pattern's group there
     * names none of them as its own (Neo4j's 42N07, drivine4j#681). After one that groups or is DISTINCT, a term of its order
     * that doesn't aggregate reads only the items, and [kept]: a pattern used as an expression there that names a variable
     * from before is refused, as Neo4j refuses it (its 42N29), where the compiler would read it as a new node that matches every
     * row; an `EXISTS { … }`, a `COUNT { … }` or a pattern comprehension binds such a variable as its own, as Neo4j does
     * (drivine4j#680). Refuses two items of one name (Neo4j's 42N38).
     */
    private fun projected(projection: Projection, scope: Scope, where: Expression?, kept: Set<String> = emptySet()): Scope {
        projection.items.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let { (name, _) ->
            throw CompilerException("Two items are named $name, as Cypher refuses: name each item of a WITH or a RETURN once, with AS.")
        }
        projection.items.forEach {
            expression(it.expression, scope)
            valued(it.expression, condition = false)
        }
        // What a WITH outside drops stays dropped after a subquery's own WITH, but for an item that names it again.
        // The clauses after it are within whatever projection [scope] is within, as it is.
        val next = if (projection.star) scope.copy() else Scope(dropped = LinkedHashSet(scope.dropped), shadowing = LinkedHashSet(scope.shadowing), horizon = scope.horizon, hidden = LinkedHashSet(scope.hidden), outer = LinkedHashSet(scope.outer))
        projection.items.forEach { next[it.name] = kindOf(it.expression, scope) }
        // An item that passes a name from outside the subquery on as it is, WITH m1, keeps it from outside, so it is bound again no more after (drivine4j#819).
        projection.items.forEach { if ((it.expression as? Expression.Variable)?.name == it.name && it.name in scope.shadowing) next.shadowing += it.name }
        val groups = projection.distinct || projection.items.any { aggregates(it.expression) }
        // Its own WHERE and order are within it, where it groups: they read its items, and what a subquery reads from outside it,
        // within a projection that groups outside it too (drivine4j#681).
        val horizon = if (!groups) scope.horizon else (next.kinds.keys + kept).toSet()
        // What an outer WITH's WHERE reads that it drops stays shadowing in a subquery's order, as in its WHERE, and what a
        // WITH outside dropped stays dropped there, as after it.
        val reading = Scope(scope.kinds.mapValuesTo(LinkedHashMap()) { Kind.ANY }.apply { putAll(next.kinds) }, LinkedHashSet(next.dropped), LinkedHashSet(next.shadowing), horizon, LinkedHashSet(next.hidden), LinkedHashSet(next.outer))
        where?.let {
            val dropped = scope.kinds.keys.filterTo(LinkedHashSet(next.dropped)) { name -> next[name] == null && name !in kept }
            // Not one a WITH before had dropped already, which Neo4j reads here no more than there.
            val shadowing = if (groups) LinkedHashSet(next.shadowing) else dropped.filterTo(LinkedHashSet(next.shadowing)) { it !in scope.dropped }
            expression(it, Scope(LinkedHashMap(reading.kinds), dropped, shadowing, horizon, LinkedHashSet(next.hidden), LinkedHashSet(next.outer)))
        }
        val items = Scope(scope.kinds.filterKeys { it in kept }.mapValuesTo(LinkedHashMap()) { Kind.ANY }.apply { putAll(next.kinds) }, LinkedHashSet(next.dropped), LinkedHashSet(next.shadowing), horizon, LinkedHashSet(next.hidden), LinkedHashSet(next.outer))
        // Read first, so a pattern that names a variable the order can't read is refused for that (42N29), before as a value (42I34), as Neo4j does.
        projection.orderBy.forEach {
            expression(it.expression, if (groups && !aggregates(it.expression)) items.copy() else reading)
            valued(it.expression, condition = false)
        }
        projection.skip?.let { expression(it, scope) }
        projection.limit?.let { expression(it, scope) }
        return next
    }

    /** The kind of [expression], where the statement fixes it: see [VariableKinds]. */
    private fun kindOf(expression: Expression, scope: Scope): Kind = when (expression) {
        is Expression.Variable -> scope[expression.name] ?: Kind.ANY
        // A value's kinds are named as Value.Known names them.
        is Expression.Literal -> Value.Known.of(expression.value)?.let { Kind.valueOf(it.name) } ?: Kind.ANY
        is Expression.ListLiteral -> Kind.LIST
        is Expression.MapLiteral -> Kind.MAP
        is Expression.MapProjection -> Kind.SIZED_MAP
        else -> Kind.ANY
    }

    /**
     * A pattern a `MATCH` finds, over [scope]: what it names, bound as it names it, or used as it is bound. Its path
     * variable is new, but in a pattern within an expression, [within], where it is the pattern's own.
     */
    private fun matched(pattern: Pattern, scope: Scope, within: Boolean = false) {
        // In the order written, so the first of two kinds is the one the variable is bound as.
        pattern.nodes.forEachIndexed { i, node ->
            node.properties?.let { expression(it, scope) }
            node.variable?.let { use(it, Kind.NODE, scope) }
            val relationship = pattern.relationships.getOrNull(i) ?: return@forEachIndexed
            relationship.properties?.let { expression(it, scope) }
            relationship.group?.let { group -> grouped(group, scope) }
            relationship.variable?.let { use(it, if (relationship.range != null) Kind.RELATIONSHIPS else Kind.RELATIONSHIP, scope) }
        }
        pattern.variable?.let { variable ->
            if (!within && scope[variable] != null) throw CompilerException("$variable is bound already, so a pattern binds no path to it, as in Neo4j.")
            scope[variable] = Kind.PATH
        }
    }

    /**
     * A quantified path pattern's [group]: what it names is a list outside it, of whatever it matched. A name bound outside
     * it, one Neo4j reads where it is, is refused, as Neo4j refuses it (its 42N07): one in scope, and one a `WITH` that
     * neither groups nor is DISTINCT drops, in its `WHERE` ([Scope.shadowing]). The compiler, which no longer has the one a
     * `WITH` dropped, nor in the order of a projection that groups one a subquery reads from outside it, would take it as the
     * group's own (drivine4j#681). One the `WITH` dropped, in its `WHERE` ([Scope.dropped]), is no longer read there.
     */
    private fun grouped(group: RelationshipPattern.Group, scope: Scope) {
        val names = group.pattern.nodes.mapNotNull { it.variable } + group.pattern.relationships.mapNotNull { it.variable }
        names.firstOrNull { (scope[it] != null && it !in scope.dropped) || it in scope.shadowing }?.let { throw CompilerException("$it is bound outside the quantified path pattern that names it.") }
        names.forEach { if (scope[it] == null) scope[it] = Kind.ANY }
    }

    /**
     * [variable] bound again within a subquery, by a `WITH`'s or an `UNWIND`'s alias, a `CALL`'s item, or an `EXISTS { … }`'s
     * or a `COUNT { … }`'s `RETURN` alias, where it is one of [outside], the names the subquery has from outside it (see
     * [Scope.shadowing] and [Scope.outer]), after a `WITH` that passes it on as it is too: Neo4j refuses it (its 42N07,
     * drivine4j#819), where the compiler gave the subquery's own value for it. A `FOREACH`'s variable, and a pattern's,
     * which reads the one from outside, are not refused, as Neo4j takes them. One a `CALL { … }`'s leading `WITH` imports is
     * not from outside: see [redeclared].
     */
    private fun rebinding(variable: String, outside: Set<String>) {
        if (variable !in outside) return
        val refused = CompilerException("The variable $variable is shadowing a variable with the same name from the outer scope, as in Neo4j: rename it.")
        if (!making) throw refused
        shadowed = shadowed ?: refused
    }

    /**
     * [variable] bound again by an `UNWIND` while it is in scope, `MATCH (m1) UNWIND ['u'] AS m1`, after a `WITH` that passes
     * it on, or where a `CALL { … }`'s leading `WITH` imports it, `CALL { WITH m1 UNWIND ['u'] AS m1 … }`, which Neo4j has
     * declared in the subquery's own scope, as at the top: Neo4j refuses it as declared already (its 42N59, "Variable `m1`
     * already declared"), where the compiler gave the `UNWIND`'s value for it (drivine4j#819). One from outside a subquery is
     * refused as shadowing instead (see [rebinding]); a `WITH`'s alias projects it anew, as Neo4j takes it.
     */
    private fun redeclared(variable: String) =
        CompilerException("Variable `$variable` already declared, as in Neo4j: an UNWIND binds no variable in scope again. Rename it.")

    /**
     * Within a property a `CREATE` or a `MERGE` makes ([making]), the first binding again [rebinding] finds, held until
     * [madeHere] has looked: Neo4j refuses a subquery there that binds a variable the same clause makes for that (its 42I58),
     * `CREATE (a), (m {f: COUNT { UNWIND [1] AS a RETURN a }})`, before as one shadowing it (its 42N07).
     */
    private var shadowed: CompilerException? = null
    private var making = false

    /** [properties] a `CREATE` or a `MERGE` makes, read over [scope] as any expression is, but a binding again held in [shadowed]. */
    private fun madeExpression(properties: Expression, scope: Scope) {
        making = true
        try {
            expression(properties, scope)
        } finally {
            making = false
        }
    }

    /** [variable] where a pattern binds it as [kind]: bound so, or used so where it is bound already as that kind. */
    private fun use(variable: String, kind: Kind, scope: Scope) {
        // One a WITH does not pass on, in its WHERE: read there as a new node, it would match every row (drivine4j#504).
        if (variable in scope.dropped) throw CompilerException("$variable is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
        read(variable, scope)
        val bound = scope[variable] ?: return scope.set(variable, kind)
        if (!fits(bound, kind)) throw conflict(variable, bound, kind)
    }

    /** [variable] read where it is: one [Scope.hidden] is refused, after a variable not defined anywhere (see [inaccessible]). */
    private fun read(variable: String, scope: Scope) {
        if (variable in scope.hidden) inaccessible = inaccessible ?: CompilerException("$variable is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
    }

    /** Whether a variable bound as [bound] may be used where a pattern takes [kind]. */
    private fun fits(bound: Kind, kind: Kind) =
        bound == kind || bound == Kind.ANY || bound == Kind.SIZED_MAP || (kind == Kind.RELATIONSHIPS && bound == Kind.LIST)

    private fun conflict(variable: String, bound: Kind, used: Kind) =
        CompilerException("$variable is ${bound.noun}, and is used as ${used.noun}: a variable is of one kind, as in Neo4j.")

    /**
     * A pattern a `CREATE` or a `MERGE` makes, over [scope]: each node of it new, or one bound already that it joins
     * up, and each relationship new: a node a pattern of the same `CREATE` made before it is bound already. [made] holds
     * the relationships the clause made before. A relationship bound before the clause, as one, is left to the compiler,
     * which refuses it as not compiled yet, as the spec has it. Within a `FOREACH`, [foreach], Neo4j takes a variable bound
     * already in each of these places, and only one of another kind is refused. [before] is the scope before the clause,
     * and [own] the variables the clause makes (see [madeBy]): a property it makes reads none of them (see [madeHere]), and
     * a pattern in the properties of a node made with no relationship names none of those bound before it either (see
     * [declared]); and none is a node, a relationship, a path or a map, nor a list of maps or lists (see [stored]).
     */
    private fun made(pattern: Pattern, scope: Scope, before: Scope, own: Set<String>, made: MutableSet<String>, merging: Boolean, foreach: Boolean) {
        val clause = if (merging) "MERGE" else "CREATE"
        pattern.nodes.forEach { node ->
            node.properties?.let {
                madeExpression(it, scope)
                madeHere(it, own, clause)
                shadowed?.let { shadowed = null; throw it }
                if (pattern.relationships.isEmpty() && !foreach) declared(it, before)
                valued(it, condition = false)
                properties(it, scope)
            }
            val variable = node.variable ?: return@forEach
            val bound = scope[variable]
            if (bound == null) {
                scope[variable] = Kind.NODE
                return@forEach
            }
            if (!foreach && (node.labels.isNotEmpty() || node.mapped)) {
                throw CompilerException("$variable is bound already, so a CREATE or MERGE makes no node of it with labels or properties.")
            }
            if (pattern.relationships.isEmpty()) {
                if (!foreach) throw CompilerException("$variable is bound already, so a $clause makes no node of it, as in Neo4j.")
                return@forEach
            }
            if (!fits(bound, Kind.NODE)) throw conflict(variable, bound, Kind.NODE)
        }
        pattern.relationships.forEach { relationship ->
            relationship.properties?.let {
                madeExpression(it, scope)
                madeHere(it, own, clause)
                shadowed?.let { shadowed = null; throw it }
                valued(it, condition = false)
                properties(it, scope)
            }
            val variable = relationship.variable ?: return@forEach
            if (foreach) return@forEach scope.set(variable, scope[variable] ?: Kind.RELATIONSHIP)
            val bound = scope[variable]
            if (bound != null && (bound != Kind.RELATIONSHIP || variable in made)) {
                throw CompilerException("$variable is bound already, so a $clause makes no relationship of it, as in Neo4j.")
            }
            scope[variable] = Kind.RELATIONSHIP
            made += variable
        }
        pattern.variable?.let { variable -> if (scope[variable] == null) scope[variable] = Kind.PATH }
    }

    /** The variables [patterns] make, a `CREATE`'s or a `MERGE`'s: each its nodes, its relationships or its path name that [before] has not bound. */
    private fun madeBy(patterns: List<Pattern>, before: Scope): Set<String> =
        patterns.flatMap { named(it) + listOfNotNull(it.variable) }.filterTo(LinkedHashSet()) { before[it] == null }

    /**
     * Refuses a property a `CREATE` or a `MERGE` makes, [properties], that reads a variable the same [clause] makes, of [own],
     * as Neo4j refuses it: in a pattern used as an expression (its 42N29, as for a variable not bound at all), `CREATE (a),
     * (m {f: NOT (a)-->()})`; and anywhere else (its 42I58), as the variable, a property of it or in `EXISTS { … }` or
     * `COUNT { … }`, `CREATE (a {name: 'x'}), (m {f: a.name})`, the node itself `CREATE (m {f: m.k})` among them. A
     * comprehension's variable, and what a pattern comprehension's pattern names, are bound anew, as in Neo4j, and are not
     * refused for it: `[(a)-->(x) | x]` matches any node as `a`, and so does a pattern in its `WHERE` that names it; any
     * other read of it there is refused (see [reads]).
     */
    private fun madeHere(properties: Expression, own: Set<String>, clause: String) {
        if (own.isEmpty()) return
        patternsIn(properties, emptySet()) { pattern, comprehension, inner ->
            if (comprehension) return@patternsIn
            named(pattern).firstOrNull { it in own && it !in inner }?.let { throw undefined(it) }
        }
        reads(properties, own, emptySet())?.let {
            throw CompilerException(
                "Creating an entity ($it) and referencing that entity in a property definition in the same $clause is not allowed, as in Neo4j: " +
                    "only reference variables created in earlier clauses. Make it in a clause before, or SET the property after."
            )
        }
        // Any other name of the clause's own, at any depth, is one Neo4j reads as a variable bound anew there (drivine4j#814).
        if (mentionsIn(properties).any { it in own }) {
            throw CompilerException("A variable a CREATE or a MERGE makes, read inside a pattern or a comprehension in its own properties, is Cypher, and is not compiled to SQL yet.")
        }
    }

    /**
     * Refuses [properties], those of any node or relationship a `CREATE` or a `MERGE` makes, within a `FOREACH` too, as not
     * compiled yet where it holds, at any depth, a pattern, a pattern comprehension, `EXISTS { … }` or `COUNT { … }`
     * (drivine4j#814). No position is kept: Neo4j reads such a read of the graph at another moment than a SQL store, which
     * reads each row's before it writes it. Neo4j reads a `CREATE`'s before it writes any of its rows, so `UNWIND [1, 2] AS i
     * CREATE (m:M {c: COUNT { (:M) }})` stores 0 and 0, not 0 and 1; and a `MERGE`'s after it makes the element, so
     * `MERGE (n)-[r:R {f: NOT (n)-->()}]->(m)` stores false, and `UNWIND [1, 2, 3] AS i MERGE (m:M {f: size([(x:M)-[*0..1]->()
     * | x])})` makes one node, counting itself, where a SQL store makes three. Read after what Neo4j refuses of the clause.
     */
    private fun timed(properties: Expression) {
        val reads = mutableListOf<Expression>()
        mentionsIn(properties, reads)
        if (reads.isNotEmpty()) {
            throw CompilerException("A pattern or a subquery in the properties a CREATE or a MERGE makes is Cypher, and is not compiled to SQL yet. Work it out in a WITH before the clause.")
        }
    }

    /**
     * Every name [expression] holds, at any depth, whatever binds it: each variable it reads, each a comprehension, a
     * quantifier or `reduce()` binds, each a pattern names, a map projection's and its selectors, and each a subquery's query
     * holds (see [mentionsIn]). Each pattern, pattern comprehension, `EXISTS { … }` and `COUNT { … }` it holds, at any depth,
     * is added to [reads] (see [timed]).
     */
    private fun mentionsIn(expression: Expression, reads: MutableList<Expression>? = null): Set<String> {
        val names = LinkedHashSet<String>()
        fun pattern(pattern: Pattern) {
            names += named(pattern) + listOfNotNull(pattern.variable)
            (pattern.nodes.mapNotNull { it.properties } + pattern.relationships.mapNotNull { it.properties }).forEach { names += mentionsIn(it, reads) }
            // A quantified path pattern's own pattern and its WHERE.
            pattern.relationships.forEach { relationship -> relationship.group?.let { group -> pattern(group.pattern); group.where?.let { names += mentionsIn(it, reads) } } }
        }
        if (expression is Expression.PatternPredicate || expression is Expression.PatternComprehension || expression is Expression.Exists || expression is Expression.Subquery) {
            reads?.add(expression)
        }
        when (expression) {
            is Expression.Variable -> names += expression.name
            is Expression.MapProjection -> {
                names += expression.variable
                expression.elements.forEach { if (it is Expression.MapProjection.Element.Variable) names += it.name }
            }
            is Expression.PatternPredicate -> pattern(expression.pattern)
            is Expression.PatternComprehension -> pattern(expression.pattern)
            is Expression.Exists -> expression.patterns.forEach(::pattern)
            is Expression.ListComprehension -> names += expression.variable
            is Expression.Quantifier -> names += expression.variable
            is Expression.Reduce -> names += listOf(expression.accumulator, expression.variable)
            is Expression.Subquery -> names += mentionsIn(expression.query, reads)
            else -> {}
        }
        parts(expression).forEach { names += mentionsIn(it, reads) }
        return names
    }

    /** Every name [query] holds, at any depth, as [mentionsIn] reads an expression: what each clause binds or reads. */
    private fun mentionsIn(query: Query, reads: MutableList<Expression>? = null): Set<String> {
        val names = LinkedHashSet<String>()
        fun expression(expression: Expression?) { if (expression != null) names += mentionsIn(expression, reads) }
        fun pattern(pattern: Pattern) = expression(Expression.PatternPredicate(pattern))
        fun projection(projection: Projection) {
            projection.items.forEach { names += it.name; expression(it.expression) }
            projection.orderBy.forEach { expression(it.expression) }
            expression(projection.skip)
            expression(projection.limit)
        }
        fun item(item: SetItem) {
            names += when (item) {
                is SetItem.Property -> item.variable
                is SetItem.Replace -> item.variable
                is SetItem.Add -> item.variable
                is SetItem.Labels -> item.variable
            }
            expression(setValue(item))
        }
        fun clauses(clauses: List<Clause>) {
            clauses.forEach { clause ->
                when (clause) {
                    is Clause.Match -> { clause.patterns.forEach(::pattern); expression(clause.where) }
                    is Clause.Unwind -> { names += clause.variable; expression(clause.list) }
                    is Clause.With -> { projection(clause.projection); expression(clause.where) }
                    is Clause.Return -> projection(clause.projection)
                    is Clause.Create -> clause.patterns.forEach(::pattern)
                    is Clause.Merge -> { pattern(clause.pattern); (clause.onCreate + clause.onMatch).forEach(::item) }
                    is Clause.Set -> clause.items.forEach(::item)
                    is Clause.Remove -> {}
                    is Clause.Delete -> clause.expressions.forEach(::expression)
                    is Clause.VectorSearch -> { names += listOfNotNull(clause.node, clause.score); expression(clause.k); expression(clause.vector); expression(clause.where) }
                    is Clause.Foreach -> { names += clause.variable; expression(clause.list); clauses(clause.clauses) }
                    is Clause.Call -> { names += clause.imports.orEmpty(); names += mentionsIn(clause.query, reads) }
                }
            }
        }
        clauses(query.clauses)
        query.unions.forEach { clauses(it.clauses) }
        return names
    }

    /**
     * The first of [names] that [expression] reads, and that is not bound anew within it ([bound]): as a variable, a map
     * projection's or its variable selector's, `x {a}`, in a pattern's properties, or named in `EXISTS { … }`'s patterns or
     * in a subquery's query (any variable it names there, see [namedIn]). Not the names of a pattern used as an expression,
     * which [madeHere] reads first, or of a pattern comprehension's pattern, which binds them anew; its `WHERE` and its
     * projection still read the clause's variable, `[(a)-->(x) WHERE a.id = 'b' | x]`, as Neo4j has it: only a
     * comprehension's, a quantifier's or `reduce()`'s variable is bound within.
     */
    private fun reads(expression: Expression, names: Set<String>, bound: Set<String>): String? {
        fun read(part: Expression, inner: Set<String> = bound) = reads(part, names, inner)
        fun own(name: String) = name.takeIf { it in names && it !in bound }
        fun properties(pattern: Pattern): String? = (pattern.nodes.mapNotNull { it.properties } + pattern.relationships.mapNotNull { it.properties }).firstNotNullOfOrNull { read(it) }
            // A quantified path pattern's own properties and its WHERE.
            ?: pattern.relationships.firstNotNullOfOrNull { relationship -> relationship.group?.let { group -> properties(group.pattern) ?: group.where?.let { read(it) } } }
        return when (expression) {
            is Expression.Variable -> own(expression.name)
            is Expression.MapProjection -> own(expression.variable)
                ?: expression.elements.firstNotNullOfOrNull { (it as? Expression.MapProjection.Element.Variable)?.let { element -> own(element.name) } }
                ?: parts(expression).firstNotNullOfOrNull { read(it) }
            is Expression.PatternPredicate -> properties(expression.pattern)
            is Expression.PatternComprehension -> properties(expression.pattern) ?: parts(expression).firstNotNullOfOrNull { read(it) }
            is Expression.ListComprehension ->
                read(expression.list) ?: listOfNotNull(expression.where, expression.projection).firstNotNullOfOrNull { read(it, bound + expression.variable) }
            is Expression.Quantifier -> read(expression.list) ?: expression.where?.let { read(it, bound + expression.variable) }
            is Expression.Reduce -> read(expression.initial) ?: read(expression.list) ?: read(expression.expression, bound + expression.accumulator + expression.variable)
            is Expression.Exists -> expression.patterns.flatMap { named(it) }.firstNotNullOfOrNull { own(it) }
                ?: expression.patterns.firstNotNullOfOrNull { properties(it) } ?: expression.where?.let { read(it) }
            is Expression.Subquery -> namedIn(expression.query).firstNotNullOfOrNull { own(it) }
            else -> parts(expression).firstNotNullOfOrNull { read(it) }
        }
    }

    /**
     * Each variable [query] names, in its patterns and its expressions, a subquery's within among them, whether it binds it
     * anew or not, but a comprehension's, a quantifier's or `reduce()`'s variable where they bind it: as much as Neo4j may
     * read from outside it. It reads a variable a clause within binds, `UNWIND [1] AS a RETURN a`, as the one outside.
     */
    private fun namedIn(query: Query): Set<String> {
        val names = LinkedHashSet<String>()
        fun expression(expression: Expression, bound: Set<String> = emptySet()) {
            fun name(name: String) { if (name !in bound) names += name }
            when (expression) {
                is Expression.Variable -> name(expression.name)
                is Expression.MapProjection -> {
                    name(expression.variable)
                    expression.elements.forEach { if (it is Expression.MapProjection.Element.Variable) name(it.name) }
                }
                is Expression.PatternPredicate -> named(expression.pattern).forEach(::name)
                // What its pattern names it binds anew, as at the top of a write's properties (drivine4j#814); its WHERE and projection are read below.
                is Expression.PatternComprehension -> {}
                is Expression.Exists -> expression.patterns.forEach { named(it).forEach(::name) }
                is Expression.Subquery -> namedIn(expression.query).forEach(::name)
                is Expression.ListComprehension -> {
                    expression(expression.list, bound)
                    listOfNotNull(expression.where, expression.projection).forEach { expression(it, bound + expression.variable) }
                    return
                }
                is Expression.Quantifier -> {
                    expression(expression.list, bound)
                    expression.where?.let { expression(it, bound + expression.variable) }
                    return
                }
                is Expression.Reduce -> {
                    expression(expression.initial, bound)
                    expression(expression.list, bound)
                    expression(expression.expression, bound + expression.accumulator + expression.variable)
                    return
                }
                else -> {}
            }
            parts(expression).forEach { expression(it, bound) }
        }
        fun pattern(pattern: Pattern) {
            names += named(pattern)
            pattern.nodes.forEach { node -> node.properties?.let(::expression) }
            pattern.relationships.forEach { relationship -> relationship.properties?.let(::expression) }
            // A quantified path pattern's own pattern and its WHERE.
            pattern.relationships.forEach { relationship -> relationship.group?.let { group -> pattern(group.pattern); group.where?.let(::expression) } }
        }
        fun projection(projection: Projection) {
            projection.items.forEach { expression(it.expression) }
            projection.orderBy.forEach { expression(it.expression) }
            listOfNotNull(projection.skip, projection.limit).forEach(::expression)
        }
        fun clauses(clauses: List<Clause>) {
            clauses.forEach { clause ->
                when (clause) {
                    is Clause.Match -> { clause.patterns.forEach(::pattern); clause.where?.let(::expression) }
                    is Clause.Unwind -> expression(clause.list)
                    is Clause.With -> { projection(clause.projection); clause.where?.let(::expression) }
                    is Clause.Return -> projection(clause.projection)
                    is Clause.Create -> clause.patterns.forEach(::pattern)
                    is Clause.Merge -> { pattern(clause.pattern); (clause.onCreate + clause.onMatch).mapNotNull { setValue(it) }.forEach(::expression) }
                    is Clause.Set -> clause.items.mapNotNull { setValue(it) }.forEach(::expression)
                    is Clause.Remove -> {}
                    is Clause.Delete -> clause.expressions.forEach(::expression)
                    is Clause.VectorSearch -> { expression(clause.k); expression(clause.vector); clause.where?.let(::expression) }
                    is Clause.Foreach -> { expression(clause.list); clauses(clause.clauses) }
                    is Clause.Call -> { names += clause.imports.orEmpty(); names += namedIn(clause.query) }
                }
            }
        }
        clauses(query.clauses)
        query.unions.forEach { clauses(it.clauses) }
        return names
    }

    /**
     * Refuses a pattern in [properties], a property of a node a `CREATE` or a `MERGE` makes with no relationship, that names
     * a variable bound before it, of [before] or a comprehension's within, as Neo4j refuses it (its 42N59, which reads such a
     * pattern as declaring the variable again): `MATCH (n) CREATE (m {f: NOT (n)-->()})`, and a pattern comprehension's
     * pattern, `[(n)-->(x) | x]`. A pattern of new variables alone is taken, and so are `EXISTS { … }` and `COUNT { … }`, in
     * a `CREATE` (a `MERGE` refuses them: see [merged]), and a relationship's properties, as Neo4j takes them.
     */
    private fun declared(properties: Expression, before: Scope) =
        patternsIn(properties, emptySet()) { pattern, _, inner ->
            named(pattern).firstOrNull { it in inner || before[it] != null }?.let {
                throw CompilerException(
                    "Variable `$it` already declared, as in Neo4j: a pattern in the properties of a node that a CREATE or a MERGE makes with no " +
                        "relationship names no variable bound before it. Work the value out in a WITH before, as in WITH n, EXISTS { (n)-->() } AS f CREATE (m {f: f})."
                )
            }
        }

    /**
     * Refuses [merge] where Neo4j refuses a subquery in a `MERGE` (its 42I48): an `EXISTS { … }` or a `COUNT { … }` in a
     * property it matches or makes, a node's or a relationship's, and a pattern, a pattern comprehension or a subquery in a
     * value its `ON CREATE` or `ON MATCH` sets. Read before the rest of it, as Neo4j does: `MERGE (m {f: NOT (n)-->(), g:
     * EXISTS { … }})` is refused for this. A pattern in a property it matches is read as in a `CREATE` (see [made]).
     */
    private fun merged(merge: Clause.Merge, scope: Scope) {
        val properties = merge.pattern.nodes.mapNotNull { it.properties } + merge.pattern.relationships.mapNotNull { it.properties }
        val values = (merge.onCreate + merge.onMatch).mapNotNull { setValue(it) }
        // A pattern in an ON CREATE or ON MATCH SET that names a variable bound nowhere is refused for that first, as Neo4j does (its 42N29).
        val bound = madeBy(listOf(merge.pattern), scope)
        values.forEach { value ->
            patternsIn(value, emptySet()) { pattern, comprehension, inner ->
                if (!comprehension) named(pattern).firstOrNull { it !in inner && scope[it] == null && it !in bound }?.let { throw undefined(it) }
            }
        }
        if (properties.any { holds(it, ::subquery) } || values.any { value -> holds(value) { subquery(it) || it is Expression.PatternPredicate || it is Expression.PatternComprehension } }) {
            throw CompilerException(
                "Subqueries are not allowed in a MERGE clause, as in Neo4j: no EXISTS { … } or COUNT { … } in what it matches, and no pattern, " +
                    "pattern comprehension or subquery in its ON CREATE or ON MATCH SET. Work the value out in a WITH before the MERGE, or SET it after."
            )
        }
    }

    private fun subquery(expression: Expression) = expression is Expression.Exists || expression is Expression.Subquery

    /** A `SET` item's value, or none for one that sets labels. */
    private fun setValue(item: SetItem): Expression? = when (item) {
        is SetItem.Property -> item.value
        is SetItem.Replace -> item.value
        is SetItem.Add -> item.value
        is SetItem.Labels -> null
    }

    /**
     * A `SET` item's value, over [scope]: a pattern is no value to set, as Neo4j refuses it (see [valued]); nor is a node, a
     * relationship or a path, `SET m.f = m`, a list written of one, `SET m.f = [m]`, or an entry of a map set, `SET n += {f: m}`,
     * which Neo4j refuses when it runs (its 22N01, "Property values can only be of primitive types or arrays thereof"), and
     * the store would hold as a document, nor a map or a list of maps or lists (see [stored]). Read after the rest of it, as Neo4j refuses what it refuses before
     * it runs first: `SET n.p = [b, zz]` is refused for `zz`, which is not defined.
     */
    private fun set(item: SetItem, scope: Scope) {
        val value = setValue(item) ?: return
        expression(value, scope)
        valued(value, condition = false)
        if (item is SetItem.Property) stored(value, scope) else properties(value, scope)
    }

    /**
     * Refuses [value], a property written, as Neo4j refuses it when it runs (its 22N01, inside 22G03): a variable of [scope]
     * bound as a node, a relationship or a path, or a list written that holds one, `[b]`; a map, written, projected
     * (`n {.id}`) or a variable bound to one written (`WITH {a: 1} AS m`); and a list written that holds a map or a list,
     * `[{a: 1}]`, `[[1, 2]]`, `[m]` (liberation-data/drivine4j#561). The store would hold each as a document. A map or a list
     * the statement doesn't fix the kind of, as a parameter's, is left to the write, which fails as it writes one given that is
     * no property (drivine4j#820).
     */
    private fun stored(value: Expression, scope: Scope) {
        when (value) {
            is Expression.Variable -> when (val kind = scope[value.name]) {
                Kind.NODE, Kind.RELATIONSHIP, Kind.RELATIONSHIPS, Kind.PATH ->
                    throw CompilerException("${value.name} is ${kind.noun}, and a property holds no node, relationship or path, as in Neo4j: set a property of it, or its elementId(), instead.")
                Kind.MAP -> throw notProperty("a map")
                else -> {}
            }
            is Expression.MapLiteral, is Expression.MapProjection -> throw notProperty("a map")
            is Expression.ListLiteral -> value.items.forEach { item ->
                val container = item is Expression.MapLiteral || item is Expression.MapProjection || item is Expression.ListLiteral ||
                    item is Expression.Variable && scope[item.name].let { it == Kind.MAP || it == Kind.LIST }
                if (container) throw notProperty("a list of maps or lists")
                stored(item, scope)
            }
            else -> {}
        }
    }

    /** The refusal of a property written as [what], a map or a list of maps or lists, in #448's words (see `SqlCypherCompiler`'s `SET`). */
    private fun notProperty(what: String) =
        CompilerException("A property is a number, a text, a truth value, a temporal value or a list of them, not $what, as in Neo4j.")

    /** Refuses each entry of [properties], a map written of the properties a write makes or sets, as [stored] refuses a property. */
    private fun properties(properties: Expression, scope: Scope) {
        if (properties is Expression.MapLiteral) properties.entries.forEach { stored(it.second, scope) }
    }

    /**
     * The patterns within [expression], each over [scope] and what it names only within it, and the functions of a
     * variable whose kind they do not take.
     */
    private fun expression(expression: Expression, scope: Scope) {
        when (expression) {
            is Expression.Literal, is Expression.Parameter, Expression.CountStar -> {}
            is Expression.Variable -> read(expression.name, scope)
            is Expression.Property -> expression(expression.subject, scope)
            is Expression.HasLabels -> expression(expression.subject, scope)
            is Expression.ListLiteral -> expression.items.forEach { expression(it, scope) }
            is Expression.MapLiteral -> expression.entries.forEach { expression(it.second, scope) }
            is Expression.MapProjection -> {
                read(expression.variable, scope)
                expression.elements.forEach {
                    if (it is Expression.MapProjection.Element.Entry) expression(it.value, scope)
                    if (it is Expression.MapProjection.Element.Variable) read(it.name, scope)
                }
            }
            is Expression.Binary -> {
                expression(expression.left, scope)
                expression(expression.right, scope)
            }
            is Expression.Unary -> expression(expression.operand, scope)
            is Expression.Index -> {
                expression(expression.subject, scope)
                expression(expression.index, scope)
            }
            is Expression.Slice -> listOfNotNull(expression.subject, expression.from, expression.to).forEach { expression(it, scope) }
            is Expression.Function -> {
                expression.arguments.forEach { expression(it, scope) }
                function(expression, scope)
            }
            is Expression.Case -> {
                listOfNotNull(expression.subject, expression.otherwise).forEach { expression(it, scope) }
                expression.alternatives.forEach { (key, value) ->
                    expression(key, scope)
                    expression(value, scope)
                }
            }
            is Expression.ListComprehension -> {
                expression(expression.list, scope)
                val inner = scope.binding(expression.variable)
                listOfNotNull(expression.where, expression.projection).forEach { expression(it, inner) }
            }
            is Expression.Quantifier -> {
                expression(expression.list, scope)
                val inner = scope.binding(expression.variable)
                expression.where?.let { expression(it, inner) }
            }
            is Expression.Reduce -> {
                expression(expression.initial, scope)
                expression(expression.list, scope)
                val inner = scope.binding(expression.accumulator, expression.variable)
                expression(expression.expression, inner)
            }
            is Expression.PatternComprehension -> {
                val inner = scope.copy()
                matched(expression.pattern, inner, within = true)
                expression.where?.let { expression(it, inner) }
                expression(expression.projection, inner)
            }
            is Expression.PatternPredicate -> {
                predicated(expression.pattern, scope)
                matched(expression.pattern, scope.copy(), within = true)
            }
            is Expression.Exists -> {
                val inner = scope.copy()
                expression.patterns.forEach { matched(it, inner, within = true) }
                expression.where?.let { expression(it, inner) }
            }
            is Expression.Subquery -> query(expression.query, scope)
        }
    }

    /**
     * A pattern used as an expression, `WHERE (n)-[r]->(a)`, that names a variable not bound in [scope]: Neo4j refuses it
     * (its 42N29), as such a pattern only tests for a match, and binds nothing.
     */
    private fun predicated(pattern: Pattern, scope: Scope) {
        named(pattern).firstOrNull { scope[it] == null }?.let { throw undefined(it) }
    }

    private fun undefined(variable: String) =
        CompilerException("$variable is not defined: a pattern used as an expression binds no new variable, as in Neo4j. Bind it in a MATCH, or test the pattern with EXISTS { … }.")

    /** The variables [pattern] names, its nodes' and its relationships', in the order written. */
    private fun named(pattern: Pattern) =
        pattern.nodes.indices.flatMap { i -> listOfNotNull(pattern.nodes[i].variable, pattern.relationships.getOrNull(i)?.variable) }

    /**
     * Each pattern within [expression], to [visit]: a pattern used as a condition, and a pattern comprehension's
     * (`comprehension`), each with the names bound within [expression] around it (`inner`), from [bound]: a comprehension's or
     * a quantifier's variable, `reduce()`'s, and what a pattern comprehension's pattern names, in its `WHERE` and its
     * projection. Not within `EXISTS { … }` or `COUNT { … }`, a query of its own.
     */
    private fun patternsIn(expression: Expression, bound: Set<String>, visit: (pattern: Pattern, comprehension: Boolean, inner: Set<String>) -> Unit) {
        fun walk(part: Expression, inner: Set<String> = bound) = patternsIn(part, inner, visit)
        when (expression) {
            is Expression.PatternPredicate -> visit(expression.pattern, false, bound)
            is Expression.PatternComprehension -> {
                visit(expression.pattern, true, bound)
                val inner = bound + named(expression.pattern) + listOfNotNull(expression.pattern.variable)
                expression.where?.let { walk(it, inner) }
                walk(expression.projection, inner)
            }
            is Expression.ListComprehension -> {
                walk(expression.list)
                listOfNotNull(expression.where, expression.projection).forEach { walk(it, bound + expression.variable) }
            }
            is Expression.Quantifier -> {
                walk(expression.list)
                expression.where?.let { walk(it, bound + expression.variable) }
            }
            is Expression.Reduce -> {
                walk(expression.initial)
                walk(expression.list)
                walk(expression.expression, bound + expression.accumulator + expression.variable)
            }
            is Expression.Exists, is Expression.Subquery -> {}
            else -> parts(expression).forEach { walk(it) }
        }
    }

    /** Whether [expression], or any expression within it, a comprehension's and a subquery's `WHERE` among them, is one [test] takes. */
    private fun holds(expression: Expression, test: (Expression) -> Boolean): Boolean = test(expression) || parts(expression).any { holds(it, test) }

    /** The expressions directly within [expression], but those of a pattern or a subquery's query. */
    private fun parts(expression: Expression): List<Expression> = when (expression) {
        is Expression.Literal, is Expression.Parameter, is Expression.Variable, Expression.CountStar, is Expression.PatternPredicate, is Expression.Subquery -> emptyList()
        is Expression.Property -> listOf(expression.subject)
        is Expression.HasLabels -> listOf(expression.subject)
        is Expression.ListLiteral -> expression.items
        is Expression.MapLiteral -> expression.entries.map { it.second }
        is Expression.MapProjection -> expression.elements.mapNotNull { (it as? Expression.MapProjection.Element.Entry)?.value }
        is Expression.Binary -> listOf(expression.left, expression.right)
        is Expression.Unary -> listOf(expression.operand)
        is Expression.Index -> listOf(expression.subject, expression.index)
        is Expression.Slice -> listOfNotNull(expression.subject, expression.from, expression.to)
        is Expression.Function -> expression.arguments
        is Expression.Case -> listOfNotNull(expression.subject, expression.otherwise) + expression.alternatives.flatMap { listOf(it.first, it.second) }
        is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection)
        is Expression.Quantifier -> listOfNotNull(expression.list, expression.where)
        is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression)
        is Expression.PatternComprehension -> listOfNotNull(expression.where, expression.projection)
        is Expression.Exists -> listOfNotNull(expression.where)
    }

    /**
     * Refuses a pattern used as a value in [expression], an item of a `WITH` or a `RETURN`, an order term, an `UNWIND`'s or a
     * `FOREACH`'s list, a `SET` item's value, or a property a `CREATE` or a `MERGE` makes, as Neo4j refuses it (its 42I34):
     * `RETURN (n)-->()`. A pattern is only a condition there, where [condition] is, as Neo4j takes one: an operand of `NOT`,
     * `AND`, `OR` or `XOR`, a searched `CASE`'s `WHEN`, the `WHERE` of a comprehension, a quantifier or a pattern. Read after [expression], so `size()` of one is refused for that first (see
     * [function]); a subquery's own items are read as its own projection's.
     */
    private fun valued(expression: Expression, condition: Boolean) {
        fun value(part: Expression) = valued(part, condition = false)
        fun test(part: Expression) = valued(part, condition = true)
        when (expression) {
            is Expression.PatternPredicate -> if (!condition) {
                throw CompilerException(
                    "A pattern expression can only be used to test the existence of a pattern, as in Neo4j: as a condition, as in a WHERE. " +
                        "Use a pattern comprehension instead, as in [(a)-->(b) | b], or EXISTS { … } or COUNT { … }."
                )
            }
            is Expression.Binary -> {
                val logical = expression.operator in setOf(Expression.Binary.Operator.AND, Expression.Binary.Operator.OR, Expression.Binary.Operator.XOR)
                listOf(expression.left, expression.right).forEach { valued(it, logical) }
            }
            is Expression.Unary -> valued(expression.operand, expression.operator == Expression.Unary.Operator.NOT)
            is Expression.Function -> expression.arguments.forEach(::value)
            is Expression.Case -> {
                listOfNotNull(expression.subject, expression.otherwise).forEach(::value)
                expression.alternatives.forEach { (key, result) ->
                    valued(key, condition = expression.subject == null)
                    value(result)
                }
            }
            is Expression.ListComprehension -> {
                value(expression.list)
                expression.where?.let(::test)
                expression.projection?.let(::value)
            }
            is Expression.Quantifier -> {
                value(expression.list)
                expression.where?.let(::test)
            }
            is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression).forEach(::value)
            is Expression.PatternComprehension -> {
                expression.where?.let(::test)
                value(expression.projection)
            }
            is Expression.Exists -> expression.where?.let(::test)
            is Expression.Property -> value(expression.subject)
            is Expression.HasLabels -> value(expression.subject)
            is Expression.ListLiteral -> expression.items.forEach(::value)
            is Expression.MapLiteral -> expression.entries.forEach { value(it.second) }
            is Expression.MapProjection -> expression.elements.forEach { if (it is Expression.MapProjection.Element.Entry) value(it.value) }
            is Expression.Index -> listOf(expression.subject, expression.index).forEach(::value)
            is Expression.Slice -> listOfNotNull(expression.subject, expression.from, expression.to).forEach(::value)
            is Expression.Literal, is Expression.Parameter, is Expression.Variable, Expression.CountStar, is Expression.Subquery -> {}
        }
    }

    /**
     * A function of a value whose kind it does not take, as Neo4j refuses it: `size()` of a path, `size()` of a pattern,
     * `size((a)-->())` (Neo4j's 42I52), and `size()` of a map written, `{a: 1}` or `n {.a}`, of a variable a `WITH` binds to
     * one, a map projection, or of one an `UNWIND` gives of a list written of maps, and `size()` of a node or a relationship (Neo4j's 22G03,
     * drivine-cypher-spec#364; the SQL stores gave null). Read after its arguments, so a pattern there that
     * names a new variable is refused for that first, as Neo4j does.
     */
    private fun function(function: Expression.Function, scope: Scope) {
        if (!function.name.equals("size", ignoreCase = true)) return
        val argument = function.arguments.singleOrNull() ?: return
        if (argument is Expression.PatternPredicate) {
            throw CompilerException("size() takes a list or a text, not a pattern, as in Neo4j: COUNT { … } counts a pattern's matches.")
        }
        val kind = kindOf(argument, scope)
        if (kind == Kind.PATH) throw CompilerException("size() takes a list or a text, not a path, as in Neo4j: length() takes a path.")
        if (kind == Kind.MAP || kind == Kind.SIZED_MAP) throw CompilerException("size() takes a list or a text, not a map, as in Neo4j.")
        if (kind == Kind.NODE || kind == Kind.RELATIONSHIP) throw CompilerException("size() takes a list or a text, not ${kind.noun}, as in Neo4j.")
    }
}
