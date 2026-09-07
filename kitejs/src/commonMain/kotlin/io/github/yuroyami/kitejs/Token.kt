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
public object Token {

    /** Which kind of comment the lexer found. */
    public enum class CommentType {
        LINE,
        BLOCK_COMMENT,
        JSDOC,
        HTML,
    }

    // debug flags
    public val printTrees: Boolean = RhinoConfig.get("rhino.printTrees", false)
    internal val printICode: Boolean = RhinoConfig.get("rhino.printICode", false)
    internal val printNames: Boolean = printTrees || printICode

    /** Token types. These values correspond to JSTokenType values in jsscan.c. */
    public const val ERROR: Int = -1 // well-known as the only code < EOF
    public const val FIRST_TOKEN: Int = ERROR
    public const val EOF: Int = ERROR + 1 // end of file token - (not EOF_CHAR)
    public const val EOL: Int = EOF + 1 // end of line

    // Interpreter reuses the following as bytecodes
    public const val FIRST_BYTECODE_TOKEN: Int = EOL + 1
    public const val ENTERWITH: Int = FIRST_BYTECODE_TOKEN
    public const val LEAVEWITH: Int = ENTERWITH + 1
    public const val RETURN: Int = LEAVEWITH + 1
    public const val GOTO: Int = RETURN + 1
    public const val IFEQ: Int = GOTO + 1
    public const val IFNE: Int = IFEQ + 1
    public const val SETNAME: Int = IFNE + 1
    public const val BITOR: Int = SETNAME + 1
    public const val BITXOR: Int = BITOR + 1
    public const val BITAND: Int = BITXOR + 1
    public const val EQ: Int = BITAND + 1
    public const val NE: Int = EQ + 1
    public const val LT: Int = NE + 1
    public const val LE: Int = LT + 1
    public const val GT: Int = LE + 1
    public const val GE: Int = GT + 1
    public const val LSH: Int = GE + 1
    public const val RSH: Int = LSH + 1
    public const val URSH: Int = RSH + 1
    public const val ADD: Int = URSH + 1
    public const val SUB: Int = ADD + 1
    public const val MUL: Int = SUB + 1
    public const val DIV: Int = MUL + 1
    public const val MOD: Int = DIV + 1
    public const val NOT: Int = MOD + 1
    public const val BITNOT: Int = NOT + 1
    public const val POS: Int = BITNOT + 1
    public const val NEG: Int = POS + 1
    public const val NEW: Int = NEG + 1
    public const val DELPROP: Int = NEW + 1
    public const val TYPEOF: Int = DELPROP + 1
    public const val GETPROP: Int = TYPEOF + 1
    public const val GETPROPNOWARN: Int = GETPROP + 1
    public const val GETPROP_SUPER: Int = GETPROPNOWARN + 1
    public const val GETPROPNOWARN_SUPER: Int = GETPROP_SUPER + 1
    public const val SETPROP: Int = GETPROPNOWARN_SUPER + 1
    public const val SETPROP_SUPER: Int = SETPROP + 1
    public const val GETELEM: Int = SETPROP_SUPER + 1
    public const val GETELEM_SUPER: Int = GETELEM + 1
    public const val SETELEM: Int = GETELEM_SUPER + 1
    public const val SETELEM_SUPER: Int = SETELEM + 1
    public const val CALL: Int = SETELEM_SUPER + 1
    public const val NAME: Int = CALL + 1
    public const val NUMBER: Int = NAME + 1
    public const val STRING: Int = NUMBER + 1
    public const val NULL: Int = STRING + 1
    public const val UNDEFINED: Int = NULL + 1
    public const val THIS: Int = UNDEFINED + 1
    public const val FALSE: Int = THIS + 1
    public const val TRUE: Int = FALSE + 1
    public const val SHEQ: Int = TRUE + 1 // shallow equality (===)
    public const val SHNE: Int = SHEQ + 1 // shallow inequality (!==)
    public const val REGEXP: Int = SHNE + 1
    public const val BINDNAME: Int = REGEXP + 1
    public const val THROW: Int = BINDNAME + 1
    public const val RETHROW: Int = THROW + 1 // rethrow caught exception: catch (e if ) use it
    public const val IN: Int = RETHROW + 1
    public const val INSTANCEOF: Int = IN + 1
    public const val LOCAL_LOAD: Int = INSTANCEOF + 1
    public const val GETVAR: Int = LOCAL_LOAD + 1
    public const val SETVAR: Int = GETVAR + 1
    public const val CATCH_SCOPE: Int = SETVAR + 1
    public const val ENUM_INIT_KEYS: Int = CATCH_SCOPE + 1
    public const val ENUM_INIT_VALUES: Int = ENUM_INIT_KEYS + 1
    public const val ENUM_INIT_ARRAY: Int = ENUM_INIT_VALUES + 1
    public const val ENUM_INIT_VALUES_IN_ORDER: Int = ENUM_INIT_ARRAY + 1
    public const val ENUM_NEXT: Int = ENUM_INIT_VALUES_IN_ORDER + 1
    public const val ENUM_ID: Int = ENUM_NEXT + 1
    public const val THISFN: Int = ENUM_ID + 1
    public const val RETURN_RESULT: Int = THISFN + 1 // to return previously stored return result
    public const val ARRAYLIT: Int = RETURN_RESULT + 1 // array literal
    public const val OBJECTLIT: Int = ARRAYLIT + 1 // object literal
    public const val GET_REF: Int = OBJECTLIT + 1 // *reference
    public const val SET_REF: Int = GET_REF + 1 // *reference    = something
    public const val DEL_REF: Int = SET_REF + 1 // delete reference
    public const val REF_CALL: Int = DEL_REF + 1 // f(args)    = something or f(args)++
    public const val REF_SPECIAL: Int = REF_CALL + 1 // reference for special properties like __proto
    public const val YIELD: Int = REF_SPECIAL + 1 // JS 1.7 yield pseudo keyword
    public const val SUPER: Int = YIELD + 1 // ES6 super keyword
    public const val STRICT_SETNAME: Int = SUPER + 1
    public const val STRING_CONCAT: Int = STRICT_SETNAME + 1 // string concatenation with toString first semantics
    public const val EXP: Int = STRING_CONCAT + 1 // Exponentiation Operator

