package org.drivine.cypher.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** drivine4j's `TextQueryTest`, on every target: how a full-text query is read. Its counts of a term in a text are the JVM's (`TextQueryCountTest`). */
class TextQueryTest {

    private fun word(text: String) = "(^|[^[:alnum:]_])$text($|[^[:alnum:]_])"

    @Test
    fun wordsScoreAndEachIsMatchedWholeAndInLowerCase() {
        val query = TextQuery.parse("Graph databases")

        assertEquals(listOf(word("graph"), word("databases")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun aPlusOrAnANDMakesAWordOneANodeMustHoldAMinusOrANOTOneItMustNot() {
        val query = TextQuery.parse("+kotlin graph AND store -python NOT rust")

        assertEquals(listOf(word("kotlin"), word("graph"), word("store")), query.scored)
        assertEquals(listOf(word("kotlin"), word("store")), query.required)
        assertEquals(listOf(word("python"), word("rust")), query.excluded)
    }

    @Test
    fun aQuotedPhraseIsItsWordsInTurnAStarTheStartOfAWordAndAFieldsWordTheWord() {
        val query = TextQuery.parse("\"graph data\" data* name:Ada")

        assertEquals(listOf("(^|[^[:alnum:]_])graph[^[:alnum:]_]+data($|[^[:alnum:]_])", "(^|[^[:alnum:]_])data", word("ada")), query.scored)
    }

    @Test
    fun eachScoringTermGivesTheWordsANodeThatMatchesItHoldsAndNoTermThatOnlyExcludesDoes() {
        val query = TextQuery.parse("\"Graph data\" +snake_case data* -python NOT rust")

        assertEquals(listOf(listOf("graph", "data"), listOf("snake_case"), listOf("data")), query.scoredWords)
    }

    @Test
    fun whatIsNotAWordIsNotLookedForSoItCannotBeReadAsAnExpression() {
        assertEquals(listOf(word("c")), TextQuery.parse("c++ (.*)").scored)
        assertFalse(TextQuery.parse("a.b").scored.single().contains("."), "a dot between words is a gap, not any character")
    }

    @Test
    fun theSameQueryForOracleTextAccumulatesWhatScoresRequiresAndTakesAwayEachWordInBraces() {
        assertEquals("({graph} ACCUM {databases})", TextQuery.parse("Graph databases").oracleText)
        assertEquals("({kotlin} ACCUM {graph} ACCUM {store}) AND {kotlin} AND {store} NOT {python} NOT {rust}", TextQuery.parse("+kotlin graph AND store -python NOT rust").oracleText)
        assertEquals("({graph data} ACCUM data% ACCUM {ada})", TextQuery.parse("\"graph data\" data* name:Ada").oracleText)
        // A word that is one of Oracle Text's own is still a word.
        assertEquals("({near} ACCUM {about})", TextQuery.parse("near about").oracleText)
    }

    @Test
    fun aQueryWithNoWordToLookForIsRefused() {
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("-only") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("  ") }
    }

    @Test
    fun theSymbolsForANDORAndNOTReadAsTheWordsDo() {
        val query = TextQuery.parse("graph && store || index ! python")

        assertEquals(listOf(word("graph"), word("store"), word("index")), query.scored)
        assertEquals(listOf(word("store")), query.required)
        assertEquals(listOf(word("python")), query.excluded)
    }

    @Test
    fun anOperatorWithNothingAfterItIsNothing() {
        val query = TextQuery.parse("graph AND")

        assertEquals(listOf(word("graph")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
        assertEquals("({graph})", query.oracleText)
    }

    @Test
    fun anEmptyPhraseAndAStarAloneHoldNoWordAndAreRefused() {
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("\"\"") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("*") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("title:\"\"") }
    }

    @Test
    fun aFieldScopedToAPhraseIsTheOnePhraseAndAFieldScopedToAWordTheWord() {
        val phrase = TextQuery.parse("title:\"graph data\"")
        assertEquals(listOf("(^|[^[:alnum:]_])graph[^[:alnum:]_]+data($|[^[:alnum:]_])"), phrase.scored)
        assertEquals("({graph data})", phrase.oracleText)

        val word = TextQuery.parse("name:Ada")
        assertEquals(listOf(word("ada")), word.scored)
        assertEquals("({ada})", word.oracleText)
    }

    @Test
    fun aStarInTheMiddleOfAWordIsAGapBetweenTwoWordsNotAWildcard() {
        val query = TextQuery.parse("foo*bar")

        assertEquals(listOf("(^|[^[:alnum:]_])foo[^[:alnum:]_]+bar($|[^[:alnum:]_])"), query.scored)
        assertEquals("({foo bar})", query.oracleText)
    }

    @Test
    fun forOracleTextARequiredWordIsBothAccumulatedAndConjoinedAnExcludedOneTakenAwayAPrefixAPercent() {
        assertEquals("({kotlin}) AND {kotlin}", TextQuery.parse("+kotlin").oracleText)
        assertEquals("({kotlin}) NOT {python}", TextQuery.parse("kotlin -python").oracleText)
        assertEquals("({kotlin}) NOT {python} NOT {rust}", TextQuery.parse("kotlin NOT python !rust".replace("!rust", "! rust")).oracleText)
        assertEquals("(pre%)", TextQuery.parse("pre*").oracleText)
        // A `*` inside quotes is text, as Lucene's parser reads it: the phrase "graph pre" (drivine4j#622).
        assertEquals("({graph pre})", TextQuery.parse("\"graph pre*\"").oracleText)
    }

    @Test
    fun aBackslashBeforeASignMakesTheSignTextSoTheWordIsNeitherRequiredNorExcluded() {
        val query = TextQuery.parse("""graph \+kotlin \-python""")

        assertEquals(listOf(word("graph"), word("kotlin"), word("python")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun aBackslashBeforeAClosingStarMakesTheStarTextSoTheWordIsMatchedWhole() {
        val query = TextQuery.parse("""pre\*""")

        assertEquals(listOf(word("pre")), query.scored)
        assertEquals("({pre})", query.oracleText)
    }

    @Test
    fun aStarAfterABackslashThatIsItselfTextIsStillTheStartOfAWord() {
        // Two backslashes are one backslash of text, so the star after them is not escaped.
        assertEquals("(pre%)", TextQuery.parse("""pre\\*""").oracleText)
    }

    @Test
    fun aBackslashBeforeAColonKeepsWhatStandsBeforeTheColonWhereABareColonMakesItAField() {
        assertEquals("({https example com docs})", TextQuery.parse("""https\:\/\/example.com\/docs""").oracleText)
        assertEquals("({example com docs})", TextQuery.parse("https://example.com/docs").oracleText)
    }

    @Test
    fun aBackslashBeforeAQuoteKeepsTheQuoteFromOpeningOrClosingAPhrase() {
        assertEquals("({say} ACCUM {hello} ACCUM {world})", TextQuery.parse("""say \"hello world\"""").oracleText)
        assertEquals("({say hello now})", TextQuery.parse(""""say \"hello\" now"""").oracleText)
    }

    @Test
    fun aBackslashBeforeASpaceJoinsTheTwoWordsIntoOneTerm() {
        assertEquals("({graph data})", TextQuery.parse("""graph\ data""").oracleText)
    }

    @Test
    fun anOperatorWrittenWithBackslashesIsTextAndHoldsNoWord() {
        val query = TextQuery.parse("""graph \&\& store \! python""")

        assertEquals(listOf(word("graph"), word("store"), word("python")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun aBackslashBeforeALetterIsTextSoItIsAGapBetweenTwoWordsAsItWasBefore() {
        assertEquals("({foo bar})", TextQuery.parse("""foo\bar""").oracleText)
        assertEquals("({domain user})", TextQuery.parse("""DOMAIN\user""").oracleText)
        // "C:" is a field, so the term is what follows it.
        assertEquals("({users bob})", TextQuery.parse("""C:\Users\bob""").oracleText)
    }

    @Test
    fun aBackslashThatEndsTheQueryIsText() {
        assertEquals("({graph})", TextQuery.parse("""graph\""").oracleText)
    }

    @Test
    fun aRequiredWordWhosePunctuationIsEscapedIsRequiredAsThePhraseOfItsParts() {
        val query = TextQuery.parse("""+PN\-4471\-B order""")

        assertEquals("({pn 4471 b} ACCUM {order}) AND {pn 4471 b}", query.oracleText)
        assertEquals(1, query.required.size)
    }

    @Test
    fun aPhraseThatOnlyABackslashedQuoteCanCloseIsClosedByIt() {
        assertEquals("({c} ACCUM {foo})", TextQuery.parse(""""C:\" foo""").oracleText)
        // The star is not the last character of the phrase, the backslash is.
        assertEquals("({pre} ACCUM {foo})", TextQuery.parse(""""pre*\" foo""").oracleText)
    }

    @Test
    fun aBackslashedQuoteDoesNotCloseAPhraseWhenAQuoteWithoutABackslashStandsLater() {
        // The phrase runs to the quote before "D". What is left is a field and a quote of text, which hold no word.
        assertEquals("({c and})", TextQuery.parse(""""C:\" and "D:\"""").oracleText)
    }

    @Test
    fun aBackslashBeforeALineBreakKeepsAPhraseOnePhrase() {
        assertEquals("({foo bar})", TextQuery.parse("\"foo\\\nbar\"").oracleText)
    }

    @Test
    fun aStarBeforeALineBreakThatEndsAPhraseIsNotTheEndOfThePhrase() {
        assertEquals("({graph pre})", TextQuery.parse("\"graph pre*\n\"").oracleText)
    }

    @Test
    fun anOperatorBeforeATokenThatHoldsNoWordDoesNotReachTheWordAfterIt() {
        val negated = TextQuery.parse("""graph NOT \! python""")
        assertEquals(listOf(word("graph"), word("python")), negated.scored)
        assertTrue(negated.excluded.isEmpty())

        val conjoined = TextQuery.parse("""graph AND \&\& store""")
        assertEquals(listOf(word("graph"), word("store")), conjoined.scored)
        assertTrue(conjoined.required.isEmpty())
    }

    @Test
    fun aWordOrAPhraseOfAnyLengthIsRead() {
        val word = "a".repeat(200_000)
        assertEquals("({$word})", TextQuery.parse(word).oracleText)
        assertEquals("({$word})", TextQuery.parse("\"$word\"").oracleText)
        assertEquals("({$word})", TextQuery.parse("title:$word").oracleText)
    }

    @Test
    fun aQueryOfManyPhrasesThatNeverCloseIsReadInOnePass() {
        val query = TextQuery.parse("\"graph ".repeat(50_000))

        assertEquals(50_000, query.scored.size)
    }

    @Test
    fun aStarInsideQuotesIsTextAsLucenesParserReadsItAndOutsideThemTheStartOfAWord() {
        // drivine4j#622: Neo4j's parser hands a quoted text to its analyzer whole, which drops the `*`.
        val quoted = TextQuery.parse("\"of sou*\"")
        assertEquals(listOf(listOf("of", "sou")), quoted.scoredWords)
        assertEquals("(^|[^[:alnum:]_])of[^[:alnum:]_]+sou(\$|[^[:alnum:]_])", quoted.scored.single())
        assertEquals("(^|[^[:alnum:]_])sou", TextQuery.parse("sou*").scored.single())
        assertEquals("(^|[^[:alnum:]_])kiw", TextQuery.parse("+kiw*").scored.single())
        val phrase = "(^|[^[:alnum:]_])of[^[:alnum:]_]+sou(\$|[^[:alnum:]_])"
        assertEquals(phrase, TextQuery.parse("body:\"of sou*\"").scored.single())
        assertEquals(phrase, TextQuery.parse("\"of sou\\*\"").scored.single())
        assertEquals("(^|[^[:alnum:]_])kiw(\$|[^[:alnum:]_])", TextQuery.parse("\"kiw*\"").scored.single())
        assertEquals("({kiw})", TextQuery.parse("\"kiw*\"").oracleText)
        assertEquals("({of sou})", TextQuery.parse("body:\"of sou*\"").oracleText)
    }

    @Test
    fun aWordIsLettersAsUnicodesAlphabeticHoldsThemDecimalDigitsAndUnderscoresAsTheTextsExpressionReadsIt() {
        // drivine4j#616: a vowel sign and a letter number are in a word, as [[:alnum:]] holds them; a superscript or a
        // fraction is between words, as it is in the text and to Neo4j's analyzer.
        assertEquals(listOf(listOf("किताब")), TextQuery.parse("किताब").scoredWords)
        assertEquals(listOf(listOf("bⅻb")), TextQuery.parse("bⅻb").scoredWords)
        assertEquals(listOf(listOf("m", "x")), TextQuery.parse("m²x").scoredWords)
        assertEquals(listOf(listOf("1", "2")), TextQuery.parse("1½2").scoredWords)
        assertEquals(listOf(listOf("٣٤")), TextQuery.parse("٣٤").scoredWords)
        // A backslash before "²" makes it text, which is still between words: before, it kept "²x" a word.
        assertEquals(listOf(listOf("m", "x")), TextQuery.parse("""m\²x""").scoredWords)
    }

    /** How often [pattern] matches in [text], each match after the last one ends, as Postgres's and SQLite's counts are. */

    /** How often the term [query] occurs in [text], counted as Postgres and SQLite count it. */

    /** How often the term [query] occurs in [text], counted as Oracle counts it (OracleSelect.matching). */

    @Test
    fun aTermsTrailingBoundaryIsLookedAheadToAndAPrefixTermWhichHasNoneIsAsItWas() {
        assertEquals("(^|[^[:alnum:]_])kiwi(?=\$|[^[:alnum:]_])", TextQuery.lookingAhead(word("kiwi")))
        assertEquals("(^|[^[:alnum:]_])kiw", TextQuery.lookingAhead(TextQuery.parse("kiw*").scored.single()))
    }

    @Test
    fun oracleCountsATermOfWordsEachOnceByTwoCountsAndAPhraseThatRepeatsAWordInTheTextWithItsGapsDoubled() {
        assertEquals("(^|[^[:alnum:]_])kiwi" to "(^|[^[:alnum:]_])kiwi[[:alnum:]_]", TextQuery.counted(word("kiwi")))
        assertEquals("(^|[^[:alnum:]_])kiw" to null, TextQuery.counted(TextQuery.parse("kiw*").scored.single()))
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"la la\"").scored.single()))
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"la di la\"").scored.single()))
        assertEquals("(^|[^[:alnum:]_])of[^[:alnum:]_]+soup", TextQuery.counted(TextQuery.parse("\"of soup\"").scored.single())?.first)
    }
}
