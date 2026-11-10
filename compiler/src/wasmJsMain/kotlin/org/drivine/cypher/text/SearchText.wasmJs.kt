package org.drivine.cypher.text

internal actual fun platformIsWordChar(cp: Int): Boolean = CommonUnicode.isWordChar(cp)

internal actual fun platformIsOracleAlnum(cp: Int): Boolean = CommonUnicode.isOracleAlnum(cp)

internal actual fun platformLowercase(text: String): String = CommonUnicode.lowercase(text)
