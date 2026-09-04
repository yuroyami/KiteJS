/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The interpreter's own opcodes, on top of the token codes.
 *
 * They count down from 0, so they never collide with the bytecode tokens, which run upward from
 * [Token.FIRST_BYTECODE_TOKEN]. That is why the token numbering had to be pinned exactly in
 * phase 0. The code generator extends this class, so the constants are reachable unqualified.
 */
internal abstract class Icode {

    companion object {
        // delete operator used on a name
        const val Icode_DELNAME = 0
        // Stack: ... value1 -> ... value1 value1
        const val Icode_DUP = Icode_DELNAME - 1
        // Stack: ... value2 value1 -> ... value2 value1 value2 value1
        const val Icode_DUP2 = Icode_DUP - 1
        // Stack: ... value2 value1 -> ... value1 value2
        const val Icode_SWAP = Icode_DUP2 - 1
        // Stack: ... value1 -> ...
        const val Icode_POP = Icode_SWAP - 1
        // Store stack top into return register and then pop it
        const val Icode_POP_RESULT = Icode_POP - 1
        // To jump conditionally and pop additional stack value
        const val Icode_IFEQ_POP = Icode_POP_RESULT - 1
        // various types of ++/--
        const val Icode_VAR_INC_DEC = Icode_IFEQ_POP - 1
        const val Icode_NAME_INC_DEC = Icode_VAR_INC_DEC - 1
        const val Icode_PROP_INC_DEC = Icode_NAME_INC_DEC - 1
        const val Icode_ELEM_INC_DEC = Icode_PROP_INC_DEC - 1
        const val Icode_REF_INC_DEC = Icode_ELEM_INC_DEC - 1
        // load/save scope from/to local
        const val Icode_SCOPE_LOAD = Icode_REF_INC_DEC - 1
        const val Icode_SCOPE_SAVE = Icode_SCOPE_LOAD - 1
        const val Icode_TYPEOFNAME = Icode_SCOPE_SAVE - 1
        // helper for function calls
        const val Icode_NAME_AND_THIS = Icode_TYPEOFNAME - 1
        const val Icode_PROP_AND_THIS = Icode_NAME_AND_THIS - 1
        const val Icode_ELEM_AND_THIS = Icode_PROP_AND_THIS - 1
        const val Icode_VALUE_AND_THIS = Icode_ELEM_AND_THIS - 1
        const val Icode_NAME_AND_THIS_OPTIONAL = Icode_VALUE_AND_THIS - 1
        const val Icode_PROP_AND_THIS_OPTIONAL = Icode_NAME_AND_THIS_OPTIONAL - 1
        const val Icode_ELEM_AND_THIS_OPTIONAL = Icode_PROP_AND_THIS_OPTIONAL - 1
        const val Icode_VALUE_AND_THIS_OPTIONAL = Icode_ELEM_AND_THIS_OPTIONAL - 1
        // Create closure object for nested functions
        const val Icode_CLOSURE_EXPR = Icode_VALUE_AND_THIS_OPTIONAL - 1
        const val Icode_CLOSURE_STMT = Icode_CLOSURE_EXPR - 1
        // Special calls
        const val Icode_CALLSPECIAL = Icode_CLOSURE_STMT - 1
        const val Icode_CALLSPECIAL_OPTIONAL = Icode_CALLSPECIAL - 1
        // To return undefined value
        const val Icode_RETUNDEF = Icode_CALLSPECIAL_OPTIONAL - 1
        // Exception handling implementation
        const val Icode_GOSUB = Icode_RETUNDEF - 1
        const val Icode_STARTSUB = Icode_GOSUB - 1
        const val Icode_RETSUB = Icode_STARTSUB - 1
        // To indicating a line number change in icodes.
        const val Icode_LINE = Icode_RETSUB - 1
        // To store shorts and ints inline
        const val Icode_SHORTNUMBER = Icode_LINE - 1
        const val Icode_INTNUMBER = Icode_SHORTNUMBER - 1
        // To create and populate array to hold values for [] and {} literals
        const val Icode_LITERAL_NEW_OBJECT = Icode_INTNUMBER - 1
        const val Icode_LITERAL_NEW_ARRAY = Icode_LITERAL_NEW_OBJECT - 1
        const val Icode_LITERAL_SET = Icode_LITERAL_NEW_ARRAY - 1
        const val Icode_METHOD_EXPR = Icode_LITERAL_SET - 1
        // Array literal with skipped index like [1,,2]
        const val Icode_SPARE_ARRAYLIT = Icode_METHOD_EXPR - 1
        // Load index register to prepare for the following index operation
        const val Icode_REG_IND_C0 = Icode_SPARE_ARRAYLIT - 1
        const val Icode_REG_IND_C1 = Icode_REG_IND_C0 - 1
        const val Icode_REG_IND_C2 = Icode_REG_IND_C1 - 1
        const val Icode_REG_IND_C3 = Icode_REG_IND_C2 - 1
        const val Icode_REG_IND_C4 = Icode_REG_IND_C3 - 1
        const val Icode_REG_IND_C5 = Icode_REG_IND_C4 - 1
        const val Icode_REG_IND1 = Icode_REG_IND_C5 - 1
        const val Icode_REG_IND2 = Icode_REG_IND1 - 1
        const val Icode_REG_IND4 = Icode_REG_IND2 - 1
        // Load string register to prepare for the following string operation
        const val Icode_REG_STR_C0 = Icode_REG_IND4 - 1
        const val Icode_REG_STR_C1 = Icode_REG_STR_C0 - 1
        const val Icode_REG_STR_C2 = Icode_REG_STR_C1 - 1
        const val Icode_REG_STR_C3 = Icode_REG_STR_C2 - 1
        const val Icode_REG_STR1 = Icode_REG_STR_C3 - 1
        const val Icode_REG_STR2 = Icode_REG_STR1 - 1
        const val Icode_REG_STR4 = Icode_REG_STR2 - 1
        // Version of getvar/setvar that read var index directly from bytecode
        const val Icode_GETVAR1 = Icode_REG_STR4 - 1
        const val Icode_SETVAR1 = Icode_GETVAR1 - 1
        // Load undefined
        const val Icode_UNDEF = Icode_SETVAR1 - 1
        const val Icode_ZERO = Icode_UNDEF - 1
        const val Icode_ONE = Icode_ZERO - 1
        // entrance and exit from .()
        const val Icode_ENTERDQ = Icode_ONE - 1
        const val Icode_LEAVEDQ = Icode_ENTERDQ - 1
        const val Icode_TAIL_CALL = Icode_LEAVEDQ - 1
        // Clear local to allow GC its context
        const val Icode_LOCAL_CLEAR = Icode_TAIL_CALL - 1
        // Literal get/set
        const val Icode_LITERAL_GETTER = Icode_LOCAL_CLEAR - 1
        const val Icode_LITERAL_SETTER = Icode_LITERAL_GETTER - 1
        // const
        const val Icode_SETCONST = Icode_LITERAL_SETTER - 1
        const val Icode_SETCONSTVAR = Icode_SETCONST - 1
        const val Icode_SETCONSTVAR1 = Icode_SETCONSTVAR - 1
        // Generator opcodes (along with Token.YIELD)
        const val Icode_GENERATOR = Icode_SETCONSTVAR1 - 1
        const val Icode_GENERATOR_END = Icode_GENERATOR - 1
        const val Icode_DEBUGGER = Icode_GENERATOR_END - 1
        const val Icode_GENERATOR_RETURN = Icode_DEBUGGER - 1
        const val Icode_YIELD_STAR = Icode_GENERATOR_RETURN - 1
        // Load BigInt register to prepare for the following BigInt operation
        const val Icode_REG_BIGINT_C0 = Icode_YIELD_STAR - 1
        const val Icode_REG_BIGINT_C1 = Icode_REG_BIGINT_C0 - 1
        const val Icode_REG_BIGINT_C2 = Icode_REG_BIGINT_C1 - 1
        const val Icode_REG_BIGINT_C3 = Icode_REG_BIGINT_C2 - 1
        const val Icode_REG_BIGINT1 = Icode_REG_BIGINT_C3 - 1
        const val Icode_REG_BIGINT2 = Icode_REG_BIGINT1 - 1
        const val Icode_REG_BIGINT4 = Icode_REG_BIGINT2 - 1
        // Call to GetTemplateLiteralCallSite
        const val Icode_TEMPLATE_LITERAL_CALLSITE = Icode_REG_BIGINT4 - 1
        const val Icode_LITERAL_KEY_SET = Icode_TEMPLATE_LITERAL_CALLSITE - 1
        // Jump if stack head is null or undefined
        const val Icode_IF_NULL_UNDEF = Icode_LITERAL_KEY_SET - 1
        const val Icode_IF_NOT_NULL_UNDEF = Icode_IF_NULL_UNDEF - 1
        // Call a method on the super object, i.e. super.foo()
        const val Icode_CALL_ON_SUPER = Icode_IF_NOT_NULL_UNDEF - 1
        // delete super.prop
        const val Icode_DELPROP_SUPER = Icode_CALL_ON_SUPER - 1
        // spread
        const val Icode_SPREAD = Icode_DELPROP_SUPER - 1
        // Last icode
        const val MIN_ICODE = Icode_SPREAD

        fun bytecodeName(bytecode: Int): String {
            if (!validBytecode(bytecode)) {
                throw IllegalArgumentException(bytecode.toString())
            }

            if (!Token.printICode) {
                return bytecode.toString()
            }

            if (validTokenCode(bytecode)) {
                return Token.name(bytecode)
            }

            return when (bytecode) {
            Icode_DELNAME -> "DELNAME"
            Icode_DUP -> "DUP"
            Icode_DUP2 -> "DUP2"
            Icode_SWAP -> "SWAP"
            Icode_POP -> "POP"
            Icode_POP_RESULT -> "POP_RESULT"
            Icode_IFEQ_POP -> "IFEQ_POP"
            Icode_VAR_INC_DEC -> "VAR_INC_DEC"
            Icode_NAME_INC_DEC -> "NAME_INC_DEC"
            Icode_PROP_INC_DEC -> "PROP_INC_DEC"
            Icode_ELEM_INC_DEC -> "ELEM_INC_DEC"
            Icode_REF_INC_DEC -> "REF_INC_DEC"
            Icode_SCOPE_LOAD -> "SCOPE_LOAD"
            Icode_SCOPE_SAVE -> "SCOPE_SAVE"
            Icode_TYPEOFNAME -> "TYPEOFNAME"
            Icode_NAME_AND_THIS -> "NAME_AND_THIS"
            Icode_PROP_AND_THIS -> "PROP_AND_THIS"
            Icode_ELEM_AND_THIS -> "ELEM_AND_THIS"
            Icode_VALUE_AND_THIS -> "VALUE_AND_THIS"
            Icode_NAME_AND_THIS_OPTIONAL -> "NAME_AND_THIS_OPTIONAL"
            Icode_PROP_AND_THIS_OPTIONAL -> "PROP_AND_THIS_OPTIONAL"
            Icode_ELEM_AND_THIS_OPTIONAL -> "ELEM_AND_THIS_OPTIONAL"
            Icode_VALUE_AND_THIS_OPTIONAL -> "VALUE_AND_THIS_OPTIONAL"
            Icode_CLOSURE_EXPR -> "CLOSURE_EXPR"
            Icode_CLOSURE_STMT -> "CLOSURE_STMT"
            Icode_CALLSPECIAL -> "CALLSPECIAL"
            Icode_CALLSPECIAL_OPTIONAL -> "CALLSPECIAL_OPTIONAL"
            Icode_RETUNDEF -> "RETUNDEF"
            Icode_GOSUB -> "GOSUB"
            Icode_STARTSUB -> "STARTSUB"
            Icode_RETSUB -> "RETSUB"
            Icode_LINE -> "LINE"
            Icode_SHORTNUMBER -> "SHORTNUMBER"
            Icode_INTNUMBER -> "INTNUMBER"
            Icode_LITERAL_NEW_OBJECT -> "LITERAL_NEW_OBJECT"
            Icode_LITERAL_NEW_ARRAY -> "LITERAL_NEW_ARRAY"
            Icode_LITERAL_SET -> "LITERAL_SET"
            Icode_METHOD_EXPR -> "METHOD_EXPR"
            Icode_SPARE_ARRAYLIT -> "SPARE_ARRAYLIT"
            Icode_REG_IND_C0 -> "REG_IND_C0"
            Icode_REG_IND_C1 -> "REG_IND_C1"
            Icode_REG_IND_C2 -> "REG_IND_C2"
            Icode_REG_IND_C3 -> "REG_IND_C3"
            Icode_REG_IND_C4 -> "REG_IND_C4"
            Icode_REG_IND_C5 -> "REG_IND_C5"
            Icode_REG_IND1 -> "LOAD_IND1"
            Icode_REG_IND2 -> "LOAD_IND2"
            Icode_REG_IND4 -> "LOAD_IND4"
            Icode_REG_STR_C0 -> "REG_STR_C0"
            Icode_REG_STR_C1 -> "REG_STR_C1"
            Icode_REG_STR_C2 -> "REG_STR_C2"
            Icode_REG_STR_C3 -> "REG_STR_C3"
            Icode_REG_STR1 -> "LOAD_STR1"
            Icode_REG_STR2 -> "LOAD_STR2"
            Icode_REG_STR4 -> "LOAD_STR4"
            Icode_GETVAR1 -> "GETVAR1"
            Icode_SETVAR1 -> "SETVAR1"
            Icode_UNDEF -> "UNDEF"
            Icode_ZERO -> "ZERO"
            Icode_ONE -> "ONE"
            Icode_ENTERDQ -> "ENTERDQ"
            Icode_LEAVEDQ -> "LEAVEDQ"
            Icode_TAIL_CALL -> "TAIL_CALL"
            Icode_LOCAL_CLEAR -> "LOCAL_CLEAR"
            Icode_LITERAL_GETTER -> "LITERAL_GETTER"
            Icode_LITERAL_SETTER -> "LITERAL_SETTER"
            Icode_SETCONST -> "SETCONST"
            Icode_SETCONSTVAR -> "SETCONSTVAR"
            Icode_SETCONSTVAR1 -> "SETCONSTVAR1"
            Icode_GENERATOR -> "GENERATOR"
            Icode_GENERATOR_END -> "GENERATOR_END"
            Icode_DEBUGGER -> "DEBUGGER"
            Icode_GENERATOR_RETURN -> "GENERATOR_RETURN"
            Icode_YIELD_STAR -> "YIELD_STAR"
            Icode_REG_BIGINT_C0 -> "REG_BIGINT_C0"
            Icode_REG_BIGINT_C1 -> "REG_BIGINT_C1"
            Icode_REG_BIGINT_C2 -> "REG_BIGINT_C2"
            Icode_REG_BIGINT_C3 -> "REG_BIGINT_C3"
            Icode_REG_BIGINT1 -> "LOAD_BIGINT1"
            Icode_REG_BIGINT2 -> "LOAD_BIGINT2"
            Icode_REG_BIGINT4 -> "LOAD_BIGINT4"
            Icode_TEMPLATE_LITERAL_CALLSITE -> "TEMPLATE_LITERAL_CALLSITE"
            Icode_LITERAL_KEY_SET -> "LITERAL_KEY_SET"
            Icode_IF_NULL_UNDEF -> "IF_NULL_UNDEF"
            Icode_IF_NOT_NULL_UNDEF -> "IF_NOT_NULL_UNDEF"
            Icode_CALL_ON_SUPER -> "CALL_ON_SUPER"
            Icode_DELPROP_SUPER -> "DELPROP_SUPER"
            Icode_SPREAD -> "SPREAD"
                // An icode with no name.
                else -> throw IllegalStateException(bytecode.toString())
            }
        }

        fun validIcode(icode: Int): Boolean = icode in MIN_ICODE..0

        fun validTokenCode(token: Int): Boolean =
            token in Token.FIRST_BYTECODE_TOKEN..Token.LAST_BYTECODE_TOKEN

        fun validBytecode(bytecode: Int): Boolean = validIcode(bytecode) || validTokenCode(bytecode)
    }
}
