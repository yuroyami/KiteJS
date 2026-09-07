/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo

/**
 * The JavaScript `Object` builtin: the plain object class, its constructor and the methods on
 * both.
 *
 * Upstream also makes every NativeObject a `java.util.Map`; that view is not ported.
 */
public open class NativeObject : ScriptableObject {

    public constructor() : super()

    public constructor(scope: Scriptable, prototype: Scriptable?) : super(scope, prototype)

    override val className: String
        get() = "Object"

    override fun toString(): String = ScriptRuntime.defaultObjectToString(this)

    public companion object {
        private const val OBJECT_TAG = "Object"

        public const val CLASS_NAME: String = "Object"

        public const val PROTO_PROPERTY: String = "__proto__"

        public const val PARENT_PROPERTY: String = "__parent__"

        internal fun init(cx: Context, s: Scriptable, sealed: Boolean): LambdaConstructor {
            val ctor = object : LambdaConstructor(s, CLASS_NAME, 1, ::js_constructorCall, ::js_constructor) {
                override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
                    js_constructor(cx, scope, args)
            }
            val proto = NativeObject()
            proto.parentScope = s
            ctor.setPrototypeProperty(proto)
            proto.defineProperty("constructor", ctor, DONTENUM)
            defOnCtor(ctor, s, "getPrototypeOf", 1, ::js_getPrototypeOf)
            if (Context.getCurrentContext()!!.languageVersion >= Context.VERSION_ES6) {
                defOnCtor(ctor, s, "setPrototypeOf", 2, ::js_setPrototypeOf)
                defOnCtor(ctor, s, "entries", 1, ::js_entries)
                defOnCtor(ctor, s, "fromEntries", 1, ::js_fromEntries)
                defOnCtor(ctor, s, "values", 1, ::js_values)
                defOnCtor(ctor, s, "hasOwn", 1, ::js_hasOwn)
            }
            defOnCtor(ctor, s, "keys", 1, ::js_keys)
            defOnCtor(ctor, s, "getOwnPropertyNames", 1, ::js_getOwnPropertyNames)
            defOnCtor(ctor, s, "getOwnPropertySymbols", 1, ::js_getOwnPropertySymbols)
            defOnCtor(ctor, s, "getOwnPropertyDescriptor", 2, ::js_getOwnPropDesc)
            defOnCtor(ctor, s, "getOwnPropertyDescriptors", 1, ::js_getOwnPropDescs)
            defOnCtor(ctor, s, "defineProperty", 3, ::js_defineProperty)
            defOnCtor(ctor, s, "isExtensible", 1, ::js_isExtensible)
            defOnCtor(ctor, s, "preventExtensions", 1, ::js_preventExtensions)
            defOnCtor(ctor, s, "defineProperties", 2, ::js_defineProperties)
            defOnCtor(ctor, s, "create", 2, ::js_create)
            defOnCtor(ctor, s, "isSealed", 1, ::js_isSealed)
            defOnCtor(ctor, s, "isFrozen", 1, ::js_isFrozen)
            defOnCtor(ctor, s, "seal", 1, ::js_seal)
            defOnCtor(ctor, s, "freeze", 1, ::js_freeze)
            defOnCtor(ctor, s, "assign", 2, ::js_assign)
            defOnCtor(ctor, s, "is", 2, ::js_is)
            defOnCtor(ctor, s, "groupBy", 2, ::js_groupBy)
            defOnProto(ctor, s, "toString", 0, ::js_toString)
            defOnProto(ctor, s, "toLocaleString", 0, ::js_toLocaleString)
            defOnProto(ctor, s, "__lookupGetter__", 1, ::js_lookupGetter)
            defOnProto(ctor, s, "__lookupSetter__", 1, ::js_lookupSetter)
            defOnProto(ctor, s, "__defineGetter__", 2, ::js_defineGetter)
            defOnProto(ctor, s, "__defineSetter__", 2, ::js_defineSetter)
            defOnProto(ctor, s, "hasOwnProperty", 1, ::js_hasOwnProperty)
            defOnProto(ctor, s, "propertyIsEnumerable", 1, ::js_propertyIsEnumerable)
            defOnProto(ctor, s, "valueOf", 0, ::js_valueOf)
            defOnProto(ctor, s, "isPrototypeOf", 1, ::js_isPrototypeOf)
            defOnProto(ctor, s, "toSource", 0, ScriptRuntime::defaultObjectToSource)
            ctor.setPrototypePropertyAttributes(PERMANENT or READONLY or DONTENUM)
            defineProperty(s, CLASS_NAME, ctor, DONTENUM)
            if (cx.languageVersion >= Context.VERSION_ES6) {
                ctor.definePrototypeProperty(
                    cx,
                    PROTO_PROPERTY,
                    LambdaGetterFunction { thisObj -> js_protoGetter(thisObj) },
                    LambdaSetterFunction { thisObj, proto -> js_protoSetter(thisObj, proto) },
                    DONTENUM or READONLY,
                )
            }
            if (sealed) {
                ctor.sealObject()
                (ctor.prototypeProperty as NativeObject).sealObject()
            }
            return ctor
        }

        private fun defOnCtor(constructor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            constructor.defineConstructorMethod(scope, name, length, null, target, DONTENUM, DONTENUM or READONLY)
        }

        private fun defOnProto(constructor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            constructor.definePrototypeMethod(scope, name, length, target)
        }

        private fun js_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.isEmpty() || args[0] == null || Undefined.isUndefined(args[0])) {
                return cx.newObject(scope)
            }
            return ScriptRuntime.toObject(cx, scope, args[0])
        }

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            if (args.isEmpty() || args[0] == null || Undefined.isUndefined(args[0])) {
                return cx.newObject(scope)
            }
            return ScriptRuntime.toObject(cx, scope, args[0])
        }

        private fun js_toLocaleString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (thisObj == null) {
                throw ScriptRuntime.notFunctionError(null)
            }
            val toString = getProperty(thisObj, "toString")
            if (toString !is Callable) {
                throw ScriptRuntime.notFunctionError(toString)
            }
            return toString.call(cx, scope, thisObj, ScriptRuntime.emptyArgs)
        }

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (cx.hasFeature(Context.FEATURE_TO_STRING_AS_SOURCE)) {
                var s = ScriptRuntime.defaultObjectToSource(cx, scope, thisObj, args)
                val l = s.length
                if (l != 0 && s[0] == '(' && s[l - 1] == ')') {
                    s = s.substring(1, l - 1)
                }
                return s
            }
            return ScriptRuntime.defaultObjectToString(thisObj)
        }

        private fun js_valueOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (cx.languageVersion >= Context.VERSION_1_8 && (thisObj == null || Undefined.isUndefined(thisObj))) {
                throw ScriptRuntime.typeErrorById("msg." + (if (thisObj == null) "null" else "undef") + ".to.object")
            }
            return thisObj
        }

        private fun js_hasOwnProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (cx.languageVersion >= Context.VERSION_1_8 && (thisObj == null || Undefined.isUndefined(thisObj))) {
                throw ScriptRuntime.typeErrorById("msg." + (if (thisObj == null) "null" else "undef") + ".to.object")
            }
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            return AbstractEcmaObjectOperations.hasOwnProperty(cx, thisObj, arg)
        }

        private fun js_propertyIsEnumerable(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (cx.languageVersion >= Context.VERSION_1_8 && (thisObj == null || Undefined.isUndefined(thisObj))) {
                throw ScriptRuntime.typeErrorById("msg." + (if (thisObj == null) "null" else "undef") + ".to.object")
            }
            var result: Boolean
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (arg is Symbol) {
                result = (thisObj as SymbolScriptable).has(arg, thisObj)
                result = result && isEnumerable(arg, thisObj)
            } else {
                val s = ScriptRuntime.toStringIdOrIndex(arg)
                try {
                    val stringId = s.stringId
                    if (stringId == null) {
                        result = thisObj!!.has(s.index, thisObj)
                        result = result && isEnumerable(s.index, thisObj)
                    } else {
                        result = thisObj!!.has(stringId, thisObj)
                        result = result && isEnumerable(stringId, thisObj)
                    }
                } catch (ee: EvaluatorException) {
                    val prefix = ScriptRuntime.getMessageById("msg.prop.not.found", s.stringId ?: s.index.toString())
                    if (ee.message.startsWith(prefix)) {
                        result = false
                    } else {
                        throw ee
                    }
                }
            }
            return ScriptRuntime.wrapBoolean(result)
        }

        private fun js_isPrototypeOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (cx.languageVersion >= Context.VERSION_1_8 && (thisObj == null || Undefined.isUndefined(thisObj))) {
                throw ScriptRuntime.typeErrorById("msg." + (if (thisObj == null) "null" else "undef") + ".to.object")
            }
            var result = false
            if (args.isNotEmpty() && args[0] is Scriptable) {
                var v: Scriptable? = args[0] as Scriptable
                do {
                    v = v!!.prototype
                    if (v === thisObj) {
                        result = true
                        break
                    }
                } while (v != null)
            }
            return ScriptRuntime.wrapBoolean(result)
        }

        private fun js_protoGetter(thisObj: Scriptable?): Any? {
            // 1. Let O be ? ToObject(this value).  2. Return ? O.[[GetPrototypeOf]]().
            val o = ScriptRuntime.toObject(thisObj!!, thisObj) as ScriptableObject
            return o.prototype
        }

        /** `obj.__proto__ = proto` with the spec's checks. */
        public fun js_protoSetter(thisObj: Scriptable?, proto: Any?) {
            // 1. Let O be ? RequireObjectCoercible(this value).
            // 2. If proto is not an Object and proto is not null, return undefined.
            // 3. If O is not an Object, return undefined.
            // 4. Let status be ? O.[[SetPrototypeOf]](proto). 5. If status is false, throw a TypeError.
            val o = ScriptRuntimeES6.requireObjectCoercible(null, thisObj, CLASS_NAME, PROTO_PROPERTY)
            if (proto !is Scriptable && proto != null) return
            if (ScriptRuntime.isSymbol(proto)) return
            if (o !is Scriptable || ScriptRuntime.isSymbol(o)) return
            setPrototypeOf(o, proto)
        }

        private fun js_defineGetter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_defineGetterOrSetter(cx, scope, false, thisObj, args)

        private fun js_defineSetter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_defineGetterOrSetter(cx, scope, true, thisObj, args)

        private fun js_defineGetterOrSetter(cx: Context, scope: Scriptable, isSetter: Boolean, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.size < 2 || args[1] !is Callable) {
                val badArg = if (args.size >= 2) args[1] else Undefined.instance
                throw ScriptRuntime.notFunctionError(badArg)
            }
            if (thisObj !is ScriptableObject) {
                throw Context.reportRuntimeErrorById(
                    "msg.extend.scriptable",
                    if (thisObj == null) "null" else thisObj::class.simpleName,
                    JavaNumbers.toString(args[0]),
                )
            }
            val s = ScriptRuntime.toStringIdOrIndex(args[0])
            val index = if (s.stringId != null) 0 else s.index
            val getterOrSetter = args[1] as Callable
            thisObj.setGetterOrSetter(s.stringId, index, getterOrSetter, isSetter)
            if (thisObj is NativeArray) thisObj.setDenseOnly(false)
            return Undefined.instance
        }

        private fun js_lookupGetter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_lookupGetterOrSetter(cx, scope, false, thisObj, args)

        private fun js_lookupSetter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_lookupGetterOrSetter(cx, scope, true, thisObj, args)

        private fun js_lookupGetterOrSetter(cx: Context, scope: Scriptable, isSetter: Boolean, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.isEmpty() || thisObj !is ScriptableObject) return Undefined.instance
            var so: ScriptableObject = thisObj
            val s = ScriptRuntime.toStringIdOrIndex(args[0])
            val index = if (s.stringId != null) 0 else s.index
            var gs: Any?
            while (true) {
                gs = so.getGetterOrSetter(s.stringId, index, scope, isSetter)
                if (gs != null) break
                val v = so.prototype ?: break
                if (v is ScriptableObject) so = v else break
            }
            return gs ?: Undefined.instance
        }

        private fun js_getPrototypeOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = getCompatibleObject(cx, scope, arg)
            return obj.prototype
        }

        private fun js_setPrototypeOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.size < 2) {
                throw ScriptRuntime.typeErrorById("msg.method.missing.parameter", "Object.setPrototypeOf", "2", args.size.toString())
            }
            val proto = if (args[1] == null) null else ensureScriptable(args[1])
            if (ScriptRuntime.isSymbol(proto)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(proto))
            }
            val arg0 = args[0]
            if (cx.languageVersion >= Context.VERSION_ES6) {
                ScriptRuntimeES6.requireObjectCoercible(cx, arg0, OBJECT_TAG, "setPrototypeOf")
            }
            return setPrototypeOf(arg0, proto)
        }

        private fun setPrototypeOf(thisObj: Any?, proto: Scriptable?): Any? {
            if (thisObj !is ScriptableObject) return thisObj
            if (thisObj.prototype === proto) return thisObj
            if (!thisObj.isExtensible) {
                throw ScriptRuntime.typeErrorById("msg.not.extensible")
            }
            var prototypeProto = proto
            while (prototypeProto != null) {
                if (prototypeProto === thisObj) {
                    throw ScriptRuntime.typeErrorById("msg.object.cyclic.prototype", thisObj::class.simpleName)
                }
                prototypeProto = prototypeProto.prototype
            }
            thisObj.prototype = proto
            return thisObj
        }

        private fun js_keys(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = getCompatibleObject(cx, scope, arg)
            val ids = obj.getIds()
            for (i in ids.indices) {
                ids[i] = ScriptRuntime.toString(ids[i])
            }
            return cx.newArray(scope, ids)
        }

        private fun js_entries(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = getCompatibleObject(cx, scope, arg)
            var ids = obj.getIds()
            var j = 0
            for (i in ids.indices) {
                val id = ids[i]
                if (id is Int) {
                    if (obj.has(id, obj) && isEnumerable(id, obj)) {
                        val stringId = ScriptRuntime.toString(id)
                        val entry = arrayOf(stringId, obj.get(id, obj))
                        ids[j++] = cx.newArray(scope, entry)
                    }
                } else {
                    val stringId = ScriptRuntime.toString(id)
                    if (obj.has(stringId, obj) && isEnumerable(stringId, obj)) {
                        val entry = arrayOf(stringId, obj.get(stringId, obj))
                        ids[j++] = cx.newArray(scope, entry)
                    }
                }
            }
            if (j != ids.size) {
                ids = ids.copyOf(j)
            }
            return cx.newArray(scope, ids)
        }

        private fun js_fromEntries(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var arg = if (args.isEmpty()) Undefined.instance else args[0]
            arg = getCompatibleObject(cx, scope, arg)
            val obj = cx.newObject(scope)
            ScriptRuntime.loadFromIterable(cx, scope, arg) { key, value ->
                if (ScriptRuntime.isInt(key)) {
                    obj.put(key as Int, obj, value)
                } else if (key is Symbol && obj is SymbolScriptable) {
                    obj.put(key, obj, value)
                } else {
                    obj.put(ScriptRuntime.toString(key), obj, value)
                }
            }
            return obj
        }

        private fun js_values(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = getCompatibleObject(cx, scope, arg)
            var ids = obj.getIds()
            var j = 0
            for (i in ids.indices) {
                val id = ids[i]
                if (id is Int) {
                    if (obj.has(id, obj) && isEnumerable(id, obj)) {
                        ids[j++] = obj.get(id, obj)
                    }
                } else {
                    val stringId = ScriptRuntime.toString(id)
                    if (obj.has(stringId, obj) && isEnumerable(stringId, obj)) {
                        ids[j++] = obj.get(stringId, obj)
                    }
                }
            }
            if (j != ids.size) {
                ids = ids.copyOf(j)
            }
            return cx.newArray(scope, ids)
        }

        private fun js_hasOwn(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val propertyName = if (args.size < 2) Undefined.instance else args[1]
            return AbstractEcmaObjectOperations.hasOwnProperty(cx, arg, propertyName)
        }

        private fun js_getOwnPropertyNames(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val s = getCompatibleObject(cx, scope, arg)
            val obj = ensureScriptableObject(s)
            val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, false) }
            for (i in ids.indices) {
                ids[i] = ScriptRuntime.toString(ids[i])
            }
            return cx.newArray(scope, ids)
        }

        private fun js_getOwnPropertySymbols(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val s = getCompatibleObject(cx, scope, arg)
            val obj = ensureScriptableObject(s)
            val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
            val syms = ArrayList<Any?>()
            for (o in ids) {
                if (o is Symbol) syms.add(o)
            }
            return cx.newArray(scope, syms.toTypedArray())
        }

        private fun js_getOwnPropDesc(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val s = getCompatibleObject(cx, scope, arg)
            val obj = ensureScriptableObject(s)
            val nameArg = if (args.size < 2) Undefined.instance else args[1]
            val desc = obj.getOwnPropertyDescriptor(cx, nameArg)
            return desc?.toObject(scope) ?: Undefined.instance
        }

        private fun js_getOwnPropDescs(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val s = getCompatibleObject(cx, scope, arg)
            val obj = ensureScriptableObject(s)
            val descs = cx.newObject(scope) as ScriptableObject
            val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
            for (key in ids) {
                val desc = obj.getOwnPropertyDescriptor(cx, key) ?: continue
                when (key) {
                    is Symbol -> descs.put(key, descs, desc.toObject(scope))
                    is Int -> descs.put(key, descs, desc.toObject(scope))
                    else -> descs.put(ScriptRuntime.toString(key), descs, desc.toObject(scope))
                }
            }
            return descs
        }

        private fun js_defineProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = ensureScriptableObject(arg)
            val name = if (args.size < 2) Undefined.instance else args[1]
            val descArg = if (args.size < 3) Undefined.instance else args[2]
            val desc = DescriptorInfo(ensureScriptableObject(descArg))
            checkPropertyDefinition(desc)
            obj.defineOwnProperty(cx, name, desc)
            return obj
        }

        private fun js_isExtensible(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return false
            }
            val obj = ensureScriptableObject(arg)
            return obj.isExtensible
        }

        private fun js_preventExtensions(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return arg
            }
            val obj = ensureScriptableObject(arg)
            if (!obj.preventExtensions()) {
                throw ScriptRuntime.typeError("Object.preventExtensions is not allowed")
            }
            return obj
        }

        private fun js_defineProperties(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = ensureScriptableObject(arg)
            val propsObj = if (args.size < 2) Undefined.instance else args[1]
            val props = ScriptRuntime.toObject(scope, propsObj)
            obj.defineOwnProperties(cx, ensureScriptableObject(props))
            return obj
        }

        private fun js_create(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            val obj = if (arg == null) null else ensureScriptable(arg)
            val newObject = NativeObject()
            newObject.parentScope = scope
            newObject.prototype = obj
            if (args.size > 1 && !Undefined.isUndefined(args[1])) {
                val props = ScriptRuntime.toObject(scope, args[1])
                newObject.defineOwnProperties(cx, ensureScriptableObject(props))
            }
            return newObject
        }

        private fun js_isSealed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return true
            }
            return AbstractEcmaObjectOperations.testIntegrityLevel(cx, arg, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.SEALED)
        }

        private fun js_isFrozen(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return true
            }
            return AbstractEcmaObjectOperations.testIntegrityLevel(cx, arg, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.FROZEN)
        }

        private fun js_seal(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return arg
            }
            val status = AbstractEcmaObjectOperations.setIntegrityLevel(cx, arg, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.SEALED)
            if (!status) {
                throw ScriptRuntime.typeError("Object is not sealable")
            }
            return arg
        }

        private fun js_freeze(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isEmpty()) Undefined.instance else args[0]
            if (cx.languageVersion >= Context.VERSION_ES6 && arg !is ScriptableObject) {
                return arg
            }
            val status = AbstractEcmaObjectOperations.setIntegrityLevel(cx, arg, AbstractEcmaObjectOperations.INTEGRITY_LEVEL.FROZEN)
            if (!status) {
                throw ScriptRuntime.typeError("Object is not freezable")
            }
            return arg
        }

        private fun js_assign(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val targetObj = if (args.isNotEmpty()) {
                ScriptRuntime.toObject(cx, scope, args[0])
            } else {
                ScriptRuntime.toObject(cx, scope, Undefined.instance)
            }
            for (i in 1 until args.size) {
                val source = args[i]
                if (source == null || Undefined.isUndefined(source)) continue
                val sourceObj = ScriptRuntime.toObject(cx, scope, source)
                val ids: Array<Any?> = if (sourceObj is ScriptableObject) {
                    sourceObj.startCompoundOp(false).use { sourceObj.getIds(it, false, true) }
                } else {
                    sourceObj.getIds()
                }
                for (key in ids) {
                    if (key is Int) {
                        if (sourceObj.has(key, sourceObj) && isEnumerable(key, sourceObj)) {
                            val v = sourceObj.get(key, sourceObj)
                            AbstractEcmaObjectOperations.put(cx, targetObj, key, v, true)
                        }
                    } else if (key is String) {
                        val stringId = ScriptRuntime.toString(key)
                        if (sourceObj.has(stringId, sourceObj) && isEnumerable(stringId, sourceObj)) {
                            val v = sourceObj.get(stringId, sourceObj)
                            AbstractEcmaObjectOperations.put(cx, targetObj, stringId, v, true)
                        }
                    }
                }
                if (sourceObj is ScriptableObject) {
                    for (key in ids) {
                        if (key is Symbol) {
                            if (sourceObj.has(key, sourceObj) && isEnumerable(key, sourceObj)) {
                                val v = sourceObj.get(key, sourceObj)
                                AbstractEcmaObjectOperations.put(cx, targetObj, key, v, true)
                            }
                        }
                    }
                }
            }
            return targetObj
        }

        private fun js_is(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val a1 = if (args.isEmpty()) Undefined.instance else args[0]
            val a2 = if (args.size < 2) Undefined.instance else args[1]
            return ScriptRuntime.wrapBoolean(ScriptRuntime.same(a1, a2))
        }

        private fun js_groupBy(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val items = if (args.isEmpty()) Undefined.instance else args[0]
            val callback = if (args.size < 2) Undefined.instance else args[1]
            val groups = AbstractEcmaObjectOperations.groupBy(
                cx, scope, OBJECT_TAG, "groupBy", items, callback, AbstractEcmaObjectOperations.KEY_COERCION.PROPERTY,
            )
            val obj = cx.newObject(scope) as NativeObject
            obj.prototype = null
            for ((key, values) in groups) {
                val elements = cx.newArray(scope, values.toTypedArray())
                val desc = cx.newObject(scope) as ScriptableObject
                desc.put("enumerable", desc, true)
                desc.put("configurable", desc, true)
                desc.put("value", desc, elements)
                obj.defineOwnProperty(cx, key, desc)
            }
            return obj
        }

        private fun isEnumerable(index: Int, obj: Any?): Boolean {
            if (obj is ScriptableObject) {
                return try {
                    val attrs = obj.getAttributes(index)
                    (attrs and DONTENUM) == 0
                } catch (re: RhinoException) {
                    true
                }
            }
            return true
        }

        private fun isEnumerable(key: String, obj: Any?): Boolean {
            if (obj is ScriptableObject) {
                return try {
                    val attrs = obj.getAttributes(key)
                    (attrs and DONTENUM) == 0
                } catch (re: RhinoException) {
                    true
                }
            }
            return true
        }

        private fun isEnumerable(sym: Symbol, obj: Any?): Boolean {
            if (obj is ScriptableObject) {
                return try {
                    val attrs = obj.getAttributes(sym)
                    (attrs and DONTENUM) == 0
                } catch (re: RhinoException) {
                    true
                }
            }
            return true
        }

        private fun getCompatibleObject(cx: Context, scope: Scriptable, arg: Any?): Scriptable {
            if (cx.languageVersion >= Context.VERSION_ES6) {
                val s = ScriptRuntime.toObject(cx, scope, arg)
                return ensureScriptable(s)
            }
            return ensureScriptable(arg)
        }
    }
}