    // For XML support:
    public const val DEFAULTNAMESPACE: Int = EXP + 1 // default xml namespace =
    public const val ESCXMLATTR: Int = DEFAULTNAMESPACE + 1
    public const val ESCXMLTEXT: Int = ESCXMLATTR + 1
    public const val REF_MEMBER: Int = ESCXMLTEXT + 1 // Reference for x.@y, x..y etc.
    public const val REF_NS_MEMBER: Int = REF_MEMBER + 1 // Reference for x.ns::y, x..ns::y etc.
    public const val REF_NAME: Int = REF_NS_MEMBER + 1 // Reference for @y, @[y] etc.
    public const val REF_NS_NAME: Int = REF_NAME + 1 // Reference for ns::y, @ns::y@[y] etc.
    public const val BIGINT: Int = REF_NS_NAME + 1 // ES2020 BigInt

    // End of interpreter bytecodes
    public const val LAST_BYTECODE_TOKEN: Int = BIGINT
    public const val TRY: Int = LAST_BYTECODE_TOKEN + 1
    public const val SEMI: Int = TRY + 1 // semicolon
    public const val LB: Int = SEMI + 1 // left and right brackets
    public const val RB: Int = LB + 1
    public const val LC: Int = RB + 1 // left and right curlies (braces)
    public const val RC: Int = LC + 1
    public const val LP: Int = RC + 1 // left and right parentheses
    public const val RP: Int = LP + 1
    public const val COMMA: Int = RP + 1 // comma operator
    public const val ASSIGN: Int = COMMA + 1 // simple assignment  (=)
    public const val ASSIGN_BITOR: Int = ASSIGN + 1 // |=
    public const val ASSIGN_LOGICAL_OR: Int = ASSIGN_BITOR + 1 // ||=
    public const val ASSIGN_BITXOR: Int = ASSIGN_LOGICAL_OR + 1 // ^=
    public const val ASSIGN_BITAND: Int = ASSIGN_BITXOR + 1 // &=
    public const val ASSIGN_LOGICAL_AND: Int = ASSIGN_BITAND + 1 // &&=
    public const val ASSIGN_LSH: Int = ASSIGN_LOGICAL_AND + 1 // <<=
    public const val ASSIGN_RSH: Int = ASSIGN_LSH + 1 // >>=
    public const val ASSIGN_URSH: Int = ASSIGN_RSH + 1 // >>>=
    public const val ASSIGN_ADD: Int = ASSIGN_URSH + 1 // +=
    public const val ASSIGN_SUB: Int = ASSIGN_ADD + 1 // -=
    public const val ASSIGN_MUL: Int = ASSIGN_SUB + 1 // *=
    public const val ASSIGN_DIV: Int = ASSIGN_MUL + 1 // /=
    public const val ASSIGN_MOD: Int = ASSIGN_DIV + 1 // %=
    public const val ASSIGN_EXP: Int = ASSIGN_MOD + 1 // **=
    public const val ASSIGN_NULLISH: Int = ASSIGN_EXP + 1 // ??=

