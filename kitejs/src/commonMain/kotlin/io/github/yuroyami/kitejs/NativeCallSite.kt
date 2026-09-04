/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** One frame of a captured stack, as handed to `Error.prepareStackTrace`. */
class NativeCallSite private constructor() : IdScriptableObject() {

    private var element: ScriptStackElement? = null

    internal fun setElement(elt: ScriptStackElement?) {
        element = elt
    }

    override val className: String
        get() = "CallSite"

    override fun initPrototypeId(id: Int) {
        val s: String
        val arity = 0
        when (id) {
            Id_constructor -> s = "constructor"
            Id_getThis -> s = "getThis"
            Id_getTypeName -> s = "getTypeName"
            Id_getFunction -> s = "getFunction"
            Id_getFunctionName -> s = "getFunctionName"
            Id_getMethodName -> s = "getMethodName"
            Id_getFileName -> s = "getFileName"
            Id_getLineNumber -> s = "getLineNumber"
            Id_getColumnNumber -> s = "getColumnNumber"
            Id_getEvalOrigin -> s = "getEvalOrigin"
            Id_isToplevel -> s = "isToplevel"
            Id_isEval -> s = "isEval"
            Id_isNative -> s = "isNative"
            Id_isConstructor -> s = "isConstructor"
            Id_toString -> s = "toString"
            else -> throw IllegalArgumentException(id.toString())
        }
        initPrototypeMethod(CALLSITE_TAG, id, s, arity)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(CALLSITE_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args)
        }
        return when (f.methodId()) {
            Id_constructor -> make(scope, f)
            Id_getFunctionName -> getFunctionName(thisObj)
            Id_getFileName -> getFileName(thisObj)
            Id_getLineNumber -> getLineNumber(thisObj)
            Id_getThis, Id_getTypeName, Id_getFunction, Id_getColumnNumber -> Undefined.instance
            Id_getMethodName -> null
            Id_getEvalOrigin, Id_isEval, Id_isConstructor, Id_isNative, Id_isToplevel -> false
            Id_toString -> js_toString(thisObj)
            else -> throw IllegalArgumentException(f.methodId().toString())
        }
    }

    override fun toString(): String = element?.toString() ?: ""

    override fun findPrototypeId(name: String): Int = when (name) {
        "constructor" -> Id_constructor
        "getThis" -> Id_getThis
        "getTypeName" -> Id_getTypeName
        "getFunction" -> Id_getFunction
        "getFunctionName" -> Id_getFunctionName
        "getMethodName" -> Id_getMethodName
        "getFileName" -> Id_getFileName
        "getLineNumber" -> Id_getLineNumber
        "getColumnNumber" -> Id_getColumnNumber
        "getEvalOrigin" -> Id_getEvalOrigin
        "isToplevel" -> Id_isToplevel
        "isEval" -> Id_isEval
        "isNative" -> Id_isNative
        "isConstructor" -> Id_isConstructor
        "toString" -> Id_toString
        else -> 0
    }

    companion object {
        private const val CALLSITE_TAG = "CallSite"

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val cs = NativeCallSite()
            cs.exportAsJSClass(MAX_PROTOTYPE_ID, scope, sealed)
        }

        internal fun make(scope: Scriptable, ctorObj: Scriptable): NativeCallSite {
            val cs = NativeCallSite()
            val proto = ctorObj.get("prototype", ctorObj) as Scriptable
            cs.parentScope = scope
            cs.prototype = proto
            return cs
        }

        /** Walks up to the first CallSite in the prototype chain, if any. */
        private fun realSite(obj: Scriptable?): NativeCallSite? {
            var o = obj
            while (o != null && o !is NativeCallSite) o = o.prototype
            return o as NativeCallSite?
        }

        private fun js_toString(obj: Scriptable?): Any? {
            val cs = realSite(obj) ?: return Scriptable.NOT_FOUND
            val sb = StringBuilder()
            cs.element!!.renderJavaStyle(sb)
            return sb.toString()
        }

        private fun getFunctionName(obj: Scriptable?): Any? {
            val cs = realSite(obj) ?: return Scriptable.NOT_FOUND
            return cs.element?.functionName
        }

        private fun getFileName(obj: Scriptable?): Any? {
            val cs = realSite(obj) ?: return Scriptable.NOT_FOUND
            return cs.element?.fileName
        }

        private fun getLineNumber(obj: Scriptable?): Any? {
            val cs = realSite(obj) ?: return Scriptable.NOT_FOUND
            val element = cs.element
            if (element == null || element.lineNumber < 0) return Undefined.instance
            return element.lineNumber
        }

        private const val Id_constructor = 1
        private const val Id_getThis = 2
        private const val Id_getTypeName = 3
        private const val Id_getFunction = 4
        private const val Id_getFunctionName = 5
        private const val Id_getMethodName = 6
        private const val Id_getFileName = 7
        private const val Id_getLineNumber = 8
        private const val Id_getColumnNumber = 9
        private const val Id_getEvalOrigin = 10
        private const val Id_isToplevel = 11
        private const val Id_isEval = 12
        private const val Id_isNative = 13
        private const val Id_isConstructor = 14
        private const val Id_toString = 15
        private const val MAX_PROTOTYPE_ID = 15
    }
}
