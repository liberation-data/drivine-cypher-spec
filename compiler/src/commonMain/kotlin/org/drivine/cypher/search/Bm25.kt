package org.drivine.cypher.search

import org.drivine.cypher.json.jvmDoubleText

/**
 * BM25, the score Lucene gives a text for a query, and so Neo4j's full-text index: each term's count in the text, weighed by
 * how rare the term is among the label's nodes and against the text's length, then summed (drivine4j#604). The SQL stores
 * without a text index of their own score so, so that they rank the texts as Neo4j does: counted alone, a common word
 * outweighed the word a query is about, and a long text won by holding more of everything.
 *
 * A prefix term is scored as Lucene scores a prefix query: the same [PREFIX] for every node that holds it (drivine4j#612).
 *
 * Each term's count is [TextQuery]'s, a whole word or phrase matched in the lower-cased text. Its rarity is over every node
 * of the label, and so is the average length, however a store narrows the nodes it counts.
 */
internal object Bm25 {

    /** How soon a term's count stops adding to its score: Lucene's. */
    const val K1 = 1.2

    /** How much a text's length, against the average, weighs its counts down: Lucene's. */
    const val B = 0.75

    /**
     * What a prefix term adds to the score of each node that holds it. Lucene scores a prefix query as a constant, its boost
     * of 1, beside terms it scores by BM25 without the `k1 + 1` that [score]'s carries: on [score]'s scale that is `k1 + 1`.
     * Measured on Neo4j, `kiw*` gave each node it found 1, and in `kiw* melon` 0.54 to those without "melon" beside 1 for
     * the one with it, as this gives (drivine4j#612).
     */
    const val PREFIX = K1 + 1

    /** How a store writes what [ranked] asks of it. */
    class Dialect(
        /**
         * How often a term occurs in a text, the term bound as a parameter: each time, where it is written again at once too, so
         * not taking the character after one (drivine4j#607).
         */
        val count: (text: String, term: String) -> String,
        /** Whether a term occurs in a text at all, the term bound as a parameter: cheaper than [count] where the store can tell. */
        val holds: (text: String, term: String) -> String,
        /** The natural logarithm of a number. */
        val ln: (String) -> String,
        /** A number as floating point. */
        val real: (String) -> String,
    )

    /**
     * The nodes whose text meets [query], each with its BM25 score against the best of them, so the best scores one: a
     * query of `nid`, the node's row ([ROW]) and `score`, only the nodes that score above 0. [candidates] is a query of `nid`,
     * the row, `text` and `len` for
     * the nodes of the label that may hold a scoring term, which a store narrows by an index where it has one; [label] is a
     * query of `len` for every node of the label. `text` is lower-cased, as a term is matched in it, and `len` is the length
     * in characters of the text before it was, 0 where there is none: lowering every node's text only to measure it took
     * most of the time a rare word's search did.
     *
     * Only the candidates that hold a scoring term are counted. Every node that holds a term is one of them, so the count
     * of those that hold it, each term's rarity, is a window aggregate over them, and exact. The label's count of nodes and
     * their average length are plain reads of [label], with no term matched. Then the nodes that lack a required term,
     * or hold an excluded one, are left out: they still count among those that hold the terms they do.
     *
     * drivine4j's `Bm25.ranked` up to its join back to the node table, its ranked candidates byte for byte but for the row
     * they now carry, so the library reads its projection from them and joins its node table no second time (#223); their
     * scores against the best as drivine4j's are.
     */
    fun ranked(candidates: String, label: String, query: TextQuery, sql: Dialect): String {
        val tf = query.scored.indices.map { "tf${it + 1}" }
        val df = query.scored.indices.map { "df${it + 1}" }
        val required = query.required.indices.map { "r${it + 1}" }
        val excluded = query.excluded.indices.map { "x${it + 1}" }
        val counts = query.scored.zip(tf) + query.required.zip(required) + query.excluded.zip(excluded)
        val select = (listOf(row("s"), "s.len") + counts.map { (term, name) -> "${sql.count("s.text", term)} AS $name" }).joinToString(", ")
        val counted = Query(select, Derived(candidates, "s"), listOf(query.scored.joinToString(" OR ", "(", ")") { sql.holds("s.text", it) }))
        // Each a subquery of its own, which no row is passed to, so a store works it out once. Joined beside the candidates,
        // Postgres read the label again for each candidate where it guessed there were few: 55 s for a rare word at 100,000.
        fun figure(of: String) = Query(sql.real(of), Derived(label, "l")).nested()
        val weighed = Query(
            (listOf("h.*", "${figure("COUNT(*)")} AS n", "${figure("AVG(l.len)")} AS avglen") +
                tf.zip(df).map { (t, d) -> "SUM(CASE WHEN h.$t > 0 THEN 1 ELSE 0 END) OVER () AS $d" }).joinToString(", "),
            Derived(counted.text(), "h"),
        )
        // A prefix term weighs the same for each node that holds it, as Lucene's prefix query scores it: a constant, not by how
        // often or how rare (drivine4j#612). Every other term by BM25.
        val score = query.scored.indices.joinToString("\n    + ") { i ->
            if (TextQuery.isPrefix(query.scored[i])) {
                "CASE WHEN w.${tf[i]} > 0 THEN ${jvmDoubleText(PREFIX)} ELSE 0 END"
            } else {
                score(listOf("w.${tf[i]}" to "w.${df[i]}"), "w.n", "w.len", "NULLIF(w.avglen, 0)", sql.ln)
            }
        }
        val scored = Query("${row("w")}, ${sql.real(score)} AS score", Derived(weighed.text(), "w"), required.map { "w.$it > 0" } + excluded.map { "w.$it = 0" })
        return Query("${row("b")}, b.score / MAX(b.score) OVER () AS score", Derived(scored.text(), "b"), listOf("b.score > 0")).text()
    }

    /**
     * What a search's candidates carry of each node beside its `nid`, as the [candidates] given to [ranked] select them: its row,
     * which the library reads its projection from, with no second join of its node table. On Oracle 23.26 a join of the node
     * table to a view of it on `nid` is eliminated with a filter on it, where an index over a `JSON_VALUE` of its properties
     * is made, so that a bound value crashed the server process and a literal one was dropped
     * (liberation-data/drivine-cypher-spec#223).
     */
    val ROW: List<String> = listOf("labels", "props", "stamp")

    /** `nid` and the node's [ROW] as the rows named [alias] carry them: what each query of a search's candidates selects of a node. */
    fun row(alias: String): String = (listOf("nid") + ROW).joinToString(", ") { "$alias.$it" }

    /**
     * The score of a text whose [length] is against the [average] over the label's [nodes]: for each term, its count in the
     * text and the count of the label's nodes that hold it, as SQL. [ln] is the store's natural logarithm. Lucene's weight
     * of a term's rarity, `ln(1 + (N - df + 0.5) / (df + 0.5))`, is never below 0, so a term every node holds adds nothing
     * but never takes away.
     */
    fun score(terms: List<Pair<String, String>>, nodes: String, length: String, average: String, ln: (String) -> String): String =
        terms.joinToString("\n    + ") { (count, holding) ->
            "${ln("1 + ($nodes - $holding + 0.5) / ($holding + 0.5)")} * $count * ${jvmDoubleText(K1 + 1)} / ($count + ${jvmDoubleText(K1)} * (${jvmDoubleText(1 - B)} + ${jvmDoubleText(B)} * $length / $average))"
        }
}