    public const val FIRST_ASSIGN: Int = ASSIGN
    public const val LAST_ASSIGN: Int = ASSIGN_NULLISH
    public const val HOOK: Int = LAST_ASSIGN + 1 // conditional (?:)
    public const val COLON: Int = HOOK + 1
    public const val OR: Int = COLON + 1 // logical or (||)
    public const val AND: Int = OR + 1 // logical and (&&)
    public const val INC: Int = AND + 1 // increment/decrement (++ --)
    public const val DEC: Int = INC + 1
    public const val DOT: Int = DEC + 1 // member operator (.)
    public const val FUNCTION: Int = DOT + 1 // function keyword
    public const val EXPORT: Int = FUNCTION + 1 // export keyword
    public const val IMPORT: Int = EXPORT + 1 // import keyword
    public const val IF: Int = IMPORT + 1 // if keyword
    public const val ELSE: Int = IF + 1 // else keyword
    public const val SWITCH: Int = ELSE + 1 // switch keyword
    public const val CASE: Int = SWITCH + 1 // case keyword
    public const val DEFAULT: Int = CASE + 1 // default keyword
    public const val WHILE: Int = DEFAULT + 1 // while keyword
    public const val DO: Int = WHILE + 1 // do keyword
    public const val FOR: Int = DO + 1 // for keyword
    public const val BREAK: Int = FOR + 1 // break keyword
    public const val CONTINUE: Int = BREAK + 1 // continue keyword
    public const val VAR: Int = CONTINUE + 1 // var keyword
    public const val WITH: Int = VAR + 1 // with keyword
    public const val CATCH: Int = WITH + 1 // catch keyword
    public const val FINALLY: Int = CATCH + 1 // finally keyword
    public const val VOID: Int = FINALLY + 1 // void keyword
    public const val RESERVED: Int = VOID + 1 // reserved keywords
    public const val EMPTY: Int = RESERVED + 1
    public const val COMPUTED_PROPERTY: Int = EMPTY + 1 // computed property in object initializer [x]

