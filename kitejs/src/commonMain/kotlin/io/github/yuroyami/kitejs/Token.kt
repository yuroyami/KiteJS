/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.config.RhinoConfig

/**
 * This class implements the JavaScript scanner's token codes.
 *
 * It is based on the C source files jsscan.c and jsscan.h in the jsref package.
 */
object Token {

    enum class CommentType {
        LINE,
        BLOCK_COMMENT,
        JSDOC,
        HTML,
    }

    // debug flags
    val printTrees: Boolean = RhinoConfig.get("rhino.printTrees", false)
    internal val printICode: Boolean = RhinoConfig.get("rhino.printICode", false)
    internal val printNames: Boolean = printTrees || printICode

    /** Token types. These values correspond to JSTokenType values in jsscan.c. */
    const val ERROR = -1 // well-known as the only code < EOF
    const val FIRST_TOKEN = ERROR
    const val EOF = ERROR + 1 // end of file token - (not EOF_CHAR)
    const val EOL = EOF + 1 // end of line

    // Interpreter reuses the following as bytecodes
    const val FIRST_BYTECODE_TOKEN = EOL + 1
    const val ENTERWITH = FIRST_BYTECODE_TOKEN
    const val LEAVEWITH = ENTERWITH + 1
    const val RETURN = LEAVEWITH + 1
    const val GOTO = RETURN + 1
    const val IFEQ = GOTO + 1
    const val IFNE = IFEQ + 1
    const val SETNAME = IFNE + 1
    const val BITOR = SETNAME + 1
    const val BITXOR = BITOR + 1
    const val BITAND = BITXOR + 1
    const val EQ = BITAND + 1
    const val NE = EQ + 1
    const val LT = NE + 1
    const val LE = LT + 1
    const val GT = LE + 1
    const val GE = GT + 1
    const val LSH = GE + 1
    const val RSH = LSH + 1
    const val URSH = RSH + 1
    const val ADD = URSH + 1
    const val SUB = ADD + 1
    const val MUL = SUB + 1
    const val DIV = MUL + 1
    const val MOD = DIV + 1
    const val NOT = MOD + 1
    const val BITNOT = NOT + 1
    const val POS = BITNOT + 1
    const val NEG = POS + 1
    const val NEW = NEG + 1
    const val DELPROP = NEW + 1
    const val TYPEOF = DELPROP + 1
    const val GETPROP = TYPEOF + 1
    const val GETPROPNOWARN = GETPROP + 1
    const val GETPROP_SUPER = GETPROPNOWARN + 1
    const val GETPROPNOWARN_SUPER = GETPROP_SUPER + 1
    const val SETPROP = GETPROPNOWARN_SUPER + 1
    const val SETPROP_SUPER = SETPROP + 1
    const val GETELEM = SETPROP_SUPER + 1
    const val GETELEM_SUPER = GETELEM + 1
    const val SETELEM = GETELEM_SUPER + 1
    const val SETELEM_SUPER = SETELEM + 1
    const val CALL = SETELEM_SUPER + 1
    const val NAME = CALL + 1
    const val NUMBER = NAME + 1
    const val STRING = NUMBER + 1
    const val NULL = STRING + 1
    const val UNDEFINED = NULL + 1
    const val THIS = UNDEFINED + 1
    const val FALSE = THIS + 1
    const val TRUE = FALSE + 1
    const val SHEQ = TRUE + 1 // shallow equality (===)
    const val SHNE = SHEQ + 1 // shallow inequality (!==)
    const val REGEXP = SHNE + 1
    const val BINDNAME = REGEXP + 1
    const val THROW = BINDNAME + 1
    const val RETHROW = THROW + 1 // rethrow caught exception: catch (e if ) use it
    const val IN = RETHROW + 1
    const val INSTANCEOF = IN + 1
    const val LOCAL_LOAD = INSTANCEOF + 1
    const val GETVAR = LOCAL_LOAD + 1
    const val SETVAR = GETVAR + 1
    const val CATCH_SCOPE = SETVAR + 1
    const val ENUM_INIT_KEYS = CATCH_SCOPE + 1
    const val ENUM_INIT_VALUES = ENUM_INIT_KEYS + 1
    const val ENUM_INIT_ARRAY = ENUM_INIT_VALUES + 1
    const val ENUM_INIT_VALUES_IN_ORDER = ENUM_INIT_ARRAY + 1
    const val ENUM_NEXT = ENUM_INIT_VALUES_IN_ORDER + 1
    const val ENUM_ID = ENUM_NEXT + 1
    const val THISFN = ENUM_ID + 1
    const val RETURN_RESULT = THISFN + 1 // to return previously stored return result
    const val ARRAYLIT = RETURN_RESULT + 1 // array literal
    const val OBJECTLIT = ARRAYLIT + 1 // object literal
    const val GET_REF = OBJECTLIT + 1 // *reference
    const val SET_REF = GET_REF + 1 // *reference    = something
    const val DEL_REF = SET_REF + 1 // delete reference
    const val REF_CALL = DEL_REF + 1 // f(args)    = something or f(args)++
    const val REF_SPECIAL = REF_CALL + 1 // reference for special properties like __proto
    const val YIELD = REF_SPECIAL + 1 // JS 1.7 yield pseudo keyword
    const val SUPER = YIELD + 1 // ES6 super keyword
    const val STRICT_SETNAME = SUPER + 1
    const val STRING_CONCAT = STRICT_SETNAME + 1 // string concatenation with toString first semantics
    const val EXP = STRING_CONCAT + 1 // Exponentiation Operator

