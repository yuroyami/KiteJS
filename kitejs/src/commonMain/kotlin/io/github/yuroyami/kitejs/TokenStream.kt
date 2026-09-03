/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * This class implements the JavaScript scanner.
 *
 * It is based on the C source files jsscan.c and jsscan.h in the jsref package.
 *
 * Ported with a String-only source (the Reader path is not ported, ledger D-2) and
 * without the E4X/XML tokenizer methods (E4X is out of scope).
 */
internal class TokenStream(
    private val parser: Parser,
    sourceString: String,
    lineno: Int,
) : Parser.CurrentPositionReporter {

    companion object {
        /*
         * For chars - because we need something out-of-range to check. (And checking EOF
         * by exception is annoying.) Note distinction from EOF token type!
         */
        private const val EOF_CHAR = -1

        /*
         * Return value for readDigits() to signal the caller has to return a number
         * format problem.
         */
        private const val REPORT_NUMBER_FORMAT_ERROR = -2

        private const val BYTE_ORDER_MARK = '\uFEFF'
        private const val NUMERIC_SEPARATOR = '_'

        internal fun isKeyword(s: String, version: Int, isStrict: Boolean): Boolean =
            Token.EOF != stringToKeyword(s, version, isStrict)

        private fun stringToKeyword(name: String, version: Int, isStrict: Boolean): Int {
            if (version < Context.VERSION_ES6) {
                return stringToKeywordForJS(name)
            }
            return stringToKeywordForES(name, isStrict)
        }

        /** JavaScript 1.8 and earlier. */
        private fun stringToKeywordForJS(name: String): Int {
            // The following assumes that Token.EOF == 0
            val id = when (name) {
                "break" -> Token.BREAK
                "case" -> Token.CASE
                "continue" -> Token.CONTINUE
                "default" -> Token.DEFAULT
                "delete" -> Token.DELPROP
                "do" -> Token.DO
                "else" -> Token.ELSE
                "export" -> Token.RESERVED
                "false" -> Token.FALSE
                "for" -> Token.FOR
                "function" -> Token.FUNCTION
                "if" -> Token.IF
                "in" -> Token.IN
                "let" -> Token.LET // reserved ES5 strict
                "new" -> Token.NEW
                "null" -> Token.NULL
                "return" -> Token.RETURN
                "switch" -> Token.SWITCH
                "this" -> Token.THIS
                "true" -> Token.TRUE
                "typeof" -> Token.TYPEOF
                "undefined" -> Token.UNDEFINED
                "var" -> Token.VAR
                "void" -> Token.VOID
                "while" -> Token.WHILE
                "with" -> Token.WITH
                "yield" -> Token.YIELD // reserved ES5 strict
                // the following are #ifdef RESERVE_JAVA_KEYWORDS in jsscan.c
                "abstract" -> Token.RESERVED // ES3 only
                "boolean" -> Token.RESERVED // ES3 only
                "byte" -> Token.RESERVED // ES3 only
                "catch" -> Token.CATCH
                "char" -> Token.RESERVED // ES3 only
                "class" -> Token.RESERVED
                "const" -> Token.CONST // reserved
                "debugger" -> Token.DEBUGGER
                "double" -> Token.RESERVED // ES3 only
                "enum" -> Token.RESERVED
                "extends" -> Token.RESERVED
                "final" -> Token.RESERVED // ES3 only
                "finally" -> Token.FINALLY
                "float" -> Token.RESERVED // ES3 only
                "goto" -> Token.RESERVED // ES3 only
                "implements" -> Token.RESERVED // ES3, ES5 strict
                "import" -> Token.RESERVED
                "instanceof" -> Token.INSTANCEOF
                "int" -> Token.RESERVED // ES3
                "interface" -> Token.RESERVED // ES3, ES5 strict
                "long" -> Token.RESERVED // ES3 only
                "native" -> Token.RESERVED // ES3 only
                "package" -> Token.RESERVED // ES3, ES5 strict
                "private" -> Token.RESERVED // ES3, ES5 strict
                "protected" -> Token.RESERVED // ES3, ES5 strict
                "public" -> Token.RESERVED // ES3, ES5 strict
                "short" -> Token.RESERVED // ES3 only
                "static" -> Token.RESERVED // ES3, ES5 strict
                "super" -> Token.RESERVED
                "synchronized" -> Token.RESERVED // ES3 only
                "throw" -> Token.THROW
                "throws" -> Token.RESERVED // ES3 only
                "transient" -> Token.RESERVED // ES3 only
                "try" -> Token.TRY
                "volatile" -> Token.RESERVED // ES3 only
                else -> 0
            }
            if (id == 0) {
                return Token.EOF
            }
            return id and 0xff
        }

        /** ECMAScript 6. */
        private fun stringToKeywordForES(name: String, isStrict: Boolean): Int {
            val id = when (name) {
                // 11.6.2.1 Keywords (ECMAScript2015)
                "break" -> Token.BREAK
                "case" -> Token.CASE
                "catch" -> Token.CATCH
                "class" -> Token.RESERVED
                "const" -> Token.CONST
                "continue" -> Token.CONTINUE
                "debugger" -> Token.DEBUGGER
                "default" -> Token.DEFAULT
                "delete" -> Token.DELPROP
                "do" -> Token.DO
                "else" -> Token.ELSE
                "export" -> Token.RESERVED
                "extends" -> Token.RESERVED
                "finally" -> Token.FINALLY
                "for" -> Token.FOR
                "function" -> Token.FUNCTION
                "if" -> Token.IF
                "import" -> Token.RESERVED
                "in" -> Token.IN
                "instanceof" -> Token.INSTANCEOF
                "new" -> Token.NEW
                "return" -> Token.RETURN
                "super" -> Token.SUPER
                "switch" -> Token.SWITCH
                "this" -> Token.THIS
                "throw" -> Token.THROW
                "try" -> Token.TRY
                "typeof" -> Token.TYPEOF
                "var" -> Token.VAR
                "void" -> Token.VOID
                "while" -> Token.WHILE
                "with" -> Token.WITH
                "yield" -> Token.YIELD
                // 11.6.2.2 Future Reserved Words
                "await" -> Token.RESERVED
                "enum" -> Token.RESERVED
                // 11.6.2.2 NOTE Strict Future Reserved Words
                "implements" -> if (isStrict) Token.RESERVED else 0
                "interface" -> if (isStrict) Token.RESERVED else 0
                "package" -> if (isStrict) Token.RESERVED else 0
                "private" -> if (isStrict) Token.RESERVED else 0
                "protected" -> if (isStrict) Token.RESERVED else 0
                "public" -> if (isStrict) Token.RESERVED else 0
                // 11.8 Literals
                "false" -> Token.FALSE
                "null" -> Token.NULL
                "undefined" -> Token.UNDEFINED
                "true" -> Token.TRUE
                // Non ReservedWord, but Non IdentifierName in strict mode code.
                // 12.1.1 Static Semantics: Early Errors
                "let" -> Token.LET
                "static" -> if (isStrict) Token.RESERVED else 0
                else -> 0
            }
            if (id == 0) {
                return Token.EOF
            }
            return id and 0xff
        }

        private fun isValidIdentifierName(str: String): Boolean {
            var i = 0
            while (i < str.length) {
                val c = Characters.codePointAt(str, i)
                if (i == 0) {
                    if (c != '$'.code && c != '_'.code && !Characters.isUnicodeIdentifierStart(c)) {
                        return false
                    }
                } else {
                    if (c != '$'.code &&
                        c != 0x200c &&
                        c != 0x200d &&
                        !Characters.isUnicodeIdentifierPart(c)
                    ) {
                        return false
                    }
                }
                i += Characters.charCount(c)
            }
            return true
        }

        private fun isAlpha(c: Int): Boolean {
            // Use 'Z' < 'a'
            if (c <= 'Z'.code) {
                return 'A'.code <= c
            }
            return 'a'.code <= c && c <= 'z'.code
        }

        private fun isDigit(base: Int, c: Int): Boolean =
            (base == 10 && isDigit(c)) ||
                (base == 16 && isHexDigit(c)) ||
                (base == 8 && isOctalDigit(c)) ||
                (base == 2 && isDualDigit(c))

        private fun isDualDigit(c: Int): Boolean = '0'.code == c || c == '1'.code

        private fun isOctalDigit(c: Int): Boolean = c in '0'.code..'7'.code

        private fun isDigit(c: Int): Boolean = c in '0'.code..'9'.code

        private fun isHexDigit(c: Int): Boolean =
            c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

        /* As defined in ECMA. jsscan.c uses C isspace() (which allows \v, I think.) note
         * that code in getChar() implicitly accepts '\r' == as well.
         */
        private fun isJSSpace(c: Int): Boolean {
            if (c <= 127) {
                return c == 0x20 || c == 0x9 || c == 0xC || c == 0xB
            }
            return c == 0xA0 || c == BYTE_ORDER_MARK.code || Characters.isSpaceSeparator(c)
        }

        private fun isJSFormatChar(c: Int): Boolean = c > 127 && Characters.isFormat(c)

        private fun convertLastCharToHex(str: String): String {
            val lastIndex = str.length - 1
            val buf = StringBuilder(str.substring(0, lastIndex))
            buf.append("\\u")
            val hexCode = str[lastIndex].code.toString(16)
            for (i in 0 until 4 - hexCode.length) {
                buf.append('0')
            }
            buf.append(hexCode)
            return buf.toString()
        }
    }

    /* This function uses the cached op, string and number fields in TokenStream; if
     * getToken has been called since the passed token was scanned, the op or string
     * printed may be incorrect.
     */
    fun tokenToString(token: Int): String {
        if (Token.printTrees) {
            val name = Token.name(token)
            when (token) {
                Token.STRING, Token.REGEXP, Token.NAME -> return "$name `$string'"
                Token.NUMBER -> return "NUMBER $number"
                Token.BIGINT -> return "BIGINT $bigInt"
            }
            return name
        }
        return ""
    }

    fun getToken(): Int {
        var c: Int

        while (true) {
            // Eat whitespace, possibly sensitive to newlines.
            while (true) {
                c = getChar()
                if (c == EOF_CHAR) {
                    tokenStartLastLineEnd = lastLineEnd
                    tokenStartLineno = lineno
                    tokenBeg = cursor - 1
                    tokenEnd = cursor
                    return Token.EOF
                } else if (c == '\n'.code) {
                    dirtyLine = false
                    tokenStartLastLineEnd = lastLineEnd
                    tokenStartLineno = lineno
                    tokenBeg = cursor - 1
                    tokenEnd = cursor
                    return Token.EOL
                } else if (!isJSSpace(c)) {
                    if (c != '-'.code) {
                        dirtyLine = true
                    }
                    break
                }
            }

            // Assume the token will be 1 char - fixed up below.
            tokenStartLastLineEnd = lastLineEnd
            tokenStartLineno = lineno
            tokenBeg = cursor - 1
            tokenEnd = cursor

            if (c == '@'.code) return Token.XMLATTR

            // identifier/keyword/instanceof?
            // watch out for starting with a <backslash>
            val identifierStart: Boolean
            var isUnicodeEscapeStart = false
            if (c == '\\'.code) {
                c = getChar()
                if (c == 'u'.code) {
                    identifierStart = true
                    isUnicodeEscapeStart = true
                    stringBufferTop = 0
                } else {
                    identifierStart = false
                    ungetChar(c)
                    c = '\\'.code
                }
            } else {
                identifierStart = Characters.isUnicodeIdentifierStart(c) || c == '$'.code || c == '_'.code
                if (identifierStart) {
                    stringBufferTop = 0
                    addToString(c)
                }
            }

            if (identifierStart) {
                var containsEscape = isUnicodeEscapeStart
                identLoop@ while (true) {
                    if (isUnicodeEscapeStart) {
                        // strictly speaking we should probably push-back all the bad
                        // characters if the <backslash>uXXXX sequence is malformed. But
                        // since there isn't a correct context(is there?) for a bad
                        // Unicode escape sequence in an identifier, we can report an
                        // error here.
                        var escapeVal = 0
                        if (matchTemplateLiteralChar('{'.code)) {
                            while (true) {
                                c = getTemplateLiteralChar()
                                if (c == '}'.code) {
                                    break
                                }
                                escapeVal = Kit.xDigitToInt(c, escapeVal)
                                if (escapeVal < 0) {
                                    break
                                }
                            }
                            if (escapeVal < 0 || escapeVal > 0x10FFFF) {
                                parser.reportError("msg.invalid.escape")
                                break@identLoop
                            }
                        } else {
                            for (i in 0 until 4) {
                                c = getChar()
                                escapeVal = Kit.xDigitToInt(c, escapeVal)
                                // Next check takes care about c < 0 and bad escape
                                if (escapeVal < 0) {
                                    parser.reportError("msg.invalid.escape")
                                    break
                                }
                            }
                        }
                        if (escapeVal < 0) {
                            parser.addError("msg.invalid.escape")
                            return Token.ERROR
                        }
                        addToString(escapeVal)
                        isUnicodeEscapeStart = false
                    } else {
                        c = getChar()
                        if (c == '\\'.code) {
                            c = getChar()
                            if (c == 'u'.code) {
                                isUnicodeEscapeStart = true
                                containsEscape = true
                            } else {
                                parser.addError("msg.illegal.character", c)
                                return Token.ERROR
                            }
                        } else {
                            if (c == EOF_CHAR ||
                                c == BYTE_ORDER_MARK.code ||
                                !(Characters.isUnicodeIdentifierPart(c) || c == '$'.code)
                            ) {
                                break@identLoop
                            }
                            addToString(c)
                        }
                    }
                }

                // `ungetChar` will decrement the cursor, however the _actual_ tokenEnd
                // should still be restored correctly after `getStringFromBuffer()`
                // mutates it, so we save and restore it.
                val savedTokenEnd = cursor
                ungetChar(c) // decrements cursor
                var str = getStringFromBuffer() // mutates tokenEnd to point to cursor
                tokenEnd = savedTokenEnd // restore tokenEnd

                if (!containsEscape || parser.compilerEnv.languageVersion >= Context.VERSION_ES6) {
                    // OPT we shouldn't have to make a string (object!) to check if it's
                    // a keyword.

                    // Return the corresponding token if it's a keyword
                    var result = stringToKeyword(
                        str,
                        parser.compilerEnv.languageVersion,
                        parser.inUseStrictDirective,
                    )
                    if (result != Token.EOF) {
                        if ((result == Token.LET || result == Token.YIELD) &&
                            parser.compilerEnv.languageVersion < Context.VERSION_1_7
                        ) {
                            // LET and YIELD are tokens only in 1.7 and later
                            string = if (result == Token.LET) "let" else "yield"
                            result = Token.NAME
                        }
                        // Save the string in case we need to use in object literal
                        // definitions.
                        this.string = internString(str)
                        if (result != Token.RESERVED) {
                            return result
                        } else if (parser.compilerEnv.languageVersion >= Context.VERSION_ES6) {
                            return result
                        } else if (!parser.compilerEnv.reservedKeywordAsIdentifier) {
                            return result
                        }
                    }
                } else if (isKeyword(
                        str,
                        parser.compilerEnv.languageVersion,
                        parser.inUseStrictDirective,
                    )
                ) {
                    // If a string contains unicodes, and converted to a keyword, we
                    // convert the last character back to unicode
                    str = convertLastCharToHex(str)
                }

                if (containsEscape &&
                    parser.compilerEnv.languageVersion >= Context.VERSION_ES6 &&
                    !isValidIdentifierName(str)
                ) {
                    parser.reportError("msg.invalid.escape")
                    return Token.ERROR
                }

                this.string = internString(str)
                return Token.NAME
            }

            // is it a number?
            if (isDigit(c) || (c == '.'.code && isDigit(peekChar()))) {
                stringBufferTop = 0
                var base = 10
                isNumericHex = false
                isNumericOldOctal = false
                isNumericOctal = false
                isNumericBinary = false
                val es6 = parser.compilerEnv.languageVersion >= Context.VERSION_ES6

                if (c == '0'.code) {
                    c = getChar()
                    if (c == 'x'.code || c == 'X'.code) {
                        base = 16
                        isNumericHex = true
                        c = getChar()
                    } else if (es6 && (c == 'o'.code || c == 'O'.code)) {
                        base = 8
                        isNumericOctal = true
                        c = getChar()
                    } else if (es6 && (c == 'b'.code || c == 'B'.code)) {
                        base = 2
                        isNumericBinary = true
                        c = getChar()
                    } else if (isDigit(c)) {
                        base = 8
                        isNumericOldOctal = true
                    } else {
                        addToString('0'.code)
                    }
                }

                val emptyDetector = stringBufferTop
                if (base == 10 || base == 16 || (base == 8 && !isNumericOldOctal) || base == 2) {
                    c = readDigits(base, c)
                    if (c == REPORT_NUMBER_FORMAT_ERROR) {
                        parser.addError("msg.caught.nfe")
                        return Token.ERROR
                    }
                } else {
                    while (isDigit(c)) {
                        // finally the oldOctal case
                        if (c >= '8'.code) {
                            /*
                             * We permit 08 and 09 as decimal numbers, which makes our
                             * behavior a superset of the ECMA numeric grammar. We might
                             * not always be so permissive, so we warn about it.
                             */
                            parser.addWarning("msg.bad.octal.literal", if (c == '8'.code) "8" else "9")
                            base = 10

                            c = readDigits(base, c)
                            if (c == REPORT_NUMBER_FORMAT_ERROR) {
                                parser.addError("msg.caught.nfe")
                                return Token.ERROR
                            }
                            break
                        }
                        addToString(c)
                        c = getChar()
                    }
                }
                if (stringBufferTop == emptyDetector && (isNumericBinary || isNumericOctal || isNumericHex)) {
                    parser.addError("msg.caught.nfe")
                    return Token.ERROR
                }

                var isInteger = true
                var isBigInt = false

                if (es6 && c == 'n'.code) {
                    isBigInt = true
                    c = getChar()
                } else if (base == 10 && (c == '.'.code || c == 'e'.code || c == 'E'.code)) {
                    isInteger = false
                    if (c == '.'.code) {
                        isInteger = false
                        addToString(c)
                        c = getChar()
                        c = readDigits(base, c)
                        if (c == REPORT_NUMBER_FORMAT_ERROR) {
                            parser.addError("msg.caught.nfe")
                            return Token.ERROR
                        }
                    }
                    if (c == 'e'.code || c == 'E'.code) {
                        isInteger = false
                        addToString(c)
                        c = getChar()
                        if (c == '+'.code || c == '-'.code) {
                            addToString(c)
                            c = getChar()
                        }
                        if (!isDigit(c)) {
                            parser.addError("msg.missing.exponent")
                            return Token.ERROR
                        }
                        c = readDigits(base, c)
                        if (c == REPORT_NUMBER_FORMAT_ERROR) {
                            parser.addError("msg.caught.nfe")
                            return Token.ERROR
                        }
                    }
                }
                ungetChar(c)
                var numString = getStringFromBuffer()
                this.string = numString

                // try to remove the separator in a fast way
                var pos = numString.indexOf(NUMERIC_SEPARATOR)
                if (pos != -1) {
                    val chars = numString.toCharArray()
                    for (i in pos + 1 until chars.size) {
                        if (chars[i] != NUMERIC_SEPARATOR) {
                            chars[pos++] = chars[i]
                        }
                    }
                    numString = chars.concatToString(0, pos)
                }

                if (isBigInt) {
                    this.bigInt = KBigInt(numString, base)
                    return Token.BIGINT
                }

                val dval: Double
                if (base == 10 && !isInteger) {
                    dval = try {
                        // Use the full string-to-double conversion...
                        numString.toDouble()
                    } catch (ex: NumberFormatException) {
                        parser.addError("msg.caught.nfe")
                        return Token.ERROR
                    }
                } else {
                    dval = ScriptRuntime.stringPrefixToNumber(numString, 0, base)
                }

                this.number = dval
                return Token.NUMBER
            }

            // is it a string?
            if (c == '"'.code || c == '\''.code) {
                // We attempt to accumulate a string the fast way, by building it directly
                // out of the reader. But if there are any escaped characters in the
                // string, we revert to building it out of a StringBuffer.

                quoteCharCode = c
                stringBufferTop = 0

                c = getCharIgnoreLineEnd(false)
                strLoop@ while (c != quoteCharCode) {
                    var unterminated = false
                    if (c == EOF_CHAR) {
                        unterminated = true
                    } else if (c == '\n'.code) {
                        when (lineEndChar) {
                            '\n'.code, '\r'.code -> unterminated = true
                            0x2028, 0x2029 -> {
                                // Line/Paragraph separators need to be included as is
                                c = lineEndChar
                            }
                        }
                    }

                    if (unterminated) {
                        ungetCharIgnoreLineEnd(c)
                        tokenEnd = cursor
                        parser.addError("msg.unterminated.string.lit")
                        return Token.ERROR
                    }

                    if (c == '\\'.code) {
                        // We've hit an escaped character
                        var escapeVal: Int

                        c = getChar()
                        when (c) {
                            'b'.code -> c = '\b'.code
                            'f'.code -> c = 0xC // \f
                            'n'.code -> c = '\n'.code
                            'r'.code -> c = '\r'.code
                            't'.code -> c = '\t'.code

                            // \v a late addition to the ECMA spec, it is not in Java,
                            // so use 0xb
                            'v'.code -> c = 0xb

                            'u'.code -> {
                                // Get 4 hex digits; if the u escape is not followed by 4
                                // hex digits, use 'u' + the literal character sequence
                                // that follows.
                                val escapeStart = stringBufferTop
                                addToString('u'.code)
                                escapeVal = 0
                                if (matchChar('{'.code)) {
                                    while (true) {
                                        c = getChar()
                                        if (c == '}'.code) {
                                            addToString(c)
                                            break
                                        }
                                        escapeVal = Kit.xDigitToInt(c, escapeVal)
                                        if (escapeVal < 0) {
                                            break
                                        }
                                        addToString(c)
                                    }

                                    if (escapeVal < 0 || escapeVal > 0x10FFFF) {
                                        parser.reportError("msg.invalid.escape")
                                        continue@strLoop
                                    }
                                } else {
                                    for (i in 0 until 4) {
                                        c = getChar()
                                        escapeVal = Kit.xDigitToInt(c, escapeVal)
                                        if (escapeVal < 0) {
                                            if (parser.compilerEnv.languageVersion >= Context.VERSION_ES6) {
                                                parser.reportError("msg.invalid.escape")
                                            }
                                            continue@strLoop
                                        }
                                        addToString(c)
                                    }
                                }
                                // prepare for replace of stored 'u' sequence by escape
                                // value
                                stringBufferTop = escapeStart
                                c = escapeVal
                            }
                            'x'.code -> {
                                // Get 2 hex digits, defaulting to 'x'+literal sequence,
                                // as above.
                                c = getChar()
                                escapeVal = Kit.xDigitToInt(c, 0)
                                if (escapeVal < 0) {
                                    addToString('x'.code)
                                    continue@strLoop
                                }
                                val c1 = c
                                c = getChar()
                                escapeVal = Kit.xDigitToInt(c, escapeVal)
                                if (escapeVal < 0) {
                                    addToString('x'.code)
                                    addToString(c1)
                                    continue@strLoop
                                }
                                // got 2 hex digits
                                c = escapeVal
                            }

                            '\n'.code -> {
                                // Remove line terminator after escape to follow
                                // SpiderMonkey and C/C++
                                c = getChar()
                                continue@strLoop
                            }

                            else ->
                                if ('0'.code <= c && c < '8'.code) {
                                    var value = c - '0'.code
                                    c = getChar()
                                    if ('0'.code <= c && c < '8'.code) {
                                        value = 8 * value + c - '0'.code
                                        c = getChar()
                                        if ('0'.code <= c && c < '8'.code && value <= 31) {
                                            // c is 3rd char of octal sequence only if
                                            // the resulting value <= 0377 (value so far
                                            // <= 037)
                                            value = 8 * value + c - '0'.code
                                            c = getChar()
                                        }
                                    }
                                    ungetChar(c)
                                    c = value
                                }
                        }
                    }
                    addToString(c)
                    c = getChar(false)
                }

                val str = getStringFromBuffer()
                this.string = internString(str)
                cursor = sourceCursor
                tokenEnd = cursor
                return Token.STRING
            }

            if (c == '#'.code &&
                cursor == 1 &&
                peekChar() == '!'.code &&
                !parser.calledByCompileFunction
            ) {
                // #! hashbang: only on the first line of a Script, no leading whitespace
                skipLine()
                return Token.COMMENT
            }

            when (c) {
                ';'.code -> return Token.SEMI
                '['.code -> return Token.LB
                ']'.code -> return Token.RB
                '{'.code -> return Token.LC
                '}'.code -> return Token.RC
                '('.code -> return Token.LP
                ')'.code -> return Token.RP
                ','.code -> return Token.COMMA
                '?'.code -> {
                    if (parser.compilerEnv.languageVersion >= Context.VERSION_ES6) {
                        if (peekChar() == '.'.code) {
                            // ?.digit is to be treated as ? .num
                            getChar()
                            if (!isDigit(peekChar())) {
                                return Token.QUESTION_DOT
                            }
                            ungetChar('.'.code)
                        } else if (matchChar('?'.code)) {
                            if (matchChar('='.code)) {
                                return Token.ASSIGN_NULLISH
                            }
                            return Token.NULLISH_COALESCING
                        }
                    }
                    return Token.HOOK
                }
                ':'.code -> {
                    if (matchChar(':'.code)) {
                        return Token.COLONCOLON
                    }
                    return Token.COLON
                }
                '.'.code -> {
                    return if (matchChar('.'.code)) {
                        if (parser.compilerEnv.languageVersion >= Context.VERSION_1_8 &&
                            matchChar('.'.code)
                        ) {
                            Token.DOTDOTDOT
                        } else {
                            Token.DOTDOT
                        }
                    } else if (matchChar('('.code)) {
                        Token.DOTQUERY
                    } else {
                        Token.DOT
                    }
                }

                '|'.code -> {
                    return if (matchChar('|'.code)) {
                        if (matchChar('='.code)) Token.ASSIGN_LOGICAL_OR else Token.OR
                    } else if (matchChar('='.code)) {
                        Token.ASSIGN_BITOR
                    } else {
                        Token.BITOR
                    }
                }

                '^'.code -> {
                    if (matchChar('='.code)) {
                        return Token.ASSIGN_BITXOR
                    }
                    return Token.BITXOR
                }

                '&'.code -> {
                    return if (matchChar('&'.code)) {
                        if (matchChar('='.code)) Token.ASSIGN_LOGICAL_AND else Token.AND
                    } else if (matchChar('='.code)) {
                        Token.ASSIGN_BITAND
                    } else {
                        Token.BITAND
                    }
                }

                '='.code -> {
                    return if (matchChar('='.code)) {
                        if (matchChar('='.code)) Token.SHEQ else Token.EQ
                    } else if (matchChar('>'.code)) {
                        Token.ARROW
                    } else {
                        Token.ASSIGN
                    }
                }

                '!'.code -> {
                    if (matchChar('='.code)) {
                        if (matchChar('='.code)) {
                            return Token.SHNE
                        }
                        return Token.NE
                    }
                    return Token.NOT
                }

                '<'.code -> {
                    /* NB:treat HTML begin-comment as comment-till-eol */
                    if (matchChar('!'.code)) {
                        if (matchChar('-'.code)) {
                            if (matchChar('-'.code)) {
                                tokenStartLastLineEnd = lastLineEnd
                                tokenStartLineno = lineno
                                tokenBeg = cursor - 4
                                skipLine()
                                commentType = Token.CommentType.HTML
                                return Token.COMMENT
                            }
                            ungetCharIgnoreLineEnd('-'.code)
                        }
                        ungetCharIgnoreLineEnd('!'.code)
                    }
                    if (matchChar('<'.code)) {
                        if (matchChar('='.code)) {
                            return Token.ASSIGN_LSH
                        }
                        return Token.LSH
                    }
                    if (matchChar('='.code)) {
                        return Token.LE
                    }
                    return Token.LT
                }

                '>'.code -> {
                    if (matchChar('>'.code)) {
                        if (matchChar('>'.code)) {
                            if (matchChar('='.code)) {
                                return Token.ASSIGN_URSH
                            }
                            return Token.URSH
                        }
                        if (matchChar('='.code)) {
                            return Token.ASSIGN_RSH
                        }
                        return Token.RSH
                    }
                    if (matchChar('='.code)) {
                        return Token.GE
                    }
                    return Token.GT
                }

                '*'.code -> {
                    if (parser.compilerEnv.languageVersion >= Context.VERSION_ES6) {
                        if (matchChar('*'.code)) {
                            if (matchChar('='.code)) {
                                return Token.ASSIGN_EXP
                            }
                            return Token.EXP
                        }
                    }
                    if (matchChar('='.code)) {
                        return Token.ASSIGN_MUL
                    }
                    return Token.MUL
                }

                '/'.code -> {
                    markCommentStart()
                    // is it a // comment?
                    if (matchChar('/'.code)) {
                        tokenStartLastLineEnd = lastLineEnd
                        tokenStartLineno = lineno
                        tokenBeg = cursor - 2
                        skipLine()
                        commentType = Token.CommentType.LINE
                        return Token.COMMENT
                    }
                    // is it a /* or /** comment?
                    if (matchChar('*'.code)) {
                        var lookForSlash = false
                        tokenStartLastLineEnd = lastLineEnd
                        tokenStartLineno = lineno
                        tokenBeg = cursor - 2
                        if (matchChar('*'.code)) {
                            lookForSlash = true
                            commentType = Token.CommentType.JSDOC
                        } else {
                            commentType = Token.CommentType.BLOCK_COMMENT
                        }
                        while (true) {
                            c = getChar()
                            if (c == EOF_CHAR) {
                                tokenEnd = cursor - 1
                                parser.addError("msg.unterminated.comment")
                                return Token.COMMENT
                            } else if (c == '*'.code) {
                                lookForSlash = true
                            } else if (c == '/'.code) {
                                if (lookForSlash) {
                                    cursor = sourceCursor
                                    tokenEnd = cursor
                                    return Token.COMMENT
                                }
                            } else {
                                lookForSlash = false
                                tokenEnd = cursor
                            }
                        }
                    }

                    if (matchChar('='.code)) {
                        return Token.ASSIGN_DIV
                    }
                    return Token.DIV
                }

                '%'.code -> {
                    if (matchChar('='.code)) {
                        return Token.ASSIGN_MOD
                    }
                    return Token.MOD
                }

                '~'.code -> return Token.BITNOT

                '+'.code -> {
                    return if (matchChar('='.code)) {
                        Token.ASSIGN_ADD
                    } else if (matchChar('+'.code)) {
                        Token.INC
                    } else {
                        Token.ADD
                    }
                }

                '-'.code -> {
                    var t: Int
                    if (matchChar('='.code)) {
                        t = Token.ASSIGN_SUB
                    } else if (matchChar('-'.code)) {
                        if (!dirtyLine) {
                            // treat HTML end-comment after possible whitespace after line
                            // start as comment-until-eol
                            if (matchChar('>'.code)) {
                                markCommentStart("--")
                                skipLine()
                                commentType = Token.CommentType.HTML
                                return Token.COMMENT
                            }
                        }
                        t = Token.DEC
                    } else {
                        t = Token.SUB
                    }
                    dirtyLine = true
                    return t
                }

                '`'.code -> return Token.TEMPLATE_LITERAL

                else -> {
                    parser.addError("msg.illegal.character", c)
                    return Token.ERROR
                }
            }
        }
    }

    /*
     * Helper to read the next digits according to the base and ignore the number
     * separator if there is one.
     */
    private fun readDigits(base: Int, startC: Int): Int {
        var c = startC
        if (isDigit(base, c)) {
            addToString(c)

            c = getChar()
            if (c == EOF_CHAR) {
                return EOF_CHAR
            }

            while (true) {
                if (c == NUMERIC_SEPARATOR.code) {
                    // we do no peek here, we are optimistic for performance reasons and
                    // because peekChar() only does a getChar/ungetChar.
                    c = getChar()
                    // if the line ends after the separator we have to report this as an
                    // error
                    if (c == '\n'.code || c == EOF_CHAR) {
                        return REPORT_NUMBER_FORMAT_ERROR
                    }

                    if (!isDigit(base, c)) {
                        // bad luck we have to roll back
                        ungetChar(c)
                        return NUMERIC_SEPARATOR.code
                    }
                    addToString(NUMERIC_SEPARATOR.code)
                } else if (isDigit(base, c)) {
                    addToString(c)
                    c = getChar()
                    if (c == EOF_CHAR) {
                        return EOF_CHAR
                    }
                } else {
                    return c
                }
            }
        }
        return c
    }

    // Use a HashMap to ensure that we only have one copy -- the original one -- of any
    // particular string. The JVM-wide intern pool is deliberately not used.
    private fun internString(s: String): String = allStrings.getOrPut(s) { s }

    /** Parser calls the method when it gets / or /= in literal context. */
    fun readRegExp(startToken: Int) {
        val start = tokenBeg
        stringBufferTop = 0
        if (startToken == Token.ASSIGN_DIV) {
            // Miss-scanned /=
            addToString('='.code)
        } else {
            if (startToken != Token.DIV) Kit.codeBug()
            if (peekChar() == '*'.code) {
                tokenEnd = cursor - 1
                this.string = stringBuffer.concatToString(0, stringBufferTop)
                parser.reportError("msg.unterminated.re.lit")
                return
            }
        }

        var inCharSet = false // true if inside a '['..']' pair
        var c: Int
        while (true) {
            c = getChar()
            if (c == '/'.code && !inCharSet) break
            if (c == '\n'.code || c == EOF_CHAR) {
                ungetChar(c)
                tokenEnd = cursor - 1
                this.string = stringBuffer.concatToString(0, stringBufferTop)
                parser.reportError("msg.unterminated.re.lit")
                return
            }
            if (c == '\\'.code) {
                addToString(c)
                c = getChar()
                if (c == '\n'.code || c == EOF_CHAR) {
                    ungetChar(c)
                    tokenEnd = cursor - 1
                    this.string = stringBuffer.concatToString(0, stringBufferTop)
                    parser.reportError("msg.unterminated.re.lit")
                    return
                }
            } else if (c == '['.code) {
                inCharSet = true
            } else if (c == ']'.code) {
                inCharSet = false
            }
            addToString(c)
        }
        val reEnd = stringBufferTop

        while (true) {
            if (matchChar('g'.code)) addToString('g'.code)
            else if (matchChar('i'.code)) addToString('i'.code)
            else if (matchChar('m'.code)) addToString('m'.code)
            else if (matchChar('s'.code)) addToString('s'.code)
            else if (matchChar('y'.code)) addToString('y'.code)
            else if (matchChar('u'.code)) addToString('u'.code)
            else break
        }
        tokenEnd = start + stringBufferTop + 2 // include slashes

        if (isAlpha(peekChar())) {
            parser.reportError("msg.invalid.re.flag", Characters.codePointToString(peekChar()))
        }

        this.string = stringBuffer.concatToString(0, reEnd)
        this.regExpFlags = stringBuffer.concatToString(reEnd, stringBufferTop)
    }

    fun readAndClearRegExpFlags(): String? {
        val flags = this.regExpFlags
        this.regExpFlags = null
        return flags
    }

    val rawString: String
        get() = if (rawStringBuffer.isEmpty()) "" else rawStringBuffer.toString()

    private fun getTemplateLiteralChar(): Int {
        /*
         * In Template Literals <CR><LF> and <CR> are normalized to <LF>
         *
         * Line and Paragraph separators (<LS> & <PS>) need to be included in the template
         * strings as is
         */
        var c = getCharIgnoreLineEnd(false)

        if (c == '\n'.code) {
            when (lineEndChar) {
                '\r'.code ->
                    // check whether dealing with a <CR><LF> sequence
                    if (charAt(cursor) == '\n'.code) {
                        // consume the <LF> that followed the <CR>
                        getCharIgnoreLineEnd(false)
                    }
                0x2028, 0x2029 -> {
                    // Line/Paragraph separators need to be included as is
                    c = lineEndChar
                }
            }

            // Adjust numbers: duplicates the logic in getChar that's skipped as getChar
            // is called via getCharIgnoreLineEnd
            lineEndChar = -1
            lineStart = sourceCursor - 1
            lineno++
        }

        rawStringBuffer.append(c.toChar())
        return c
    }

    private fun ungetTemplateLiteralChar(c: Int) {
        ungetCharIgnoreLineEnd(c)
        rawStringBuffer.setLength(rawStringBuffer.length - 1)
    }

    private fun matchTemplateLiteralChar(test: Int): Boolean {
        val c = getTemplateLiteralChar()
        if (c == test) {
            return true
        }
        ungetTemplateLiteralChar(c)
        return false
    }

    private fun peekTemplateLiteralChar(): Int {
        val c = getTemplateLiteralChar()
        ungetTemplateLiteralChar(c)
        return c
    }

    fun readTemplateLiteral(isTaggedLiteral: Boolean): Int {
        rawStringBuffer.setLength(0)
        stringBufferTop = 0
        var hasInvalidEscapeSequences = false

        while (true) {
            var c = getTemplateLiteralChar()
            when (c) {
                EOF_CHAR -> {
                    this.string = if (hasInvalidEscapeSequences) null else getStringFromBuffer()
                    tokenEnd = cursor - 1 // restore tokenEnd
                    parser.reportError("msg.unexpected.eof")
                    return Token.ERROR
                }
                '`'.code -> {
                    rawStringBuffer.setLength(rawStringBuffer.length - 1) // don't include "`"
                    this.string = if (hasInvalidEscapeSequences) null else getStringFromBuffer()
                    cursor = sourceCursor
                    tokenEnd = cursor
                    return Token.TEMPLATE_LITERAL
                }
                '$'.code -> {
                    if (matchTemplateLiteralChar('{'.code)) {
                        rawStringBuffer.setLength(rawStringBuffer.length - 2) // don't include "${"
                        this.string = if (hasInvalidEscapeSequences) null else getStringFromBuffer()
                        this.tokenEnd = cursor - 1 // don't include "{"
                        return Token.TEMPLATE_LITERAL_SUBST
                    } else {
                        addToString(c)
                    }
                }
                '\\'.code -> {
                    // LineContinuation ::
                    //   \ LineTerminatorSequence
                    // EscapeSequence ::
                    //   CharacterEscapeSequence
                    //   0 [LA not DecimalDigit]
                    //   HexEscapeSequence
                    //   UnicodeEscapeSequence
                    // CharacterEscapeSequence ::
                    //   SingleEscapeCharacter
                    //   NonEscapeCharacter
                    // SingleEscapeCharacter ::
                    //   ' "  \  b f n r t v
                    // NonEscapeCharacter ::
                    //   SourceCharacter but not one of EscapeCharacter or LineTerminator
                    // EscapeCharacter ::
                    //   SingleEscapeCharacter
                    //   DecimalDigit
                    //   x
                    //   u
                    c = getTemplateLiteralChar()
                    when (c) {
                        '\n'.code, 0x2028, 0x2029 -> continue
                        '\''.code, '"'.code, '\\'.code -> {
                            // use as-is
                        }
                        'b'.code -> c = '\b'.code
                        'f'.code -> c = 0xC // \f
                        'n'.code -> c = '\n'.code
                        'r'.code -> c = '\r'.code
                        't'.code -> c = '\t'.code
                        'v'.code -> c = 0xb
                        'x'.code -> {
                            var escapeVal = 0
                            for (i in 0 until 2) {
                                if (peekTemplateLiteralChar() == '`'.code) {
                                    escapeVal = -1
                                    break
                                }
                                escapeVal = Kit.xDigitToInt(getTemplateLiteralChar(), escapeVal)
                            }

                            if (escapeVal < 0) {
                                if (isTaggedLiteral) {
                                    hasInvalidEscapeSequences = true
                                    continue
                                } else {
                                    parser.reportError("msg.syntax")
                                    return Token.ERROR
                                }
                            }
                            c = escapeVal
                        }
                        'u'.code -> {
                            var escapeVal = 0

                            if (matchTemplateLiteralChar('{'.code)) {
                                while (true) {
                                    if (peekTemplateLiteralChar() == '`'.code) {
                                        escapeVal = -1
                                        break
                                    }

                                    c = getTemplateLiteralChar()
                                    if (c == EOF_CHAR) {
                                        parser.reportError("msg.syntax")
                                        return Token.ERROR
                                    }

                                    if (c == '}'.code) {
                                        break
                                    }
                                    escapeVal = Kit.xDigitToInt(c, escapeVal)
                                }

                                if (escapeVal < 0 || escapeVal > 0x10FFFF) {
                                    if (isTaggedLiteral) {
                                        hasInvalidEscapeSequences = true
                                        continue
                                    } else {
                                        parser.reportError("msg.syntax")
                                        return Token.ERROR
                                    }
                                }

                                if (escapeVal > 0xFFFF) {
                                    addToString(Characters.highSurrogate(escapeVal).code)
                                    addToString(Characters.lowSurrogate(escapeVal).code)
                                    continue
                                }
                                c = escapeVal
                                addToString(c)
                                continue
                            }

                            for (i in 0 until 4) {
                                if (peekTemplateLiteralChar() == '`'.code) {
                                    escapeVal = -1
                                    break
                                }
                                escapeVal = Kit.xDigitToInt(getTemplateLiteralChar(), escapeVal)
                            }

                            if (escapeVal < 0) {
                                if (isTaggedLiteral) {
                                    hasInvalidEscapeSequences = true
                                    continue
                                } else {
                                    parser.reportError("msg.syntax")
                                    return Token.ERROR
                                }
                            }
                            c = escapeVal
                        }
                        '0'.code -> {
                            val d = peekTemplateLiteralChar()
                            if (d >= '0'.code && d <= '9'.code) {
                                if (isTaggedLiteral) {
                                    hasInvalidEscapeSequences = true
                                    continue
                                } else {
                                    parser.reportError("msg.syntax")
                                    return Token.ERROR
                                }
                            }
                            c = 0x00
                        }
                        '1'.code, '2'.code, '3'.code, '4'.code, '5'.code,
                        '6'.code, '7'.code, '8'.code, '9'.code -> {
                            if (isTaggedLiteral) {
                                hasInvalidEscapeSequences = true
                                continue
                            } else {
                                parser.reportError("msg.syntax")
                                return Token.ERROR
                            }
                        }
                        else -> {
                            // use as-is
                        }
                    }
                    addToString(c)
                }
                else -> addToString(c)
            }
        }
    }

    private fun getStringFromBuffer(): String {
        tokenEnd = cursor
        return stringBuffer.concatToString(0, stringBufferTop)
    }

    private fun addToString(c: Int) {
        val n = stringBufferTop
        val codePointLen = Characters.charCount(c)
        if (n + codePointLen >= stringBuffer.size) {
            val tmp = CharArray(stringBuffer.size * 2)
            stringBuffer.copyInto(tmp, 0, 0, n)
            stringBuffer = tmp
        }
        if (codePointLen == 1) {
            stringBuffer[n] = c.toChar()
        } else {
            stringBuffer[n] = Characters.highSurrogate(c)
            stringBuffer[n + 1] = Characters.lowSurrogate(c)
        }
        stringBufferTop = n + codePointLen
    }

    private fun canUngetChar(): Boolean = ungetCursor == 0 || ungetBuffer[ungetCursor - 1] != '\n'.code

    private fun ungetChar(c: Int) {
        // can not unread past across line boundary
        if (ungetCursor != 0 && ungetBuffer[ungetCursor - 1] == '\n'.code) Kit.codeBug()
        ungetBuffer[ungetCursor++] = c
        cursor--
    }

    private fun matchChar(test: Int): Boolean {
        val c = getCharIgnoreLineEnd()
        if (c == test) {
            tokenEnd = cursor
            return true
        }
        ungetCharIgnoreLineEnd(c)
        return false
    }

    private fun peekChar(): Int {
        val c = getChar()
        ungetChar(c)
        return c
    }

    private fun getChar(): Int = getChar(true, false)

    private fun getChar(skipFormattingChars: Boolean): Int = getChar(skipFormattingChars, false)

    private fun getChar(skipFormattingChars: Boolean, ignoreLineEnd: Boolean): Int {
        if (ungetCursor != 0) {
            cursor++
            return ungetBuffer[--ungetCursor]
        }

        while (true) {
            if (sourceCursor == sourceEnd) {
                hitEOF = true
                return EOF_CHAR
            }
            cursor++
            var c = Characters.codePointAt(sourceString, sourceCursor)
            sourceCursor += Characters.charCount(c)

            if (!ignoreLineEnd && lineEndChar >= 0) {
                if (lineEndChar == '\r'.code && c == '\n'.code) {
                    lineEndChar = '\n'.code
                    continue
                }
                lineEndChar = -1
                lineStart = sourceCursor - 1
                lastLineEnd = tokenEnd
                lineno++
            }

            if (c <= 127) {
                if (c == '\n'.code || c == '\r'.code) {
                    lineEndChar = c
                    c = '\n'.code
                }
            } else {
                if (c == BYTE_ORDER_MARK.code) return c // BOM is considered whitespace
                if (skipFormattingChars && isJSFormatChar(c)) {
                    continue
                }
                if (ScriptRuntime.isJSLineTerminator(c)) {
                    lineEndChar = c
                    c = '\n'.code
                }
            }
            return c
        }
    }

    private fun getCharIgnoreLineEnd(): Int = getChar(true, true)

    private fun getCharIgnoreLineEnd(skipFormattingChars: Boolean): Int =
        getChar(skipFormattingChars, true)

    private fun ungetCharIgnoreLineEnd(c: Int) {
        ungetBuffer[ungetCursor++] = c
        cursor--
    }

    private fun skipLine() {
        // skip to end of line
        var c: Int
        while (true) {
            c = getChar()
            if (c == EOF_CHAR || c == '\n'.code) break
        }
        if (c == EOF_CHAR) {
            // If we've hit EOF, the cursor hasn't been incremented, so we need to save
            // tokenEnd _before_ ungetChar
            tokenEnd = cursor
            ungetChar(c)
        } else {
            // If we've hit a newline, the cursor has been incremented past it, and
            // ungetChar will point at the newline, so saving tokenEnd after ungetChar
            // will correctly exclude the newline
            ungetChar(c)
            tokenEnd = cursor
        }
    }

    /** Returns the offset into the current line. */
    override val offset: Int
        get() {
            var n = sourceCursor - lineStart
            if (lineEndChar >= 0) {
                --n
            }
            return n
        }

    private fun charAt(index: Int): Int {
        if (index < 0) {
            return EOF_CHAR
        }
        if (index >= sourceEnd) {
            return EOF_CHAR
        }
        return sourceString[index].code
    }

    private fun substring(beginIndex: Int, endIndex: Int): String =
        sourceString.substring(beginIndex, endIndex)

    override val line: String
        get() {
            var lineEnd = sourceCursor
            if (lineEndChar >= 0) {
                // move cursor before newline sequence
                lineEnd -= 1
                if (lineEndChar == '\n'.code && charAt(lineEnd - 1) == '\r'.code) {
                    lineEnd -= 1
                }
            } else {
                // Read until the end of line
                var lineLength = lineEnd - lineStart
                while (true) {
                    val c = charAt(lineStart + lineLength)
                    if (c == EOF_CHAR || ScriptRuntime.isJSLineTerminator(c)) {
                        break
                    }
                    ++lineLength
                }
                lineEnd = lineStart + lineLength
            }
            return substring(lineStart, lineEnd)
        }

    fun getLine(position: Int, linep: IntArray): String? {
        var delta = (cursor + ungetCursor) - position
        var cur = sourceCursor
        if (delta > cur) {
            // requested line outside of source buffer
            return null
        }
        // read back until position
        var end = 0
        var lines = 0
        while (delta > 0) {
            val c = charAt(cur - 1)
            if (ScriptRuntime.isJSLineTerminator(c)) {
                if (c == '\n'.code && charAt(cur - 2) == '\r'.code) {
                    // \r\n sequence
                    delta -= 1
                    cur -= 1
                }
                lines += 1
                end = cur - 1
            }
            --delta
            --cur
        }
        // read back until line start
        var start = 0
        var offset = 0
        while (cur > 0) {
            val c = charAt(cur - 1)
            if (ScriptRuntime.isJSLineTerminator(c)) {
                start = cur
                break
            }
            --cur
            ++offset
        }
        linep[0] = lineno - lines + (if (lineEndChar >= 0) 1 else 0)
        linep[1] = offset
        if (lines == 0) {
            return line
        }
        return substring(start, end)
    }

    /** Return the type of the last scanned comment, or null if none have been scanned. */
    var commentType: Token.CommentType? = null
        private set

    private fun markCommentStart(prefix: String = "") {
        // KMP: only meaningful with the Reader source path, which is not ported (D-2).
    }

    private fun isMarkingComment(): Boolean = commentCursor != -1

    fun getAndResetCurrentComment(): String {
        if (isMarkingComment()) Kit.codeBug()
        return sourceString.substring(tokenBeg, tokenEnd)
    }

    override val position: Int
        get() = tokenBeg

    override val length: Int
        get() = tokenEnd - tokenBeg

    val tokenColumn: Int
        get() = tokenBeg - tokenStartLastLineEnd + 1

    // stuff other than whitespace since start of line
    private var dirtyLine = false

    private var regExpFlags: String? = null

    // Set this to an initial non-null value so that the Parser has something to retrieve
    // even if an error has occurred and no string is found. Fosters one class of error,
    // but saves lots of code.
    var string: String? = ""
        private set

    var number: Double = 0.0
        private set

    var bigInt: KBigInt? = null
        private set

    var isNumericBinary: Boolean = false
        private set

    var isNumericOldOctal: Boolean = false
        private set

    var isNumericOctal: Boolean = false
        private set

    var isNumericHex: Boolean = false
        private set

    // delimiter for last string literal scanned
    private var quoteCharCode = 0
    val quoteChar: Char
        get() = quoteCharCode.toChar()

    private var stringBuffer = CharArray(128)
    private var stringBufferTop = 0
    private val allStrings = HashMap<String, String>()

    // Room to backtrace from to < on failed match of the last - in <!--
    private val ungetBuffer = IntArray(3)
    private var ungetCursor = 0

    private var hitEOF = false
    val eof: Boolean
        get() = hitEOF

    private var lineStart = 0
    private var lineEndChar = -1
    override var lineno: Int = 0

    internal val sourceString: String
    private val sourceEnd: Int

    // sourceCursor is an index into the source string: the next code unit to be read.
    var sourceCursor: Int = 0
        private set

    // cursor is a monotonically increasing index into the original source stream,
    // tracking exactly how far scanning has progressed. Its value is the index of the
    // next character to be scanned.
    var cursor: Int = 0
        private set

    // Record start and end positions of last scanned token.
    var tokenBeg: Int = 0
        private set
    var tokenEnd: Int = 0
        private set

    private var lastLineEnd = 0
    private var tokenStartLastLineEnd = 0
    var tokenStartLineno: Int = 0
        private set

    private val rawStringBuffer = StringBuilder()

    private var commentPrefix = ""
    private var commentCursor = -1

    // Kotlin requires initialization after the declarations above; upstream does this at
    // the top, in the constructor.
    init {
        this.lineno = lineno
        this.sourceString = sourceString
        this.sourceEnd = sourceString.length
        this.sourceCursor = 0
        this.cursor = 0
    }
}
