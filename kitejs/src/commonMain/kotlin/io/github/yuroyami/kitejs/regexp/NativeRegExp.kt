/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.regexp

import io.github.yuroyami.kitejs.Characters
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.EcmaError
import io.github.yuroyami.kitejs.IdFunctionObject
import io.github.yuroyami.kitejs.IdScriptableObject
import io.github.yuroyami.kitejs.Kit
import io.github.yuroyami.kitejs.Messages
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.ScriptRuntimeES6
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.Symbol
import io.github.yuroyami.kitejs.SymbolKey
import io.github.yuroyami.kitejs.TopLevel
import io.github.yuroyami.kitejs.Undefined

/**
 * The `RegExp` object: a bytecode compiler for the pattern plus a backtracking matcher that runs it.
 *
 * Nothing here calls a platform regex engine. The pattern is compiled to Rhino's own bytecode and
 * matched by [executeREBytecode], so JVM, JS, iOS and Wasm all answer the same thing.
 */
public open class NativeRegExp : IdScriptableObject {

    internal var re: RECompiled? = null
    internal var lastIndex: Any? = ScriptRuntime.zeroObj
    private var lastIndexAttr: Int = DONTENUM or PERMANENT

    internal constructor() : super()

    internal constructor(scope: Scriptable, regexpCompiled: RECompiled) : super() {
        this.re = regexpCompiled
        setLastIndex(ScriptRuntime.zeroObj)
        ScriptRuntime.setBuiltinProtoAndParent(this, scope, TopLevel.Builtins.RegExp)
    }

    override val className: String
        get() = "RegExp"

    override val typeOf: String
        get() = "object"

    internal fun compile(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        val first = if (args.isNotEmpty()) args[0] else null
        if (args.isNotEmpty() && first is NativeRegExp && (args.size == 1 || args[1] === Undefined.instance)) {
            // Same pattern and no new flags, so there is nothing to compile.
            this.re = first.re
        } else {
            val pattern: String = when {
                args.isEmpty() || first === Undefined.instance -> ""
                first is NativeRegExp -> first.re!!.source.concatToString()
                else -> escapeRegExp(first)
            }

            val flags = if (args.size > 1 && args[1] !== Undefined.instance) ScriptRuntime.toString(args[1]) else null

            // A regexp plus flags is allowed from ES6 on, and rejected before it.
            if (args.isNotEmpty() && first is NativeRegExp && flags != null && cx.languageVersion < Context.VERSION_ES6) {
                throw ScriptRuntime.typeErrorById("msg.bad.regexp.compile")
            }

            this.re = compileRE(cx, pattern, flags, false)
        }
        setLastIndex(ScriptRuntime.zeroObj)
        return this
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append('/')
        val source = re!!.source
        if (source.isNotEmpty()) {
            buf.append(source)
        } else {
            // An empty pattern prints as (?:) so the result parses back to the same thing.
            buf.append("(?:)")
        }
        buf.append('/')
        appendFlags(buf)
        return buf.toString()
    }

    private fun appendFlags(buf: StringBuilder) {
        val flags = re!!.flags
        if ((flags and JSREG_GLOB) != 0) buf.append('g')
        if ((flags and JSREG_FOLD) != 0) buf.append('i')
        if ((flags and JSREG_MULTILINE) != 0) buf.append('m')
        if ((flags and JSREG_DOTALL) != 0) buf.append('s')
        if ((flags and JSREG_STICKY) != 0) buf.append('y')
        if ((flags and JSREG_UNICODE) != 0) buf.append('u')
    }

    internal val flags: Int get() = re!!.flags

    internal fun execSub(cx: Context, scopeObj: Scriptable, args: Array<Any?>, matchType: Int): Any? {
        val reImpl = getImpl(cx)
        val str: String = if (args.isEmpty()) {
            reImpl.input ?: ScriptRuntime.toString(Undefined.instance)
        } else {
            ScriptRuntime.toString(args[0])
        }

        val globalOrSticky = (re!!.flags and JSREG_GLOB) != 0 || (re!!.flags and JSREG_STICKY) != 0
        var d = 0.0
        if (globalOrSticky) {
            d = ScriptRuntime.toInteger(lastIndex)
            if (d < 0 || str.length < d) {
                setLastIndex(ScriptRuntime.zeroObj)
                return null
            }
        }

        val indexp = intArrayOf(d.toInt())
        val rval = executeRegExp(cx, scopeObj, reImpl, str, indexp, matchType)
        if (globalOrSticky) {
            if (rval == null || rval === Undefined.instance) {
                setLastIndex(ScriptRuntime.zeroObj)
            } else {
                setLastIndex(indexp[0].toDouble())
            }
        }
        return rval
    }

    private fun setLastIndex(value: Any?) {
        if ((lastIndexAttr and READONLY) != 0) {
            throw ScriptRuntime.typeErrorById("msg.modify.readonly", "lastIndex")
        }
        lastIndex = value
    }

    // ---- Running a match ---------------------------------------------------------------------

    internal fun executeRegExp(cx: Context, scope: Scriptable, res: RegExpImpl, str: String, indexp: IntArray, matchType: Int): Any? {
        val result = executeRegExpInternal(cx, scope, res, str, indexp, matchType)

        if (result == null) {
            if (matchType != PREFIX) return null
            return Undefined.instance
        }
        if (matchType == TEST) {
            // A test only needs a yes, so no array is built.
            return true
        }

        val captures = result.captures
        val obj = cx.newArray(scope, captures.size + 1)
        obj.put(0, obj, result.match)
        for (i in captures.indices) {
            obj.put(i + 1, obj, captures[i] ?: Undefined.instance)
        }
        obj.put("index", obj, result.index)
        obj.put("input", obj, str)
        if (result.groups.isNotEmpty()) {
            val groups = io.github.yuroyami.kitejs.NativeObject()
            for ((k, v) in result.groups) groups.put(k, groups, v ?: Undefined.instance)
            obj.put("groups", obj, groups)
        } else {
            obj.put("groups", obj, Undefined.instance)
        }
        return obj
    }

    /** [indexp] is one int in and one int out: where to start, then where the match ended. */
    internal fun executeRegExpInternal(cx: Context, scope: Scriptable, res: RegExpImpl, str: String, indexp: IntArray, matchType: Int): ExecResult? {
        val gData = REGlobalData()

        var start = indexp[0]
        val end = str.length
        if (start > end) start = end

        val matches = matchRegExp(cx, gData, re!!, str, start, end, res.multiline)
        if (!matches) return null

        var index = gData.cp
        val ep = index
        indexp[0] = index
        val matchlen = ep - (start + gData.skipped)
        index -= matchlen

        val result: ExecResult = if (matchType == TEST) {
            ExecResult(index, str)
        } else {
            ExecResult(index, str, str.substring(index, index + matchlen))
        }

        val re = re!!
        if (re.parenCount == 0) {
            res.parens = null
            res.lastParen = SubString()
        } else {
            var parsub: SubString? = null
            var namedCaptureGroups: Array<String?>? = null

            if (matchType != TEST) {
                // Named groups are recorded by capture index so they come out in source order.
                namedCaptureGroups = arrayOfNulls(re.parenCount)
                for ((key, indices) in re.namedCaptureGroups) {
                    for (i in indices) namedCaptureGroups[i] = key
                }
            }

            res.parens = arrayOfNulls(re.parenCount)
            for (num in 0 until re.parenCount) {
                val capIndex = gData.parensIndex(num)
                if (capIndex != -1) {
                    val capLength = gData.parensLength(num)
                    parsub = SubString(str, capIndex, capLength)
                    res.parens!![num] = parsub
                    if (matchType != TEST) {
                        result.captures.add(parsub.toString())
                        val name = namedCaptureGroups!![num]
                        if (name != null) result.groups[name] = parsub.toString()
                    }
                } else {
                    result.captures.add(null)
                    if (matchType != TEST) {
                        val name = namedCaptureGroups!![num]
                        if (name != null && !result.groups.containsKey(name)) result.groups[name] = null
                    }
                }
            }
            res.lastParen = parsub
        }

        if (res.lastMatch == null) {
            res.lastMatch = SubString()
            res.leftContext = SubString()
            res.rightContext = SubString()
        }
        res.lastMatch!!.str = str
        res.lastMatch!!.index = index
        res.lastMatch!!.length = matchlen

        res.leftContext!!.str = str
        if (cx.languageVersion == Context.VERSION_1_2) {
            // JS 1.2 defined $` as the left context of the last match, following perl4.
            res.leftContext!!.index = start
            res.leftContext!!.length = gData.skipped
        } else {
            res.leftContext!!.index = 0
            res.leftContext!!.length = start + gData.skipped
        }

        res.rightContext!!.str = str
        res.rightContext!!.index = ep
        res.rightContext!!.length = end - ep

        return result
    }

    // ---- The id-based property surface ---------------------------------------------------------

    override val maxInstanceId: Int get() = MAX_INSTANCE_ID

    override fun findInstanceIdInfo(s: String): Int {
        val id = when (s) {
            "lastIndex" -> Id_lastIndex
            "source" -> Id_source
            "flags" -> Id_flags
            "global" -> Id_global
            "ignoreCase" -> Id_ignoreCase
            "multiline" -> Id_multiline
            "dotAll" -> Id_dotAll
            "sticky" -> Id_sticky
            "unicode" -> Id_unicode
            else -> 0
        }
        if (id == 0) return super.findInstanceIdInfo(s)

        val attr = when (id) {
            Id_lastIndex -> lastIndexAttr
            else -> PERMANENT or READONLY or DONTENUM
        }
        return instanceIdInfo(attr, id)
    }

    override fun getInstanceIdName(id: Int): String = when (id) {
        Id_lastIndex -> "lastIndex"
        Id_source -> "source"
        Id_flags -> "flags"
        Id_global -> "global"
        Id_ignoreCase -> "ignoreCase"
        Id_multiline -> "multiline"
        Id_dotAll -> "dotAll"
        Id_sticky -> "sticky"
        Id_unicode -> "unicode"
        else -> super.getInstanceIdName(id)
    }

    override fun getInstanceIdValue(id: Int): Any? = when (id) {
        Id_lastIndex -> lastIndex
        Id_source -> re!!.source.concatToString()
        Id_flags -> StringBuilder().also { appendFlags(it) }.toString()
        Id_global -> (re!!.flags and JSREG_GLOB) != 0
        Id_ignoreCase -> (re!!.flags and JSREG_FOLD) != 0
        Id_multiline -> (re!!.flags and JSREG_MULTILINE) != 0
        Id_dotAll -> (re!!.flags and JSREG_DOTALL) != 0
        Id_sticky -> (re!!.flags and JSREG_STICKY) != 0
        Id_unicode -> (re!!.flags and JSREG_UNICODE) != 0
        else -> super.getInstanceIdValue(id)
    }

    override fun setInstanceIdValue(id: Int, value: Any?) {
        when (id) {
            Id_lastIndex -> {
                setLastIndex(value)
                return
            }
            Id_source, Id_flags, Id_global, Id_ignoreCase, Id_multiline, Id_dotAll, Id_sticky -> return
        }
        super.setInstanceIdValue(id, value)
    }

    override fun setInstanceIdAttributes(id: Int, attr: Int) {
        if (id == Id_lastIndex) {
            lastIndexAttr = attr
            return
        }
        super.setInstanceIdAttributes(id, attr)
    }

    override fun initPrototypeId(id: Int) {
        when (id) {
            SymbolId_match -> {
                initPrototypeMethod(REGEXP_TAG, id, SymbolKey.MATCH, "[Symbol.match]", 1)
                return
            }
            SymbolId_matchAll -> {
                initPrototypeMethod(REGEXP_TAG, id, SymbolKey.MATCH_ALL, "[Symbol.matchAll]", 1)
                return
            }
            SymbolId_search -> {
                initPrototypeMethod(REGEXP_TAG, id, SymbolKey.SEARCH, "[Symbol.search]", 1)
                return
            }
            SymbolId_replace -> {
                initPrototypeMethod(REGEXP_TAG, id, SymbolKey.REPLACE, "[Symbol.replace]", 2)
                return
            }
            SymbolId_split -> {
                initPrototypeMethod(REGEXP_TAG, id, SymbolKey.SPLIT, "[Symbol.split]", 2)
                return
            }
        }

        val s: String
        val arity: Int
        when (id) {
            Id_compile -> { arity = 2; s = "compile" }
            Id_toString -> { arity = 0; s = "toString" }
            Id_toSource -> { arity = 0; s = "toSource" }
            Id_exec -> { arity = 1; s = "exec" }
            Id_test -> { arity = 1; s = "test" }
            Id_prefix -> { arity = 1; s = "prefix" }
            else -> throw IllegalArgumentException(id.toString())
        }
        initPrototypeMethod(REGEXP_TAG, id, s, arity)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(REGEXP_TAG)) return super.execIdCall(f, cx, scope, thisObj, args)