    // For XML support:
    const val DEFAULTNAMESPACE = EXP + 1 // default xml namespace =
    const val ESCXMLATTR = DEFAULTNAMESPACE + 1
    const val ESCXMLTEXT = ESCXMLATTR + 1
    const val REF_MEMBER = ESCXMLTEXT + 1 // Reference for x.@y, x..y etc.
    const val REF_NS_MEMBER = REF_MEMBER + 1 // Reference for x.ns::y, x..ns::y etc.
    const val REF_NAME = REF_NS_MEMBER + 1 // Reference for @y, @[y] etc.
    const val REF_NS_NAME = REF_NAME + 1 // Reference for ns::y, @ns::y@[y] etc.
    const val BIGINT = REF_NS_NAME + 1 // ES2020 BigInt

    // End of interpreter bytecodes
    const val LAST_BYTECODE_TOKEN = BIGINT
    const val TRY = LAST_BYTECODE_TOKEN + 1
    const val SEMI = TRY + 1 // semicolon
    const val LB = SEMI + 1 // left and right brackets
    const val RB = LB + 1
    const val LC = RB + 1 // left and right curlies (braces)
    const val RC = LC + 1
    const val LP = RC + 1 // left and right parentheses
    const val RP = LP + 1
    const val COMMA = RP + 1 // comma operator
    const val ASSIGN = COMMA + 1 // simple assignment  (=)
    const val ASSIGN_BITOR = ASSIGN + 1 // |=
    const val ASSIGN_LOGICAL_OR = ASSIGN_BITOR + 1 // ||=
    const val ASSIGN_BITXOR = ASSIGN_LOGICAL_OR + 1 // ^=
    const val ASSIGN_BITAND = ASSIGN_BITXOR + 1 // &=
    const val ASSIGN_LOGICAL_AND = ASSIGN_BITAND + 1 // &&=
    const val ASSIGN_LSH = ASSIGN_LOGICAL_AND + 1 // <<=
    const val ASSIGN_RSH = ASSIGN_LSH + 1 // >>=
    const val ASSIGN_URSH = ASSIGN_RSH + 1 // >>>=
    const val ASSIGN_ADD = ASSIGN_URSH + 1 // +=
    const val ASSIGN_SUB = ASSIGN_ADD + 1 // -=
    const val ASSIGN_MUL = ASSIGN_SUB + 1 // *=
    const val ASSIGN_DIV = ASSIGN_MUL + 1 // /=
    const val ASSIGN_MOD = ASSIGN_DIV + 1 // %=
    const val ASSIGN_EXP = ASSIGN_MOD + 1 // **=
    const val ASSIGN_NULLISH = ASSIGN_EXP + 1 // ??=

