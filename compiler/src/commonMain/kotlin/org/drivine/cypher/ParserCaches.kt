package org.drivine.cypher

import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.atn.ATN
import org.antlr.v4.kotlinruntime.atn.LexerATNSimulator
import org.antlr.v4.kotlinruntime.atn.ParserATNSimulator
import org.antlr.v4.kotlinruntime.atn.PredictionContextCache
import org.antlr.v4.kotlinruntime.dfa.DFA
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The lexer's and the parser's prediction caches, bounded (#179). ANTLR keeps what it learns of the grammar while it parses,
 * its DFA states and the prediction contexts they share, in a cache for the life of the process, which the generated
 * classes hold statically and nothing trims: it grew to 42 MB over the spec's corpus, and ran an Android app out of a 48 MB
 * heap after some 4,100 of its requests. Here each parse is given the caches of [current], and once their DFA
 * states pass [cap] a fresh set takes their place: a parse under way keeps the set it began with, so one thread's swap is
 * no other's concern, and the set left behind is freed once its last parse ends. A fresh set costs the first statements
 * after it the predictions they learn again, as the first statements of a process pay them.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object ParserCaches {

    /** The most DFA states the caches hold before they are made anew: [org.drivine.cypher.api.CypherCompiler.parserCacheStates]. */
    // Volatile: set by one thread, read after every parse by any other.
    @Volatile
    internal var cap: Int = DEFAULT_CAP

    /** A set of caches, the lexer's and the parser's, for the ATNs of [CypherLexer] and [CypherParser]. */
    internal class Set(lexerAtn: ATN, parserAtn: ATN) {
        val lexer: Array<DFA> = Array(lexerAtn.numberOfDecisions) { DFA(lexerAtn.getDecisionState(it)!!, it) }
        val parser: Array<DFA> = Array(parserAtn.numberOfDecisions) { DFA(parserAtn.getDecisionState(it)!!, it) }
        val lexerContexts = PredictionContextCache()
        val parserContexts = PredictionContextCache()

        /** The DFA states the set holds, the lexer's and the parser's. */
        fun states(): Int = lexer.sumOf { it.states.size } + parser.sumOf { it.states.size }
    }

    private val current = AtomicReference<Set?>(null)
    private val renewed = AtomicInt(0)

    /** How many times the caches have been made anew since the process began. */
    internal val renewals: Int get() = renewed.load()

    /** The set a parse uses: the one there is, or a first. */
    fun of(lexerAtn: ATN, parserAtn: ATN): Set =
        current.load() ?: Set(lexerAtn, parserAtn).also { if (!current.compareAndSet(null, it)) return current.load()!! }

    /** [lexer] reading through [set]'s caches. */
    fun reading(lexer: Lexer, set: Set) {
        lexer.interpreter = LexerATNSimulator(lexer, lexer.atn, set.lexer, set.lexerContexts)
    }

    /** [parser] predicting through [set]'s caches. */
    fun predicting(parser: Parser, set: Set) {
        parser.interpreter = ParserATNSimulator(parser, parser.atn, set.parser, set.parserContexts)
    }

    /**
     * After a parse through [set]: the set made anew where it has passed [cap]. Its states are counted after every parse, a
     * sum over the grammar's few hundred decisions, about a microsecond beside a compile's 140 µs.
     */
    fun parsed(set: Set, lexerAtn: ATN, parserAtn: ATN) {
        if (set.states() > cap && current.compareAndSet(set, Set(lexerAtn, parserAtn))) renewed.addAndFetch(1)
    }

    /** The set's states now, for a test. */
    internal fun states(): Int = current.load()?.states() ?: 0

    /** No set: the next parse begins one, for a test. */
    internal fun reset() {
        current.store(null)
    }
}

/**
 * The DFA states the caches hold at most, as measured on the JVM over the spec's corpus, 9,136 distinct statements (#179):
 * unbounded, the caches fill to 13,947 states and keep 42 MB; at 5,000 they keep 5 MB once the corpus is done, at about
 * 3 KB a state with its configurations. 5,000 states hold about the first 300 distinct statements of that varied mix; an
 * application's statements are fewer and more alike, and compile at no cost. Past the cap, as the corpus made the
 * caches anew again and again, its median compile went from 135 to 167 µs, and its p99 from about 3 to 4 ms.
 */
internal const val DEFAULT_CAP: Int = 5_000
