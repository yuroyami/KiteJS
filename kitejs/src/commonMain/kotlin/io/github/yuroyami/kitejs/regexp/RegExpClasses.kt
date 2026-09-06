/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.regexp

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.ES6Iterator
import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable
import io.github.yuroyami.kitejs.SerializableConstructable
import io.github.yuroyami.kitejs.TopLevel
import io.github.yuroyami.kitejs.Undefined

/**
 * Before ES6 a regexp was callable, so `/x/("axb")` did the same thing as `exec`. That behaviour is
 * kept for scripts running at an older language version.
 */
internal class NativeRegExpCallable : NativeRegExp, Function {

    constructor(scope: Scriptable, compiled: RECompiled) : super(scope, compiled)

    constructor() : super()

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        execSub(cx, scope, args, MATCH)

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        execSub(cx, scope, args, MATCH) as Scriptable
}

/** Picks the callable or the plain regexp object, depending on the language version. */
internal object NativeRegExpInstantiator {

    fun withLanguageVersion(languageVersion: Int): NativeRegExp =
        if (languageVersion < Context.VERSION_ES6) NativeRegExpCallable() else NativeRegExp()

    fun withLanguageVersionScopeCompiled(languageVersion: Int, scope: Scriptable, compiled: RECompiled): NativeRegExp =
        if (languageVersion < Context.VERSION_ES6) {
            NativeRegExpCallable(scope, compiled)
        } else {
            NativeRegExp(scope, compiled)
        }
}

/**
 * The `RegExp` constructor, including the legacy statics `RegExp.$1` through `$9`, `input`,
 * `lastMatch`, `lastParen`, `leftContext` and `rightContext`.
 */
internal object NativeRegExpCtor {

