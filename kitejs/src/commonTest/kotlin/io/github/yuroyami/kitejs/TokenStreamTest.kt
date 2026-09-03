/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingReporter : ErrorReporter {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun warning(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int) {
        warnings += message
    }

    override fun error(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int) {
        errors += message
    }

    override fun runtimeError(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ): EvaluatorException = EvaluatorException(message, sourceName, line, lineSource, lineOffset)
}

class TokenStreamTest {

    private fun lexer(
        src: String,
        version: Int = Context.VERSION_ES6,
        reporter: ErrorReporter = RecordingReporter(),
        recover: Boolean = true,
        strict: Boolean = false,
    ): TokenStream {
        val env = CompilerEnvirons()
        env.languageVersion = version
        env.recoverFromErrors = recover
        env.errorReporter = reporter
        val parser = Parser(env)
        parser.inUseStrictDirective = strict
        val ts = TokenStream(parser, src, 1)
        parser.currentPos = ts
        return ts
    }

    private fun tokens(src: String, version: Int = Context.VERSION_ES6): List<Int> {
        val ts = lexer(src, version)
        val out = mutableListOf<Int>()
        while (true) {
            val t = ts.getToken()
            if (t == Token.EOF) break
            out += t
        }
        return out
    }

    private fun numberOf(src: String, version: Int = Context.VERSION_ES6): Double {
        val ts = lexer(src, version)
        assertEquals(Token.NUMBER, ts.getToken(), "expected NUMBER for: $src")
        return ts.number
    }

    // ---------- numbers ----------

    @Test
    fun decimalNumbers() {
        assertEquals(42.0, numberOf("42"))
        assertEquals(3.14, numberOf("3.14"))
        assertEquals(0.5, numberOf(".5"))
        assertEquals(1000.0, numberOf("1e3"))
        assertEquals(1000.0, numberOf("1E3"))
        assertEquals(0.01, numberOf("1e-2"))
        assertEquals(Double.POSITIVE_INFINITY, numberOf("1e309"))
    }

    @Test
    fun radixNumbers() {
        val hex = lexer("0x1F")
        assertEquals(Token.NUMBER, hex.getToken())
        assertEquals(31.0, hex.number)
        assertTrue(hex.isNumericHex)

        val octal = lexer("0o17")
        assertEquals(Token.NUMBER, octal.getToken())
        assertEquals(15.0, octal.number)
        assertTrue(octal.isNumericOctal)

        val binary = lexer("0b101")
        assertEquals(Token.NUMBER, binary.getToken())
        assertEquals(5.0, binary.number)
        assertTrue(binary.isNumericBinary)

        val oldOctal = lexer("0777")
        assertEquals(Token.NUMBER, oldOctal.getToken())
        assertEquals(511.0, oldOctal.number)
        assertTrue(oldOctal.isNumericOldOctal)
    }

    @Test
    fun leadingZeroEightFallsBackToDecimalWithWarning() {
        val reporter = RecordingReporter()
        val ts = lexer("08", reporter = reporter)
        assertEquals(Token.NUMBER, ts.getToken())
        assertEquals(8.0, ts.number)
        assertEquals(1, reporter.warnings.size)
        assertTrue(reporter.warnings[0].contains("octal"))
    }

    @Test
    fun numericSeparators() {
        assertEquals(1000000.0, numberOf("1_000_000"))
        assertEquals(31.0, numberOf("0x1_F"))
    }

    @Test
    fun danglingSeparatorIsANumberFormatError() {
        val reporter = RecordingReporter()
        val ts = lexer("1_", reporter = reporter)
        assertEquals(Token.ERROR, ts.getToken())
        assertTrue(reporter.errors.contains("number format error"))
    }

    @Test
    fun bigNumbersUseTheCorrectionPaths() {
        // decimal fallback to full string conversion
        assertEquals(9007199254740992.0, numberOf("9007199254740993"))
        // hex 54-bit rounding: 0x1000000000000081 rounds to 0x1000000000000100
        assertEquals((1L shl 60).toDouble() + 256.0, numberOf("0x1000000000000081"))
    }

    @Test
    fun bigIntLiterals() {
        val ts = lexer("123n")
        assertEquals(Token.BIGINT, ts.getToken())
        assertEquals(KBigInt("123", 10), ts.bigInt)

        val hex = lexer("0xFFn")
        assertEquals(Token.BIGINT, hex.getToken())
        assertEquals(KBigInt("FF", 16), hex.bigInt)
    }

    // ---------- strings ----------

    private fun stringOf(src: String): String? {
        val ts = lexer(src)
        assertEquals(Token.STRING, ts.getToken(), "expected STRING for: $src")
        return ts.string
    }