    const val FIRST_ASSIGN = ASSIGN
    const val LAST_ASSIGN = ASSIGN_NULLISH
    const val HOOK = LAST_ASSIGN + 1 // conditional (?:)
    const val COLON = HOOK + 1
    const val OR = COLON + 1 // logical or (||)
    const val AND = OR + 1 // logical and (&&)
    const val INC = AND + 1 // increment/decrement (++ --)
    const val DEC = INC + 1
    const val DOT = DEC + 1 // member operator (.)
    const val FUNCTION = DOT + 1 // function keyword
    const val EXPORT = FUNCTION + 1 // export keyword
    const val IMPORT = EXPORT + 1 // import keyword
    const val IF = IMPORT + 1 // if keyword
    const val ELSE = IF + 1 // else keyword
    const val SWITCH = ELSE + 1 // switch keyword
    const val CASE = SWITCH + 1 // case keyword
    const val DEFAULT = CASE + 1 // default keyword
    const val WHILE = DEFAULT + 1 // while keyword
    const val DO = WHILE + 1 // do keyword
    const val FOR = DO + 1 // for keyword
    const val BREAK = FOR + 1 // break keyword
    const val CONTINUE = BREAK + 1 // continue keyword
    const val VAR = CONTINUE + 1 // var keyword
    const val WITH = VAR + 1 // with keyword
    const val CATCH = WITH + 1 // catch keyword
    const val FINALLY = CATCH + 1 // finally keyword
    const val VOID = FINALLY + 1 // void keyword
    const val RESERVED = VOID + 1 // reserved keywords
    const val EMPTY = RESERVED + 1
    const val COMPUTED_PROPERTY = EMPTY + 1 // computed property in object initializer [x]

    /* types used for the parse tree - these never get returned by the scanner. */
    const val BLOCK = COMPUTED_PROPERTY + 1 // statement block
    const val LABEL = BLOCK + 1 // label
    const val TARGET = LABEL + 1
    const val LOOP = TARGET + 1
    const val EXPR_VOID = LOOP + 1 // expression statement in functions
    const val EXPR_RESULT = EXPR_VOID + 1 // expression statement in scripts
    const val JSR = EXPR_RESULT + 1
    const val SCRIPT = JSR + 1 // top-level node for entire script
    const val TYPEOFNAME = SCRIPT + 1 // for typeof(simple-name)
    const val USE_STACK = TYPEOFNAME + 1
    const val SETPROP_OP = USE_STACK + 1 // x.y op= something
    const val SETELEM_OP = SETPROP_OP + 1 // x[y] op= something
    const val LOCAL_BLOCK = SETELEM_OP + 1
    const val SET_REF_OP = LOCAL_BLOCK + 1 // *reference op= something

    // For XML support:
    const val DOTDOT = SET_REF_OP + 1 // member operator (..)
    const val COLONCOLON = DOTDOT + 1 // namespace::name
    const val XML = COLONCOLON + 1 // XML type
    const val DOTQUERY = XML + 1 // .() -- e.g., x.emps.emp.(name == "terry")
    const val XMLATTR = DOTQUERY + 1 // @
    const val XMLEND = XMLATTR + 1