    fun init(cx: Context, scopeArg: Scriptable, sealed: Boolean): LambdaConstructor {
        val scope = scopeArg as ScriptableObject

        val ctor = LambdaConstructor(
            scope,
            "RegExp",
            2,
            SerializableCallable { icx, s, thisObj, args -> js_constructCall(icx, s, thisObj, args) },
            SerializableConstructable { icx, s, args -> js_construct(icx, s, args) },
        )

        ctor.defineProperty(
            cx, "multiline",
            ScriptableObject.LambdaGetterFunction { getImpl().multiline },
            ScriptableObject.LambdaSetterFunction { _, v -> getImpl().multiline = ScriptRuntime.toBoolean(v) },
            ScriptableObject.PERMANENT,
        )
        ctor.defineProperty(
            cx, "\$*",
            ScriptableObject.LambdaGetterFunction { getImpl().multiline },
            ScriptableObject.LambdaSetterFunction { _, v -> getImpl().multiline = ScriptRuntime.toBoolean(v) },
            ScriptableObject.PERMANENT,
        )
        ctor.defineProperty(
            cx, "input",
            ScriptableObject.LambdaGetterFunction { toStr(getImpl().input) },
            ScriptableObject.LambdaSetterFunction { _, v -> getImpl().input = ScriptRuntime.toString(v) },
            ScriptableObject.PERMANENT,
        )
        ctor.defineProperty(
            cx, "\$_",
            ScriptableObject.LambdaGetterFunction { toStr(getImpl().input) },
            ScriptableObject.LambdaSetterFunction { _, v -> getImpl().input = ScriptRuntime.toString(v) },
            ScriptableObject.PERMANENT,
        )
        ctor.defineProperty(cx, "lastMatch", ScriptableObject.LambdaGetterFunction { toStr(getImpl().lastMatch) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "\$&", ScriptableObject.LambdaGetterFunction { toStr(getImpl().lastMatch) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "lastParen", ScriptableObject.LambdaGetterFunction { toStr(getImpl().lastParen) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "\$+", ScriptableObject.LambdaGetterFunction { toStr(getImpl().lastParen) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "leftContext", ScriptableObject.LambdaGetterFunction { toStr(getImpl().leftContext) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "\$`", ScriptableObject.LambdaGetterFunction { toStr(getImpl().leftContext) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "rightContext", ScriptableObject.LambdaGetterFunction { toStr(getImpl().rightContext) }, ScriptableObject.PERMANENT)
        ctor.defineProperty(cx, "\$'", ScriptableObject.LambdaGetterFunction { toStr(getImpl().rightContext) }, ScriptableObject.PERMANENT)

        for (i in 1 until 10) {
            val c = i - 1
            ctor.defineProperty(
                cx, "\$$i",
                ScriptableObject.LambdaGetterFunction { toStr(getImpl().getParenSubString(c)) },
                null,
                ScriptableObject.PERMANENT,
            )
        }
        return ctor
    }

    private fun toStr(subStr: String?): String = subStr ?: ""

    private fun toStr(subStr: SubString?): String = subStr?.toString() ?: ""

    private fun js_constructCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        // RegExp(re) with no new flags hands back the same object.
        if (args.isNotEmpty() && args[0] is NativeRegExp && (args.size == 1 || args[1] === Undefined.instance)) {
            return args[0] as Scriptable
        }
        return js_construct(cx, scope, args)
    }

    private fun js_construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        val re = NativeRegExpInstantiator.withLanguageVersion(cx.languageVersion)
        re.compile(cx, scope, args)
        ScriptRuntime.setBuiltinProtoAndParent(re, scope, TopLevel.Builtins.RegExp)
        return re
    }

    private fun getImpl(): RegExpImpl =
        ScriptRuntime.getRegExpProxy(Context.getCurrentContext()!!) as RegExpImpl
}

/** What `String.prototype.matchAll` hands back: one `exec` result per step. */
class NativeRegExpStringIterator : ES6Iterator {

    private var regexp: Scriptable? = null
    private var string: String = ""
    private var global: Boolean = false
    private var fullUnicode: Boolean = false
    private var nextDone: Boolean = false
    private var next: Any? = null

    /** Only for building the prototype object. */
    private constructor() : super()

    constructor(scope: Scriptable, regexp: Scriptable, string: String, global: Boolean, fullUnicode: Boolean) :
        super(scope, ITERATOR_TAG) {
        this.regexp = regexp
        this.string = string
        this.global = global
        this.fullUnicode = fullUnicode
        this.nextDone = false
    }

    override val className: String
        get() = "RegExp String Iterator"

    override fun isDone(cx: Context, scope: Scriptable): Boolean {
        // The base class asks isDone before nextValue, so the next match is computed here and
        // simply handed over afterwards.
        if (nextDone) return true

        next = NativeRegExp.regExpExec(regexp!!, string, cx, scope)
        if (next == null) {
            next = Undefined.instance
            nextDone = true
            return true
        }
        if (!global) {
            // A non-global pattern yields one match and is done on the step after.
            nextDone = true
            return false
        }

        // An empty match would otherwise stall on the same index forever.
        val matchStr = ScriptRuntime.toString(ScriptRuntime.getObjectIndex(next, 0.0, cx, scope))
        if (matchStr.isEmpty()) {
            val thisIndex = ScriptRuntime.toLength(ScriptRuntime.getObjectProp(regexp!!, "lastIndex", cx))
            val nextIndex = ScriptRuntime.advanceStringIndex(string, thisIndex, fullUnicode)
            ScriptRuntime.setObjectProp(regexp!!, "lastIndex", nextIndex, cx)
        }
        return false
    }

    override fun nextValue(cx: Context, scope: Scriptable): Any? = next

    override fun getTag(): String = ITERATOR_TAG

    companion object {
        private const val ITERATOR_TAG = "RegExpStringIterator"

        fun init(scope: ScriptableObject, sealed: Boolean) {
            ES6Iterator.init(scope, sealed, NativeRegExpStringIterator(), ITERATOR_TAG)
        }
    }
}
