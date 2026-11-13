package org.drivine.cypher.sql

import org.drivine.cypher.CompilerException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Expression.Binary.Operator
import org.drivine.cypher.ast.Pattern

/**
 * The forms of Cypher a fragment takes ([SqlCypherCompiler.condition], [SqlCypherCompiler.value], [SqlCypherCompiler.order]):
 * those the three libraries' object managers write their filters, orders and seeks in, and no other, so each form is one the
 * spec's `fragments.yaml` holds to Neo4j's answer on each store, in each place a library puts it (liberation-data/drivine-cypher-spec#161).
 *
 * - a value: a literal, a parameter, a list of values, a variable, its property and a property of that (a temporal value's
 *   field), and a label test (`n:A:B`);
 * - a comparison: `=`, `<>`, `<`, `<=`, `>`, `>=`, `IN` (of a list given, or of a stored list), `STARTS WITH`, `ENDS WITH`,
 *   `CONTAINS`, `=~`, `IS NULL`, `IS NOT NULL`;
 * - `AND`, `OR`, `XOR`, `NOT`;
 * - arithmetic: `+`, `-`, `*`, `/`, and a negation;
 * - `CASE`;
 * - the functions in [FUNCTIONS];
 * - `any`, `all` and `none` over a list;
 * - `EXISTS { pattern [WHERE …] }`, of one pattern that starts at a node bound already (the library's row, or one an
 *   enclosing pattern or quantifier binds), written bare, through nodes and relationships, a variable length among them
 *   unnamed, with properties of these forms; and its `EXISTS { MATCH pattern [WHERE …] }`, as TypeScript and Python write it.
 *
 * Anything else is refused by name: what the libraries write around a fragment (a seek's chain of terms, a group, an
 * aggregate, a view's projection) is their own SQL, and a form no library writes is one no case holds to an answer.
 */
internal object FragmentForms {

    /** The functions a fragment calls, by the name Cypher gives them, in lower case. */
    val FUNCTIONS = setOf("tolower", "toupper", "tostring", "tofloat", "tointeger", "coalesce", "exp", "floor", "abs", "size", "labels")

    private val OPERATORS = Operator.entries.toSet() - setOf(Operator.MODULO, Operator.POWER)

    /**
     * Refuses [expression], the fragment [cypher] over the library's rows [variables], where any part of it is no form a
     * fragment takes.
     */
    fun check(expression: Expression, cypher: String, variables: Set<String>) = form(expression, cypher, variables)