    // Optimizer-only-tokens
    const val TO_OBJECT = XMLEND + 1
    const val TO_DOUBLE = TO_OBJECT + 1
    const val GET = TO_DOUBLE + 1 // JS 1.5 get pseudo keyword
    const val SET = GET + 1 // JS 1.5 set pseudo keyword
    const val LET = SET + 1 // JS 1.7 let pseudo keyword
    const val CONST = LET + 1
    const val SETCONST = CONST + 1
    const val SETCONSTVAR = SETCONST + 1
    const val ARRAYCOMP = SETCONSTVAR + 1 // array comprehension
    const val LETEXPR = ARRAYCOMP + 1
    const val WITHEXPR = LETEXPR + 1
    const val DEBUGGER = WITHEXPR + 1
    const val COMMENT = DEBUGGER + 1
    const val GENEXPR = COMMENT + 1
    const val METHOD = GENEXPR + 1 // ES6 MethodDefinition
    const val ARROW = METHOD + 1 // ES6 ArrowFunction
    const val YIELD_STAR = ARROW + 1 // ES6 "yield *", a specialization of yield
    const val TEMPLATE_LITERAL = YIELD_STAR + 1 // template literal
    const val TEMPLATE_CHARS = TEMPLATE_LITERAL + 1 // template literal - literal section
    const val TEMPLATE_LITERAL_SUBST = TEMPLATE_CHARS + 1 // template literal - substitution
    const val TAGGED_TEMPLATE_LITERAL = TEMPLATE_LITERAL_SUBST + 1 // template literal - tagged/handler
    const val DOTDOTDOT = TAGGED_TEMPLATE_LITERAL + 1 // spread/rest ...
    const val NULLISH_COALESCING = DOTDOTDOT + 1 // nullish coalescing (??)
    const val QUESTION_DOT = NULLISH_COALESCING + 1 // optional chaining operator (?.)
    const val LAST_TOKEN = QUESTION_DOT + 1

    /**
     * Returns a name for the token. If the engine is compiled with certain hardcoded
     * debugging flags in this file, it calls [typeToName]; otherwise it returns a string
     * whose value is the token number.
     */
    fun name(token: Int): String {
        if (!printNames) {
            return token.toString()
        }
        return typeToName(token)
    }