    @Test
    fun stringEscapes() {
        assertEquals("a\nb", stringOf("\"a\\nb\""))
        assertEquals("A", stringOf("'\\x41'"))
        assertEquals("A", stringOf("'\\u0041'"))
        assertEquals("A", stringOf("'\\u{41}'"))
        assertEquals("😀", stringOf("'\\u{1F600}'"))
        assertEquals("A", stringOf("'\\101'"))
        assertEquals("ab", stringOf("'a\\\nb'")) // line continuation
        assertEquals("8", stringOf("'\\8'")) // non-escape char used as-is
    }

    @Test
    fun quoteCharIsRecorded() {
        val ts = lexer("'x'")
        ts.getToken()
        assertEquals('\'', ts.quoteChar)
    }

    @Test
    fun lineAndParagraphSeparatorsAreLegalInsideStrings() {
        assertEquals("a\u2028b", stringOf("'a\u2028b'"))
    }

    @Test
    fun unterminatedStringIsAnError() {
        val reporter = RecordingReporter()
        val ts = lexer("\"abc", reporter = reporter)
        assertEquals(Token.ERROR, ts.getToken())
        assertTrue(reporter.errors.contains("unterminated string literal"))

        val newline = RecordingReporter()
        val ts2 = lexer("'a\nb'", reporter = newline)
        assertEquals(Token.ERROR, ts2.getToken())
        assertTrue(newline.errors.contains("unterminated string literal"))
    }

    // ---------- identifiers and keywords ----------

    @Test
    fun identifiers() {
        val ts = lexer("\$_a1 été")
        assertEquals(Token.NAME, ts.getToken())
        assertEquals("\$_a1", ts.string)
        assertEquals(Token.NAME, ts.getToken())
        assertEquals("été", ts.string)
    }

    @Test
    fun unicodeEscapeInIdentifier() {
        val ts = lexer("\\u0041bc")
        assertEquals(Token.NAME, ts.getToken())
        assertEquals("Abc", ts.string)
    }

    @Test
    fun escapedKeywordScansAsKeywordInEs6() {
        val ts = lexer("\\u0069f")
        assertEquals(Token.IF, ts.getToken())
        assertEquals("if", ts.string)
    }

    @Test
    fun es6Keywords() {
        assertEquals(
            listOf(Token.IF, Token.ELSE, Token.RESERVED, Token.LET, Token.RESERVED, Token.YIELD),
            tokens("if else class let await yield"),
        )
        assertEquals(listOf(Token.NAME, Token.NAME), tokens("letx ifx"))
        assertEquals(listOf(Token.UNDEFINED, Token.NULL, Token.TRUE, Token.FALSE), tokens("undefined null true false"))
    }

    @Test
    fun strictModeReservedWords() {
        val nonStrict = lexer("static")
        assertEquals(Token.NAME, nonStrict.getToken())

        val strict = lexer("static", strict = true)
        assertEquals(Token.RESERVED, strict.getToken())
    }

    @Test
    fun legacyVersionKeywords() {
        // let is a NAME before 1.7
        assertEquals(listOf(Token.NAME, Token.NAME), tokens("let x", version = Context.VERSION_1_5))
        // java reserved words exist only pre-ES6
        assertTrue(TokenStream.isKeyword("boolean", Context.VERSION_1_8, false))
        assertTrue(!TokenStream.isKeyword("boolean", Context.VERSION_ES6, false))
    }

    // ---------- operators and punctuation ----------

    @Test
    fun es2020Operators() {
        assertEquals(listOf(Token.NAME, Token.QUESTION_DOT, Token.NAME), tokens("a ?. b"))
        assertEquals(listOf(Token.NAME, Token.NULLISH_COALESCING, Token.NAME), tokens("a ?? b"))
        assertEquals(listOf(Token.NAME, Token.ASSIGN_NULLISH, Token.NAME), tokens("a ??= b"))
        assertEquals(listOf(Token.NAME, Token.ASSIGN_LOGICAL_OR, Token.NAME), tokens("a ||= b"))
        assertEquals(listOf(Token.NAME, Token.ASSIGN_LOGICAL_AND, Token.NAME), tokens("a &&= b"))
        assertEquals(listOf(Token.NAME, Token.EXP, Token.NAME), tokens("a ** b"))
        assertEquals(listOf(Token.NAME, Token.ASSIGN_EXP, Token.NAME), tokens("a **= b"))
    }