    /* types used for the parse tree - these never get returned by the scanner. */
    public const val BLOCK: Int = COMPUTED_PROPERTY + 1 // statement block
    public const val LABEL: Int = BLOCK + 1 // label
    public const val TARGET: Int = LABEL + 1
    public const val LOOP: Int = TARGET + 1
    public const val EXPR_VOID: Int = LOOP + 1 // expression statement in functions
    public const val EXPR_RESULT: Int = EXPR_VOID + 1 // expression statement in scripts
    public const val JSR: Int = EXPR_RESULT + 1
    public const val SCRIPT: Int = JSR + 1 // top-level node for entire script
    public const val TYPEOFNAME: Int = SCRIPT + 1 // for typeof(simple-name)
    public const val USE_STACK: Int = TYPEOFNAME + 1
    public const val SETPROP_OP: Int = USE_STACK + 1 // x.y op= something
    public const val SETELEM_OP: Int = SETPROP_OP + 1 // x[y] op= something
    public const val LOCAL_BLOCK: Int = SETELEM_OP + 1
    public const val SET_REF_OP: Int = LOCAL_BLOCK + 1 // *reference op= something

    // For XML support:
    public const val DOTDOT: Int = SET_REF_OP + 1 // member operator (..)
    public const val COLONCOLON: Int = DOTDOT + 1 // namespace::name
    public const val XML: Int = COLONCOLON + 1 // XML type
    public const val DOTQUERY: Int = XML + 1 // .() -- e.g., x.emps.emp.(name == "terry")
    public const val XMLATTR: Int = DOTQUERY + 1 // @
    public const val XMLEND: Int = XMLATTR + 1

    // Optimizer-only-tokens
    public const val TO_OBJECT: Int = XMLEND + 1
    public const val TO_DOUBLE: Int = TO_OBJECT + 1
    public const val GET: Int = TO_DOUBLE + 1 // JS 1.5 get pseudo keyword
    public const val SET: Int = GET + 1 // JS 1.5 set pseudo keyword
    public const val LET: Int = SET + 1 // JS 1.7 let pseudo keyword
    public const val CONST: Int = LET + 1
    public const val SETCONST: Int = CONST + 1
    public const val SETCONSTVAR: Int = SETCONST + 1
    public const val ARRAYCOMP: Int = SETCONSTVAR + 1 // array comprehension
    public const val LETEXPR: Int = ARRAYCOMP + 1
    public const val WITHEXPR: Int = LETEXPR + 1
    public const val DEBUGGER: Int = WITHEXPR + 1
    public const val COMMENT: Int = DEBUGGER + 1
    public const val GENEXPR: Int = COMMENT + 1
    public const val METHOD: Int = GENEXPR + 1 // ES6 MethodDefinition
    public const val ARROW: Int = METHOD + 1 // ES6 ArrowFunction
    public const val YIELD_STAR: Int = ARROW + 1 // ES6 "yield *", a specialization of yield
    public const val TEMPLATE_LITERAL: Int = YIELD_STAR + 1 // template literal
    public const val TEMPLATE_CHARS: Int = TEMPLATE_LITERAL + 1 // template literal - literal section
    public const val TEMPLATE_LITERAL_SUBST: Int = TEMPLATE_CHARS + 1 // template literal - substitution
    public const val TAGGED_TEMPLATE_LITERAL: Int = TEMPLATE_LITERAL_SUBST + 1 // template literal - tagged/handler
    public const val DOTDOTDOT: Int = TAGGED_TEMPLATE_LITERAL + 1 // spread/rest ...
    public const val NULLISH_COALESCING: Int = DOTDOTDOT + 1 // nullish coalescing (??)
    public const val QUESTION_DOT: Int = NULLISH_COALESCING + 1 // optional chaining operator (?.)
    public const val LAST_TOKEN: Int = QUESTION_DOT + 1

    /**
     * Returns a name for the token. If the engine is compiled with certain hardcoded
     * debugging flags in this file, it calls [typeToName]; otherwise it returns a string
     * whose value is the token number.
     */
    public fun name(token: Int): String {
        if (!printNames) {
            return token.toString()
        }
        return typeToName(token)
    }

    /**
     * Always returns a human-readable string for the token name. For instance, [FINALLY]
     * has the name "FINALLY".
     */
    public fun typeToName(token: Int): String = when (token) {
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
    public fun keywordToName(token: Int): String? = when (token) {
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
    public fun isValidToken(code: Int): Boolean = code in ERROR..LAST_TOKEN
}
