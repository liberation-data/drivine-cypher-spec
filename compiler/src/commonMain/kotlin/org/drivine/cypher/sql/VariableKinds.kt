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
 * written, and a variable a `WITH` passes on as it is. Any other value (a property, a parameter, an unwound item, a
 * function's result) may be of any kind, and is never refused here as being of another: what it is used as is left to
 * the compiler, as before.
 */
internal object VariableKinds {

    /** A kind of variable: see [VariableKinds]. [noun] names a variable of it, as a message does; a value's kind is named as in [Value.Known]. */
    private enum class Kind(val noun: String) {
        NODE("a node"), RELATIONSHIP("a relationship"), RELATIONSHIPS("a list of relationships"), PATH("a path"),
        INTEGER("an integer"), FLOAT("a float"), TEXT("a text"), BOOLEAN("a truth value"), LIST("a list"), MAP("a map"),

        /** Any kind at all: a value whose kind the statement leaves open. */
        ANY("a value"),
    }

    /**
     * The variables in scope, by name, each of its kind. Of them, those a `WITH` passes on not, read in its `WHERE` ([dropped]):
     * a value there may read them, but a pattern names none of them (see [use]), in a subquery's too. One bound again within, as a
     * comprehension's variable, is no longer one of them.
     */
    private class Scope(val kinds: MutableMap<String, Kind> = LinkedHashMap(), val dropped: MutableSet<String> = LinkedHashSet()) {
        fun copy() = Scope(LinkedHashMap(kinds), LinkedHashSet(dropped))
        operator fun get(name: String) = kinds[name]
        operator fun set(name: String, kind: Kind) {
            kinds[name] = kind
            dropped -= name
        }
    }

    /**
     * Refuses [query] where a variable of it is used as two kinds, or made where it is bound already; where a pattern in a
     * `WITH`'s `WHERE` names a variable the `WITH` drops (see [Scope]); where a `WITH` or a `RETURN` names two items alike;
     * where a pattern is used as a value, not as a condition (see [valued]); and where a pattern or a subquery is in a
     * property a `CREATE` or a `MERGE` makes, or a value a `MERGE` sets, where Neo4j refuses it (see [made] and [merged]).
     */
    fun check(query: Query) {
        query(query, Scope())
    }

    /** [query]'s variables, from those of [outer] it reads: what each part of it returns. */
    private fun query(query: Query, outer: Scope): List<Map<String, Kind>> =
        // Those of [outer] stay in scope after each WITH within, as Neo4j has a subquery read them, but those a WITH outside drops.
        (listOf(query.clauses) + query.unions.map { it.clauses }).map { clauses(it, outer.copy(), kept = outer.kinds.filterKeys { name -> name !in outer.dropped }) }

    /**
     * [clauses] in turn, over [scope]: what their `RETURN` returns, of the kinds it returns. Within a `FOREACH`, [foreach],
     * a `CREATE` or a `MERGE` may name a variable bound already, as Neo4j takes it there. [kept] stay in scope after each
     * `WITH` that does not name them, as what a `CALL`'s brackets import does, and a subquery's variables from outside it.
     */
    private fun clauses(clauses: List<Clause>, scope: Scope, foreach: Boolean = false, kept: Map<String, Kind> = emptyMap()): Map<String, Kind> {
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
                    current[clause.variable] = Kind.ANY
                }
                is Clause.With -> current = projected(clause.projection, current, clause.where, kept.keys).also { next ->
                    kept.forEach { (name, kind) -> if (next[name] == null) next[name] = kind }
                }
                is Clause.Return -> returned = projected(clause.projection, current, null).kinds
                is Clause.Create -> {
                    val made = mutableSetOf<String>()
                    val before = current.copy()
                    clause.patterns.forEach { made(it, current, before, made, merging = false, foreach) }
                }
                is Clause.Merge -> {
                    merged(clause)
                    made(clause.pattern, current, current.copy(), mutableSetOf(), merging = true, foreach)
                    (clause.onCreate + clause.onMatch).forEach { set(it, current) }
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
                is Clause.Call -> called(clause, current).forEach { (name, kind) -> current[name] = kind }
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
        val query = call.query
        val parts = (listOf(query.clauses) + query.unions.map { it.clauses }).map { clauses ->
            val imported = imports(call, clauses, scope.kinds.keys)
            val inner = scope.kinds.filterTo(LinkedHashMap()) { (name, _) -> name in imported }
            // What a WITH outside drops, imported by a CALL within its WHERE, CALL (*) { … }, stays dropped in the call (drivine4j#504).
            val dropped = scope.dropped.filterTo(LinkedHashSet()) { it in imported }
            clauses(clauses, Scope(inner, dropped), kept = if (call.imports != null || call.importsAll) inner.filterKeys { it !in dropped } else emptyMap())
        }
        return parts.first().mapValues { (name, kind) -> if (parts.all { it[name] == kind }) kind else Kind.ANY }
    }

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

    /**
     * The scope after a `WITH` or a `RETURN` of [projection] over [scope]: each item of the kind its expression is, and with
     * `*` each variable in scope. Its `WHERE`, [where], and its order read the items, and a variable before it as of any kind;
     * but a pattern in the `WHERE` names only what the `WITH` passes on, and what a `CALL`'s brackets import or a subquery
     * reads from outside it, [kept], as Neo4j has it after a `WITH` that groups or is DISTINCT (drivine4j#504). Refuses two
     * items of one name (Neo4j's 42N38).
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
        val next = if (projection.star) scope.copy() else Scope(dropped = LinkedHashSet(scope.dropped))
        projection.items.forEach { next[it.name] = kindOf(it.expression, scope) }
        val reading = Scope(scope.kinds.mapValuesTo(LinkedHashMap()) { Kind.ANY }.apply { putAll(next.kinds) })
        where?.let {
            val dropped = scope.kinds.keys.filterTo(LinkedHashSet(next.dropped)) { name -> next[name] == null && name !in kept }
            expression(it, Scope(LinkedHashMap(reading.kinds), dropped))
        }
        projection.orderBy.forEach {
            expression(it.expression, reading)
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

    /** A quantified path pattern's [group]: what it names is a list outside it, of whatever it matched. */
    private fun grouped(group: RelationshipPattern.Group, scope: Scope) {
        val names = group.pattern.nodes.mapNotNull { it.variable } + group.pattern.relationships.mapNotNull { it.variable }
        names.forEach { if (scope[it] == null) scope[it] = Kind.ANY }
    }

    /** [variable] where a pattern binds it as [kind]: bound so, or used so where it is bound already as that kind. */
    private fun use(variable: String, kind: Kind, scope: Scope) {
        // One a WITH does not pass on, in its WHERE: read there as a new node, it would match every row (drivine4j#504).
        if (variable in scope.dropped) throw CompilerException("$variable is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
        val bound = scope[variable] ?: return scope.set(variable, kind)
        if (!fits(bound, kind)) throw conflict(variable, bound, kind)
    }

    /** Whether a variable bound as [bound] may be used where a pattern takes [kind]. */
    private fun fits(bound: Kind, kind: Kind) = bound == kind || bound == Kind.ANY || (kind == Kind.RELATIONSHIPS && bound == Kind.LIST)

    private fun conflict(variable: String, bound: Kind, used: Kind) =
        CompilerException("$variable is ${bound.noun}, and is used as ${used.noun}: a variable is of one kind, as in Neo4j.")

    /**
     * A pattern a `CREATE` or a `MERGE` makes, over [scope]: each node of it new, or one bound already that it joins
     * up, and each relationship new: a node a pattern of the same `CREATE` made before it is bound already. [made] holds
     * the relationships the clause made before. A relationship bound before the clause, as one, is left to the compiler,
     * which refuses it as not compiled yet, as the spec has it. Within a `FOREACH`, [foreach], Neo4j takes a variable bound
     * already in each of these places, and only one of another kind is refused. [before] is the scope before the clause:
     * a pattern in a property it makes names none of the clause's own variables (see [madeHere]), and, of a node made with
     * no relationship, none of those bound before it either (see [declared]).
     */
    private fun made(pattern: Pattern, scope: Scope, before: Scope, made: MutableSet<String>, merging: Boolean, foreach: Boolean) {
        val clause = if (merging) "MERGE" else "CREATE"
        pattern.nodes.forEach { node ->
            node.properties?.let {
                expression(it, scope)
                madeHere(it, scope, before)
                if (pattern.relationships.isEmpty() && !foreach) declared(it, before)
                valued(it, condition = false)
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
                expression(it, scope)
                madeHere(it, scope, before)
                valued(it, condition = false)
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

    /**
     * Refuses a pattern in [properties], a property a `CREATE` or a `MERGE` makes, that names a variable the same clause
     * makes, one of [scope] not of [before], as Neo4j refuses it (its 42N29): `CREATE (a), (b {f: NOT (a)-->()})`. A pattern
     * comprehension binds such a name anew, as in Neo4j, and is not refused for it.
     */
    private fun madeHere(properties: Expression, scope: Scope, before: Scope) =
        patternsIn(properties, emptySet()) { pattern, comprehension, inner ->
            if (comprehension) return@patternsIn
            named(pattern).firstOrNull { it !in inner && scope[it] != null && before[it] == null }?.let { throw undefined(it) }
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
    private fun merged(merge: Clause.Merge) {
        val properties = merge.pattern.nodes.mapNotNull { it.properties } + merge.pattern.relationships.mapNotNull { it.properties }
        val values = (merge.onCreate + merge.onMatch).mapNotNull { setValue(it) }
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

    /** A `SET` item's value, over [scope]: a pattern is no value to set, as Neo4j refuses it (see [valued]). */
    private fun set(item: SetItem, scope: Scope) {
        val value = setValue(item) ?: return
        expression(value, scope)
        valued(value, condition = false)
    }

    /**
     * The patterns within [expression], each over [scope] and what it names only within it, and the functions of a
     * variable whose kind they do not take.
     */
    private fun expression(expression: Expression, scope: Scope) {
        when (expression) {
            is Expression.Literal, is Expression.Parameter, is Expression.Variable, Expression.CountStar -> {}
            is Expression.Property -> expression(expression.subject, scope)
            is Expression.HasLabels -> expression(expression.subject, scope)
            is Expression.ListLiteral -> expression.items.forEach { expression(it, scope) }
            is Expression.MapLiteral -> expression.entries.forEach { expression(it.second, scope) }
            is Expression.MapProjection -> expression.elements.forEach { if (it is Expression.MapProjection.Element.Entry) expression(it.value, scope) }
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
                val inner = scope.copy().also { it[expression.variable] = Kind.ANY }
                listOfNotNull(expression.where, expression.projection).forEach { expression(it, inner) }
            }
            is Expression.Quantifier -> {
                expression(expression.list, scope)
                val inner = scope.copy().also { it[expression.variable] = Kind.ANY }
                expression.where?.let { expression(it, inner) }
            }
            is Expression.Reduce -> {
                expression(expression.initial, scope)
                expression(expression.list, scope)
                val inner = scope.copy().also {
                    it[expression.accumulator] = Kind.ANY
                    it[expression.variable] = Kind.ANY
                }
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
     * A function of a variable whose kind it does not take, as Neo4j refuses it: `size()` of a path, and `size()` of a
     * pattern, `size((a)-->())` (Neo4j's 42I52). Read after its arguments, so a pattern there that names a new variable is
     * refused for that first, as Neo4j does.
     */
    private fun function(function: Expression.Function, scope: Scope) {
        if (function.name.equals("size", ignoreCase = true) && function.arguments.singleOrNull() is Expression.PatternPredicate) {
            throw CompilerException("size() takes a list or a text, not a pattern, as in Neo4j: COUNT { … } counts a pattern's matches.")
        }
        val argument = (function.arguments.singleOrNull() as? Expression.Variable)?.let { scope[it.name] } ?: return
        if (function.name.equals("size", ignoreCase = true) && argument == Kind.PATH) {
            throw CompilerException("size() takes a list or a text, not a path, as in Neo4j: length() takes a path.")
        }
    }
}