    @Test
    fun optionalChainingDoesNotEatTernaryWithNumber() {
        // a?.5:b must scan as HOOK, not QUESTION_DOT
        assertEquals(
            listOf(Token.NAME, Token.HOOK, Token.NUMBER, Token.COLON, Token.NAME),
            tokens("a?.5:b"),
        )
    }

    @Test
    fun exponentIsNotATokenBeforeEs6() {
        assertEquals(listOf(Token.NAME, Token.MUL, Token.MUL, Token.NAME), tokens("a ** b", version = Context.VERSION_1_8))
    }

    @Test
    fun comparisonAndShiftOperators() {
        assertEquals(
            listOf(Token.SHEQ, Token.SHNE, Token.EQ, Token.NE, Token.LE, Token.GE, Token.URSH, Token.ASSIGN_URSH, Token.ARROW),
            tokens("=== !== == != <= >= >>> >>>= =>"),
        )
        assertEquals(listOf(Token.DOTDOTDOT, Token.DOTDOT, Token.DOT), tokens("... .. ."))
    }

    // ---------- comments ----------

    @Test
    fun lineComment() {
        val ts = lexer("// hi\nx")
        assertEquals(Token.COMMENT, ts.getToken())
        assertEquals(Token.CommentType.LINE, ts.commentType)
        assertEquals("// hi", ts.getAndResetCurrentComment())
        assertEquals(Token.EOL, ts.getToken())
        assertEquals(Token.NAME, ts.getToken())
    }

    @Test
    fun blockAndJsdocComments() {
        val block = lexer("/* a */ x")
        assertEquals(Token.COMMENT, block.getToken())
        assertEquals(Token.CommentType.BLOCK_COMMENT, block.commentType)
        assertEquals("/* a */", block.getAndResetCurrentComment())
        assertEquals(Token.NAME, block.getToken())

        val jsdoc = lexer("/** d */")
        assertEquals(Token.COMMENT, jsdoc.getToken())
        assertEquals(Token.CommentType.JSDOC, jsdoc.commentType)
    }

    @Test
    fun unterminatedBlockCommentReportsButStaysAComment() {
        val reporter = RecordingReporter()
        val ts = lexer("/* x", reporter = reporter)
        assertEquals(Token.COMMENT, ts.getToken())
        assertTrue(reporter.errors.contains("unterminated comment"))
    }

    @Test
    fun htmlComments() {
        val begin = lexer("<!-- x")
        assertEquals(Token.COMMENT, begin.getToken())
        assertEquals(Token.CommentType.HTML, begin.commentType)

        val end = lexer("--> x")
        assertEquals(Token.COMMENT, end.getToken())
        assertEquals(Token.CommentType.HTML, end.commentType)

        // after code on the line, -- > is DEC then GT
        assertEquals(listOf(Token.NAME, Token.DEC, Token.GT, Token.NAME), tokens("a --> b"))
    }

    @Test
    fun hashbangOnFirstLine() {
        val ts = lexer("#!/usr/bin/env node\nx")
        assertEquals(Token.COMMENT, ts.getToken())
        assertEquals(Token.EOL, ts.getToken())
        assertEquals(Token.NAME, ts.getToken())
    }

    // ---------- regular expressions ----------

    @Test
    fun regExpScanning() {
        val ts = lexer("/ab+c/gi x")
        assertEquals(Token.DIV, ts.getToken())
        ts.readRegExp(Token.DIV)
        assertEquals("ab+c", ts.string)
        assertEquals("gi", ts.readAndClearRegExpFlags())
        assertNull(ts.readAndClearRegExpFlags())
        assertEquals(Token.NAME, ts.getToken())
    }

    @Test
    fun regExpFromMisscannedAssignDiv() {
        val ts = lexer("/=a/")
        assertEquals(Token.ASSIGN_DIV, ts.getToken())
        ts.readRegExp(Token.ASSIGN_DIV)
        assertEquals("=a", ts.string)
    }

    @Test
    fun regExpErrors() {
        val unterminated = RecordingReporter()
        val ts = lexer("/ab", reporter = unterminated)
        assertEquals(Token.DIV, ts.getToken())
        ts.readRegExp(Token.DIV)
        assertTrue(unterminated.errors.contains("unterminated regular expression literal"))

        val badFlag = RecordingReporter()
        val ts2 = lexer("/a/q", reporter = badFlag)
        assertEquals(Token.DIV, ts2.getToken())
        ts2.readRegExp(Token.DIV)
        assertTrue(badFlag.errors.contains("invalid flag 'q' after regular expression"))
    }

    // ---------- template literals ----------

