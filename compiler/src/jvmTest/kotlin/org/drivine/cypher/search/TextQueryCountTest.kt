package org.drivine.cypher.search

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine4j's `TextQueryTest`, its counts of a term in a text: each term's expression counted as Postgres and SQLite count
 * it, and as Oracle does (drivine4j's `OracleSelect.matching`), by Java's regular expressions with POSIX's classes as
 * drivine4j's `SqlitePatterns` reads them.
 */
class TextQueryCountTest {

    private fun word(text: String) = "(^|[^[:alnum:]_])$text($|[^[:alnum:]_])"

    /** drivine4j's `SqlitePatterns`: Java's expression, with a class written as POSIX names it read as Java's. */
    private fun pattern(expression: String): Pattern =
        Pattern.compile(POSIX.entries.fold(expression) { text, (posix, java) -> text.replace(posix, java) }, Pattern.UNICODE_CHARACTER_CLASS)

    private val POSIX = mapOf("[:alnum:]" to "\\p{Alnum}", "[:alpha:]" to "\\p{Alpha}", "[:digit:]" to "\\p{Digit}")

    private fun count(pattern: String, text: String): Int {
        val matcher = pattern(pattern).matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    private fun counted(query: String, text: String) = count(TextQuery.lookingAhead(TextQuery.parse(query).scored.single()), text)

    private fun countedByOracle(query: String, text: String): Int {
        val term = TextQuery.parse(query).scored.single()
        return TextQuery.counted(term)?.let { (ended, continued) -> count(ended, text) - (continued?.let { count(it, text) } ?: 0) }
            ?: count(term, pattern(TextQuery.SEPARATOR).matcher(text).replaceAll("\$1\$1"))
    }

    @Test
    fun aTermWrittenAgainAtOnceIsCountedEachTimeWhereverItStandsAndWhateverIsAroundIt() {
        val texts = mapOf(
            "kiwi kiwi kiwi" to 3,
            "kiwi,kiwi.kiwi!" to 3,
            "(kiwi)" to 1,
            "kiwi" to 1,
            "kiwis kiwi_kiwi kiwi2 akiwi" to 0,
            "kiwi\nkiwi\n" to 2,
        )
        texts.forEach { (text, n) ->
            assertEquals(n, counted("kiwi", text), text)
            assertEquals(n, countedByOracle("kiwi", text), text)
        }
    }

    @Test
    fun aPhraseWrittenAgainAtOnceIsCountedEachTimeAndItsGapMayBeAnyPunctuation() {
        val texts = mapOf("of soup of soup" to 2, "of  soup, of -- soup." to 2, "of soups of soup" to 1, "of soup_ of soup" to 1, "of soup_of soup" to 0)
        texts.forEach { (text, n) ->
            assertEquals(n, counted("\"of soup\"", text), text)
            assertEquals(n, countedByOracle("\"of soup\"", text), text)
        }
    }

    @Test
    fun aPrefixTermCountsEachWordItStartsItsLastWordTheStartOfOne() {
        assertEquals(3, counted("kiw*", "kiwi kiwis kiw akiwi"))
        assertEquals(3, countedByOracle("kiw*", "kiwi kiwis kiw akiwi"))
        // A `*` inside quotes is text (drivine4j#622): "of sou*" is the phrase "of sou", which "of soup of soups" doesn't hold.
        assertEquals(0, counted("\"of sou*\"", "of soup of soups"))
        assertEquals(0, countedByOracle("\"of sou*\"", "of soup of soups"))
    }

    @Test
    fun aWordInAnyScriptIsAWordSoALetterAfterATermInAnotherScriptGoesOnWithIt() {
        assertEquals(2, counted("café", "café café cafés caféé"))
        assertEquals(2, countedByOracle("café", "café café cafés caféé"))
        assertEquals(2, counted("кот", "кот кот котик"))
        assertEquals(2, countedByOracle("кот", "кот кот котик"))
    }

    @Test
    fun aPhraseWhoseWordsBeginItAgainCountsTheSameOnOracleWhereTheWordAfterItGoesOn() {
        // "la la" ends at the space before "land", and once; Oracle's count of "la la" ending anywhere took the
        // first, and of it going on into a word took the second, "la la" of "la land", so it counted none.
        assertEquals(1, counted("\"la la\"", "la la land"))
        assertEquals(1, countedByOracle("\"la la\"", "la la land"))
    }

    @Test
    fun aPhraseWhoseLastWordStartsItsFirstCountsTheSameOnOracleWhereTheWordAfterItGoesOn() {
        // Its words are each once, yet "kiwis kiwi" ending anywhere took the first two words, and going on into a word
        // took them too, so the two counts counted none.
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"kiwis kiwi\"").scored.single()))
        assertEquals(1, counted("\"kiwis kiwi\"", "kiwis kiwis kiwi"))
        assertEquals(1, countedByOracle("\"kiwis kiwi\"", "kiwis kiwis kiwi"))
        assertEquals(1, countedByOracle("\"kiwis bird kiwi\"", "kiwis bird kiwis bird kiwi"))
    }

    @Test
    fun aPhraseOfDistinctWordsWhoseLastDoesntStartItsFirstCountsTheSameOnOracleByItsTwoCounts() {
        // No occurrence of such a term begins inside another: one would begin at a later word of it, which would be its first.
        val cases = listOf(
            // Its first word starts its last.
            "\"kiwi kiwis\"" to "kiwi kiwi kiwis kiwis kiwi kiwisx kiwi kiwis",
            // Its first word starts a middle one.
            "\"ki kiwi bird\"" to "ki ki kiwi bird ki kiwi birds ki kiwi bird ki kiwi bird",
            // Words of digits and underscores, which go on as letters do.
            "\"a1 a12\"" to "a1 a1 a12 a1 a12 a1 a123 a1 a12_",
            "\"_a a_b\"" to "_a _a a_b _a a_b _a a_bc",
            "a_1" to "a_1 a_1 a_1a a_1_ a_1",
        )
        cases.forEach { (query, text) ->
            assertTrue(TextQuery.counted(TextQuery.parse(query).scored.single()) != null, query)
            assertEquals(counted(query, text), countedByOracle(query, text), "$query in $text")
        }
        assertEquals(2, counted("\"kiwi kiwis\"", "kiwi kiwi kiwis kiwis kiwi kiwisx kiwi kiwis"))
        assertEquals(3, countedByOracle("\"ki kiwi bird\"", "ki ki kiwi bird ki kiwi birds ki kiwi bird ki kiwi bird"))
    }

    @Test
    fun aWordHoldingACharacterNotMeasuredAsAlnumOnOracleIsCountedThereByItsOneExpressionSoNoOccurrenceIsLost() {
        // A circled letter, "ⓐ", is a word's to the query, which reads it as alphabetic, and may not be to Oracle's [[:alnum:]]:
        // an occurrence could begin inside another there, so the one count counts it.
        val circled = TextQuery.parse("bⓐb").scored.single()
        assertEquals(circled to null, TextQuery.counted(circled))
        // "²" is between words (drivine4j#616): "b²b" is the phrase "b b", which repeats a word, and still counts the same.
        assertEquals(null, TextQuery.counted(TextQuery.parse("b²b").scored.single()))
        assertEquals(1, counted("b²b", "b²b²bx"))
        assertEquals(1, countedByOracle("b²b", "b²b²bx"))
    }

    @Test
    fun aWordWithAVowelSignWrittenAgainAtOnceIsCountedEachTimeOnOracleByItsTwoCounts() {
        // A vowel sign is in a word (drivine4j#616) and a combining mark is [[:alnum:]] on Oracle, so no occurrence begins
        // inside another: counted by the one expression, "किताब किताब" was once.
        assertTrue(TextQuery.counted(TextQuery.parse("किताब").scored.single())?.second != null)
        assertEquals(2, counted("किताब", "किताब किताब"))
        assertEquals(2, countedByOracle("किताब", "किताब किताब"))
        assertEquals(2, countedByOracle("\"एक किताब\"", "एक किताब एक किताब"))
        // Its next character a vowel sign, the word goes on, as it does in the text on each store: no occurrence.
        assertEquals(0, countedByOracle("किताब", "किताबें"))
    }

    @Test
    fun aWordWithALetterNumberWrittenAgainAtOnceIsCountedEachTimeOnOracleByItsTwoCounts() {
        // "ⅻ" is [[:alnum:]] on Oracle, as measured (drivine4j#616): counted by the one expression, "bⅻb bⅻb" was once.
        assertTrue(TextQuery.counted(TextQuery.parse("bⅻb").scored.single())?.second != null)
        assertEquals(2, counted("bⅻb", "bⅻb bⅻb"))
        assertEquals(2, countedByOracle("bⅻb", "bⅻb bⅻb"))
    }
}
