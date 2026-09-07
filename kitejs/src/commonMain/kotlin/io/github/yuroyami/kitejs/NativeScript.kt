/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The non-standard `Script` object: a compiled script that can be run again with `exec`. */
class NativeScript private constructor(private var script: Script?) : BaseFunction() {

    override val className: String
        get() = "Script"

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val s = script
        if (s != null) return s.exec(cx, scope, thisObj ?: scope)
        return Undefined.instance
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        throw Context.reportRuntimeErrorById("msg.script.is.not.constructor")

    override val length: Int get() = 0

    override val arity: Int get() = 0

    override fun decompile(indent: Int, flags: Set<DecompilerFlag>): String {
        val s = script
        if (s is JSFunction) return s.decompile(indent, flags)
        return super.decompile(indent, flags)
    }

    companion object {
        private const val SCRIPT_TAG = "Script"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): LambdaConstructor {
            val obj = LambdaConstructor(scope, "Script", 1, ::js_constructorCall, ::js_constructor)
            val proto = NativeScript(null)
            proto.setPrototypeProperty(null)
            obj.setPrototypeProperty(proto)
            val function = getProperty(scope, "Function") as Scriptable
            val functionProto = getProperty(function, "prototype") as Scriptable
            proto.prototype = functionProto
            defineMethod(obj, scope, "toString", 0, ::js_toString)
            defineMethod(obj, scope, "exec", 0, ::js_exec)
            defineMethod(obj, scope, "compile", 0, ::js_compile)
            defineProperty(scope, "Script", obj, DONTENUM)
            if (sealed) {
                obj.sealObject()
                (obj.prototypeProperty as ScriptableObject).sealObject()
            }
            return obj
        }

        private fun defineMethod(ctor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            ctor.definePrototypeMethod(scope, name, length, target, DONTENUM, DONTENUM or READONLY)
        }

        private fun js_compile(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val real = realThis(thisObj, "compile")
            val source = ScriptRuntime.toString(args, 0)
            real.script = compile(cx, source)
            return real
        }

        private fun js_exec(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            throw Context.reportRuntimeErrorById("msg.cant.call.indirect", "exec")

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val real = realThis(thisObj, "toString")
            val realScript = real.script ?: return ""
            return cx.decompileScript(realScript, 0)
        }

        private fun js_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_constructor(cx, scope, args)

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val source = if (args.isEmpty()) "" else ScriptRuntime.toString(args[0])
            val script = compile(cx, source)
            val nscript = NativeScript(script)
            ScriptRuntime.setObjectProtoAndParent(nscript, scope)
            return nscript
        }

        private fun realThis(thisObj: Scriptable?, name: String): NativeScript =
            ensureType<NativeScript>(thisObj, name)

        private fun compile(cx: Context, source: String): Script {
            val linep = intArrayOf(0)
            var filename = Context.getSourcePositionFromStack(linep)
            if (filename == null) {
                filename = "<Script object>"
                linep[0] = 1
            }
            val reporter = DefaultErrorReporter.forEval(cx.errorReporter)
            return cx.compileString(source, null, reporter, filename, linep[0], null, null)
        }
    }
}
