package org.drivine.cypher.sql

import org.drivine.cypher.CompilerException
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
 * - `EXISTS { pattern [WHERE …] }`, of a pattern of nodes and relationships, a variable length among them, with properties
 *   of these forms.
 *
 * Anything else is refused by name: what the libraries write around a fragment (a seek's chain of terms, a group, an
 * aggregate, a view's projection) is their own SQL, and a form no library writes is one no case holds to an answer.
 */
internal object FragmentForms {

    /** The functions a fragment calls, by the name Cypher gives them, in lower case. */
    val FUNCTIONS = setOf("tolower", "toupper", "tostring", "tofloat", "tointeger", "coalesce", "exp", "floor", "abs", "size", "labels", "type")

    private val OPERATORS = Operator.entries.toSet() - setOf(Operator.MODULO, Operator.POWER)

    /** Refuses [expression], the fragment [cypher], where any part of it is no form a fragment takes. */
    fun check(expression: Expression, cypher: String) = form(expression, cypher)

    private fun form(e: Expression, cypher: String) {
        when (e) {
        is Expression.Literal, is Expression.Parameter, is Expression.Variable -> Unit
        is Expression.Property -> form(e.subject, cypher)
        is Expression.HasLabels -> form(e.subject, cypher)
        is Expression.ListLiteral -> e.items.forEach { form(it, cypher) }
        is Expression.Binary -> {
            if (e.operator !in OPERATORS) refuse(e.operator.name.lowercase().replace('_', ' '), cypher)
            form(e.left, cypher)
            form(e.right, cypher)
        }
        is Expression.Unary -> form(e.operand, cypher)
        is Expression.Case -> {
            e.subject?.let { form(it, cypher) }
            e.alternatives.forEach { (test, value) -> form(test, cypher); form(value, cypher) }
            e.otherwise?.let { form(it, cypher) }
        }
        is Expression.Function -> {
            if (e.name.lowercase() !in FUNCTIONS) refuse("${e.name}()", cypher)
            if (e.distinct) refuse("DISTINCT in ${e.name}()", cypher)
            e.arguments.forEach { form(it, cypher) }
        }
        is Expression.Quantifier -> {
            if (e.kind == Expression.Quantifier.Kind.SINGLE) refuse("single()", cypher)
            form(e.list, cypher)
            e.where?.let { form(it, cypher) }
        }
        is Expression.Exists -> {
            e.patterns.forEach { pattern(it, cypher) }
            e.where?.let { form(it, cypher) }
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
        is Expression.Subquery -> refuse("a subquery, ${e.kind.name} { … }", cypher)
        }
    }

    /** A pattern of an `EXISTS`: nodes and relationships, a variable length among them, each's properties of a fragment's forms. */
    private fun pattern(p: Pattern, cypher: String) {
        if (p.shortest != null) refuse("a shortest path", cypher)
        if (p.variable != null) refuse("a path variable", cypher)
        p.nodes.forEach { node -> node.properties?.let { properties(it, cypher) } }
        p.relationships.forEach { relationship ->
            if (relationship.group != null) refuse("a quantified path pattern", cypher)
            relationship.properties?.let { properties(it, cypher) }
        }
    }

    /** A pattern's properties: a map of a fragment's forms, the one place a map is. */
    private fun properties(e: Expression, cypher: String) {
        if (e !is Expression.MapLiteral) return form(e, cypher)
        e.entries.forEach { (_, value) -> form(value, cypher) }
    }

    private fun refuse(what: String, cypher: String): Nothing =
        throw CompilerException("The fragment API doesn't take $what: a fragment is a condition, a value or an order of the forms compiler/README.md lists. $cypher")
}