    @Test
    fun simpleTemplateLiteral() {
        val ts = lexer("`abc`")
        assertEquals(Token.TEMPLATE_LITERAL, ts.getToken())
        assertEquals(Token.TEMPLATE_LITERAL, ts.readTemplateLiteral(false))
        assertEquals("abc", ts.string)
        assertEquals("abc", ts.rawString)
    }

    @Test
    fun templateLiteralWithSubstitution() {
        val src = "`a" + "$" + "{x}b`"
        val ts = lexer(src)
        assertEquals(Token.TEMPLATE_LITERAL, ts.getToken())
        assertEquals(Token.TEMPLATE_LITERAL_SUBST, ts.readTemplateLiteral(false))
        assertEquals("a", ts.string)
        assertEquals(Token.NAME, ts.getToken())
        assertEquals(Token.RC, ts.getToken())
        assertEquals(Token.TEMPLATE_LITERAL, ts.readTemplateLiteral(false))
        assertEquals("b", ts.string)
    }

    @Test
    fun templateLiteralEscapesAndRawText() {
        val ts = lexer("`a\\nb`")
        ts.getToken()
        ts.readTemplateLiteral(false)
        assertEquals("a\nb", ts.string)
        assertEquals("a\\nb", ts.rawString)
    }

    @Test
    fun templateLiteralNormalizesCrlf() {
        val ts = lexer("`a\r\nb`")
        ts.getToken()
        ts.readTemplateLiteral(false)
        assertEquals("a\nb", ts.string)
        assertEquals("a\nb", ts.rawString)
    }

    @Test
    fun taggedTemplateToleratesInvalidEscape() {
        val ts = lexer("`\\xZZ`")
        ts.getToken()
        assertEquals(Token.TEMPLATE_LITERAL, ts.readTemplateLiteral(true))
        assertNull(ts.string) // cooked value is undefined
    }

    @Test
    fun untaggedTemplateRejectsInvalidEscape() {
        val reporter = RecordingReporter()
        val ts = lexer("`\\xZZ`", reporter = reporter)
        ts.getToken()
        assertEquals(Token.ERROR, ts.readTemplateLiteral(false))
        assertTrue(reporter.errors.contains("syntax error"))
    }

    // ---------- whitespace, lines, positions ----------

    @Test
    fun bomIsWhitespace() {
        assertEquals(listOf(Token.VAR, Token.NAME), tokens("\uFEFFvar x"))
    }

    @Test
    fun eolTokensAndLineNumbers() {
        val ts = lexer("a\nb")
        assertEquals(Token.NAME, ts.getToken())
        assertEquals(1, ts.tokenStartLineno)
        assertEquals(Token.EOL, ts.getToken())
        assertEquals(Token.NAME, ts.getToken())
        assertEquals(2, ts.tokenStartLineno)
        assertEquals(2, ts.lineno)
        assertEquals(Token.EOF, ts.getToken())
        assertTrue(ts.eof)
    }

    @Test
    fun tokenPositions() {
        // Expected values verified against upstream Rhino (PositionParityTest): for a
        // name token, tokenEnd includes the delimiter character the scanner consumed.
        val ts = lexer("ab cd")
        ts.getToken()
        assertEquals(0, ts.tokenBeg)
        assertEquals(3, ts.tokenEnd)
        assertEquals(1, ts.tokenColumn)
        ts.getToken()
        assertEquals(3, ts.tokenBeg)
        assertEquals(5, ts.tokenEnd)
        assertEquals(4, ts.tokenColumn)
    }

    @Test
    fun lineTextForErrorReporting() {
        val ts = lexer("let x = 5")
        ts.getToken()
        assertEquals("let x = 5", ts.line)
        assertEquals(0, ts.position)
        assertEquals(4, ts.length) // upstream-verified: includes the consumed delimiter
    }

    // ---------- error plumbing ----------

    @Test
    fun illegalCharacterReportsAndReturnsError() {
        val reporter = RecordingReporter()
        val ts = lexer("§", reporter = reporter)
        assertEquals(Token.ERROR, ts.getToken())
        assertEquals(listOf("illegal character: §"), reporter.errors)
    }

    @Test
    fun defaultReporterThrowsEvaluatorException() {
        val ts = lexer("§", reporter = DefaultErrorReporter.instance)
        assertFailsWith<EvaluatorException> { ts.getToken() }
    }

    @Test
    fun reportErrorThrowsParserExceptionWithoutRecovery() {
        val ts = lexer("/ab", reporter = RecordingReporter(), recover = false)
        assertEquals(Token.DIV, ts.getToken())
        assertFailsWith<Parser.ParserException> { ts.readRegExp(Token.DIV) }
    }
}