    /** [e] in a fragment where [bound] are the variables bound: the library's rows, and those a quantifier or a pattern binds. */
    private fun form(e: Expression, cypher: String, bound: Set<String>) {
        when (e) {
        is Expression.Literal, is Expression.Parameter, is Expression.Variable -> Unit
        is Expression.Property -> form(e.subject, cypher, bound)
        is Expression.HasLabels -> form(e.subject, cypher, bound)
        is Expression.ListLiteral -> e.items.forEach { form(it, cypher, bound) }
        is Expression.Binary -> {
            if (e.operator !in OPERATORS) refuse(e.operator.name.lowercase().replace('_', ' '), cypher)
            form(e.left, cypher, bound)
            form(e.right, cypher, bound)
        }
        is Expression.Unary -> form(e.operand, cypher, bound)
        is Expression.Case -> {
            e.subject?.let { form(it, cypher, bound) }
            e.alternatives.forEach { (test, value) -> form(test, cypher, bound); form(value, cypher, bound) }
            e.otherwise?.let { form(it, cypher, bound) }
        }
        is Expression.Function -> {
            if (e.name.lowercase() !in FUNCTIONS) refuse("${e.name}()", cypher)
            if (e.distinct) refuse("DISTINCT in ${e.name}()", cypher)
            e.arguments.forEach { form(it, cypher, bound) }
        }
        is Expression.Quantifier -> {
            if (e.kind == Expression.Quantifier.Kind.SINGLE) refuse("single()", cypher)
            form(e.list, cypher, bound)
            e.where?.let { form(it, cypher, bound + e.variable) }
        }
        is Expression.Exists -> {
            val inner = patterns(e.patterns, cypher, bound)
            e.where?.let { form(it, cypher, inner) }
        }
        is Expression.MapLiteral -> refuse("a map", cypher)
        is Expression.MapProjection -> refuse("a map projection", cypher)
        is Expression.Index -> refuse("an item read by its place or key, x[i]", cypher)
        is Expression.Slice -> refuse("a slice, x[i..j]", cypher)
        Expression.CountStar -> refuse("count(*)", cypher)
        is Expression.ListComprehension -> refuse("a list comprehension", cypher)
        is Expression.Reduce -> refuse("reduce()", cypher)
        is Expression.PatternComprehension -> refuse("a pattern comprehension", cypher)
        is Expression.PatternPredicate -> refuse("a pattern as a predicate, which EXISTS { … } writes", cypher)
        // `EXISTS { MATCH pattern [WHERE …] }`, as TypeScript and Python write it, is `EXISTS { pattern [WHERE …] }`.
        is Expression.Subquery -> {
            val match = (e.query.clauses.singleOrNull() as? Clause.Match)?.takeIf { e.kind == Expression.Subquery.Kind.EXISTS && !it.optional && e.query.unions.isEmpty() }
                ?: refuse("a subquery, ${e.kind.name} { … }", cypher)
            val inner = patterns(match.patterns, cypher, bound)
            match.where?.let { form(it, cypher, inner) }
        }
        }
    }

    /**
     * An `EXISTS`'s patterns, one, as the libraries write, and no list of several, which no case holds to an answer: the
     * variables bound within it, [bound] and the pattern's own.
     */
    private fun patterns(patterns: List<Pattern>, cypher: String, bound: Set<String>): Set<String> {
        if (patterns.size > 1) refuse("several patterns in an EXISTS", cypher)
        return patterns.fold(bound) { inner, pattern -> pattern(pattern, cypher, inner) }
    }

    /**
     * A pattern of an `EXISTS`, as the libraries write one: from a node [bound] already, the library's row or one an
     * enclosing pattern or quantifier binds, written bare (its labels and properties are a condition's, not the pattern's),
     * through relationships, a variable length among them unnamed, each node's and relationship's properties of a fragment's
     * forms. Gives [bound] and the variables it binds.
     */
    private fun pattern(p: Pattern, cypher: String, bound: Set<String>): Set<String> {
        if (p.shortest != null) refuse("a shortest path", cypher)
        if (p.variable != null) refuse("a path variable", cypher)
        val start = p.nodes.first()
        if (start.variable == null || start.variable !in bound) refuse("a pattern that doesn't start at a node bound already", cypher)
        if (start.labels.isNotEmpty() || start.properties != null) refuse("labels or properties on a pattern's first node, which is bound already", cypher)
        p.nodes.drop(1).forEach { node -> node.properties?.let { properties(it, cypher, bound) } }
        p.relationships.forEach { relationship ->
            if (relationship.group != null) refuse("a quantified path pattern", cypher)
            if (relationship.range != null && relationship.variable != null) refuse("a variable-length relationship with a variable", cypher)
            relationship.properties?.let { properties(it, cypher, bound) }
        }
        return bound + p.nodes.mapNotNull { it.variable } + p.relationships.mapNotNull { it.variable }
    }

    /** A pattern's properties: a map of a fragment's forms, the one place a map is. */
    private fun properties(e: Expression, cypher: String, bound: Set<String>) {
        if (e !is Expression.MapLiteral) return form(e, cypher, bound)
        e.entries.forEach { (_, value) -> form(value, cypher, bound) }
    }

    private fun refuse(what: String, cypher: String): Nothing =
        throw CompilerException("The fragment API doesn't take $what: a fragment is a condition, a value or an order of the forms compiler/README.md lists. $cypher")
}