        return when (f.methodId()) {
            Id_compile -> realThis(thisObj, f).compile(cx, scope, args)
            Id_toString -> {
                // A plain object with source and flags prints like a regexp, which is what
                // RegExp.prototype.toString.call({source, flags}) is expected to do.
                if (thisObj !== scope && thisObj is io.github.yuroyami.kitejs.NativeObject) {
                    val sourceObj = thisObj.get("source", thisObj)
                    val source = if (sourceObj == Scriptable.NOT_FOUND) "undefined" else escapeRegExp(sourceObj)
                    val flagsObj = thisObj.get("flags", thisObj)
                    val flags = if (flagsObj == Scriptable.NOT_FOUND) "undefined" else flagsObj.toString()
                    "/$source/$flags"
                } else {
                    realThis(thisObj, f).toString()
                }
            }
            Id_toSource -> realThis(thisObj, f).toString()
            Id_exec -> js_exec(cx, scope, thisObj, args)
            Id_test -> realThis(thisObj, f).execSub(cx, scope, args, TEST) == true
            Id_prefix -> realThis(thisObj, f).execSub(cx, scope, args, PREFIX)
            SymbolId_match -> js_SymbolMatch(cx, scope, thisObj, args)
            SymbolId_matchAll -> js_SymbolMatchAll(cx, scope, thisObj, args)
            SymbolId_search -> js_SymbolSearch(cx, scope, thisObj, args)
            SymbolId_replace -> js_SymbolReplace(cx, scope, thisObj, args)
            SymbolId_split -> js_SymbolSplit(cx, scope, thisObj, args)
            else -> throw IllegalArgumentException(f.methodId().toString())
        }
    }

    override fun findPrototypeId(k: Symbol): Int = when {
        SymbolKey.MATCH == k -> SymbolId_match
        SymbolKey.MATCH_ALL == k -> SymbolId_matchAll
        SymbolKey.SEARCH == k -> SymbolId_search
        SymbolKey.REPLACE == k -> SymbolId_replace
        SymbolKey.SPLIT == k -> SymbolId_split
        else -> 0
    }

    override fun findPrototypeId(name: String): Int = when (name) {
        "compile" -> Id_compile
        "toString" -> Id_toString
        "toSource" -> Id_toSource
        "exec" -> Id_exec
        "test" -> Id_test
        "prefix" -> Id_prefix
        else -> 0
    }

    // ---- The Symbol protocols String.prototype uses --------------------------------------------

    private fun js_SymbolMatch(cx: Context, scope: Scriptable, thisScriptable: Scriptable?, args: Array<Any?>): Any? {
        val thisObj = ensureScriptableObject(thisScriptable)

        val string = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
        val flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx))
        val fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1

        if (flags.indexOf('g') == -1) return regExpExec(thisObj, string, cx, scope)

        setLastIndexChecked(thisObj, ScriptRuntime.zeroObj)
        val result = cx.newArray(scope, 0)
        var i = 0
        while (true) {
            val match = regExpExec(thisObj, string, cx, scope) ?: return if (i == 0) null else result

            val matchStr = ScriptRuntime.toString(ScriptRuntime.getObjectIndex(match, 0.0, cx, scope))
            result.put(i++, result, matchStr)

            // An empty match would otherwise stall on the same index forever.
            if (matchStr.isEmpty()) {
                val thisIndex = getLastIndex(cx, thisObj)
                setLastIndexChecked(thisObj, ScriptRuntime.advanceStringIndex(string, thisIndex, fullUnicode))
            }
        }
    }

    /** The spec's "this must be an object" step, answering a value the rest of the body can use. */
    private fun requireObject(value: Scriptable?): Scriptable {
        if (!ScriptRuntime.isObject(value)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(value))
        }
        return value!!
    }

    private fun js_SymbolSearch(cx: Context, scope: Scriptable, thisArg: Scriptable?, args: Array<Any?>): Any? {
        val thisObj = requireObject(thisArg)

        val string = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
        val previousLastIndex = getLastIndex(cx, thisObj)
        if (previousLastIndex != 0L) setLastIndex(thisObj, ScriptRuntime.zeroObj)

        val result = regExpExec(thisObj, string, cx, scope)

        // A search must not move lastIndex, so it is put back.
        val currentLastIndex = getLastIndex(cx, thisObj)
        if (previousLastIndex != currentLastIndex) setLastIndex(thisObj, previousLastIndex)

        return if (result == null) -1 else ScriptRuntime.getObjectProp(result, "index", cx, scope)
    }

    private fun js_SymbolMatchAll(cx: Context, scope: Scriptable, thisArg: Scriptable?, args: Array<Any?>): Any? {
        val thisObj = requireObject(thisArg)

        val s = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)

        val topLevelScope = getTopLevelScope(scope)
        val defaultConstructor = ScriptRuntime.getExistingCtor(cx, topLevelScope, className)
        val c = io.github.yuroyami.kitejs.AbstractEcmaObjectOperations.speciesConstructor(cx, thisObj, defaultConstructor)

        val flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx))
        val matcher = c.construct(cx, scope, arrayOf<Any?>(thisObj, flags))

        setLastIndex(matcher, getLastIndex(cx, thisObj))
        val global = flags.indexOf('g') != -1
        val fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1

        return NativeRegExpStringIterator(scope, matcher, s, global, fullUnicode)
    }

    private fun js_SymbolReplace(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        // The fast path only applies when nothing about this regexp has been replaced by a script.
        if (thisObj is NativeRegExp) {
            val exec = getProperty(thisObj, "exec")
            if ((thisObj.lastIndexAttr and READONLY) == 0 && exec is IdFunctionObject &&
                exec.methodId() == Id_exec && exec.tag === REGEXP_TAG
            ) {
                return thisObj.js_SymbolReplaceFast(cx, scope, thisObj, args)
            }
        }
        return js_SymbolReplaceSlow(cx, scope, requireObject(thisObj), args)
    }

    private fun js_SymbolReplaceFast(cx: Context, scope: Scriptable, thisObj: NativeRegExp, args: Array<Any?>): Any? {
        val s = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
        val lengthS = s.length
        val replaceValue = if (args.size > 1) args[1] else Undefined.instance
        val functionalReplace = replaceValue is io.github.yuroyami.kitejs.Callable
        val replaceOps: List<io.github.yuroyami.kitejs.AbstractEcmaStringOperations.ReplacementOperation>
        val replaceFn: io.github.yuroyami.kitejs.Callable?
        if (!functionalReplace) {
            replaceFn = null
            replaceOps = io.github.yuroyami.kitejs.AbstractEcmaStringOperations.buildReplacementList(ScriptRuntime.toString(replaceValue))
        } else {
            replaceFn = replaceValue
            replaceOps = emptyList()
        }
        val flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx))
        val fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1

        val results = ArrayList<ExecResult>()
        var done = false

        val reImpl = getImpl(cx)
        val sticky = (re!!.flags and JSREG_STICKY) != 0
        val global = (re!!.flags and JSREG_GLOB) != 0

        val indexp = intArrayOf(0)
        if (sticky) indexp[0] = getLastIndex(cx, thisObj).toInt()
        while (!done) {
            val result = if (indexp[0] < 0 || indexp[0] > s.length) {
                null
            } else {
                executeRegExpInternal(cx, scope, reImpl, s, indexp, MATCH)
            }
            if (result == null) {
                if (global || sticky) indexp[0] = 0
                done = true
            } else {
                results.add(result)
                if (!global) {
                    done = true
                } else if (result.match!!.isEmpty()) {
                    indexp[0] = ScriptRuntime.advanceStringIndex(s, indexp[0].toLong(), fullUnicode).toInt()
                }
            }
        }
        setLastIndexChecked(thisObj, indexp[0])

        val accumulatedResult = StringBuilder()
        var nextSourcePosition = 0
        for (result in results) {
            val matched = result.match!!
            val matchLength = matched.length
            val position = ScriptRuntime.clamp(result.index, 0, lengthS)

            val namedCaptures: Any? = if (result.groups.isNotEmpty()) {
                val groups = io.github.yuroyami.kitejs.NativeObject()
                for ((k, v) in result.groups) groups.put(k, groups, v ?: Undefined.instance)
                groups
            } else {
                Undefined.instance
            }

            val replacementString = if (functionalReplace) {
                makeComplexReplacement(cx, scope, matched, result.captures, position, s, namedCaptures, replaceFn!!)
            } else {
                makeSimpleReplacement(cx, scope, matched, result.captures, position, s, namedCaptures, replaceOps)
            }

            if (position >= nextSourcePosition) {
                accumulatedResult.append(s, nextSourcePosition, position)
                accumulatedResult.append(replacementString)
                nextSourcePosition = position + matchLength
            }
        }

        if (nextSourcePosition >= lengthS) return accumulatedResult.toString()
        accumulatedResult.append(s.substring(nextSourcePosition))
        return accumulatedResult.toString()
    }

    private fun js_SymbolReplaceSlow(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
        if (!ScriptRuntime.isObject(thisObj)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
        }

        val s = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
        val lengthS = s.length
        val replaceValue = if (args.size > 1) args[1] else Undefined.instance
        val functionalReplace = replaceValue is io.github.yuroyami.kitejs.Callable
        val replaceOps: List<io.github.yuroyami.kitejs.AbstractEcmaStringOperations.ReplacementOperation>
        val replaceFn: io.github.yuroyami.kitejs.Callable?

        if (!functionalReplace) {
            replaceFn = null
            replaceOps = io.github.yuroyami.kitejs.AbstractEcmaStringOperations.buildReplacementList(ScriptRuntime.toString(replaceValue))
        } else {
            replaceFn = replaceValue
            replaceOps = emptyList()
        }
        val flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx))
        val global = flags.indexOf('g') != -1
        val fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1
        if (global) setLastIndex(thisObj, ScriptRuntime.zeroObj)

        val results = ArrayList<Any?>()
        var done = false
        while (!done) {
            val result = regExpExec(thisObj, s, cx, scope)
            if (result == null) {
                done = true
            } else {
                results.add(result)
                if (!global) {
                    done = true
                } else {
                    val matchStr = ScriptRuntime.toString(ScriptRuntime.getObjectIndex(result, 0.0, cx, scope))
                    if (matchStr.isEmpty()) {
                        val thisIndex = getLastIndex(cx, thisObj)
                        setLastIndex(thisObj, ScriptRuntime.advanceStringIndex(s, thisIndex, fullUnicode))
                    }
                }
            }
        }

        val accumulatedResult = StringBuilder()
        var nextSourcePosition = 0
        for (result in results) {
            val resultLength = ScriptRuntime.toLength(ScriptRuntime.getObjectProp(result, "length", cx, scope))
            val nCaptures = if (resultLength - 1 > 0) resultLength - 1 else 0L
            val matched = ScriptRuntime.toString(ScriptRuntime.getObjectIndex(result, 0.0, cx, scope))
            val matchLength = matched.length
            val positionDbl = ScriptRuntime.toInteger(ScriptRuntime.getObjectProp(result, "index", cx, scope))
            val position = ScriptRuntime.clamp(positionDbl.toInt(), 0, lengthS)

            val captures = ArrayList<Any?>()
            var n = 1L
            while (n <= nCaptures) {
                var capN = ScriptRuntime.getObjectElem(result, n.toDouble(), cx, scope)
                if (!Undefined.isUndefined(capN)) capN = ScriptRuntime.toString(capN)
                captures.add(capN)
                ++n
            }

            val namedCaptures = ScriptRuntime.getObjectProp(result, "groups", cx, scope)
            val replacementString = if (functionalReplace) {
                makeComplexReplacement(cx, scope, matched, captures, position, s, namedCaptures, replaceFn!!)
            } else {
                makeSimpleReplacement(cx, scope, matched, captures, position, s, namedCaptures, replaceOps)
            }

            if (position >= nextSourcePosition) {
                accumulatedResult.append(s, nextSourcePosition, position)
                accumulatedResult.append(replacementString)
                nextSourcePosition = position + matchLength
            }
        }

        if (nextSourcePosition >= lengthS) return accumulatedResult.toString()
        accumulatedResult.append(s.substring(nextSourcePosition))
        return accumulatedResult.toString()
    }

    private fun makeComplexReplacement(
        cx: Context,
        scope: Scriptable,
        matched: String,
        captures: List<Any?>,
        position: Int,
        s: String,
        namedCaptures: Any?,
        replaceFunction: io.github.yuroyami.kitejs.Callable,
    ): String {
        val replacerArgs = arrayOfNulls<Any?>(1 + captures.size + (if (Undefined.isUndefined(namedCaptures)) 2 else 3))
        replacerArgs[0] = matched
        var i = 1
        while (i <= captures.size) {
            replacerArgs[i] = captures[i - 1] ?: Undefined.instance
            i++
        }
        replacerArgs[i++] = position
        replacerArgs[i++] = s
        if (!Undefined.isUndefined(namedCaptures)) replacerArgs[i++] = namedCaptures

        val callThis = ScriptRuntime.getApplyOrCallThis(cx, scope, null, 0, replaceFunction)
        return ScriptRuntime.toString(replaceFunction.call(cx, scope, callThis, replacerArgs))
    }

    private fun makeSimpleReplacement(
        cx: Context,
        scope: Scriptable,
        matched: String,
        captures: List<Any?>,
        position: Int,
        s: String,
        namedCapturesIn: Any?,
        replaceOps: List<io.github.yuroyami.kitejs.AbstractEcmaStringOperations.ReplacementOperation>,
    ): String {
        var namedCaptures = namedCapturesIn
        if (!Undefined.isUndefined(namedCaptures)) namedCaptures = ScriptRuntime.toObject(scope, namedCaptures)
        return io.github.yuroyami.kitejs.AbstractEcmaStringOperations.getSubstitution(
            cx, scope, matched, s, position, captures, namedCaptures, replaceOps,
        )
    }

    private fun js_SymbolSplit(cx: Context, scope: Scriptable, rxArg: Scriptable?, args: Array<Any?>): Any? {
        val rx = requireObject(rxArg)

        val s = ScriptRuntime.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)

        val topLevelScope = getTopLevelScope(scope)
        val defaultConstructor = ScriptRuntime.getExistingCtor(cx, topLevelScope, className)
        val c = io.github.yuroyami.kitejs.AbstractEcmaObjectOperations.speciesConstructor(cx, rx, defaultConstructor)

        val flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(rx, "flags", cx))
        val unicodeMatching = flags.indexOf('u') != -1 || flags.indexOf('v') != -1
        val a = cx.newArray(scope, 0) as io.github.yuroyami.kitejs.NativeArray
        // The splitter always runs sticky, so each step starts exactly where the last one ended.
        val newFlags = if (flags.indexOf('y') != -1) flags else flags + "y"
        val splitter = c.construct(cx, scope, arrayOf<Any?>(rx, newFlags))

        val limit = if (args.size > 1) args[1] else Undefined.instance
        val lim = if (Undefined.isUndefined(limit)) Int.MAX_VALUE.toLong() else ScriptRuntime.toUint32(limit)
        if (lim == 0L) return a

        if (splitter is NativeRegExp) {
            val exec = getProperty(splitter, "exec")
            if ((splitter.lastIndexAttr and READONLY) == 0 && exec is IdFunctionObject &&
                exec.methodId() == Id_exec && exec.tag === REGEXP_TAG
            ) {
                return js_SymbolSplitFast(cx, scope, splitter, s, lim, unicodeMatching, a)
            }
        }
        return js_SymbolSplitSlow(cx, scope, splitter, s, lim, unicodeMatching, a)
    }

    private fun js_SymbolSplitSlow(
        cx: Context,
        scope: Scriptable,
        splitter: Scriptable,
        s: String,
        lim: Long,
        unicodeMatching: Boolean,
        a: io.github.yuroyami.kitejs.NativeArray,
    ): Any? {
        var lengthA = 0

        if (s.isEmpty()) {
            if (regExpExec(splitter, s, cx, scope) != null) return a
            a.put(0, a, s)
            return a
        }

        val size = s.length
        var p = 0L
        var q = p
        while (q < size) {
            setLastIndex(splitter, q)
            val z = regExpExec(splitter, s, cx, scope)
            if (z == null) {
                q = ScriptRuntime.advanceStringIndex(s, q, unicodeMatching)
            } else {
                var e = getLastIndex(cx, splitter)
                if (e > size) e = size.toLong()
                if (e == p) {
                    q = ScriptRuntime.advanceStringIndex(s, q, unicodeMatching)
                } else {
                    a.put(a.length.toInt(), a, s.substring(p.toInt(), q.toInt()))
                    lengthA++
                    if (a.length == lim) return a

                    p = e
                    var numberOfCaptures = ScriptRuntime.toLength(ScriptRuntime.getObjectProp(z, "length", cx, scope))
                    numberOfCaptures = if (numberOfCaptures - 1 > 0) numberOfCaptures - 1 else 0L
                    var i = 1L
                    while (i <= numberOfCaptures) {
                        a.put(a.length.toInt(), a, ScriptRuntime.getObjectIndex(z, i.toDouble(), cx, scope))
                        i += 1
                        lengthA++
                        if (lengthA.toLong() == lim) return a
                    }
                    q = p
                }
            }
        }
        a.put(a.length.toInt(), a, s.substring(p.toInt(), size))
        return a
    }

    private fun js_SymbolSplitFast(
        cx: Context,
        scope: Scriptable,
        splitter: NativeRegExp,
        s: String,
        lim: Long,
        unicodeMatching: Boolean,
        a: io.github.yuroyami.kitejs.NativeArray,
    ): Any? {
        var lengthA = 0
        val indexp = intArrayOf(0)
        val reImpl = getImpl(cx)

        if (s.isEmpty()) {
            if (splitter.executeRegExpInternal(cx, scope, reImpl, s, indexp, MATCH) != null) return a
            a.put(0, a, s)
            return a
        }

        val size = s.length
        var p = 0L
        var q = p
        while (q < size) {
            indexp[0] = q.toInt()
            val result = splitter.executeRegExpInternal(cx, scope, reImpl, s, indexp, MATCH)

            if (result == null) {
                q = ScriptRuntime.advanceStringIndex(s, q, unicodeMatching)
            } else {
                var e = indexp[0].toLong()
                if (e > size) e = size.toLong()
                if (e == p) {
                    q = ScriptRuntime.advanceStringIndex(s, q, unicodeMatching)
                } else {
                    a.put(a.length.toInt(), a, s.substring(p.toInt(), q.toInt()))
                    lengthA++
                    if (a.length == lim) return a

                    p = e
                    var i = 0
                    while (i < result.captures.size) {
                        a.put(a.length.toInt(), a, result.captures[i] ?: Undefined.instance)
                        i += 1
                        lengthA++
                        if (lengthA.toLong() == lim) return a
                    }
                    q = p
                }
            }
        }
        a.put(a.length.toInt(), a, s.substring(p.toInt(), size))
        return a
    }

    public companion object {
        internal val REGEXP_TAG: Any = Any()

        public const val JSREG_GLOB: Int = 0x1 // 'g', global
        public const val JSREG_FOLD: Int = 0x2 // 'i', fold case
        public const val JSREG_MULTILINE: Int = 0x4 // 'm'
        public const val JSREG_DOTALL: Int = 0x8 // 's'
        public const val JSREG_STICKY: Int = 0x10 // 'y'
        public const val JSREG_UNICODE: Int = 0x20 // 'u'

        // What kind of answer the caller wants.
        public const val TEST: Int = 0
        public const val MATCH: Int = 1
        public const val PREFIX: Int = 2

        // The bytecode. The "simple" opcodes are the ones simpleMatch can answer on its own.
        internal const val REOP_SIMPLE_START: Byte = 1
        internal const val REOP_EMPTY: Byte = REOP_SIMPLE_START
        internal const val REOP_BOL: Byte = 2
        internal const val REOP_EOL: Byte = 3
        internal const val REOP_WBDRY: Byte = 4
        internal const val REOP_WNONBDRY: Byte = 5
        internal const val REOP_DOT: Byte = 6
        internal const val REOP_DIGIT: Byte = 7
        internal const val REOP_NONDIGIT: Byte = 8
        internal const val REOP_ALNUM: Byte = 9
        internal const val REOP_NONALNUM: Byte = 10
        internal const val REOP_SPACE: Byte = 11
        internal const val REOP_NONSPACE: Byte = 12
        internal const val REOP_BACKREF: Byte = 13
        internal const val REOP_FLAT: Byte = 14
        internal const val REOP_FLAT1: Byte = 15
        internal const val REOP_FLATi: Byte = 16
        internal const val REOP_FLAT1i: Byte = 17
        internal const val REOP_UCFLAT1: Byte = 18
        internal const val REOP_UCFLAT1i: Byte = 19
        internal const val REOP_UCSPFLAT1: Byte = 20
        internal const val REOP_CLASS: Byte = 21
        internal const val REOP_NCLASS: Byte = 22
        internal const val REOP_NAMED_BACKREF: Byte = 23
        internal const val REOP_UPROP: Byte = 24
        internal const val REOP_UPROP_NOT: Byte = 25
        internal const val REOP_SIMPLE_END: Byte = REOP_UPROP_NOT

        internal const val REOP_QUANT: Byte = 26
        internal const val REOP_STAR: Byte = 27
        internal const val REOP_PLUS: Byte = 28
        internal const val REOP_OPT: Byte = 29
        internal const val REOP_LPAREN: Byte = 30
        internal const val REOP_RPAREN: Byte = 31
        internal const val REOP_ALT: Byte = 32
        internal const val REOP_JUMP: Byte = 33
        internal const val REOP_ASSERT: Byte = 34
        internal const val REOP_ASSERT_NOT: Byte = 35
        internal const val REOP_ASSERTTEST: Byte = 36
        internal const val REOP_ASSERTNOTTEST: Byte = 37
        internal const val REOP_MINIMALSTAR: Byte = 38
        internal const val REOP_MINIMALPLUS: Byte = 39
        internal const val REOP_MINIMALOPT: Byte = 40
        internal const val REOP_MINIMALQUANT: Byte = 41
        internal const val REOP_ENDCHILD: Byte = 42
        internal const val REOP_REPEAT: Byte = 43
        internal const val REOP_MINIMALREPEAT: Byte = 44
        internal const val REOP_ALTPREREQ: Byte = 45
        internal const val REOP_ALTPREREQi: Byte = 46
        internal const val REOP_ALTPREREQ2: Byte = 47
        internal const val REOP_ASSERTBACK: Byte = 48
        internal const val REOP_ASSERTBACK_NOT: Byte = 49
        internal const val REOP_ASSERTBACKTEST: Byte = 50
        internal const val REOP_ASSERTBACKNOTTEST: Byte = 51
        internal const val REOP_END: Byte = 52

        private const val ANCHOR_BOL = -2
        private const val INDEX_LEN = 2

        // ---- Setup ---------------------------------------------------------------------------

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val proto = NativeRegExpInstantiator.withLanguageVersion(cx.languageVersion)
            proto.re = compileRE(cx, "", null, false)
            proto.activatePrototypeMap(MAX_PROTOTYPE_ID)
            proto.parentScope = scope
            proto.prototype = getObjectPrototype(scope)

            val ctor = NativeRegExpCtor.init(cx, scope, sealed)
            // The spec pins RegExp.prototype.constructor to the built-in constructor.
            proto.defineProperty("constructor", ctor, DONTENUM)

            ScriptRuntime.setFunctionProtoAndParent(ctor, cx, scope)
            ctor.setImmunePrototypeProperty(proto)

            if (sealed) {
                proto.sealObject()
                ctor.sealObject()
            }

            defineProperty(scope, "RegExp", ctor, DONTENUM)
            ScriptRuntimeES6.addSymbolSpecies(cx, scope, ctor)
            return ctor
        }

        private fun getImpl(cx: Context): RegExpImpl = ScriptRuntime.getRegExpProxy(cx) as RegExpImpl

        /** Escapes bare slashes so the source can go back between two slashes and still parse. */
        private fun escapeRegExp(src: Any?): String {
            var s = ScriptRuntime.toString(src)
            var sb: StringBuilder? = null
            var start = 0
            var slash = s.indexOf('/')
            while (slash > -1) {
                if (slash == start || s[slash - 1] != '\\') {
                    if (sb == null) sb = StringBuilder()
                    sb.append(s, start, slash)
                    sb.append("\\/")
                    start = slash + 1
                }
                slash = s.indexOf('/', slash + 1)
            }
            if (sb != null) {
                sb.append(s, start, s.length)
                s = sb.toString()
            }
            return s
        }

        // ---- The compiler --------------------------------------------------------------------

        /** Which optional syntax the parse pass allows. Some patterns need a second pass. */
        internal class ParserParameters(var namedCaptureGroups: Boolean, var unicodeMode: Boolean)

        internal fun compileRE(cx: Context, str: String, global: String?, flat: Boolean): RECompiled {
            val regexp = RECompiled(str)
            val length = str.length
            var flags = 0
            if (global != null) {
                for (c in global) {
                    val f = when (c) {
                        'g' -> JSREG_GLOB
                        'i' -> JSREG_FOLD
                        'm' -> JSREG_MULTILINE
                        's' -> JSREG_DOTALL
                        'y' -> JSREG_STICKY
                        'u' -> JSREG_UNICODE
                        else -> reportError("msg.invalid.re.flag", c.toString())
                    }
                    if ((flags and f) != 0) reportError("msg.invalid.re.flag", c.toString())
                    flags = flags or f
                }
            }

            // Upstream does not support u and i together yet.
            if ((flags and JSREG_UNICODE) != 0 && (flags and JSREG_FOLD) != 0) {
                reportError("msg.invalid.re.flag", "u and i")
            }
            if ((flags and JSREG_UNICODE) != 0 && cx.languageVersion < Context.VERSION_ES6) {
                reportError("msg.invalid.re.flag", "u")
            }

            regexp.flags = flags

            var state = CompilerState(cx, regexp.source, length, flags)
            if (flat && length > 0) {
                state.result = RENode(REOP_FLAT).apply {
                    chr = state.cpbegin[0]
                    this.length = length
                    flatIndex = 0
                }
                state.progLength += 5
            } else {
                val unicodeMode = (flags and JSREG_UNICODE) != 0
                // Unicode mode always turns named capture groups on.
                val params = ParserParameters(unicodeMode, unicodeMode)

                parseDisjunction(state, params)

                // A back reference past the last group is an octal escape instead, which means
                // reparsing. So does finding a named group when the first pass did not expect one.
                var reParseState: CompilerState? = null
                if (state.maxBackReference > state.parenCount) {
                    if (params.unicodeMode) {
                        reportError("msg.invalid.escape", "")
                    } else {
                        reParseState = CompilerState(cx, regexp.source, length, flags)
                        reParseState.backReferenceLimit = state.parenCount
                    }
                }
                if (state.namedCaptureGroupsFound && !params.namedCaptureGroups) {
                    params.namedCaptureGroups = true
                    if (reParseState == null) reParseState = CompilerState(cx, regexp.source, length, flags)
                }
                if (reParseState != null) {
                    state = reParseState
                    parseDisjunction(state, params)
                }
            }

            regexp.namedCaptureGroups = HashMap()
            if (state.namedCaptureGroupsFound) {
                extractNamedCaptureGroups(state.result, regexp.namedCaptureGroups)
                regexp.namedBackRefs = state.namedCaptureBackRefs
            }

            regexp.program = ByteArray(state.progLength + 1)
            if (state.classCount != 0) {
                regexp.classList = arrayOfNulls(state.classCount)
                regexp.classCount = state.classCount
            }
            var endPC = emitREBytecode(state, regexp, 0, state.result)
            regexp.program[endPC++] = REOP_END

            regexp.parenCount = state.parenCount

            // A pattern that starts with a literal lets the search skip ahead to that character.
            when (regexp.program[0]) {
                REOP_UCFLAT1, REOP_UCFLAT1i -> regexp.anchorCodePoint = getIndex(regexp.program, 1)
                REOP_FLAT1, REOP_FLAT1i -> regexp.anchorCodePoint = regexp.program[1].toInt() and 0xFF
                REOP_FLAT, REOP_FLATi -> {
                    val k = getIndex(regexp.program, 1)
                    regexp.anchorCodePoint = regexp.source[k].code
                }
                REOP_BOL -> regexp.anchorCodePoint = ANCHOR_BOL
                REOP_ALT -> {
                    val n = state.result!!
                    if (n.kid!!.op == REOP_BOL && n.kid2!!.op == REOP_BOL) {
                        regexp.anchorCodePoint = ANCHOR_BOL
                    }
                }
                else -> {}
            }
            return regexp
        }

        /**
         * Collects the named groups off the parse tree. Two branches of an alternation may use the
         * same name, so a name maps to a list of capture indices rather than to one.
         */
        private fun extractNamedCaptureGroups(re: RENode?, namedCaptureGroups: MutableMap<String, MutableList<Int>>) {
            var node = re
            while (node != null) {
                if (node.op == REOP_LPAREN) {
                    val name = node.namedCaptureGroupName
                    if (name != null) {
                        val entry = ArrayList<Int>(1)
                        if (namedCaptureGroups.put(name, entry) != null) {
                            reportError("msg.duplicate.group.name", name)
                        }
                        entry.add(node.parenIndex)
                        extractNamedCaptureGroups(node.kid, namedCaptureGroups)
                    }
                } else if (node.op == REOP_ALT) {
                    // The same name on both sides of an alternation is allowed; across the whole
                    // pattern it is not. Merge the two sides before checking.
                    val groupCaptures1 = HashMap<String, MutableList<Int>>()
                    extractNamedCaptureGroups(node.kid, groupCaptures1)
                    if (groupCaptures1.isEmpty()) {
                        extractNamedCaptureGroups(node.kid2, namedCaptureGroups)
                    } else {
                        val groupCaptures2 = HashMap<String, MutableList<Int>>()
                        extractNamedCaptureGroups(node.kid2, groupCaptures2)
                        for ((k, v) in groupCaptures2) {
                            val existing = groupCaptures1[k]
                            if (existing == null) groupCaptures1[k] = v else existing.addAll(v)
                        }
                        for ((k, v) in groupCaptures1) {
                            if (namedCaptureGroups.put(k, v) != null) reportError("msg.duplicate.group.name", k)
                        }
                    }
                } else {
                    extractNamedCaptureGroups(node.kid, namedCaptureGroups)
                }
                node = node.next
            }
        }

        internal fun isDigit(c: Char): Boolean = c in '0'..'9'

        private fun isWord(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z') || isDigit(c) || c == '_'

        private fun isControlLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

        private fun isLineTerm(c: Char): Boolean = ScriptRuntime.isJSLineTerminator(c.code)

        private fun isREWhiteSpace(c: Int): Boolean = ScriptRuntime.isJSWhitespaceOrLineTerminator(c)

        /**
         * The spec's Canonicalize for case-insensitive matching: upper-case the character, but keep
         * the original when a non-ASCII character would fold into ASCII.
         */
        private fun upcase(ch: Char): Char {
            if (ch.code < 128) {
                if (ch in 'a'..'z') return (ch.code + ('A'.code - 'a'.code)).toChar()
                return ch
            }
            val cu = Characters.toUpperCase(ch.code)
            return if (cu < 128) ch else cu.toChar()
        }

        private fun downcase(ch: Char): Char {
            if (ch.code < 128) {
                if (ch in 'A'..'Z') return (ch.code + ('a'.code - 'A'.code)).toChar()
                return ch
            }
            val cl = Characters.toLowerCase(ch.code)
            return if (cl < 128) ch else cl.toChar()
        }

        /** A regexp is one or more alternatives separated by `|`. */
        private fun parseDisjunction(state: CompilerState, params: ParserParameters): Boolean {
            if (!parseAlternative(state, params)) return false
            val source = state.cpbegin
            val index = state.cp
            if (index != source.size && source[index] == '|') {
                ++state.cp
                val result = RENode(REOP_ALT)
                result.kid = state.result
                if (!parseDisjunction(state, params)) return false
                result.kid2 = state.result
                state.result = result

                // When both sides start with something the matcher can test cheaply, the
                // alternation gets a prerequisite check instead of two full attempts.
                val kid = result.kid!!
                val kid2 = result.kid2!!
                if (kid.op == REOP_FLAT && kid2.op == REOP_FLAT && kid.lowSurrogate.code == 0 && kid2.lowSurrogate.code == 0) {
                    result.op = if ((state.flags and JSREG_FOLD) == 0) REOP_ALTPREREQ else REOP_ALTPREREQi
                    result.chr = kid.chr
                    result.index = kid2.chr.code
                    state.progLength += 13
                } else if (kid.op == REOP_CLASS && kid.index < 256 && kid2.op == REOP_FLAT &&
                    kid2.lowSurrogate.code == 0 && (state.flags and JSREG_FOLD) == 0
                ) {
                    result.op = REOP_ALTPREREQ2
                    result.chr = kid2.chr
                    result.index = kid.index
                    state.progLength += 13
                } else if (kid.op == REOP_FLAT && kid2.op == REOP_CLASS && kid2.index < 256 &&
                    kid.lowSurrogate.code == 0 && (state.flags and JSREG_FOLD) == 0
                ) {
                    result.op = REOP_ALTPREREQ2
                    result.chr = kid.chr
                    result.index = kid2.index
                    state.progLength += 13
                } else {
                    state.progLength += 9
                }
            }
            return true
        }

        /** An alternative is one or more items concatenated. */
        private fun parseAlternative(state: CompilerState, params: ParserParameters): Boolean {
            var headTerm: RENode? = null
            var tailTerm: RENode? = null
            val source = state.cpbegin
            while (true) {
                if (state.cp == state.cpend || source[state.cp] == '|' ||
                    (state.parenNesting != 0 && source[state.cp] == ')')
                ) {
                    state.result = headTerm ?: RENode(REOP_EMPTY)
                    return true
                }
                if (!parseTerm(state, params)) return false
                if (headTerm == null) {
                    headTerm = state.result
                    tailTerm = headTerm
                } else {
                    tailTerm!!.next = state.result
                }
                while (tailTerm!!.next != null) {
                    // Neighbouring literals that are slices of the source merge into one.
                    val n = tailTerm.next!!
                    if (tailTerm.op == REOP_FLAT && tailTerm.flatIndex != -1 &&
                        n.op == REOP_FLAT && n.flatIndex == (tailTerm.flatIndex + tailTerm.length)
                    ) {
                        tailTerm.length += n.length
                        tailTerm.next = n.next
                    } else {
                        tailTerm = n
                    }
                }
            }
        }

        /** How wide the class bitmap has to be, which the highest character in the class decides. */
        private fun calculateBitmapSize(flags: Int, classContents: ClassContents, target: RENode): Boolean {
            var max = 0

            for (ch in classContents.chars) {
                if (ch.code > max) max = ch.code
                if ((flags and JSREG_FOLD) != 0) {
                    val cu = upcase(ch)
                    val cd = downcase(ch)
                    val n = if (cu >= cd) cu.code else cd.code
                    if (n > max) max = n
                }
            }

            var i = 1
            while (i < classContents.bmpRanges.size) {
                val rangeEnd = classContents.bmpRanges[i]
                if (rangeEnd.code > max) max = rangeEnd.code
                if ((flags and JSREG_FOLD) != 0) {
                    val cu = upcase(rangeEnd)
                    val cd = downcase(rangeEnd)
                    val n = if (cu >= cd) cu.code else cd.code
                    if (n > max) max = n
                }
                i += 2
            }

            for (node in classContents.escapeNodes) {
                if (node.op != REOP_FLAT) {
                    // An escape such as \d or \s can reach anywhere, so the bitmap covers the BMP.
                    target.bmsize = 0xFFFF + 1
                    break
                }
            }

            target.bmsize = if (target.bmsize > max + 1) target.bmsize else max + 1
            return true
        }

        private fun doFlat(state: CompilerState, c: Char) {
            state.result = RENode(REOP_FLAT).apply {
                chr = c
                lowSurrogate = 0.toChar()
                length = 1
                flatIndex = -1
            }
            state.progLength += 3
        }

        private fun doFlatSurrogatePair(state: CompilerState, high: Char, low: Char) {
            state.result = RENode(REOP_FLAT).apply {
                chr = high
                lowSurrogate = low
                length = 2
                flatIndex = -1
            }
            state.progLength += 5
        }

        private fun getDecimalValue(first: Char, state: CompilerState, overflowMessageId: String): Int {
            var overflow = false
            val start = state.cp
            val src = state.cpbegin
            var value = first - '0'
            while (state.cp != state.cpend) {
                val c = src[state.cp]
                if (!isDigit(c)) break
                if (!overflow) {
                    val v = value * 10 + (c - '0')
                    if (v < 65535) {
                        value = v
                    } else {
                        overflow = true
                        value = 65535
                    }
                }
                ++state.cp
            }
            if (overflow) {
                reportError(overflowMessageId, src.concatToString(start, state.cp))
            }
            return value
        }

        /** Lookbehind matches right to left, so its child list is reversed before it is emitted. */
        private fun reverseNodeList(head: RENode?): RENode? {
            var prev: RENode? = null
            var node = head
            while (node != null) {
                if (node.kid != null && node.op != REOP_ASSERT && node.op != REOP_ASSERT_NOT &&
                    node.op != REOP_ASSERTBACK && node.op != REOP_ASSERTBACK_NOT
                ) {
                    node.kid = reverseNodeList(node.kid)
                }
                val next = node.next
                node.next = prev
                prev = node
                node = next
            }
            return prev
        }

        private fun reportWarning(cx: Context, messageId: String, arg: String) {
            if (cx.hasFeature(Context.FEATURE_STRICT_MODE)) {
                Context.reportWarning(Messages.getMessageById(messageId, arg))
            }
        }

        private fun reportError(messageId: String, arg: String): Nothing {
            throw ScriptRuntime.constructError("SyntaxError", Messages.getMessageById(messageId, arg))
        }

        // ---- Escapes -------------------------------------------------------------------------

        private fun codePointAt(src: CharArray, index: Int, limit: Int = src.size): Int {
            val c1 = src[index]
            if (c1.isHighSurrogate() && index + 1 < limit) {
                val c2 = src[index + 1]
                if (c2.isLowSurrogate()) return Characters.toCodePoint(c1, c2)
            }
            return c1.code
        }

        /** Reads `<name>` after `(?` or `\k`. Leaves the cursor where it was when the name is bad. */
        private fun extractCaptureGroupName(state: CompilerState, builder: StringBuilder): Boolean {
            val src = state.cpbegin
            val termBegin = state.cp
            var isStart = true
            var segmentStart = 0
            var segmentLength = 0

            if (state.cp >= state.cpend) return false
            if (src[state.cp++] != '<') {
                state.cp = termBegin
                return false
            }

            while (state.cp < state.cpend && src[state.cp] != '>') {
                val codePoint: Int
                if (state.cp + 1 < state.cpend && src[state.cp] == '\\' && src[state.cp + 1] == 'u') {
                    state.cp += 2
                    val n = readRegExpUnicodeEscapeSequence(state, ParserParameters(false, true))
                    if (n == -1) {
                        state.cp = termBegin
                        reportError("msg.invalid.escape", "")
                    }
                    codePoint = n
                    if (segmentLength != 0) {
                        builder.appendRange(src, segmentStart, segmentStart + segmentLength)
                        segmentLength = 0
                    }
                    builder.append(Characters.codePointToString(codePoint))
                } else {
                    codePoint = codePointAt(src, state.cp, state.cpend)
                    if (segmentLength != 0) {
                        segmentLength += Characters.charCount(codePoint)
                    } else {
                        segmentStart = state.cp
                        segmentLength = Characters.charCount(codePoint)
                    }
                    state.cp += Characters.charCount(codePoint)
                }

                val ok = codePoint == '$'.code ||
                    (isStart && codePoint == '_'.code) ||
                    (isStart && Characters.isUnicodeIdentifierStart(codePoint)) ||
                    (!isStart && Characters.isUnicodeIdentifierPart(codePoint))
                if (!ok) {
                    state.cp = termBegin
                    return false
                }
                isStart = false
            }

            if (state.cp >= state.cpend || src[state.cp++] != '>') {
                state.cp = termBegin
                return false
            }
            if (segmentLength != 0) builder.appendRange(src, segmentStart, segmentStart + segmentLength)
            return true
        }

        /** Reads as many octal digits as keep the value under 0x100. The cursor is on a digit. */
        private fun parseLegacyOctalEscapeSequence(state: CompilerState): Boolean {
            val src = state.cpbegin
            var c = src[state.cp]
            if (c < '0' || c > '7') return false
            var num = c - '0'
            state.cp++
            var nDigits = 1
            while (nDigits < 3 && num < 32 && state.cp < state.cpend) {
                c = src[state.cp]
                nDigits++
                if (c in '0'..'7') {
                    state.cp++
                    num = 8 * num + (c - '0')
                } else {
                    break
                }
            }
            doFlat(state, num.toChar())
            return true
        }

        /** A backslash in front of a character that is not an escape at all. */
        private fun parseIdentityEscape(state: CompilerState, params: ParserParameters): Boolean {
            val src = state.cpbegin
            if (state.cp < state.cpend) {
                val c = src[state.cp++]
                if (params.unicodeMode) {
                    when (c) {
                        '^', '$', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '/' -> {
                            doFlat(state, c)
                            state.result!!.flatIndex = state.cp - 1
                            return true
                        }
                        else -> {
                            state.cp--
                            return false
                        }
                    }
                } else {
                    if ('c' != c) {
                        if (params.namedCaptureGroups) {
                            if ('k' != c) {
                                doFlat(state, c)
                                state.result!!.flatIndex = state.cp - 1
                                return true
                            }
                        } else {
                            doFlat(state, c)
                            state.result!!.flatIndex = state.cp - 1
                            return true
                        }
                    }
                }
            }
            state.cp--
            return false
        }

        /** -1 on failure, and the cursor is put back. Unicode mode insists on all [nDigits]. */
        private fun readNHexDigits(state: CompilerState, nDigits: Int, params: ParserParameters): Int {
            val termBegin = state.cp
            var n = 0
            for (i in 0 until nDigits) {
                if (state.cp >= state.cpend) {
                    if (params.unicodeMode || i == 0) {
                        state.cp = termBegin
                        return -1
                    }
                    return n
                }
                val c = state.cpbegin[state.cp++]
                n = Kit.xDigitToInt(c.code, n)
                if (n < 0) {
                    state.cp = termBegin
                    return -1
                }
            }
            return n
        }

        /** The `\u{...}` form. */
        private fun parseUnicodeCodePoint(state: CompilerState): Int {
            val src = state.cpbegin
            val cpOriginal = state.cp
            var n = 0

            if (state.cp == state.cpend || src[state.cp++] != '{') {
                state.cp = cpOriginal
                return -1
            }
            if (state.cp == state.cpend || src[state.cp] == '}') reportError("msg.invalid.escape", "")
            while (state.cp != state.cpend) {
                if (src[state.cp] == '\\') break
                val res = Kit.xDigitToInt(src[state.cp].code, n)
                if (res == -1) break
                if (res > 0x10FFFF) reportError("msg.invalid.escape", "")
                n = res
                state.cp += 1
            }
            if (state.cp == state.cpend || src[state.cp++] != '}') {
                state.cp = cpOriginal
                return -1
            }
            return n
        }

        /** The leading `u` has already been read. */
        internal fun readRegExpUnicodeEscapeSequence(state: CompilerState, params: ParserParameters): Int {
            val src = state.cpbegin
            val n = readNHexDigits(state, 4, params)
            if (n < 0) {
                if (params.unicodeMode) return parseUnicodeCodePoint(state)
            }

            if (params.unicodeMode && n.toChar().isHighSurrogate()) {
                // A surrogate pair written as two \u escapes is one code point.
                if (state.cp + 2 < state.cpend && src[state.cp] == '\\' && src[state.cp + 1] == 'u') {
                    state.cp += 2
                    val n2 = readNHexDigits(state, 4, params)
                    if (n2 < 0) {
                        state.cp -= 2
                    } else if (n2.toChar().isLowSurrogate()) {
                        return Characters.toCodePoint(n.toChar(), n2.toChar())
                    } else {
                        state.cp -= 6
                    }
                }
            }
            return n
        }

        private fun parseRegExpUnicodeEscapeSequence(state: CompilerState, params: ParserParameters): Boolean {
            val n = readRegExpUnicodeEscapeSequence(state, params)
            if (n < 0) return false
            if (n <= 0xFFFF) {
                doFlat(state, n.toChar())
            } else {
                doFlatSurrogatePair(state, Characters.highSurrogate(n), Characters.lowSurrogate(n))
            }
            return true
        }

        /** Only `\p{X}` and `\P{X}`. The backslash has already been read. */
        private fun parseUnicodePropertyEscape(state: CompilerState): Boolean {
            val src = state.cpbegin
            val termBegin = state.cp
            var c = src[state.cp++]

            if (c != 'p' && c != 'P') {
                state.cp = termBegin
                return false
            }
            val sense = c == 'p'

            if (state.cp == state.cpend || src[state.cp++] != '{') {
                state.cp = termBegin
                return false
            }
            val contentBegin = state.cp
            while (state.cp != state.cpend) {
                c = src[state.cp++]
                if (c == '}') break
            }
            val contentEnd = state.cp
            if (contentBegin == contentEnd) {
                state.cp = termBegin
                return false
            }

            val content = src.concatToString(contentBegin, contentEnd - 1)
            val encodedProp = UnicodeProperties.lookup(content)
            if (encodedProp == -1) reportError("msg.invalid.escape", "")

            state.result = RENode(if (sense) REOP_UPROP else REOP_UPROP_NOT).apply {
                unicodeProperty = encodedProp
            }
            state.progLength += 3
            return true
        }

        /** Annex B.1.2: everything a backslash can introduce inside an atom or a class. */
        private fun parseCharacterAndCharacterClassEscape(state: CompilerState, params: ParserParameters): Boolean {
            val src = state.cpbegin
            val termBegin = state.cp

            if (state.cp >= state.cpend) {
                // A trailing backslash is an error.
                reportError("msg.trail.backslash", "")
            }

            var c = src[state.cp++]
            when (c) {
                '0' -> {
                    // Outside unicode mode a digit after \0 makes the whole thing an octal escape.
                    if (state.cp < state.cpend && isDigit(src[state.cp])) {
                        if (params.unicodeMode) {
                            reportError("msg.invalid.escape", "")
                        } else {
                            state.cp--
                            if (!parseLegacyOctalEscapeSequence(state)) throw Kit.codeBug("parseLegacyOctalEscapeSequence failed")
                        }
                    } else {
                        doFlat(state, 0.toChar())
                    }
                }
                '1', '2', '3', '4', '5', '6', '7' -> {
                    if (params.unicodeMode) reportError("msg.invalid.escape", "")
                    state.cp--
                    if (!parseLegacyOctalEscapeSequence(state)) throw Kit.codeBug("parseLegacyOctalEscapeSequence failed")
                }
                'f' -> doFlat(state, 0xC.toChar())
                'n' -> doFlat(state, 0xA.toChar())
                'r' -> doFlat(state, 0xD.toChar())
                't' -> doFlat(state, 0x9.toChar())
                'v' -> doFlat(state, 0xB.toChar())
                'c' -> {
                    if (state.cp < state.cpend && isControlLetter(src[state.cp])) {
                        c = (src[state.cp++].code and 0x1F).toChar()
                    } else {
                        state.cp = termBegin
                        return false
                    }
                    doFlat(state, c)
                }
                'u' -> {
                    if (!parseRegExpUnicodeEscapeSequence(state, params)) {
                        state.cp-- // back onto the 'u'
                        if (parseIdentityEscape(state, params)) return true
                        reportError("msg.invalid.escape", "")
                    }
                }
                'x' -> {
                    val n = readNHexDigits(state, 2, params)
                    if (n < 0) {
                        state.cp-- // back onto the 'x'
                        if (parseIdentityEscape(state, params)) return true
                        reportError("msg.invalid.escape", "")
                    }
                    doFlat(state, n.toChar())
                }
                'd' -> { state.result = RENode(REOP_DIGIT); state.progLength++ }
                'D' -> { state.result = RENode(REOP_NONDIGIT); state.progLength++ }
                's' -> { state.result = RENode(REOP_SPACE); state.progLength++ }
                'S' -> { state.result = RENode(REOP_NONSPACE); state.progLength++ }
                'w' -> { state.result = RENode(REOP_ALNUM); state.progLength++ }
                'W' -> { state.result = RENode(REOP_NONALNUM); state.progLength++ }
                'p', 'P' -> {
                    state.cp--
                    if (!parseUnicodePropertyEscape(state)) reportError("msg.invalid.property", "")
                }
                else -> {
                    state.cp--
                    return parseIdentityEscape(state, params)
                }
            }
            return true
        }

        /** `\00...`: several leading zeros, which SpiderMonkey and the web accept as one octal. */
        private fun parseMultipleLeadingZerosAsOctalEscape(state: CompilerState) {
            val src = state.cpbegin
            var num = 0
            reportWarning(state.cx, "msg.bad.backref", "")
            while (num < 32 && state.cp < state.cpend) {
                val c = src[state.cp]
                if (c in '0'..'7') {
                    state.cp++
                    num = 8 * num + (c - '0')
                } else {
                    break
                }
            }
            doFlat(state, num.toChar())
        }

        // ---- Character classes ---------------------------------------------------------------

        private fun parseClassContents(state: CompilerState, params: ParserParameters): ClassContents? {
            val src = state.cpbegin
            var rangeStart = 0
            var inRange = false
            var thisCodePoint: Int
            val contents = ClassContents()

            if (state.cp >= state.cpend) return null

            if (src[state.cp] == ']') {
                state.cp++
                return contents
            }
            if (src[state.cp] == '^') {
                state.cp++
                contents.sense = false
            }

            while (state.cp != state.cpend && src[state.cp] != ']') {
                if (src[state.cp] == '\\') {
                    state.cp++
                    if (state.cp < state.cpend && src[state.cp] == 'b') {
                        state.cp++
                        thisCodePoint = 0x08
                    } else if (params.unicodeMode && state.cp < state.cpend && src[state.cp] == '-') {
                        state.cp++
                        thisCodePoint = '-'.code
                    } else {
                        if (!parseCharacterAndCharacterClassEscape(state, params)) {
                            if (src[state.cp] == 'c' && !params.unicodeMode) {
                                // With 'c' next, the backslash itself is the literal.
                                thisCodePoint = '\\'.code
                            } else {
                                reportError("msg.invalid.escape", "")
                            }
                        } else {
                            val result = state.result!!
                            if (result.op == REOP_FLAT) {
                                thisCodePoint = if (result.lowSurrogate.code == 0) {
                                    result.chr.code
                                } else {
                                    Characters.toCodePoint(result.chr, result.lowSurrogate)
                                }
                            } else {
                                contents.escapeNodes.add(result)
                                if (inRange) {
                                    if (!params.unicodeMode) {
                                        contents.chars.add('-')
                                        inRange = false
                                    } else {
                                        reportError("msg.invalid.class", "")
                                    }
                                } else {
                                    if (state.cp < state.cpend && src[state.cp] == '-' && params.unicodeMode) {
                                        reportError("msg.invalid.class", "")
                                    }
                                }
                                // A whole-set escape cannot be one end of a range.
                                continue
                            }
                        }
                    }
                } else {
                    if ((state.flags and JSREG_UNICODE) != 0) {
                        thisCodePoint = codePointAt(src, state.cp, state.cpend)
                        state.cp += Characters.charCount(thisCodePoint)
                    } else {
                        thisCodePoint = src[state.cp].code
                        state.cp++
                    }
                }

                if (inRange) {
                    if (rangeStart > thisCodePoint) reportError("msg.bad.range", "")
                    inRange = false
                    if (rangeStart > 0xFFFF || thisCodePoint > 0xFFFF) {
                        contents.nonBMPRanges.add(rangeStart)
                        contents.nonBMPRanges.add(thisCodePoint)
                    } else {
                        contents.bmpRanges.add(rangeStart.toChar())
                        contents.bmpRanges.add(thisCodePoint.toChar())
                    }
                } else {
                    if (thisCodePoint > 0xFFFF) {
                        contents.nonBMPCodepoints.add(thisCodePoint)
                    } else {
                        contents.chars.add(thisCodePoint.toChar())
                    }
                    if (state.cp + 1 < state.cpend && src[state.cp + 1] != ']') {
                        if (src[state.cp] == '-') {
                            state.cp++
                            inRange = true
                            rangeStart = thisCodePoint
                        }
                    }
                }
            }

            if (state.cp < state.cpend && src[state.cp] == ']') state.cp++
            return contents
        }

        // ---- Terms and quantifiers -----------------------------------------------------------

        private fun parseTerm(state: CompilerState, params: ParserParameters): Boolean {
            val src = state.cpbegin
            var c = src[state.cp++]
            val parenBaseCount = state.parenCount
            var num: Int
            val term: RENode?
            var termStart: Int

            when (c) {
                '^' -> {
                    state.result = RENode(REOP_BOL)
                    state.progLength++
                    return true
                }
                '$' -> {
                    state.result = RENode(REOP_EOL)
                    state.progLength++
                    return true
                }
                '\\' -> {
                    if (state.cp < state.cpend) {
                        c = src[state.cp++]
                        when (c) {
                            'b' -> {
                                state.result = RENode(REOP_WBDRY)
                                state.progLength++
                                return true
                            }
                            'B' -> {
                                state.result = RENode(REOP_WNONBDRY)
                                state.progLength++
                                return true
                            }
                            '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                                termStart = state.cp - 1
                                num = getDecimalValue(c, state, "msg.overlarge.backref")
                                if (!params.unicodeMode && num > state.backReferenceLimit) {
                                    // Not enough groups for this number, so it is an octal escape.
                                    reportWarning(state.cx, "msg.bad.backref", "")
                                    state.cp = termStart
                                    if (!parseCharacterAndCharacterClassEscape(state, params)) return false
                                } else {
                                    state.result = RENode(REOP_BACKREF).apply { parenIndex = num - 1 }
                                    state.progLength += 3
                                    if (state.maxBackReference < num) state.maxBackReference = num
                                }
                            }
                            else -> {
                                var handled = false
                                if (c == '0' && state.cp < state.cpend && src[state.cp] == '0') {
                                    if (params.unicodeMode) {
                                        reportError("msg.invalid.escape", "")
                                    } else {
                                        // Deliberately looser than ES5.1, matching SpiderMonkey and
                                        // what the web actually relies on.
                                        parseMultipleLeadingZerosAsOctalEscape(state)
                                        handled = true
                                    }
                                }
                                if (!handled) {
                                    state.cp--
                                    if (!parseCharacterAndCharacterClassEscape(state, params)) {
                                        if (c == 'k' && params.namedCaptureGroups) {
                                            state.cp++
                                            val groupNameBuilder = StringBuilder()
                                            if (extractCaptureGroupName(state, groupNameBuilder)) {
                                                val groupName = groupNameBuilder.toString()
                                                if (groupName.isEmpty()) reportError("msg.invalid.group.name", "")
                                                state.result = RENode(REOP_NAMED_BACKREF).apply {
                                                    namedCaptureGroupBackRefIndex = state.namedCaptureBackRefs.size
                                                }
                                                state.namedCaptureBackRefs.add(groupName)
                                                state.progLength += 3
                                            } else {
                                                reportError("msg.invalid.named.backref", "")
                                            }
                                        } else if (c == 'c' && !params.unicodeMode) {
                                            // With 'c' next, the backslash itself is the literal.
                                            doFlat(state, '\\')
                                        } else {
                                            reportError("msg.invalid.escape", "")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // A trailing backslash is an error.
                        reportError("msg.trail.backslash", "")
                    }
                }
                '(' -> {
                    var result: RENode? = null
                    if (state.cp + 1 < state.cpend && src[state.cp] == '?' &&
                        (src[state.cp + 1].also { c = it } == '=' || c == '!' || c == ':')
                    ) {
                        state.cp += 2
                        if (c == '=') {
                            result = RENode(REOP_ASSERT)
                            state.progLength += 4
                        } else if (c == '!') {
                            result = RENode(REOP_ASSERT_NOT)
                            state.progLength += 4
                        }
                    } else if (state.cp + 2 < state.cpend && src[state.cp] == '?' && src[state.cp + 1] == '<' &&
                        (src[state.cp + 2].also { c = it } == '=' || c == '!')
                    ) {
                        state.cp += 3
                        result = if (c == '=') RENode(REOP_ASSERTBACK) else RENode(REOP_ASSERTBACK_NOT)
                        state.progLength += 4
                    } else {
                        result = RENode(REOP_LPAREN)
                        if (state.cp + 2 < state.cpend && src[state.cp] == '?' && src[state.cp + 1] == '<') {
                            state.cp += 1
                            val nameBuilder = StringBuilder()
                            if (!extractCaptureGroupName(state, nameBuilder)) reportError("msg.invalid.group.name", "")
                            result.namedCaptureGroupName = nameBuilder.toString()
                            if (result.namedCaptureGroupName!!.isEmpty()) reportError("msg.invalid.group.name", "")
                            state.namedCaptureGroupsFound = true
                        }
                        state.progLength += 6
                        result.parenIndex = state.parenCount++
                    }
                    ++state.parenNesting
                    if (!parseDisjunction(state, params)) return false
                    if (state.cp == state.cpend || src[state.cp] != ')') reportError("msg.unterm.paren", "")
                    ++state.cp
                    --state.parenNesting
                    if (result != null) {
                        // A lookbehind matches right to left, so its children are reversed.
                        if (result.op == REOP_ASSERTBACK || result.op == REOP_ASSERTBACK_NOT) {
                            state.result = reverseNodeList(state.result)
                        }
                        result.kid = state.result
                        state.result = result
                    }
                }
                ')' -> reportError("msg.re.unmatched.right.paren", "")
                '[' -> {
                    val classContents = parseClassContents(state, params) ?: reportError("msg.unterm.class", "")
                    state.result = RENode(REOP_CLASS).apply {
                        this.classContents = classContents
                        index = state.classCount++
                    }
                    // Sized now so a bad range is reported while parsing, not while matching.
                    if (!calculateBitmapSize(state.flags, classContents, state.result!!)) return false
                    state.progLength += 3
                }
                '.' -> {
                    state.result = RENode(REOP_DOT)
                    state.progLength++
                }
                '*', '+', '?' -> reportError("msg.bad.quant", src[state.cp - 1].toString())
                else -> {
                    if (params.unicodeMode && (c == ']' || c == '{' || c == '}')) {
                        reportError("msg.lone.quantifier.bracket", "")
                    }
                    if (params.unicodeMode && c.isHighSurrogate() && state.cp < state.cpend && src[state.cp].isLowSurrogate()) {
                        val low = src[state.cp++]
                        doFlatSurrogatePair(state, c, low)
                    } else {
                        state.result = RENode(REOP_FLAT).apply {
                            chr = c
                            length = 1
                            flatIndex = state.cp - 1
                        }
                        state.progLength += 3
                    }
                }
            }

            term = state.result
            if (state.cp == state.cpend) return true

            var hasQ = false
            when (src[state.cp]) {
                '+' -> {
                    state.result = RENode(REOP_QUANT).apply { min = 1; max = -1 }
                    state.progLength += 8
                    hasQ = true
                }
                '*' -> {
                    state.result = RENode(REOP_QUANT).apply { min = 0; max = -1 }
                    state.progLength += 8
                    hasQ = true
                }
                '?' -> {
                    state.result = RENode(REOP_QUANT).apply { min = 0; max = 1 }
                    state.progLength += 8
                    hasQ = true
                }
                '{' -> {
                    var min = 0
                    var max = -1
                    val leftCurl = state.cp

                    // Anything that is not exactly {n}, {n,} or {n,m} is not a quantifier at all;
                    // back off and let the braces be literals.
                    if (++state.cp < src.size && isDigit(src[state.cp].also { c = it })) {
                        ++state.cp
                        min = getDecimalValue(c, state, "msg.overlarge.min")
                        if (state.cp < src.size) {
                            c = src[state.cp]
                            if (c == ',' && ++state.cp < src.size) {
                                c = src[state.cp]
                                if (isDigit(c) && ++state.cp < src.size) {
                                    max = getDecimalValue(c, state, "msg.overlarge.max")
                                    c = src[state.cp]
                                    if (min > max) {
                                        throw ScriptRuntime.constructError(
                                            "SyntaxError",
                                            Messages.getMessageById("msg.max.lt.min", max, min),
                                        )
                                    }
                                }
                            } else {
                                max = min
                            }
                            if (c == '}') {
                                state.result = RENode(REOP_QUANT).apply { this.min = min; this.max = max }
                                state.progLength += 12
                                hasQ = true
                            }
                        }
                    }
                    if (!hasQ) state.cp = leftCurl
                }
                else -> {}
            }
            if (!hasQ) return true

            if (term!!.op == REOP_ASSERTBACK || term.op == REOP_ASSERTBACK_NOT) {
                reportError("msg.bad.quant", "")
            }
            if (params.unicodeMode && (term.op == REOP_ASSERT || term.op == REOP_ASSERT_NOT)) {
                reportError("msg.bad.quant", "")
            }

            ++state.cp
            val result = state.result!!
            result.kid = term
            result.parenIndex = parenBaseCount
            result.parenCount = state.parenCount - parenBaseCount
            if (state.cp < state.cpend && src[state.cp] == '?') {
                ++state.cp
                result.greedy = false
            } else {
                result.greedy = true
            }
            return true
        }

        // ---- Emitting the bytecode -----------------------------------------------------------

        private fun resolveForwardJump(array: ByteArray, from: Int, pc: Int) {
            if (from > pc) throw Kit.codeBug()
            addIndex(array, from, pc - from)
        }

        private fun getOffset(array: ByteArray, pc: Int): Int = getIndex(array, pc)

        private fun addIndex(array: ByteArray, pc: Int, index: Int): Int {
            if (index < 0) throw Kit.codeBug()
            if (index > 0xFFFF) throw Context.reportRuntimeError("Too complex regexp")
            array[pc] = (index shr 8).toByte()
            array[pc + 1] = index.toByte()
            return pc + 2
        }

        private fun getIndex(array: ByteArray, pc: Int): Int =
            ((array[pc].toInt() and 0xFF) shl 8) or (array[pc + 1].toInt() and 0xFF)

        private fun emitREBytecode(state: CompilerState, re: RECompiled, pcIn: Int, tIn: RENode?): Int {
            var pc = pcIn
            var t = tIn
            val program = re.program
            var nextAlt: RENode?
            var nextAltFixup: Int
            var nextTermFixup: Int

            while (t != null) {
                program[pc++] = t.op
                when (t.op) {
                    REOP_EMPTY -> --pc
                    REOP_ALTPREREQ, REOP_ALTPREREQi, REOP_ALTPREREQ2, REOP_ALT -> {
                        if (t.op == REOP_ALTPREREQ || t.op == REOP_ALTPREREQi || t.op == REOP_ALTPREREQ2) {
                            val ignoreCase = t.op == REOP_ALTPREREQi
                            addIndex(program, pc, if (ignoreCase) upcase(t.chr).code else t.chr.code)
                            pc += INDEX_LEN
                            addIndex(program, pc, if (ignoreCase) upcase(t.index.toChar()).code else t.index)
                            pc += INDEX_LEN
                        }
                        nextAlt = t.kid2
                        nextAltFixup = pc // where the other alternative starts
                        pc += INDEX_LEN
                        pc = emitREBytecode(state, re, pc, t.kid)
                        program[pc++] = REOP_JUMP
                        nextTermFixup = pc // where the term after the alternation starts
                        pc += INDEX_LEN
                        resolveForwardJump(program, nextAltFixup, pc)
                        pc = emitREBytecode(state, re, pc, nextAlt)

                        program[pc++] = REOP_JUMP
                        nextAltFixup = pc
                        pc += INDEX_LEN

                        resolveForwardJump(program, nextTermFixup, pc)
                        resolveForwardJump(program, nextAltFixup, pc)
                    }
                    REOP_FLAT -> {
                        if (t.flatIndex != -1 && t.length > 1) {
                            program[pc - 1] = if ((state.flags and JSREG_FOLD) != 0) REOP_FLATi else REOP_FLAT
                            pc = addIndex(program, pc, t.flatIndex)
                            pc = addIndex(program, pc, t.length)
                        } else if (t.chr.code < 256) {
                            program[pc - 1] = if ((state.flags and JSREG_FOLD) != 0) REOP_FLAT1i else REOP_FLAT1
                            program[pc++] = t.chr.code.toByte()
                        } else if (t.lowSurrogate.code == 0) {
                            program[pc - 1] = if ((state.flags and JSREG_FOLD) != 0) REOP_UCFLAT1i else REOP_UCFLAT1
                            pc = addIndex(program, pc, t.chr.code)
                        } else {
                            program[pc - 1] = REOP_UCSPFLAT1
                            pc = addIndex(program, pc, t.chr.code)
                            pc = addIndex(program, pc, t.lowSurrogate.code)
                        }
                    }
                    REOP_LPAREN -> {
                        pc = addIndex(program, pc, t.parenIndex)
                        pc = emitREBytecode(state, re, pc, t.kid)
                        program[pc++] = REOP_RPAREN
                        pc = addIndex(program, pc, t.parenIndex)
                    }
                    REOP_BACKREF -> pc = addIndex(program, pc, t.parenIndex)
                    REOP_NAMED_BACKREF -> {
                        val names = re.namedBackRefs ?: reportError("msg.invalid.named.backref", "")
                        val backRefName = names.getOrNull(t.namedCaptureGroupBackRefIndex)
                            ?: throw Kit.codeBug("emitREBytecode: namedBackRefIndex(${t.namedCaptureGroupBackRefIndex}) out of bounds")
                        val indices = re.namedCaptureGroups[backRefName]
                            ?: reportError("msg.invalid.named.backref", "")
                        if (indices.size == 1) {
                            // One capture with this name, so a plain back reference will do.
                            program[pc - 1] = REOP_BACKREF
                            pc = addIndex(program, pc, indices[0])
                        } else {
                            pc = addIndex(program, pc, t.namedCaptureGroupBackRefIndex)
                        }
                    }
                    REOP_ASSERT, REOP_ASSERTBACK -> {
                        nextTermFixup = pc
                        pc += INDEX_LEN
                        pc = emitREBytecode(state, re, pc, t.kid)
                        program[pc++] = if (t.op == REOP_ASSERT) REOP_ASSERTTEST else REOP_ASSERTBACKTEST
                        resolveForwardJump(program, nextTermFixup, pc)
                    }
                    REOP_ASSERT_NOT, REOP_ASSERTBACK_NOT -> {
                        nextTermFixup = pc
                        pc += INDEX_LEN
                        pc = emitREBytecode(state, re, pc, t.kid)
                        program[pc++] = if (t.op == REOP_ASSERT_NOT) REOP_ASSERTNOTTEST else REOP_ASSERTBACKNOTTEST
                        resolveForwardJump(program, nextTermFixup, pc)
                    }
                    REOP_QUANT -> {
                        if (t.min == 0 && t.max == -1) {
                            program[pc - 1] = if (t.greedy) REOP_STAR else REOP_MINIMALSTAR
                        } else if (t.min == 0 && t.max == 1) {
                            program[pc - 1] = if (t.greedy) REOP_OPT else REOP_MINIMALOPT
                        } else if (t.min == 1 && t.max == -1) {
                            program[pc - 1] = if (t.greedy) REOP_PLUS else REOP_MINIMALPLUS
                        } else {
                            if (!t.greedy) program[pc - 1] = REOP_MINIMALQUANT
                            pc = addIndex(program, pc, t.min)
                            // max may be -1, which addIndex refuses, so it is stored plus one.
                            pc = addIndex(program, pc, t.max + 1)
                        }
                        pc = addIndex(program, pc, t.parenCount)
                        pc = addIndex(program, pc, t.parenIndex)
                        nextTermFixup = pc
                        pc += INDEX_LEN
                        pc = emitREBytecode(state, re, pc, t.kid)
                        program[pc++] = REOP_ENDCHILD
                        resolveForwardJump(program, nextTermFixup, pc)
                    }
                    REOP_CLASS -> {
                        val contents = t.classContents!!
                        if (!contents.sense) program[pc - 1] = REOP_NCLASS
                        pc = addIndex(program, pc, t.index)
                        re.classList!![t.index] = RECharSet(contents, t.bmsize)
                    }
                    REOP_UPROP, REOP_UPROP_NOT -> pc = addIndex(program, pc, t.unicodeProperty)
                    else -> {}
                }
                t = t.next
            }
            return pc
        }

        // ---- The matcher ---------------------------------------------------------------------

        private fun pushProgState(
            gData: REGlobalData,
            min: Int,
            max: Int,
            cp: Int,
            matchBackward: Boolean,
            backTrackLastToSave: REBackTrackData?,
            continuationOp: Int,
            continuationPc: Int,
        ) {
            gData.stateStackTop = REProgState(
                gData.stateStackTop, min, max, cp, backTrackLastToSave, matchBackward, continuationOp, continuationPc,
            )
        }

        private fun popProgState(gData: REGlobalData): REProgState {
            val state = gData.stateStackTop!!
            gData.stateStackTop = state.previous
            return state
        }

        private fun pushBackTrackState(gData: REGlobalData, op: Byte, pc: Int) {
            val state = gData.stateStackTop!!
            gData.backTrackStackTop = REBackTrackData(gData, op.toInt(), pc, gData.cp, state.continuationOp, state.continuationPc)
        }

        private fun pushBackTrackState(gData: REGlobalData, op: Byte, pc: Int, cp: Int, continuationOp: Int, continuationPc: Int) {
            gData.backTrackStackTop = REBackTrackData(gData, op.toInt(), pc, cp, continuationOp, continuationPc)
        }

        private fun flatNMatcher(gData: REGlobalData, matchChars: Int, length: Int, input: String, end: Int): Boolean {
            if ((gData.cp + length) > end) return false
            val source = gData.regexp!!.source
            for (i in 0 until length) {
                if (source[matchChars + i] != input[gData.cp + i]) return false
            }
            gData.cp += length
            return true
        }

        private fun flatNMatcherBackward(gData: REGlobalData, matchChars: Int, length: Int, input: String): Boolean {
            if ((gData.cp - length) < 0) return false
            val source = gData.regexp!!.source
            // Walks the input backwards from cp - 1 and the pattern forwards from its end.
            for (i in 1..length) {
                if (source[matchChars + length - i] != input[gData.cp - i]) return false
            }
            gData.cp -= length
            return true
        }

        private fun flatNIMatcher(gData: REGlobalData, matchChars: Int, length: Int, input: String, end: Int): Boolean {
            if ((gData.cp + length) > end) return false
            val source = gData.regexp!!.source
            for (i in 0 until length) {
                val c1 = source[matchChars + i]
                val c2 = input[gData.cp + i]
                if (c1 != c2 && upcase(c1) != upcase(c2)) return false
            }
            gData.cp += length
            return true
        }

        private fun flatNIMatcherBackward(gData: REGlobalData, matchChars: Int, length: Int, input: String): Boolean {
            if ((gData.cp - length) < 0) return false
            val source = gData.regexp!!.source
            for (i in 1..length) {
                val c1 = source[matchChars + length - i]
                val c2 = input[gData.cp - i]
                if (c1 != c2 && upcase(c1) != upcase(c2)) return false
            }
            gData.cp -= length
            return true
        }

        /** A back reference matches whatever the numbered group captured, if it captured at all. */
        private fun backrefMatcher(gData: REGlobalData, parenIndex: Int, input: String, end: Int, matchBackward: Boolean): Boolean {
            val parens = gData.parens ?: return false
            if (parenIndex >= parens.size) return false
            val parenContent = gData.parensIndex(parenIndex)
            // A group that never took part matches the empty string.
            if (parenContent == -1) return true

            val len = gData.parensLength(parenIndex)
            val fold = (gData.regexp!!.flags and JSREG_FOLD) != 0

            // The capture itself is always stored in input order, whichever way we are matching.
            if (matchBackward) {
                if ((gData.cp - len) < 0) return false
                if (fold) {
                    for (i in 0 until len) {
                        val c1 = input[parenContent + i]
                        val c2 = input[gData.cp + i - len]
                        if (c1 != c2 && upcase(c1) != upcase(c2)) return false
                    }
                } else if (!input.regionMatches(gData.cp - len, input, parenContent, len)) {
                    return false
                }
                gData.cp -= len
            } else {
                if ((gData.cp + len) > end) return false
                if (fold) {
                    for (i in 0 until len) {
                        val c1 = input[parenContent + i]
                        val c2 = input[gData.cp + i]
                        if (c1 != c2 && upcase(c1) != upcase(c2)) return false
                    }
                } else if (!input.regionMatches(gData.cp, input, parenContent, len)) {
                    return false
                }
                gData.cp += len
            }
            return true
        }

        private fun addCharacterToCharSet(cs: RECharSet, c: Char) {
            val byteIndex = c.code / 8
            if (c.code >= cs.length) {
                throw ScriptRuntime.constructError("SyntaxError", "invalid range in character class")
            }
            cs.bits!![byteIndex] = (cs.bits!![byteIndex].toInt() or (1 shl (c.code and 0x7))).toByte()
        }

        private fun addCharacterRangeToCharSet(cs: RECharSet, c1In: Char, c2In: Char) {
            val byteIndex1 = c1In.code / 8
            val byteIndex2 = c2In.code / 8

            if (c2In.code >= cs.length || c1In > c2In) {
                throw ScriptRuntime.constructError("SyntaxError", "invalid range in character class")
            }

            val c1 = c1In.code and 0x7
            val c2 = c2In.code and 0x7
            val bits = cs.bits!!

            if (byteIndex1 == byteIndex2) {
                bits[byteIndex1] = (bits[byteIndex1].toInt() or ((0xFF shr (7 - (c2 - c1))) shl c1)).toByte()
            } else {
                bits[byteIndex1] = (bits[byteIndex1].toInt() or (0xFF shl c1)).toByte()
                for (i in byteIndex1 + 1 until byteIndex2) bits[i] = 0xFF.toByte()
                bits[byteIndex2] = (bits[byteIndex2].toInt() or (0xFF shr (7 - c2))).toByte()
            }
        }

        /** Builds the bitmap for a class the first time the matcher reaches it. */
        private fun processCharSet(gData: REGlobalData, charSet: RECharSet) {
            if (!charSet.converted) {
                processCharSetImpl(gData, charSet)
                charSet.converted = true
            }
        }

        private fun processCharSetImpl(gData: REGlobalData, charSet: RECharSet) {
            val classContents = charSet.classContents
            val byteLength = (charSet.length + 7) / 8
            charSet.bits = ByteArray(byteLength)
            val fold = (gData.regexp!!.flags and JSREG_FOLD) != 0

            for (ch in classContents.chars) {
                addCharacterToCharSet(charSet, ch)
                if (fold) {
                    val uch = upcase(ch)
                    val dch = downcase(ch)
                    if (ch != uch) addCharacterToCharSet(charSet, uch)
                    if (ch != dch) addCharacterToCharSet(charSet, dch)
                }
            }

            var j = 0
            while (j < classContents.bmpRanges.size) {
                val start = classContents.bmpRanges[j]
                val end = classContents.bmpRanges[j + 1]
                if (fold) {
                    var ch = start
                    while (ch <= end) {
                        addCharacterToCharSet(charSet, ch)
                        val uch = upcase(ch)
                        val dch = downcase(ch)
                        if (ch != uch) addCharacterToCharSet(charSet, uch)
                        if (ch != dch) addCharacterToCharSet(charSet, dch)
                        ch = (ch.code + 1).toChar()
                        if (ch.code == 0) break // wrapped past the end of the BMP
                    }
                } else {
                    addCharacterRangeToCharSet(charSet, start, end)
                }
                j += 2
            }

            for (escape in classContents.escapeNodes) {
                when (escape.op) {
                    REOP_DIGIT -> addCharacterRangeToCharSet(charSet, '0', '9')
                    REOP_NONDIGIT -> {
                        addCharacterRangeToCharSet(charSet, 0.toChar(), ('0'.code - 1).toChar())
                        addCharacterRangeToCharSet(charSet, ('9'.code + 1).toChar(), (charSet.length - 1).toChar())
                    }
                    REOP_SPACE -> for (i in charSet.length - 1 downTo 0) if (isREWhiteSpace(i)) addCharacterToCharSet(charSet, i.toChar())
                    REOP_NONSPACE -> for (i in charSet.length - 1 downTo 0) if (!isREWhiteSpace(i)) addCharacterToCharSet(charSet, i.toChar())
                    REOP_ALNUM -> for (i in charSet.length - 1 downTo 0) if (isWord(i.toChar())) addCharacterToCharSet(charSet, i.toChar())
                    REOP_NONALNUM -> for (i in charSet.length - 1 downTo 0) if (!isWord(i.toChar())) addCharacterToCharSet(charSet, i.toChar())
                    REOP_UPROP -> charSet.unicodeProps.add(escape.unicodeProperty)
                    REOP_UPROP_NOT -> charSet.negUnicodeProps.add(escape.unicodeProperty)
                    else -> throw Kit.codeBug("classContents contains invalid escape node type")
                }
            }
        }

        /** Tests a code point against a class. A negated class answers the other way round. */
        private fun classMatcher(gData: REGlobalData, charSet: RECharSet, codePoint: Int): Boolean {
            if (!charSet.converted) processCharSet(gData, charSet)

            if (codePoint <= 0xFFFF) {
                val byteIndex = codePoint shr 3
                if (!(charSet.length == 0 || codePoint >= charSet.length ||
                        (charSet.bits!![byteIndex].toInt() and (1 shl (codePoint and 0x7))) == 0)
                ) {
                    return charSet.classContents.sense
                }
            }

            if (charSet.classContents.nonBMPCodepoints.contains(codePoint)) return charSet.classContents.sense

            var i = 0
            while (i < charSet.classContents.nonBMPRanges.size) {
                if (codePoint >= charSet.classContents.nonBMPRanges[i] && codePoint <= charSet.classContents.nonBMPRanges[i + 1]) {
                    return charSet.classContents.sense
                }
                i += 2
            }

            for (encodedProp in charSet.unicodeProps) {
                if (UnicodeProperties.hasProperty(encodedProp, codePoint)) return charSet.classContents.sense
            }
            for (encodedProp in charSet.negUnicodeProps) {
                if (!UnicodeProperties.hasProperty(encodedProp, codePoint)) return charSet.classContents.sense
            }
            return !charSet.classContents.sense
        }

        private fun reopIsSimple(op: Int): Boolean = op >= REOP_SIMPLE_START && op <= REOP_SIMPLE_END

        /**
         * Runs one simple opcode. Answers the pc after it on a match and -1 on a miss; with
         * [updatecp] false the input position is put back either way.
         */
        private fun simpleMatch(
            gData: REGlobalData,
            input: String,
            op: Int,
            program: ByteArray,
            pcIn: Int,
            end: Int,
            updatecp: Boolean,
            matchBackward: Boolean,
        ): Int {
            var pc = pcIn
            var result = false
            var matchCodePoint: Int
            val startcp = gData.cp
            val cpDelta: Int
            val cpToMatch: Int

            if ((gData.regexp!!.flags and JSREG_UNICODE) != 0 && gData.cp < end) {
                if (matchBackward) {
                    if (gData.cp - 2 >= 0 && input[gData.cp - 2].isHighSurrogate() && input[gData.cp - 1].isLowSurrogate()) {
                        cpDelta = -2
                        cpToMatch = gData.cp - 2
                    } else {
                        cpDelta = -1
                        cpToMatch = gData.cp - 1
                    }
                } else {
                    cpDelta = Characters.charCount(Characters.codePointAt(input, gData.cp))
                    cpToMatch = gData.cp
                }
            } else {
                cpDelta = if (matchBackward) -1 else 1
                cpToMatch = gData.cp + (if (matchBackward) -1 else 0)
            }
            val cpInBounds = cpToMatch >= 0 && cpToMatch < end

            when (op) {
                REOP_EMPTY.toInt() -> result = true
                // The anchors read gData.cp directly: they behave the same in both directions.
                REOP_BOL.toInt() -> {
                    if (gData.cp != 0 && (!gData.multiline || !isLineTerm(input[gData.cp - 1]))) {
                        // no match
                    } else {
                        result = true
                    }
                }
                REOP_EOL.toInt() -> {
                    if (gData.cp != end && (!gData.multiline || !isLineTerm(input[gData.cp]))) {
                        // no match
                    } else {
                        result = true
                    }
                }
                REOP_WBDRY.toInt() -> {
                    result = (gData.cp == 0 || !isWord(input[gData.cp - 1])) xor
                        !((gData.cp < end) && isWord(input[gData.cp]))
                }
                REOP_WNONBDRY.toInt() -> {
                    result = (gData.cp == 0 || !isWord(input[gData.cp - 1])) xor
                        ((gData.cp < end) && isWord(input[gData.cp]))
                }
                REOP_DOT.toInt() -> {
                    if (cpInBounds && ((gData.regexp!!.flags and JSREG_DOTALL) != 0 || !isLineTerm(input[cpToMatch]))) {
                        result = true
                        gData.cp += cpDelta
                    }
                }
                REOP_DIGIT.toInt() -> if (cpInBounds && isDigit(input[cpToMatch])) { result = true; gData.cp += cpDelta }
                REOP_NONDIGIT.toInt() -> if (cpInBounds && !isDigit(input[cpToMatch])) { result = true; gData.cp += cpDelta }
                REOP_ALNUM.toInt() -> if (cpInBounds && isWord(input[cpToMatch])) { result = true; gData.cp += cpDelta }
                REOP_NONALNUM.toInt() -> if (cpInBounds && !isWord(input[cpToMatch])) { result = true; gData.cp += cpDelta }
                REOP_SPACE.toInt() -> if (cpInBounds && isREWhiteSpace(input[cpToMatch].code)) { result = true; gData.cp += cpDelta }
                REOP_NONSPACE.toInt() -> if (cpInBounds && !isREWhiteSpace(input[cpToMatch].code)) { result = true; gData.cp += cpDelta }
                REOP_BACKREF.toInt() -> {
                    val parenIndex = getIndex(program, pc)
                    pc += INDEX_LEN
                    result = backrefMatcher(gData, parenIndex, input, end, matchBackward)
                }
                REOP_NAMED_BACKREF.toInt() -> {
                    val backRefNameIndex = getIndex(program, pc)
                    pc += INDEX_LEN
                    val names = gData.regexp!!.namedBackRefs
                    if (gData.parens != null && names != null && backRefNameIndex < names.size) {
                        val backRefName = names[backRefNameIndex]
                        val indices = gData.regexp!!.namedCaptureGroups[backRefName]!!
                        var failed = false
                        for (i in indices) {
                            if (gData.parensIndex(i) == -1) continue
                            result = backrefMatcher(gData, i, input, end, matchBackward)
                            if (result) break else failed = true
                        }
                        // No branch of the name captured anything, so it matches the empty string.
                        if (!failed) result = true
                    }
                }
                REOP_FLAT.toInt() -> {
                    val offset = getIndex(program, pc)
                    pc += INDEX_LEN
                    val length = getIndex(program, pc)
                    pc += INDEX_LEN
                    result = if (matchBackward) {
                        flatNMatcherBackward(gData, offset, length, input)
                    } else {
                        flatNMatcher(gData, offset, length, input, end)
                    }
                }
                REOP_FLAT1.toInt() -> {
                    matchCodePoint = program[pc++].toInt() and 0xFF
                    if (cpInBounds) {
                        val inputCodePoint = if ((gData.regexp!!.flags and JSREG_UNICODE) != 0) {
                            Characters.codePointAt(input, cpToMatch)
                        } else {
                            input[cpToMatch].code
                        }
                        if (inputCodePoint == matchCodePoint) {
                            result = true
                            gData.cp += cpDelta
                        }
                    }
                }
                REOP_FLATi.toInt() -> {
                    val offset = getIndex(program, pc)
                    pc += INDEX_LEN
                    val length = getIndex(program, pc)
                    pc += INDEX_LEN
                    result = if (matchBackward) {
                        flatNIMatcherBackward(gData, offset, length, input)
                    } else {
                        flatNIMatcher(gData, offset, length, input, end)
                    }
                }
                REOP_FLAT1i.toInt() -> {
                    // The u and i flags cannot be used together, so no code point handling here.
                    matchCodePoint = program[pc++].toInt() and 0xFF
                    if (cpInBounds) {
                        val c = input[cpToMatch]
                        if (matchCodePoint == c.code || upcase(matchCodePoint.toChar()) == upcase(c)) {
                            result = true
                            gData.cp += cpDelta
                        }
                    }
                }
                REOP_UCFLAT1.toInt() -> {
                    matchCodePoint = getIndex(program, pc)
                    pc += INDEX_LEN
                    if (cpInBounds) {
                        val inputCodePoint = if ((gData.regexp!!.flags and JSREG_UNICODE) != 0) {
                            Characters.codePointAt(input, cpToMatch)
                        } else {
                            input[cpToMatch].code
                        }
                        if (inputCodePoint == matchCodePoint) {
                            result = true
                            gData.cp += cpDelta
                        }
                    }
                }
                REOP_UCFLAT1i.toInt() -> {
                    matchCodePoint = getIndex(program, pc)
                    pc += INDEX_LEN
                    if (cpInBounds) {
                        val c = input[cpToMatch]
                        if (matchCodePoint == c.code || upcase(matchCodePoint.toChar()) == upcase(c)) {
                            result = true
                            gData.cp += cpDelta
                        }
                    }
                }
                REOP_CLASS.toInt(), REOP_NCLASS.toInt() -> {
                    val index = getIndex(program, pc)
                    pc += INDEX_LEN
                    if (cpInBounds) {
                        val inputCodePoint = if ((gData.regexp!!.flags and JSREG_UNICODE) != 0) {
                            Characters.codePointAt(input, cpToMatch)
                        } else {
                            input[cpToMatch].code
                        }
                        if (classMatcher(gData, gData.regexp!!.classList!![index]!!, inputCodePoint)) {
                            gData.cp += cpDelta
                            result = true
                        }
                    }
                }
                REOP_UCSPFLAT1.toInt() -> {
                    val highSurrogate = getIndex(program, pc).toChar()
                    pc += INDEX_LEN
                    val lowSurrogate = getIndex(program, pc).toChar()
                    pc += INDEX_LEN
                    matchCodePoint = Characters.toCodePoint(highSurrogate, lowSurrogate)
                    if (cpInBounds) {
                        if (matchCodePoint == Characters.codePointAt(input, cpToMatch)) {
                            result = true
                            gData.cp += cpDelta
                        }
                    }
                }
                REOP_UPROP.toInt(), REOP_UPROP_NOT.toInt() -> {
                    val encodedProp = getIndex(program, pc)
                    pc += INDEX_LEN
                    if (cpInBounds) {
                        val sense = op == REOP_UPROP.toInt()
                        result = sense xor !UnicodeProperties.hasProperty(encodedProp, Characters.codePointAt(input, cpToMatch))
                        gData.cp += cpDelta
                    }
                }
                else -> throw Kit.codeBug()
            }
            if (result) {
                if (!updatecp) gData.cp = startcp
                return pc
            }
            gData.cp = startcp
            return -1
        }

        /**
         * Runs the compiled bytecode. Failure at any point pops the newest backtrack frame and
         * carries on from there; when there is none left the whole attempt has failed.
         */
        private fun executeREBytecode(cx: Context, gData: REGlobalData, input: String, end: Int): Boolean {
            var pc = 0
            val program = gData.regexp!!.program
            var continuationOp = REOP_END.toInt()
            var continuationPc = 0
            var result = false
            var matchBackward = false

            var op = program[pc++].toInt()

            // When the pattern opens with something simple, walk the input forward until it
            // matches rather than trying the whole pattern at every position.
            if (gData.regexp!!.anchorCodePoint < 0 && reopIsSimple(op)) {
                var anchor = false
                while (gData.cp <= end) {
                    val match = simpleMatch(gData, input, op, program, pc, end, true, false)
                    if (match < 0) {
                        if ((gData.regexp!!.flags and JSREG_STICKY) != 0) return false
                    } else {
                        anchor = true
                        pc = match
                        op = program[pc++].toInt()
                        break
                    }

                    if ((gData.regexp!!.flags and JSREG_UNICODE) != 0 && gData.cp < end) {
                        val toSkip = Characters.charCount(Characters.codePointAt(input, gData.cp))
                        gData.cp += toSkip
                        gData.skipped += toSkip
                    } else {
                        gData.cp++
                        gData.skipped++
                    }
                }
                if (!anchor) return false
            }

            val instructionCounting = cx.instructionObserverThreshold != 0
            mainLoop@ while (true) {
                if (instructionCounting) ScriptRuntime.addInstructionCount(cx, 5)

                if (reopIsSimple(op)) {
                    val match = simpleMatch(gData, input, op, program, pc, end, true, matchBackward)
                    result = match >= 0
                    if (result) pc = match
                } else {
                    // The body stands in for Java's labelled switch: leaving it early is the
                    // equivalent of "break switchStatement", falling out the bottom of the case.
                    run switchBlock@{
                        when (op) {
                            REOP_ALTPREREQ.toInt(), REOP_ALTPREREQi.toInt(), REOP_ALTPREREQ2.toInt(), REOP_ALT.toInt() -> {
                                if (op != REOP_ALT.toInt()) {
                                    val matchCh1 = getIndex(program, pc).toChar()
                                    pc += INDEX_LEN
                                    val matchCh2 = getIndex(program, pc).toChar()
                                    pc += INDEX_LEN

                                    val cpToMatch = gData.cp + (if (matchBackward) -1 else 0)
                                    if (!(cpToMatch >= 0 && cpToMatch < end)) {
                                        result = false
                                        return@switchBlock
                                    }
                                    var c = input[cpToMatch]
                                    if (op == REOP_ALTPREREQ2.toInt()) {
                                        if (c != matchCh1 && !classMatcher(gData, gData.regexp!!.classList!![matchCh2.code]!!, c.code)) {
                                            result = false
                                            return@switchBlock
                                        }
                                    } else {
                                        if (op == REOP_ALTPREREQi.toInt()) c = upcase(c)
                                        if (c != matchCh1 && c != matchCh2) {
                                            result = false
                                            return@switchBlock
                                        }
                                    }
                                }
                                // Falls into the plain ALT handling.
                                var nextpc = pc + getOffset(program, pc)
                                pc += INDEX_LEN
                                op = program[pc++].toInt()
                                val startcp = gData.cp
                                if (reopIsSimple(op)) {
                                    val match = simpleMatch(gData, input, op, program, pc, end, true, matchBackward)
                                    if (match < 0) {
                                        op = program[nextpc++].toInt()
                                        pc = nextpc
                                        continue@mainLoop
                                    }
                                    result = true
                                    pc = match
                                    op = program[pc++].toInt()
                                }
                                val nextop = program[nextpc++]
                                pushBackTrackState(gData, nextop, nextpc, startcp, continuationOp, continuationPc)
                                continue@mainLoop
                            }

                            REOP_JUMP.toInt() -> {
                                val offset = getOffset(program, pc)
                                pc += offset
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_LPAREN.toInt() -> {
                                val parenIndex = getIndex(program, pc)
                                pc += INDEX_LEN
                                gData.setParens(parenIndex, gData.cp, 0)
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_RPAREN.toInt() -> {
                                val parenIndex = getIndex(program, pc)
                                pc += INDEX_LEN
                                val capIndex = gData.parensIndex(parenIndex)
                                if (matchBackward) {
                                    // A lookbehind captures right to left, so the span is flipped.
                                    gData.setParens(parenIndex, gData.cp, capIndex - gData.cp)
                                } else {
                                    gData.setParens(parenIndex, capIndex, gData.cp - capIndex)
                                }
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_ASSERTBACK.toInt() -> {
                                val nextpc = pc + getIndex(program, pc)
                                pc += INDEX_LEN
                                op = program[pc++].toInt()
                                if (reopIsSimple(op) && simpleMatch(gData, input, op, program, pc, end, false, true) < 0) {
                                    result = false
                                    return@switchBlock
                                }
                                pushProgState(gData, 0, 0, gData.cp, matchBackward, gData.backTrackStackTop, continuationOp, continuationPc)
                                pushBackTrackState(gData, REOP_ASSERTBACKTEST, nextpc, gData.cp, continuationOp, continuationPc)
                                matchBackward = true
                                continue@mainLoop
                            }

                            REOP_ASSERTBACK_NOT.toInt() -> {
                                val nextpc = pc + getIndex(program, pc)
                                pc += INDEX_LEN
                                op = program[pc++].toInt()
                                if (reopIsSimple(op)) {
                                    val match = simpleMatch(gData, input, op, program, pc, end, false, true)
                                    if (match >= 0 && program[match] == REOP_ASSERTBACKNOTTEST) {
                                        result = false
                                        return@switchBlock
                                    }
                                }
                                pushProgState(gData, 0, 0, gData.cp, matchBackward, gData.backTrackStackTop, continuationOp, continuationPc)
                                pushBackTrackState(gData, REOP_ASSERTBACKNOTTEST, nextpc, gData.cp, continuationOp, continuationPc)
                                matchBackward = true
                                continue@mainLoop
                            }

                            REOP_ASSERT.toInt() -> {
                                val nextpc = pc + getIndex(program, pc)
                                pc += INDEX_LEN
                                op = program[pc++].toInt()
                                if (reopIsSimple(op) && simpleMatch(gData, input, op, program, pc, end, false, false) < 0) {
                                    result = false
                                    return@switchBlock
                                }
                                pushProgState(gData, 0, 0, gData.cp, matchBackward, gData.backTrackStackTop, continuationOp, continuationPc)
                                pushBackTrackState(gData, REOP_ASSERTTEST, nextpc)
                                matchBackward = false
                                continue@mainLoop
                            }

                            REOP_ASSERT_NOT.toInt() -> {
                                val nextpc = pc + getIndex(program, pc)
                                pc += INDEX_LEN
                                op = program[pc++].toInt()
                                if (reopIsSimple(op)) {
                                    val match = simpleMatch(gData, input, op, program, pc, end, false, false)
                                    if (match >= 0 && program[match] == REOP_ASSERTNOTTEST) {
                                        result = false
                                        return@switchBlock
                                    }
                                }
                                pushProgState(gData, 0, 0, gData.cp, matchBackward, gData.backTrackStackTop, continuationOp, continuationPc)
                                pushBackTrackState(gData, REOP_ASSERTNOTTEST, nextpc)
                                matchBackward = false
                                continue@mainLoop
                            }

                            REOP_ASSERTTEST.toInt(), REOP_ASSERTBACKTEST.toInt(),
                            REOP_ASSERTNOTTEST.toInt(), REOP_ASSERTBACKNOTTEST.toInt(),
                            -> {
                                val state = popProgState(gData)
                                gData.cp = state.index
                                gData.backTrackStackTop = state.backTrack
                                matchBackward = state.matchBackward
                                continuationPc = state.continuationPc
                                continuationOp = state.continuationOp
                                if (op == REOP_ASSERTNOTTEST.toInt() || op == REOP_ASSERTBACKNOTTEST.toInt()) {
                                    result = !result
                                }
                            }

                            REOP_STAR.toInt(), REOP_PLUS.toInt(), REOP_OPT.toInt(), REOP_QUANT.toInt(),
                            REOP_MINIMALSTAR.toInt(), REOP_MINIMALPLUS.toInt(), REOP_MINIMALOPT.toInt(), REOP_MINIMALQUANT.toInt(),
                            -> {
                                val min: Int
                                val max: Int
                                var greedy = false
                                when (op) {
                                    REOP_STAR.toInt() -> { greedy = true; min = 0; max = -1 }
                                    REOP_MINIMALSTAR.toInt() -> { min = 0; max = -1 }
                                    REOP_PLUS.toInt() -> { greedy = true; min = 1; max = -1 }
                                    REOP_MINIMALPLUS.toInt() -> { min = 1; max = -1 }
                                    REOP_OPT.toInt() -> { greedy = true; min = 0; max = 1 }
                                    REOP_MINIMALOPT.toInt() -> { min = 0; max = 1 }
                                    REOP_QUANT.toInt(), REOP_MINIMALQUANT.toInt() -> {
                                        greedy = op == REOP_QUANT.toInt()
                                        min = getOffset(program, pc)
                                        pc += INDEX_LEN
                                        // Stored plus one, see emitREBytecode.
                                        max = getOffset(program, pc) - 1
                                        pc += INDEX_LEN
                                    }
                                    else -> throw Kit.codeBug()
                                }
                                pushProgState(gData, min, max, gData.cp, matchBackward, null, continuationOp, continuationPc)
                                if (greedy) {
                                    pushBackTrackState(gData, REOP_REPEAT, pc)
                                    continuationOp = REOP_REPEAT.toInt()
                                    continuationPc = pc
                                    // Steps over <parencount>, <parenindex> and <next>.
                                    pc += 3 * INDEX_LEN
                                } else {
                                    if (min != 0) {
                                        continuationOp = REOP_MINIMALREPEAT.toInt()
                                        continuationPc = pc
                                        pc += 3 * INDEX_LEN
                                    } else {
                                        pushBackTrackState(gData, REOP_MINIMALREPEAT, pc)
                                        popProgState(gData)
                                        pc += 2 * INDEX_LEN
                                        pc += getOffset(program, pc)
                                    }
                                }
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_ENDCHILD.toInt() -> {
                                // Reaching here without a result means the child matched nothing,
                                // which is the same as REOP_EMPTY.
                                result = true
                                pc = continuationPc
                                op = continuationOp
                                continue@mainLoop
                            }

                            REOP_REPEAT.toInt() -> {
                                var nextpc = 0
                                var nextop = 0
                                do {
                                    val state = popProgState(gData)
                                    if (!result) {
                                        // Enough children already, if the minimum allows it.
                                        if (state.min == 0) result = true
                                        continuationPc = state.continuationPc
                                        continuationOp = state.continuationOp
                                        pc += 2 * INDEX_LEN
                                        pc += getOffset(program, pc)
                                        return@switchBlock
                                    }
                                    if (state.min == 0 && (gData.cp == state.index || state.max == 0)) {
                                        // An empty match or an {0} quantifier would loop forever.
                                        result = false
                                        continuationPc = state.continuationPc
                                        continuationOp = state.continuationOp
                                        pc += 2 * INDEX_LEN
                                        pc += getOffset(program, pc)
                                        return@switchBlock
                                    }
                                    var newMin = state.min
                                    var newMax = state.max
                                    if (newMin != 0) newMin--
                                    if (newMax != -1) newMax--
                                    if (newMax == 0) {
                                        result = true
                                        continuationPc = state.continuationPc
                                        continuationOp = state.continuationOp
                                        pc += 2 * INDEX_LEN
                                        pc += getOffset(program, pc)
                                        return@switchBlock
                                    }
                                    nextpc = pc + 3 * INDEX_LEN
                                    nextop = program[nextpc].toInt()
                                    val startcp = gData.cp
                                    if (reopIsSimple(nextop)) {
                                        nextpc++
                                        val match = simpleMatch(gData, input, nextop, program, nextpc, end, true, matchBackward)
                                        if (match < 0) {
                                            result = newMin == 0
                                            continuationPc = state.continuationPc
                                            continuationOp = state.continuationOp
                                            pc += 2 * INDEX_LEN
                                            pc += getOffset(program, pc)
                                            return@switchBlock
                                        }
                                        result = true
                                        nextpc = match
                                    }
                                    continuationOp = REOP_REPEAT.toInt()
                                    continuationPc = pc
                                    pushProgState(gData, newMin, newMax, startcp, matchBackward, null, state.continuationOp, state.continuationPc)
                                    if (newMin == 0) {
                                        pushBackTrackState(gData, REOP_REPEAT, pc, startcp, state.continuationOp, state.continuationPc)
                                    }
                                    val parenCount = getIndex(program, pc)
                                    val parenIndex = getIndex(program, pc + INDEX_LEN)
                                    for (k in 0 until parenCount) gData.setParens(parenIndex + k, -1, 0)
                                } while (program[nextpc] == REOP_ENDCHILD)

                                pc = nextpc
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_MINIMALREPEAT.toInt() -> {
                                val state = popProgState(gData)
                                if (!result) {
                                    // The lazy path failed, so take one more child if allowed.
                                    if (state.max == -1 || state.max > 0) {
                                        pushProgState(gData, state.min, state.max, gData.cp, matchBackward, null, state.continuationOp, state.continuationPc)
                                        continuationOp = REOP_MINIMALREPEAT.toInt()
                                        continuationPc = pc
                                        val parenCount = getIndex(program, pc)
                                        pc += INDEX_LEN
                                        val parenIndex = getIndex(program, pc)
                                        pc += 2 * INDEX_LEN
                                        for (k in 0 until parenCount) gData.setParens(parenIndex + k, -1, 0)
                                        op = program[pc++].toInt()
                                        continue@mainLoop
                                    }
                                    // pc does not need adjusting, the frame is about to be popped.
                                    continuationPc = state.continuationPc
                                    continuationOp = state.continuationOp
                                    return@switchBlock
                                }
                                if (state.min == 0 && gData.cp == state.index) {
                                    // An empty match would loop forever.
                                    result = false
                                    continuationPc = state.continuationPc
                                    continuationOp = state.continuationOp
                                    return@switchBlock
                                }
                                var newMin = state.min
                                var newMax = state.max
                                if (newMin != 0) newMin--
                                if (newMax != -1) newMax--
                                pushProgState(gData, newMin, newMax, gData.cp, matchBackward, null, state.continuationOp, state.continuationPc)
                                if (newMin != 0) {
                                    continuationOp = REOP_MINIMALREPEAT.toInt()
                                    continuationPc = pc
                                    val parenCount = getIndex(program, pc)
                                    pc += INDEX_LEN
                                    val parenIndex = getIndex(program, pc)
                                    pc += 2 * INDEX_LEN
                                    for (k in 0 until parenCount) gData.setParens(parenIndex + k, -1, 0)
                                } else {
                                    continuationPc = state.continuationPc
                                    continuationOp = state.continuationOp
                                    pushBackTrackState(gData, REOP_MINIMALREPEAT, pc)
                                    popProgState(gData)
                                    pc += 2 * INDEX_LEN
                                    pc += getOffset(program, pc)
                                }
                                op = program[pc++].toInt()
                                continue@mainLoop
                            }

                            REOP_END.toInt() -> return true

                            else -> throw Kit.codeBug("invalid bytecode")
                        }
                    }
                }

                // A failed step takes the newest backtrack frame, or gives up.
                if (!result) {
                    val backTrackData = gData.backTrackStackTop
                    if (backTrackData != null) {
                        gData.backTrackStackTop = backTrackData.previous
                        gData.parens = backTrackData.parens
                        gData.cp = backTrackData.cp
                        gData.stateStackTop = backTrackData.stateStackTop
                        continuationOp = backTrackData.continuationOp
                        continuationPc = backTrackData.continuationPc
                        pc = backTrackData.pc
                        op = backTrackData.op
                        continue@mainLoop
                    }
                    return false
                }

                op = program[pc++].toInt()
            }
        }

        /** Tries the pattern at each position from [start] until one sticks. */
        private fun matchRegExp(
            cx: Context,
            gData: REGlobalData,
            re: RECompiled,
            input: String,
            start: Int,
            end: Int,
            multiline: Boolean,
        ): Boolean {
            gData.parens = if (re.parenCount != 0) LongArray(re.parenCount) else null
            gData.backTrackStackTop = null
            gData.stateStackTop = null
            gData.multiline = multiline || (re.flags and JSREG_MULTILINE) != 0
            gData.regexp = re

            val anchorCodePoint = gData.regexp!!.anchorCodePoint

            // The loop goes one past the last character so an end-of-input assertion can fire.
            var i = start
            while (i <= end) {
                if (anchorCodePoint >= 0) {
                    // The pattern starts with a known character, so skip ahead to the next one.
                    while (true) {
                        if (i == end) return false

                        val charCount: Int
                        if ((gData.regexp!!.flags and JSREG_UNICODE) != 0) {
                            val matchCodePoint = Characters.codePointAt(input, i)
                            if (matchCodePoint == anchorCodePoint) break
                            charCount = Characters.charCount(matchCodePoint)
                        } else {
                            val matchCh = input[i]
                            if (matchCh.code == anchorCodePoint ||
                                ((gData.regexp!!.flags and JSREG_FOLD) != 0 && upcase(matchCh) == upcase(anchorCodePoint.toChar()))
                            ) {
                                break
                            }
                            charCount = 1
                        }

                        if ((gData.regexp!!.flags and JSREG_STICKY) != 0) return false
                        i += charCount
                    }
                }
                gData.cp = i
                gData.skipped = i - start
                for (j in 0 until re.parenCount) gData.parens!![j] = -1L
                val result = executeREBytecode(cx, gData, input, end)

                gData.backTrackStackTop = null
                gData.stateStackTop = null
                if (result) return true

                if (anchorCodePoint == ANCHOR_BOL && !gData.multiline) {
                    gData.skipped = end
                    return false
                }
                if ((gData.regexp!!.flags and JSREG_STICKY) != 0) return false

                i = start + gData.skipped
                i++
            }
            return false
        }

        /** One successful match: the text, the captures, the named groups and where it started. */
        internal class ExecResult {
            val match: String?
            val captures: MutableList<String?> = ArrayList()
            val groups: LinkedHashMap<String, String?> = LinkedHashMap()
            val index: Int
            val input: String

            constructor(index: Int, input: String) {
                this.match = null
                this.index = index
                this.input = input
            }

            constructor(index: Int, input: String, match: String?) {
                this.match = match
                this.index = index
                this.input = input
            }
        }

        private fun getLastIndex(cx: Context, thisObj: Scriptable): Long =
            ScriptRuntime.toLength(ScriptRuntime.getObjectProp(thisObj, "lastIndex", cx))

        private fun setLastIndex(thisObj: Scriptable, value: Any?) {
            putProperty(thisObj, "lastIndex", value)
        }

        /**
         * The overload upstream selects when `this` is a ScriptableObject. Reading the attributes
         * of a property the object does not have is itself an error, which is observable.
         */
        private fun setLastIndexChecked(thisObj: ScriptableObject, value: Any?) {
            if ((thisObj.getAttributes("lastIndex") and READONLY) != 0) {
                throw ScriptRuntime.typeErrorById("msg.modify.readonly", "lastIndex")
            }
            setLastIndex(thisObj as Scriptable, value)
        }

        private fun realThis(thisObj: Scriptable?, f: IdFunctionObject): NativeRegExp =
            realThis(thisObj, f.functionName)

        private fun realThis(thisObj: Scriptable?, functionName: String): NativeRegExp =
            ensureType<NativeRegExp>(thisObj, functionName)

        /** The spec's RegExpExec: call the object's own `exec` when it has one. */
        public fun regExpExec(regexp: Scriptable, string: String, cx: Context, scope: Scriptable): Any? {
            val execMethod = ScriptRuntime.getObjectProp(regexp, "exec", cx, scope)
            if (execMethod is io.github.yuroyami.kitejs.Callable) {
                return execMethod.call(cx, scope, regexp, arrayOf<Any?>(string))
            }
            return js_exec(cx, scope, regexp, arrayOf<Any?>(string))
        }

        internal fun js_exec(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            realThis(thisObj, "exec").execSub(cx, scope, args, MATCH)

        // Ids for the instance and prototype members.
        internal const val Id_lastIndex = 1
        internal const val Id_source = 2
        internal const val Id_flags = 3
        internal const val Id_global = 4
        internal const val Id_ignoreCase = 5
        internal const val Id_multiline = 6
        internal const val Id_dotAll = 7
        internal const val Id_sticky = 8
        internal const val Id_unicode = 9
        internal const val MAX_INSTANCE_ID = 9

        internal const val Id_compile = 1
        internal const val Id_toString = 2
        internal const val Id_toSource = 3
        internal const val Id_exec = 4
        internal const val Id_test = 5
        internal const val Id_prefix = 6
        internal const val SymbolId_match = 7
        internal const val SymbolId_matchAll = 8
        internal const val SymbolId_search = 9
        internal const val SymbolId_replace = 10
        internal const val SymbolId_split = 11
        internal const val MAX_PROTOTYPE_ID = SymbolId_split
    }
}
