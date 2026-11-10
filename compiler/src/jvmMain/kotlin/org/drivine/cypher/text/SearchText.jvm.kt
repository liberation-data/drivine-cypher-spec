package org.drivine.cypher.text

internal actual fun platformIsWordChar(cp: Int): Boolean =
    Character.isAlphabetic(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() || cp == '_'.code

internal actual fun platformIsOracleAlnum(cp: Int): Boolean =
    Character.isLetter(cp) || Character.isDigit(cp) || Character.getType(cp).toByte() in ORACLE_ALNUM_TYPES

private val ORACLE_ALNUM_TYPES = setOf(Character.LETTER_NUMBER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

internal actual fun platformLowercase(text: String): String = text.lowercase()