    /**
     * Always returns a human-readable string for the token name. For instance, [FINALLY]
     * has the name "FINALLY".
     */
    fun typeToName(token: Int): String = when (token) {
        ERROR -> "ERROR"
        EOF -> "EOF"
        EOL -> "EOL"
        ENTERWITH -> "ENTERWITH"
        LEAVEWITH -> "LEAVEWITH"
        RETURN -> "RETURN"
        GOTO -> "GOTO"
        IFEQ -> "IFEQ"
        IFNE -> "IFNE"
        SETNAME -> "SETNAME"
        STRICT_SETNAME -> "STRICT_SETNAME"
        BITOR -> "BITOR"
        BITXOR -> "BITXOR"
        BITAND -> "BITAND"
        EQ -> "EQ"
        NE -> "NE"
        LT -> "LT"
        LE -> "LE"
        GT -> "GT"
        GE -> "GE"
        LSH -> "LSH"
        RSH -> "RSH"
        URSH -> "URSH"
        ADD -> "ADD"
        SUB -> "SUB"
        MUL -> "MUL"
        DIV -> "DIV"
        MOD -> "MOD"
        NOT -> "NOT"
        BITNOT -> "BITNOT"
        POS -> "POS"
        NEG -> "NEG"
        NEW -> "NEW"
        DELPROP -> "DELPROP"
        TYPEOF -> "TYPEOF"
        GETPROP -> "GETPROP"
        GETPROPNOWARN -> "GETPROPNOWARN"
        GETPROP_SUPER -> "GETPROP_SUPER"
        GETPROPNOWARN_SUPER -> "GETPROPNOWARN_SUPER"
        SETPROP -> "SETPROP"
        SETPROP_SUPER -> "SETPROP_SUPER"
        GETELEM -> "GETELEM"
        GETELEM_SUPER -> "GETELEM_SUPER"
        SETELEM -> "SETELEM"
        SETELEM_SUPER -> "SETELEM_SUPER"
        CALL -> "CALL"
        NAME -> "NAME"
        NUMBER -> "NUMBER"
        STRING -> "STRING"
        NULL -> "NULL"
        UNDEFINED -> "UNDEFINED"
        THIS -> "THIS"
        FALSE -> "FALSE"
        TRUE -> "TRUE"
        SHEQ -> "SHEQ"
        SHNE -> "SHNE"
        REGEXP -> "REGEXP"
        BINDNAME -> "BINDNAME"
        THROW -> "THROW"
        RETHROW -> "RETHROW"
        IN -> "IN"
        INSTANCEOF -> "INSTANCEOF"
        LOCAL_LOAD -> "LOCAL_LOAD"
        GETVAR -> "GETVAR"
        SETVAR -> "SETVAR"
        CATCH_SCOPE -> "CATCH_SCOPE"
        ENUM_INIT_KEYS -> "ENUM_INIT_KEYS"
        ENUM_INIT_VALUES -> "ENUM_INIT_VALUES"
        ENUM_INIT_ARRAY -> "ENUM_INIT_ARRAY"
        ENUM_INIT_VALUES_IN_ORDER -> "ENUM_INIT_VALUES_IN_ORDER"
        ENUM_NEXT -> "ENUM_NEXT"
        ENUM_ID -> "ENUM_ID"
        THISFN -> "THISFN"
        RETURN_RESULT -> "RETURN_RESULT"
        ARRAYLIT -> "ARRAYLIT"
        OBJECTLIT -> "OBJECTLIT"
        GET_REF -> "GET_REF"
        SET_REF -> "SET_REF"
        DEL_REF -> "DEL_REF"
        REF_CALL -> "REF_CALL"
        REF_SPECIAL -> "REF_SPECIAL"
        DEFAULTNAMESPACE -> "DEFAULTNAMESPACE"
        ESCXMLTEXT -> "ESCXMLTEXT"
        ESCXMLATTR -> "ESCXMLATTR"
        REF_MEMBER -> "REF_MEMBER"
        REF_NS_MEMBER -> "REF_NS_MEMBER"
        REF_NAME -> "REF_NAME"
        REF_NS_NAME -> "REF_NS_NAME"
        TRY -> "TRY"
        SEMI -> "SEMI"
        LB -> "LB"
        RB -> "RB"
        LC -> "LC"
        RC -> "RC"
        LP -> "LP"
        RP -> "RP"
        COMMA -> "COMMA"
        ASSIGN -> "ASSIGN"
        ASSIGN_BITOR -> "ASSIGN_BITOR"
        ASSIGN_LOGICAL_OR -> "ASSIGN_LOGICAL_OR"
        ASSIGN_BITXOR -> "ASSIGN_BITXOR"
        ASSIGN_BITAND -> "ASSIGN_BITAND"
        ASSIGN_LOGICAL_AND -> "ASSIGN_LOGICAL_AND"
        ASSIGN_LSH -> "ASSIGN_LSH"
        ASSIGN_RSH -> "ASSIGN_RSH"
        ASSIGN_URSH -> "ASSIGN_URSH"
        ASSIGN_ADD -> "ASSIGN_ADD"
        ASSIGN_SUB -> "ASSIGN_SUB"
        ASSIGN_MUL -> "ASSIGN_MUL"
        ASSIGN_DIV -> "ASSIGN_DIV"
        ASSIGN_MOD -> "ASSIGN_MOD"
        ASSIGN_EXP -> "ASSIGN_EXP"
        ASSIGN_NULLISH -> "ASSIGN_NULLISH"
        HOOK -> "HOOK"
        COLON -> "COLON"
        OR -> "OR"
        NULLISH_COALESCING -> "NULLISH_COALESCING"
        AND -> "AND"
        INC -> "INC"
        DEC -> "DEC"
        DOT -> "DOT"
        FUNCTION -> "FUNCTION"
        EXPORT -> "EXPORT"
        IMPORT -> "IMPORT"
        IF -> "IF"
        ELSE -> "ELSE"
        SWITCH -> "SWITCH"
        CASE -> "CASE"
        DEFAULT -> "DEFAULT"
        WHILE -> "WHILE"
        DO -> "DO"
        FOR -> "FOR"
        BREAK -> "BREAK"
        CONTINUE -> "CONTINUE"
        VAR -> "VAR"
        WITH -> "WITH"
        CATCH -> "CATCH"
        FINALLY -> "FINALLY"
        VOID -> "VOID"
        RESERVED -> "RESERVED"
        EMPTY -> "EMPTY"
        COMPUTED_PROPERTY -> "COMPUTED_PROPERTY"
        BLOCK -> "BLOCK"
        LABEL -> "LABEL"
        TARGET -> "TARGET"
        LOOP -> "LOOP"
        EXPR_VOID -> "EXPR_VOID"
        EXPR_RESULT -> "EXPR_RESULT"
        JSR -> "JSR"
        SCRIPT -> "SCRIPT"
        TYPEOFNAME -> "TYPEOFNAME"
        USE_STACK -> "USE_STACK"
        SETPROP_OP -> "SETPROP_OP"
        SETELEM_OP -> "SETELEM_OP"
        LOCAL_BLOCK -> "LOCAL_BLOCK"
        SET_REF_OP -> "SET_REF_OP"
        DOTDOT -> "DOTDOT"
        COLONCOLON -> "COLONCOLON"
        XML -> "XML"
        DOTQUERY -> "DOTQUERY"
        XMLATTR -> "XMLATTR"
        XMLEND -> "XMLEND"
        TO_OBJECT -> "TO_OBJECT"
        TO_DOUBLE -> "TO_DOUBLE"
        GET -> "GET"
        SET -> "SET"
        LET -> "LET"
        YIELD -> "YIELD"
        SUPER -> "SUPER"
        EXP -> "EXP"
        CONST -> "CONST"
        SETCONST -> "SETCONST"
        SETCONSTVAR -> "SETCONSTVAR"
        ARRAYCOMP -> "ARRAYCOMP"
        WITHEXPR -> "WITHEXPR"
        LETEXPR -> "LETEXPR"
        DEBUGGER -> "DEBUGGER"
        COMMENT -> "COMMENT"
        GENEXPR -> "GENEXPR"
        METHOD -> "METHOD"
        ARROW -> "ARROW"
        YIELD_STAR -> "YIELD_STAR"
        BIGINT -> "BIGINT"
        TEMPLATE_LITERAL -> "TEMPLATE_LITERAL"
        STRING_CONCAT -> "STRING_CONCAT"
        TEMPLATE_CHARS -> "TEMPLATE_CHARS"
        TEMPLATE_LITERAL_SUBST -> "TEMPLATE_LITERAL_SUBST"
        TAGGED_TEMPLATE_LITERAL -> "TAGGED_TEMPLATE_LITERAL"
        DOTDOTDOT -> "DOTDOTDOT"
        QUESTION_DOT -> "QUESTION_DOT"
        // Token without name
        else -> throw IllegalStateException(token.toString())
    }

    /** Convert a keyword token to a name string. */
    fun keywordToName(token: Int): String? = when (token) {
        BREAK -> "break"
        CASE -> "case"
        CONTINUE -> "continue"
        DEFAULT -> "default"
        DELPROP -> "delete"
        DO -> "do"
        ELSE -> "else"
        FALSE -> "false"
        FOR -> "for"
        FUNCTION -> "function"
        IF -> "if"
        IN -> "in"
        LET -> "let"
        NEW -> "new"
        NULL -> "null"
        RETURN -> "return"
        SWITCH -> "switch"
        THIS -> "this"
        TRUE -> "true"
        TYPEOF -> "typeof"
        UNDEFINED -> "undefined"
        VAR -> "var"
        VOID -> "void"
        WHILE -> "while"
        WITH -> "with"
        YIELD -> "yield"
        SUPER -> "super"
        CATCH -> "catch"
        CONST -> "const"
        DEBUGGER -> "debugger"
        FINALLY -> "finally"
        INSTANCEOF -> "instanceof"
        THROW -> "throw"
        TRY -> "try"
        else -> null
    }

    /** Return true if the passed code is a valid Token constant. */
    fun isValidToken(code: Int): Boolean = code in ERROR..LAST_TOKEN
}
