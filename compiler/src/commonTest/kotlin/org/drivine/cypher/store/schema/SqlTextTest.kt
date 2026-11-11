package org.drivine.cypher.store.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SqlText] reads the columns of an index's text past what is quoted: a property or a label may hold a comma, a
 * parenthesis or a quote, and a column read wrong there would make an index Drivine made look like another item.
 */
class SqlTextTest {

    @Test
    fun aColumnListIsSplitOnlyAtTheCommasOutsideQuotesAndParentheses() {
        val columns = """(CASE json_type(props, '$."a,b"') WHEN 'text' THEN 1 END), (props ->> 'it''s, (x)'), "c,d""""
        assertEquals(
            listOf("""(CASE json_type(props, '$."a,b"') WHEN 'text' THEN 1 END)""", "(props ->> 'it''s, (x)')", "\"c,d\""),
            SqlText.split(columns),
        )
    }

    @Test
    fun aParenthesisClosesPastWhatIsQuoted() {
        val text = "ON t ((props ->> ')''(') , x) WHERE y"
        val open = text.indexOf('(')
        assertEquals(text.indexOf(" WHERE") - 1, SqlText.closing(text, open))
        assertNull(SqlText.closing("(props ->> 'x)", 0), "a quote that never closes")
    }

    @Test
    fun onlyParenthesesThatWrapTheWholeAreTakenOff() {
        assertEquals("props ->> 'name'::text", SqlText.unwrapped(" ((props ->> 'name'::text)) "))
        assertEquals("(a) || (b)", SqlText.unwrapped("(a) || (b)"))
        assertEquals("lower(')')", SqlText.unwrapped("(lower(')'))"))
    }

    @Test
    fun aLiteralsBodyAndItsJsonAreRead() {
        assertEquals("it's", SqlText.unquoted("it''s"))
        assertEquals("a\"b", SqlText.jsonString("\"a\\\"b\""))
        assertEquals("Person", SqlText.jsonLabel("[\"Person\"]"))
        assertNull(SqlText.jsonString("name"), "not a JSON string")
        assertNull(SqlText.jsonLabel("[\"A\", \"B\"]"), "not one label")
    }
}
