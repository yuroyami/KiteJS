/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.Script as UScript
import org.mozilla.javascript.Scriptable as UScriptable

/**
 * test262 through both engines, comparing outcomes.
 *
 * The point is not a pass rate. It is that for every file upstream runs, the port passes exactly
 * when upstream passes. A test both engines fail is fine and expected: upstream has no classes and
 * no modules either. A test where they disagree is either a bug to fix or a ledger entry.
 *
 * The suite is fetched by `tools/fetch-test262.sh` at the commit upstream pins. This test reports
 * that it was skipped rather than failing when the tree is not there, so a checkout without it
 * still builds.
 *
 * Run with `./gradlew test262Parity`. `-Dtest262.filter=built-ins/Array` narrows it while working.
 */
class Test262ParityTest {

    private val testRoot = File("../reference/test262/test")
    private val harnessRoot = File("../reference/test262/harness")
    private val propertiesFile = File("src/jvmTest/resources/test262.properties")

    /** Upstream's list, copied. A test needing any of these is not run by either engine. */
    private val unsupportedFeatures = setOf(
        "Atomics", "IsHTMLDDA", "async-functions", "async-iteration", "class",
        "class-fields-private", "class-fields-public", "default-arg", "new.target",
        "object-rest", "regexp-dotall", "regexp-unicode-property-escapes",
        "resizable-arraybuffer", "SharedArrayBuffer", "tail-call-optimization", "Temporal",
        "upsert", "u180e",
    )

    /**
     * Files where the two engines are known to disagree, with the reason. Each one is a ledger
     * entry, and each is asserted to still differ: if upstream changes, the entry goes stale and
     * this test says so rather than quietly passing.
     */
    private val knownDifferences = buildMap {
        // The port accepts identifier characters upstream rejects. Upstream asks the JDK's
        // Character.isJavaIdentifierStart, which is Java's rule and not JavaScript's; the port
        // uses its own generated ID_Start tables (D-43, D-57). The port is the correct one.
        for (version in listOf("5.2.0", "6.1.0", "7.0.0", "8.0.0", "11.0.0", "13.0.0", "15.0.0")) {
            put("language/identifiers/start-unicode-$version.js", "D-57: the port follows ID_Start, upstream follows Java identifiers")
        }

        // Tests upstream fails and the port passes. Nothing to fix here, but they are pinned so
        // that an upstream fix shows up as a stale entry rather than as silence (D-58).
        for (path in listOf(
            "built-ins/Proxy/construct/call-parameters.js",
            "built-ins/TypedArray/prototype/set/BigInt/bigint-tobiguint64.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/bigint-tobiguint64.js",
            "built-ins/TypedArrayConstructors/from/nan-conversion.js",
            "built-ins/TypedArrayConstructors/from/new-instance-from-sparse-array.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/bigint-tobiguint64.js",
            "built-ins/Proxy/construct/arguments-realm.js",
            "language/destructuring/binding/keyed-destructuring-property-reference-target-evaluation-order-with-bindings.js",
            // Typed array views read and write little-endian here and big-endian upstream, so a
            // test that copies between two views over one buffer only passes here.
            "built-ins/TypedArray/prototype/set/typedarray-arg-set-values-same-buffer-other-type.js",
        )) {
            put(path, "D-58: upstream fails this and the port passes it")
        }

        // Upstream's getOwnPropertyDescriptor trap reads the target descriptor without a null
        // check and throws a NullPointerException. The port answers undefined (D-50).
        for (path in listOf(
            "built-ins/Object/getOwnPropertyDescriptors/proxy-undefined-descriptor.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/result-is-undefined-targetdesc-is-undefined.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/trap-is-null-target-is-proxy.js",
        )) {
            put(path, "D-50: upstream crashes where the port answers undefined")
        }

        // A let or const loop head gives each iteration a binding of its own here. Upstream shares
        // one binding across the loop, so a closure made in the body sees the last value (D-72).
        for (path in listOf(
            "language/block-scope/syntax/for-in/mixed-values-in-iteration.js",
            "language/statements/for-in/head-let-fresh-binding-per-iteration.js",
            "language/statements/for-in/scope-body-lex-boundary.js",
            "language/statements/for-of/head-let-fresh-binding-per-iteration.js",
            "language/statements/for-of/scope-body-lex-boundary.js",
        )) {
            put(path, "D-72: each iteration of a let or const loop head binds afresh here")
        }

        // A function declaration, labelled or not, as the body of a loop, a with or an if is an
        // early SyntaxError here, and a labelled generator or one in strict code too. Upstream
        // accepts them all (D-76).
        for (path in listOf(
            "language/block-scope/syntax/function-declarations/in-statement-position-do-statement-while-expression.js",
            "language/block-scope/syntax/function-declarations/in-statement-position-for-statement.js",
            "language/block-scope/syntax/function-declarations/in-statement-position-if-expression-statement-else-statement.js",
            "language/block-scope/syntax/function-declarations/in-statement-position-if-expression-statement.js",
            "language/block-scope/syntax/function-declarations/in-statement-position-while-expression-statement.js",
            "language/statements/do-while/decl-fun.js",
            "language/statements/do-while/decl-gen.js",
            "language/statements/do-while/labelled-fn-stmt.js",
            "language/statements/for-in/decl-fun.js",
            "language/statements/for-in/decl-gen.js",
            "language/statements/for-in/labelled-fn-stmt-let.js",
            "language/statements/for-in/labelled-fn-stmt-lhs.js",
            "language/statements/for-in/labelled-fn-stmt-var.js",
            "language/statements/for-of/decl-fun.js",
            "language/statements/for-of/decl-gen.js",
            "language/statements/for-of/labelled-fn-stmt-let.js",
            "language/statements/for-of/labelled-fn-stmt-lhs.js",
            "language/statements/for-of/labelled-fn-stmt-var.js",
            "language/statements/for/decl-fun.js",
            "language/statements/for/decl-gen.js",
            "language/statements/for/labelled-fn-stmt-expr.js",
            "language/statements/for/labelled-fn-stmt-let.js",
            "language/statements/for/labelled-fn-stmt-var.js",
            "language/statements/if/if-decl-else-decl-strict.js",
            "language/statements/if/if-decl-else-stmt-strict.js",
            "language/statements/if/if-decl-no-else-strict.js",
            "language/statements/if/if-fun-else-fun-strict.js",
            "language/statements/if/if-fun-else-stmt-strict.js",
            "language/statements/if/if-fun-no-else-strict.js",
            "language/statements/if/if-gen-else-gen.js",
            "language/statements/if/if-gen-else-stmt.js",
            "language/statements/if/if-gen-no-else.js",
            "language/statements/if/if-stmt-else-decl-strict.js",
            "language/statements/if/if-stmt-else-fun-strict.js",
            "language/statements/if/if-stmt-else-gen.js",
            "language/statements/if/labelled-fn-stmt-first.js",
            "language/statements/if/labelled-fn-stmt-lone.js",
            "language/statements/if/labelled-fn-stmt-second.js",
            "language/statements/labeled/decl-fun-strict.js",
            "language/statements/labeled/decl-gen.js",
            "language/statements/while/decl-fun.js",
            "language/statements/while/decl-gen.js",
            "language/statements/while/labelled-fn-stmt.js",
            "language/statements/with/decl-fun.js",
            "language/statements/with/decl-gen.js",
        )) {
            put(path, "D-76: a function declaration as a statement body is an early error here")
        }

        // An accessor defined with `set: undefined` has no setter, so a strict write to it throws
        // here. Upstream calls the empty setter and drops the write, so these strict-only files
        // fail there (D-78).
        for (path in buildList {
            add("language/expressions/assignment/11.13.1-2-s.js")
            for (n in 34..44) add("language/expressions/compound-assignment/11.13.2-$n-s.js")
            for (op in listOf("and", "nullish", "or")) {
                add("language/expressions/logical-assignment/lgcl-$op-assignment-operator-no-set.js")
                add("language/expressions/logical-assignment/lgcl-$op-assignment-operator-no-set-put.js")
            }
        }) {
            put(path, "D-78: a strict write to an accessor with set: undefined throws here")
        }

        // A const in a block is bound afresh each time here, so the harness that parses native
        // function source, which declares consts in its loops, now works, and the tests that use
        // it pass. Upstream keeps the first pass's value and its copy of the harness fails (D-74).
        for (path in listOf(
            "harness/nativeFunctionMatcher.js",
            "built-ins/Function/prototype/toString/bound-function.js",
            "built-ins/Function/prototype/toString/symbol-named-builtins.js",
        )) {
            put(path, "D-74: a const in a loop body binds afresh here, so the harness runs")
        }

        // Math is fdlibm here, as V8 has it. Upstream computes log2 as log(x) * LOG2E, which is a
        // unit off for powers of two: Math.log2(8) is 2.9999999999999996 (D-73).
        put("built-ins/Math/log2/log2-basicTests.js", "D-73: log2 is fdlibm's here and exact for powers of two")

        // toReversed and toSorted validate their receiver, and with converts a bigint view's
        // replacement with ToBigInt. Upstream does neither (D-83).
        for (path in listOf(
            "built-ins/TypedArray/prototype/toReversed/this-value-invalid.js",
            "built-ins/TypedArray/prototype/toSorted/this-value-invalid.js",
            "built-ins/TypedArray/prototype/with/BigInt/early-type-coercion-bigint.js",
        )) {
            put(path, "D-83: the ES2023 typed array methods validate and convert as the spec says here")
        }
        // ToBigInt turns a Number away here. Upstream converts it the way BigInt() does (D-84).
        for (path in listOf(
            "built-ins/TypedArray/prototype/set/BigInt/number-tobigint.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/number-tobigint.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/undefined-tobigint.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/typedarray-arg/src-typedarray-not-big-throws.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/number-tobigint.js",
        )) {
            put(path, "D-84: a Number written into a bigint view is a TypeError here")
        }
        // set puts a primitive source through ToObject here; upstream demands an object (D-86).
        for (path in listOf(
            "built-ins/TypedArray/prototype/set/array-arg-primitive-toobject.js",
            "built-ins/TypedArray/prototype/set/BigInt/array-arg-primitive-toobject.js",
        )) {
            put(path, "D-86: TypedArray set takes any array-like here")
        }

        // Typed arrays are integer-indexed exotic objects here: every canonical numeric key stays on the
        // element path, elements have descriptors and own keys, and a write converts first (D-88).
        for (path in listOf(
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-not-canonical-index.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-numericindex-accessor-desc-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-numericindex-desc-not-configurable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-numericindex-desc-not-enumerable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-numericindex-desc-not-writable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/key-is-numericindex.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/BigInt/non-extensible-redefine-key.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-not-canonical-index.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-numericindex-accessor-desc-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-numericindex-desc-not-configurable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-numericindex-desc-not-enumerable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-numericindex-desc-not-writable-throws.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/key-is-numericindex.js",
            "built-ins/TypedArrayConstructors/internals/DefineOwnProperty/non-extensible-redefine-key.js",
            "built-ins/TypedArrayConstructors/internals/Delete/BigInt/indexed-value-ab-strict.js",
            "built-ins/TypedArrayConstructors/internals/Delete/BigInt/key-is-not-minus-zero-strict.js",
            "built-ins/TypedArrayConstructors/internals/Delete/BigInt/key-is-out-of-bounds-strict.js",
            "built-ins/TypedArrayConstructors/internals/Delete/indexed-value-ab-strict.js",
            "built-ins/TypedArrayConstructors/internals/Delete/key-is-not-minus-zero-strict.js",
            "built-ins/TypedArrayConstructors/internals/Delete/key-is-out-of-bounds-strict.js",
            "built-ins/TypedArrayConstructors/internals/Get/BigInt/key-is-not-integer.js",
            "built-ins/TypedArrayConstructors/internals/Get/BigInt/key-is-not-minus-zero.js",
            "built-ins/TypedArrayConstructors/internals/Get/BigInt/key-is-out-of-bounds.js",
            "built-ins/TypedArrayConstructors/internals/Get/key-is-not-integer.js",
            "built-ins/TypedArrayConstructors/internals/Get/key-is-not-minus-zero.js",
            "built-ins/TypedArrayConstructors/internals/Get/key-is-out-of-bounds.js",
            "built-ins/TypedArrayConstructors/internals/GetOwnProperty/BigInt/index-prop-desc.js",
            "built-ins/TypedArrayConstructors/internals/GetOwnProperty/index-prop-desc.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/BigInt/abrupt-from-ordinary-has-parent-hasproperty.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/BigInt/key-is-lower-than-zero.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/BigInt/key-is-minus-zero.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/BigInt/key-is-not-integer.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/abrupt-from-ordinary-has-parent-hasproperty.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/key-is-lower-than-zero.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/key-is-minus-zero.js",
            "built-ins/TypedArrayConstructors/internals/HasProperty/key-is-not-integer.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/BigInt/integer-indexes-and-string-and-symbol-keys-.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/BigInt/integer-indexes-and-string-keys.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/BigInt/integer-indexes.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/integer-indexes-and-string-and-symbol-keys-.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/integer-indexes-and-string-keys.js",
            "built-ins/TypedArrayConstructors/internals/OwnPropertyKeys/integer-indexes.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-canonical-invalid-index-prototype-chain-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/tonumber-value-throws.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-canonical-invalid-index-prototype-chain-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/tonumber-value-throws.js",
            "language/statements/with/set-mutable-binding-binding-deleted-with-typed-array-in-proto-chain.js",
        )) {
            put(path, "D-88: typed arrays implement the integer-indexed exotic object methods here")
        }
        // Seal, freeze and defineProperty send partial descriptors through each object's own
        // [[DefineOwnProperty]], a refusal is a TypeError, and only the fields a descriptor has are
        // compared (D-88).
        for (path in listOf(
            "built-ins/Object/defineProperties/15.2.3.7-6-a-184.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-185.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-282.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-188.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-189.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-293-1.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-293-3.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-293-4.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-336.js",
            "built-ins/Object/freeze/proxy-with-defineProperty-handler.js",
            "built-ins/Object/seal/proxy-with-defineProperty-handler.js",
            "built-ins/Proxy/defineProperty/trap-is-missing-target-is-proxy.js",
            "built-ins/Proxy/defineProperty/trap-is-undefined-target-is-proxy.js",
        )) {
            put(path, "D-88: definitions use partial descriptors and a refusal throws here")
        }
        // An arguments object gives a live argument its slot before a definition is checked, so a
        // non-configurable or read-only argument keeps the mapping rules the spec gives it (D-88).
        for (path in listOf(
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-3.js",
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-delete-1.js",
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-nonwritable-1.js",
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-nonwritable-2.js",
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-nonwritable-5.js",
            "language/arguments-object/mapped/mapped-arguments-nonconfigurable-strict-delete-1.js",
            "language/arguments-object/mapped/mapped-arguments-nonwritable-nonconfigurable-1.js",
            "language/arguments-object/mapped/mapped-arguments-nonwritable-nonconfigurable-2.js",
            "language/arguments-object/mapped/mapped-arguments-nonwritable-nonconfigurable-3.js",
            "language/arguments-object/mapped/mapped-arguments-nonwritable-nonconfigurable-4.js",
            "language/arguments-object/mapped/nonconfigurable-descriptors-basic.js",
            "language/arguments-object/mapped/nonconfigurable-descriptors-set-value-by-arguments.js",
            "language/arguments-object/mapped/nonconfigurable-descriptors-set-value-with-define-property.js",
            "language/arguments-object/mapped/nonconfigurable-descriptors-with-param-assign.js",
            "language/arguments-object/mapped/nonconfigurable-nonenumerable-nonwritable-descriptors-basic.js",
            "language/arguments-object/mapped/nonconfigurable-nonwritable-descriptors-basic.js",
            "language/arguments-object/mapped/nonconfigurable-nonwritable-descriptors-define-property-consecutive.js",
            "language/arguments-object/mapped/nonwritable-nonconfigurable-descriptors-basic.js",
            "language/arguments-object/mapped/nonwritable-nonconfigurable-descriptors-set-by-arguments.js",
            "language/arguments-object/mapped/nonwritable-nonconfigurable-descriptors-set-by-param.js",
            "language/arguments-object/mapped/nonwritable-nonenumerable-nonconfigurable-descriptors-basic.js",
            "language/arguments-object/mapped/nonwritable-nonenumerable-nonconfigurable-descriptors-set-by-arguments.js",
            "language/arguments-object/mapped/nonwritable-nonenumerable-nonconfigurable-descriptors-set-by-param.js",
        )) {
            put(path, "D-88: arguments objects define their mapped properties as the spec says here")
        }

        // Date.prototype[Symbol.toPrimitive] is non-writable here and writable upstream (D-56).
        put(
            "built-ins/Date/prototype/Symbol.toPrimitive/prop-desc.js",
            "D-56: the port makes the property non-writable, as the spec asks",
        )

        // The two places the port is the weaker one, both for want of Unicode data that common
        // Kotlin has none of: there is no normalizer (D-38) and no collation (D-37).
        for (path in listOf(
            "built-ins/String/prototype/normalize/return-normalized-string.js",
            "built-ins/String/prototype/normalize/return-normalized-string-from-coerced-form.js",
            "built-ins/String/prototype/normalize/return-normalized-string-using-default-parameter.js",
        )) {
            put(path, "D-38: there is no Unicode normalizer in common Kotlin, so normalize returns its input")
        }
        put(
            "built-ins/String/prototype/localeCompare/15.5.4.9_CE.js",
            "D-37: there is no collation data, so localeCompare falls back to code unit order",
        )

        // Reflect.get, set, deleteProperty and defineProperty run the target's own internal methods
        // with the caller's receiver, convert the key first and report a refusal as false (D-89).
        for (path in listOf(
            "built-ins/Reflect/defineProperty/return-abrupt-from-property-key.js",
            "built-ins/Reflect/deleteProperty/return-abrupt-from-result.js",
            "built-ins/Reflect/get/return-value-from-receiver.js",
            "built-ins/Reflect/set/call-prototype-property-set.js",
            "built-ins/Reflect/set/different-property-descriptors.js",
            "built-ins/Reflect/set/receiver-is-not-object.js",
            "built-ins/Reflect/set/return-abrupt-from-result.js",
            "built-ins/Reflect/set/return-false-if-receiver-is-not-writable.js",
            "built-ins/Reflect/set/return-false-if-target-is-not-writable.js",
        )) {
            put(path, "D-89: Reflect follows the target's internal methods with the receiver here")
        }

        // Proxy traps get the receiver and their false answers count, a trapless proxy forwards with
        // itself as the receiver, and no `has` or `getPrototypeOf` trap is called that the spec
        // never reaches (D-89).
        for (path in listOf(
            "built-ins/Array/prototype/splice/property-traps-order-with-species.js",
            "built-ins/JSON/parse/reviver-array-define-prop-err.js",
            "built-ins/JSON/parse/reviver-object-define-prop-err.js",
            "built-ins/Proxy/defineProperty/desc-realm.js",
            "built-ins/Proxy/defineProperty/targetdesc-not-configurable-writable-desc-not-writable.js",
            "built-ins/Proxy/deleteProperty/boolean-trap-result-boolean-false.js",
            "built-ins/Proxy/deleteProperty/return-false-not-strict.js",
            "built-ins/Proxy/deleteProperty/return-false-strict.js",
            "built-ins/Proxy/deleteProperty/targetdesc-is-configurable-target-is-not-extensible.js",
            "built-ins/Proxy/deleteProperty/trap-is-null-target-is-proxy.js",
            "built-ins/Proxy/deleteProperty/trap-is-undefined-strict.js",
            "built-ins/Proxy/deleteProperty/trap-is-undefined-target-is-proxy.js",
            "built-ins/Proxy/get/trap-is-undefined-receiver.js",
            "built-ins/Proxy/has/call-in-prototype.js",
            "built-ins/Proxy/has/call-with.js",
            "built-ins/Proxy/has/return-false-target-not-extensible-using-with.js",
            "built-ins/Proxy/has/return-false-target-prop-exists-using-with.js",
            "built-ins/Proxy/has/return-false-targetdesc-not-configurable-using-with.js",
            "built-ins/Proxy/has/return-is-abrupt-with.js",
            "built-ins/Proxy/has/trap-is-not-callable-using-with.js",
            "built-ins/Proxy/set/boolean-trap-result-is-false-boolean-return-false.js",
            "built-ins/Proxy/set/boolean-trap-result-is-false-null-return-false.js",
            "built-ins/Proxy/set/boolean-trap-result-is-false-number-return-false.js",
            "built-ins/Proxy/set/boolean-trap-result-is-false-string-return-false.js",
            "built-ins/Proxy/set/boolean-trap-result-is-false-undefined-return-false.js",
            "built-ins/Proxy/set/call-parameters-prototype-dunder-proto.js",
            "built-ins/Proxy/set/call-parameters-prototype.js",
            "built-ins/Proxy/set/call-parameters.js",
            "built-ins/Proxy/set/trap-is-missing-receiver-multiple-calls.js",
            "built-ins/Proxy/set/trap-is-missing-target-is-proxy.js",
            "built-ins/Proxy/set/trap-is-null-receiver.js",
            "built-ins/Proxy/set/trap-is-null-target-is-proxy.js",
            "built-ins/Proxy/set/trap-is-undefined-target-is-proxy.js",
        )) {
            put(path, "D-89: proxy traps receive the receiver and are believed when they refuse here")
        }

        // A typed array's [[Set]] reached through Reflect.set or a prototype chain keeps the element
        // semantics only when the typed array is the receiver (D-89).
        for (path in listOf(
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-canonical-invalid-index-reflect-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-not-canonical-index.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-not-numeric-index.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-symbol.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-valid-index-prototype-chain-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/key-is-valid-index-reflect-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-canonical-invalid-index-reflect-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-not-canonical-index.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-not-numeric-index.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-out-of-bounds-receiver-is-not-object.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-symbol.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-valid-index-prototype-chain-set.js",
            "built-ins/TypedArrayConstructors/internals/Set/key-is-valid-index-reflect-set.js",
        )) {
            put(path, "D-89: a typed array's [[Set]] honours the receiver here")
        }

        // A `with` object binds a name only when it has the property and its @@unscopables does not
        // block it, and a binding deleted while that is read is undefined or a ReferenceError (D-89).
        for (path in listOf(
            "language/expressions/arrow-function/unscopables-with-in-nested-fn.js",
            "language/expressions/arrow-function/unscopables-with.js",
            "language/expressions/function/unscopables-with-in-nested-fn.js",
            "language/expressions/function/unscopables-with.js",
            "language/expressions/generators/unscopables-with-in-nested-fn.js",
            "language/expressions/generators/unscopables-with.js",
            "language/expressions/object/prop-def-id-eval-error-2.js",
            "language/expressions/object/prop-def-id-eval-error.js",
            "language/statements/function/unscopables-with-in-nested-fn.js",
            "language/statements/function/unscopables-with.js",
            "language/statements/generators/unscopables-with-in-nested-fn.js",
            "language/statements/generators/unscopables-with.js",
            "language/statements/with/binding-blocked-by-unscopables.js",
            "language/statements/with/get-binding-value-call-with-proxy-env.js",
            "language/statements/with/get-binding-value-idref-with-proxy-env.js",
            "language/statements/with/get-mutable-binding-binding-deleted-in-get-unscopables-strict-mode.js",
            "language/statements/with/get-mutable-binding-binding-deleted-in-get-unscopables.js",
            "language/statements/with/has-binding-call-with-proxy-env.js",
            "language/statements/with/has-binding-idref-with-proxy-env.js",
            "language/statements/with/has-property-err.js",
            "language/statements/with/set-mutable-binding-binding-deleted-in-get-unscopables.js",
            "language/statements/with/set-mutable-binding-binding-deleted-with-typed-array-in-proto-chain-strict-mode.js",
            "language/statements/with/set-mutable-binding-idref-compound-assign-with-proxy-env.js",
            "language/statements/with/set-mutable-binding-idref-with-proxy-env.js",
            "language/statements/with/unscopables-get-err.js",
            "language/statements/with/unscopables-inc-dec.js",
            "language/statements/with/unscopables-prop-get-err.js",
        )) {
            put(path, "D-89: with environments implement HasBinding and @@unscopables here")
        }

        // Strict code that assigns to a binding the right-hand side deleted is a ReferenceError (D-89).
        for (path in listOf(
            "language/expressions/assignment/assignment-operator-calls-putvalue-lref--rval--1.js",
            "language/expressions/assignment/assignment-operator-calls-putvalue-lref--rval-.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--1.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--10.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--11.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--12.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--13.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--14.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--15.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--16.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--17.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--18.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--19.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--2.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--20.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--21.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--3.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--4.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--5.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--6.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--7.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--8.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v--9.js",
            "language/expressions/compound-assignment/compound-assignment-operator-calls-putvalue-lref--v-.js",
        )) {
            put(path, "D-89: a strict write to a binding that disappeared is a ReferenceError here")
        }

        // IsConstructor is true only for a function with [[Construct]], so a built-in method, an arrow,
        // a method or a generator is no constructor and no newTarget (D-91).
        for (path in listOf(
            "annexB/built-ins/Date/prototype/getYear/not-a-constructor.js",
            "annexB/built-ins/Date/prototype/setYear/not-a-constructor.js",
            "annexB/built-ins/Date/prototype/toGMTString/not-a-constructor.js",
            "built-ins/Date/UTC/not-a-constructor.js",
            "built-ins/Date/now/not-a-constructor.js",
            "built-ins/Date/parse/not-a-constructor.js",
            "built-ins/Date/prototype/getDate/not-a-constructor.js",
            "built-ins/Date/prototype/getDay/not-a-constructor.js",
            "built-ins/Date/prototype/getFullYear/not-a-constructor.js",
            "built-ins/Date/prototype/getHours/not-a-constructor.js",
            "built-ins/Date/prototype/getMilliseconds/not-a-constructor.js",
            "built-ins/Date/prototype/getMinutes/not-a-constructor.js",
            "built-ins/Date/prototype/getMonth/not-a-constructor.js",
            "built-ins/Date/prototype/getSeconds/not-a-constructor.js",
            "built-ins/Date/prototype/getTime/not-a-constructor.js",
            "built-ins/Date/prototype/getTimezoneOffset/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCDate/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCDay/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCFullYear/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCHours/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCMilliseconds/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCMinutes/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCMonth/not-a-constructor.js",
            "built-ins/Date/prototype/getUTCSeconds/not-a-constructor.js",
            "built-ins/Date/prototype/setDate/not-a-constructor.js",
            "built-ins/Date/prototype/setFullYear/not-a-constructor.js",
            "built-ins/Date/prototype/setHours/not-a-constructor.js",
            "built-ins/Date/prototype/setMilliseconds/not-a-constructor.js",
            "built-ins/Date/prototype/setMinutes/not-a-constructor.js",
            "built-ins/Date/prototype/setMonth/not-a-constructor.js",
            "built-ins/Date/prototype/setSeconds/not-a-constructor.js",
            "built-ins/Date/prototype/setTime/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCDate/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCFullYear/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCHours/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCMilliseconds/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCMinutes/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCMonth/not-a-constructor.js",
            "built-ins/Date/prototype/setUTCSeconds/not-a-constructor.js",
            "built-ins/Date/prototype/toDateString/not-a-constructor.js",
            "built-ins/Date/prototype/toISOString/not-a-constructor.js",
            "built-ins/Date/prototype/toJSON/not-a-constructor.js",
            "built-ins/Date/prototype/toLocaleDateString/not-a-constructor.js",
            "built-ins/Date/prototype/toLocaleString/not-a-constructor.js",
            "built-ins/Date/prototype/toLocaleTimeString/not-a-constructor.js",
            "built-ins/Date/prototype/toString/not-a-constructor.js",
            "built-ins/Date/prototype/toTimeString/not-a-constructor.js",
            "built-ins/Date/prototype/toUTCString/not-a-constructor.js",
            "built-ins/Date/prototype/valueOf/not-a-constructor.js",
            "built-ins/Error/isError/is-a-constructor.js",
            "built-ins/Error/prototype/toString/not-a-constructor.js",
            "built-ins/Proxy/revocable/revocation-function-not-a-constructor.js",
            "built-ins/Reflect/construct/newtarget-is-not-constructor-throws.js",
            "built-ins/RegExp/prototype/Symbol.match/not-a-constructor.js",
            "built-ins/RegExp/prototype/Symbol.matchAll/not-a-constructor.js",
            "built-ins/RegExp/prototype/Symbol.replace/not-a-constructor.js",
            "built-ins/RegExp/prototype/Symbol.search/not-a-constructor.js",
            "built-ins/RegExp/prototype/Symbol.split/not-a-constructor.js",
            "built-ins/RegExp/prototype/exec/not-a-constructor.js",
            "built-ins/RegExp/prototype/test/not-a-constructor.js",
            "built-ins/RegExp/prototype/toString/S15.10.6.4_A6.js",
            "built-ins/RegExp/prototype/toString/S15.10.6.4_A7.js",
            "built-ins/RegExp/prototype/toString/not-a-constructor.js",
            "harness/isConstructor.js",
        )) {
            put(path, "D-91: IsConstructor is exact here")
        }

        // [[Construct]] takes a newTarget: the object gets newTarget's `prototype`, or the same intrinsic's
        // prototype in newTarget's realm when that is not an object, and a proxy passes newTarget on (D-91).
        for (path in listOf(
            "built-ins/AggregateError/proto-from-ctor-realm.js",
            "built-ins/Array/proto-from-ctor-realm-one.js",
            "built-ins/Array/proto-from-ctor-realm-two.js",
            "built-ins/Array/proto-from-ctor-realm-zero.js",
            "built-ins/ArrayBuffer/proto-from-ctor-realm.js",
            "built-ins/ArrayBuffer/prototype-from-newtarget.js",
            "built-ins/Boolean/proto-from-ctor-realm.js",
            "built-ins/DataView/custom-proto-access-throws.js",
            "built-ins/DataView/custom-proto-if-object-is-used.js",
            "built-ins/DataView/proto-from-ctor-realm.js",
            "built-ins/Date/proto-from-ctor-realm-one.js",
            "built-ins/Date/proto-from-ctor-realm-two.js",
            "built-ins/Date/proto-from-ctor-realm-zero.js",
            "built-ins/Error/proto-from-ctor-realm.js",
            "built-ins/Function/internals/Construct/base-ctor-revoked-proxy-realm.js",
            "built-ins/Function/internals/Construct/base-ctor-revoked-proxy.js",
            "built-ins/Function/proto-from-ctor-realm-prototype.js",
            "built-ins/Function/proto-from-ctor-realm.js",
            "built-ins/Function/prototype/bind/get-fn-realm-recursive.js",
            "built-ins/Function/prototype/bind/get-fn-realm.js",
            "built-ins/Function/prototype/bind/proto-from-ctor-realm.js",
            "built-ins/GeneratorFunction/proto-from-ctor-realm-prototype.js",
            "built-ins/Iterator/proto-from-ctor-realm.js",
            "built-ins/Map/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/EvalError/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/RangeError/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/ReferenceError/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/SyntaxError/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/TypeError/proto-from-ctor-realm.js",
            "built-ins/NativeErrors/URIError/proto-from-ctor-realm.js",
            "built-ins/Number/proto-from-ctor-realm.js",
            "built-ins/Object/proto-from-ctor-realm.js",
            "built-ins/Promise/get-prototype-abrupt.js",
            "built-ins/Promise/proto-from-ctor-realm.js",
            "built-ins/Proxy/construct/call-parameters-new-target.js",
            "built-ins/Proxy/construct/trap-is-null.js",
            "built-ins/Proxy/construct/trap-is-undefined-no-property.js",
            "built-ins/Proxy/construct/trap-is-undefined-proto-from-newtarget-realm.js",
            "built-ins/Proxy/construct/trap-is-undefined.js",
            "built-ins/Proxy/get-fn-realm-recursive.js",
            "built-ins/Proxy/get-fn-realm.js",
            "built-ins/RegExp/proto-from-ctor-realm.js",
            "built-ins/Set/proto-from-ctor-realm.js",
            "built-ins/String/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/buffer-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/buffer-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/buffer-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/no-args/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/no-args/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/no-args/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/typedarray-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/typedarray-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/buffer-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors/buffer-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/buffer-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors/no-args/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors/no-args/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/no-args/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/use-custom-proto-if-object.js",
            "built-ins/TypedArrayConstructors/ctors/typedarray-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors/typedarray-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/typedarray-arg/use-custom-proto-if-object.js",
            "built-ins/WeakMap/proto-from-ctor-realm.js",
            "built-ins/WeakSet/proto-from-ctor-realm.js",
        )) {
            put(path, "D-91: a constructor takes its prototype from newTarget here")
        }

        // A proxy validates its getOwnPropertyDescriptor answer, believes its setPrototypeOf answer, and
        // hands index keys to its traps as strings (D-91).
        for (path in listOf(
            "built-ins/Proxy/getOwnPropertyDescriptor/resultdesc-is-invalid-descriptor.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/resultdesc-is-not-configurable-not-writable-targetdesc-is-writable.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/resultdesc-is-not-configurable-targetdesc-is-configurable.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/resultdesc-is-not-configurable-targetdesc-is-undefined.js",
            "built-ins/Proxy/has/call-in-prototype-index.js",
            "built-ins/Proxy/ownKeys/trap-is-undefined-target-is-proxy.js",
            "built-ins/Proxy/set/call-parameters-prototype-index.js",
            "built-ins/Proxy/set/trap-is-missing-receiver-multiple-calls-index.js",
            "built-ins/Proxy/setPrototypeOf/internals-call-order.js",
            "built-ins/Proxy/setPrototypeOf/not-extensible-target-not-same-target-prototype.js",
            "built-ins/Proxy/setPrototypeOf/toboolean-trap-result-false.js",
            "built-ins/Proxy/setPrototypeOf/toboolean-trap-result-true-target-is-extensible.js",
            "built-ins/Proxy/setPrototypeOf/trap-is-missing-target-is-proxy.js",
            "built-ins/Proxy/setPrototypeOf/trap-is-null-target-is-proxy.js",
        )) {
            put(path, "D-91: the proxy's internal methods follow the spec here")
        }

        // Keys come in [[OwnPropertyKeys]] order with every array index first, and enumeration asks
        // [[GetOwnProperty]] for each key just before reading it (D-91).
        for (path in listOf(
            "built-ins/Object/assign/assignment-to-readonly-property-of-target-must-throw-a-typeerror-exception.js",
            "built-ins/Object/assign/source-own-prop-error.js",
            "built-ins/Object/assign/strings-and-symbol-order-proxy.js",
            "built-ins/Object/defineProperties/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/entries/observable-operations.js",
            "built-ins/Object/freeze/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/getOwnPropertyDescriptors/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/isFrozen/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/isSealed/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/keys/property-traps-order-with-proxied-array.js",
            "built-ins/Object/seal/proxy-no-ownkeys-returned-keys-order.js",
            "built-ins/Object/values/observable-operations.js",
            "built-ins/Reflect/ownKeys/return-on-corresponding-order-large-index.js",
            "language/expressions/object/object-spread-proxy-get-not-called-on-dontenum-keys.js",
            "language/expressions/object/object-spread-proxy-no-excluded-keys.js",
            "language/expressions/object/object-spread-proxy-ownkeys-returned-keys-order.js",
        )) {
            put(path, "D-91: enumeration asks each key for its descriptor here")
        }

        // hasOwnProperty, Object.hasOwn and propertyIsEnumerable convert the key before `this`, and a
        // ToPrimitive that answers a symbol finds the symbol property (D-91).
        for (path in listOf(
            "built-ins/Object/hasOwn/length.js",
            "built-ins/Object/hasOwn/symbol_property_toPrimitive.js",
            "built-ins/Object/hasOwn/symbol_property_toString.js",
            "built-ins/Object/hasOwn/symbol_property_valueOf.js",
            "built-ins/Object/prototype/hasOwnProperty/symbol_property_toPrimitive.js",
            "built-ins/Object/prototype/hasOwnProperty/symbol_property_toString.js",
            "built-ins/Object/prototype/hasOwnProperty/symbol_property_valueOf.js",
            "built-ins/Object/prototype/hasOwnProperty/topropertykey_before_toobject.js",
            "built-ins/Object/prototype/propertyIsEnumerable/symbol_property_toPrimitive.js",
            "built-ins/Object/prototype/propertyIsEnumerable/symbol_property_toString.js",
            "built-ins/Object/prototype/propertyIsEnumerable/symbol_property_valueOf.js",
        )) {
            put(path, "D-91: own-property checks convert the key first here")
        }

        // The __proto__ accessor and Object.setPrototypeOf go through [[SetPrototypeOf]], whose cycle walk
        // stops at a proxy and which keeps Object.prototype's prototype null (D-91).
        for (path in listOf(
            "built-ins/Object/prototype/__proto__/get-to-obj-abrupt.js",
            "built-ins/Object/prototype/__proto__/set-cycle-shadowed.js",
            "built-ins/Object/prototype/setPrototypeOf-with-non-circular-values-__proto__.js",
            "built-ins/Object/prototype/setPrototypeOf-with-non-circular-values.js",
        )) {
            put(path, "D-91: [[SetPrototypeOf]] follows the spec here")
        }

        // JSON.parse keeps -0 and internalizes with [[Get]], IsArray, CreateDataProperty and [[Delete]];
        // JSON.stringify reads toJSON once, sees a proxy for an array as an array and passes string keys (D-91).
        for (path in listOf(
            "built-ins/JSON/parse/revived-proxy.js",
            "built-ins/JSON/parse/reviver-array-get-prop-from-prototype.js",
            "built-ins/JSON/parse/reviver-array-length-coerce-err.js",
            "built-ins/JSON/parse/reviver-array-length-get-err.js",
            "built-ins/JSON/parse/reviver-call-order.js",
            "built-ins/JSON/parse/reviver-object-get-prop-from-prototype.js",
            "built-ins/JSON/parse/reviver-object-non-configurable-prop-create.js",
            "built-ins/JSON/parse/text-negative-zero.js",
            "built-ins/JSON/stringify/replacer-array-abrupt.js",
            "built-ins/JSON/stringify/replacer-array-proxy-revoked-realm.js",
            "built-ins/JSON/stringify/replacer-array-proxy-revoked.js",
            "built-ins/JSON/stringify/replacer-array-proxy.js",
            "built-ins/JSON/stringify/replacer-function-arguments.js",
            "built-ins/JSON/stringify/replacer-function-object-deleted-property.js",
            "built-ins/JSON/stringify/replacer-function-result.js",
            "built-ins/JSON/stringify/value-array-abrupt.js",
            "built-ins/JSON/stringify/value-array-proxy.js",
        )) {
            put(path, "D-91: JSON follows SerializeJSONProperty and InternalizeJSONProperty here")
        }

        // Only a call to the name eval is a direct eval, so another realm's eval runs in that realm (D-91).
        for (path in listOf(
            "language/types/reference/get-value-prop-base-primitive-realm.js",
            "language/types/reference/put-value-prop-base-primitive-realm.js",
        )) {
            put(path, "D-91: a property call of eval is an indirect eval here")
        }

        // instanceof asks the target's Symbol.hasInstance, primitive left side and all, and throws
        // for one that cannot be called (D-92).
        for (path in listOf(
            "language/expressions/instanceof/S11.8.6_A6_T2.js",
            "language/expressions/instanceof/symbol-hasinstance-get-err.js",
            "language/expressions/instanceof/symbol-hasinstance-invocation.js",
            "language/expressions/instanceof/symbol-hasinstance-not-callable.js",
            "language/expressions/instanceof/symbol-hasinstance-to-boolean.js",
        )) {
            put(path, "D-92: instanceof is InstanceofOperator here")
        }
    }

    /**
     * Like [knownDifferences], for files whose two engines disagree only outside strict mode. In
     * strict mode they agree, so a file-wide entry would read as stale there.
     */
    private val knownSloppyDifferences = buildMap {
        // Assigning to a const throws a TypeError here in any mode. Upstream ignores the write
        // outside strict mode, so these destructuring assignments pass only here (D-71).
        for (path in listOf(
            "language/expressions/assignment/dstr/array-elem-put-const.js",
            "language/expressions/assignment/dstr/obj-id-put-const.js",
            "language/expressions/assignment/dstr/obj-prop-put-const.js",
            "language/statements/for-of/dstr/array-elem-put-const.js",
            "language/statements/for-of/dstr/obj-id-put-const.js",
            "language/statements/for-of/dstr/obj-prop-put-const.js",
        )) {
            put(path, "D-71: a write to a const throws here and is ignored upstream")
        }
        // A labelled function as the body of a with is an early error here (D-76). In strict code
        // the with is an error already, in both engines.
        put("language/statements/with/labelled-fn-stmt.js", "D-76: a function declaration as a statement body is an early error here")
        // A trapless proxy's define answers false, and a proxy prototype's set trap sees an
        // assignment to a primitive. In strict mode the two engines agree (D-89).
        for (path in listOf(
            "built-ins/Proxy/defineProperty/targetdesc-undefined-target-is-not-extensible-realm.js",
            "language/types/reference/put-value-prop-base-primitive.js",
        )) {
            put(path, "D-89: proxy traps receive the receiver and are believed when they refuse here")
        }
    }

    /**
     * Like [knownDifferences], for files whose two engines disagree only in strict mode, where a
     * failed write throws. Outside strict mode both still let it pass silently, so a file-wide
     * entry would read as stale there.
     */
    private val knownStrictDifferences = buildMap {
        // An array whose length is read-only leaves dense mode, so push, unshift and splice reach
        // the length write that throws (D-88).
        for (path in listOf(
            "built-ins/Array/prototype/push/set-length-zero-array-length-is-non-writable.js",
            "built-ins/Array/prototype/splice/S15.4.4.12_A6.1_T2.js",
            "built-ins/Array/prototype/unshift/set-length-zero-array-length-is-non-writable.js",
        )) {
            put(path, "D-88: an array with a read-only length stops growing here")
        }
        // A refused delete through Reflect or a trapless proxy answers false. Upstream throws a
        // TypeError instead when the calling code is strict; outside strict mode both answer
        // false (D-89).
        for (path in listOf(
            "built-ins/Proxy/deleteProperty/trap-is-missing-target-is-proxy.js",
            "built-ins/Reflect/deleteProperty/return-boolean.js",
        )) {
            put(path, "D-89: a refused delete answers false here")
        }
        // A reviver's undefined answer for a non-configurable property is a [[Delete]] that answers
        // false. Upstream deletes with the calling code's strictness, so strict code throws (D-91).
        put("built-ins/JSON/parse/reviver-object-non-configurable-prop-delete.js", "D-91: the reviver's refused delete answers false here")
    }

    /**
     * Whether upstream failed before it could run the file. Upstream's parser rejects syntax this
     * engine takes, such as spread in an argument list, and a rejected harness file fails every
     * test that includes it, so those runs carry no verdict to compare with.
     */
    private fun upstreamFailedToParse(outcome: String): Boolean =
        outcome.startsWith("threw SyntaxError") || outcome.startsWith("crashed with EvaluatorException")

    private val upstreamHarness = HashMap<String, UScript>()
    private val portedHarness = HashMap<String, Script>()

    @Test
    fun theSuiteBehavesTheSameOnBothEngines() {
        if (!testRoot.isDirectory) {
            println("test262 not fetched; run tools/fetch-test262.sh. Skipping parity run.")
            return
        }

        val properties = Test262Properties.load(propertiesFile, testRoot)
        val filter = System.getProperty("test262.filter") ?: ""

        val expectations = StringBuilder()
        val differences = mutableListOf<String>()
        // Files upstream cannot parse. The port takes syntax upstream rejects, so there is
        // nothing to compare on these: they are counted and listed, not treated as a difference.
        val upstreamCannotParse = mutableListOf<String>()
        val staleExpectations = mutableListOf<String>()
        val agreedButExpectedToDiffer = mutableListOf<String>()
        var ran = 0
        var skipped = 0
        // Counting outcomes, not just differences: a run where both engines pass everything and a
        // run where the harness quietly does nothing both report zero differences otherwise.
        var passed = 0
        var failedBoth = 0
        val perFolder = HashMap<String, IntArray>()

        for (file in testRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.sorted()) {
            val relative = file.relativeTo(testRoot).path.replace('\\', '/')
            if (filter.isNotEmpty() && !relative.startsWith(filter)) continue
            // _FIXTURE files are imported by other tests, never run on their own.
            if (relative.endsWith("_FIXTURE.js")) continue
            if (properties.isSkipped(relative)) { skipped++; continue }

            val source = file.readText()
            val meta = Test262FrontMatter.parse(source)
            if (meta.features.any { it in unsupportedFeatures }) { skipped++; continue }
            // Neither engine has modules, and async needs a host event loop the runner has not got.
            if (meta.hasFlag("module") || meta.hasFlag("async")) { skipped++; continue }

            val modes = buildList {
                if (!meta.hasFlag("onlyStrict")) add(false)
                if (!meta.hasFlag("noStrict") && !meta.hasFlag("raw")) add(true)
            }

            for (strict in modes) {
                ran++
                currentTest = relative
                val upstream = runUpstream(relative, source, meta, strict)
                val ported = runPorted(relative, source, meta, strict)
                // What the port did here, for the other targets to match (Test262SliceTest).
                expectations.append(relative).append(if (strict) "\tstrict\t" else "\tsloppy\t")
                    .append(ported).append('\n')
                val known = relative in knownDifferences ||
                    (!strict && relative in knownSloppyDifferences) ||
                    (strict && relative in knownStrictDifferences)
                if (upstream != ported) {
                    val mode = if (strict) "strict" else "non-strict"
                    if (known) {
                        // Pinned on purpose; the stale check below watches it.
                    } else if (upstreamFailedToParse(upstream)) {
                        upstreamCannotParse.add("$relative [$mode]\n  upstream: $upstream\n  ported:   $ported")
                    } else {
                        differences.add("$relative [$mode]\n  upstream: $upstream\n  ported:   $ported")
                    }
                } else if (known) {
                    agreedButExpectedToDiffer.add(relative)
                } else if (upstream == PASS) {
                    passed++
                } else {
                    failedBoth++
                }
                val folder = relative.split('/').take(2).joinToString("/")
                val tally = perFolder.getOrPut(folder) { IntArray(2) }
                if (upstream == PASS && ported == PASS) tally[0]++
                tally[1]++
                if (!strict && relative in properties.expectedToFail && upstream == PASS) {
                    staleExpectations.add(relative)
                }
            }
        }

        println(
            "test262 parity: ran $ran cases, skipped $skipped files. " +
                "$passed agree passing, $failedBoth agree failing, ${differences.size} differ.",
        )
        if (upstreamCannotParse.isNotEmpty()) {
            val ranHere = upstreamCannotParse.count { it.endsWith("ported:   $PASS") }
            println(
                "${upstreamCannotParse.size} cases upstream cannot parse, so nothing to compare: " +
                    "the port ran them and $ranHere passed.",
            )
            val report = File("build/test262/upstream-cannot-parse.txt")
            report.parentFile.mkdirs()
            report.writeText(upstreamCannotParse.joinToString("\n\n"))
            println("that list: ${report.absolutePath}")
        }

        // The whole list goes to a file: a long run is expensive, so nothing it learned is thrown
        // away just because the assertion message has to stay readable.
        if (differences.isNotEmpty()) {
            val report = File("build/test262/differences.txt")
            report.parentFile.mkdirs()
            report.writeText(differences.joinToString("\n\n"))
            println("full difference list: ${report.absolutePath}")

            if (portCrashByTest.isNotEmpty()) {
                val byTest = File("build/test262/crashes.txt")
                byTest.parentFile.mkdirs()
                byTest.writeText(
                    portCrashByTest.distinct().sortedBy { it.first }
                        .joinToString("\n") { "${it.first}\t${it.second}" },
                )
            }
            if (portCrashSites.isNotEmpty()) {
                println("where the port crashed:")
                portCrashSites.entries.sortedByDescending { it.value }.take(20)
                    .forEach { println("  ${it.value.toString().padStart(6)}  ${it.key}") }
            }
            println("differences by folder:")
            differences.groupingBy { it.substringBefore(" [").split('/').take(2).joinToString("/") }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(30)
                .forEach { println("  ${it.value.toString().padStart(6)}  ${it.key}") }
        }
        assertTrue(ran > 0, "no test262 cases ran; is the filter '$filter' right?")

        // The port's own outcomes, which the common runner replays on JS and iOS. Lines rather
        // than JSON: common Kotlin has no parser in scope, and using the engine's own JSON to read
        // the expectations for the engine would be circular.
        val expectationsFile = File("build/test262/expectations.txt")
        expectationsFile.parentFile.mkdirs()
        expectationsFile.writeText(expectations.toString())
        println("recorded $ran outcomes to ${expectationsFile.absolutePath}")

        // A summary PORTING_STATUS can quote, so the numbers there come from a run.
        val summary = File("build/test262/summary.md")
        summary.parentFile.mkdirs()
        summary.writeText(
            buildString {
                appendLine("| Folder | Both pass | Cases |")
                appendLine("|---|---:|---:|")
                perFolder.entries.sortedBy { it.key }.forEach {
                    appendLine("| ${it.key} | ${it.value[0]} | ${it.value[1]} |")
                }
                appendLine()
                appendLine("Total: $passed of $ran cases pass on both engines, $failedBoth fail on both.")
            },
        )

        if (staleExpectations.isNotEmpty()) {
            println("${staleExpectations.size} files are marked as failing but upstream passes them:")
            staleExpectations.take(20).forEach { println("  $it") }
        }

        assertEquals(
            emptyList(),
            agreedButExpectedToDiffer.distinct(),
            "these are listed as known differences but the two engines now agree; drop the entry",
        )
        assertEquals(
            emptyList(),
            differences.take(MAX_REPORTED),
            "the port and upstream disagree on ${differences.size} cases",
        )
    }

    // ---- Running one case on each engine --------------------------------------------------------

    private fun runUpstream(path: String, source: String, meta: Test262FrontMatter, strict: Boolean): String {
        val cx = UContext.enter()
        try {
            cx.languageVersion = UContext.VERSION_ES6
            cx.isInterpretedMode = true
            val scope = cx.initSafeStandardObjects(org.mozilla.javascript.TopLevel(), false)
            for (name in meta.harnessFiles()) {
                upstreamHarness.getOrPut(name) {
                    cx.compileString(harnessSource(name), "harness/$name", 1, null)
                }.exec(cx, scope, scope)
            }
            Test262Host.installUpstream(cx, scope)

            var failedEarly = true
            return try {
                val text = if (strict) "\"use strict\";\n$source" else source
                val script = cx.compileString(text, path, if (strict) 0 else 1, null)
                failedEarly = false
                script.exec(cx, scope, scope)
                if (meta.isNegative) unexpectedPass(meta) else PASS
            } catch (e: org.mozilla.javascript.RhinoException) {
                judge(meta, errorNameUpstream(e), failedEarly)
            }
        } catch (e: Throwable) {
            return crash(e)
        } finally {
            UContext.exit()
        }
    }

    /** The port's side, run through the same code every other target uses. */
    private fun runPorted(path: String, source: String, meta: Test262FrontMatter, strict: Boolean): String =
        Test262Execution.run(
            path,
            source,
            meta,
            strict,
            meta.harnessFiles().map { harnessSource(it) },
            onCrash = { crash(it, record = true) },
        )

    // ---- Turning what happened into a string the two engines can be compared on -----------------

    /** Both engines answer this the same way when they behave the same way. */
    private fun judge(meta: Test262FrontMatter, errorName: String, failedEarly: Boolean): String {
        if (!meta.isNegative) return "threw $errorName"
        if (meta.hasEarlyError && !failedEarly) return "expected an early ${meta.negativeType}, got $errorName at runtime"
        if (errorName != meta.negativeType) return "expected ${meta.negativeType}, got $errorName"
        return PASS
    }

    private fun unexpectedPass(meta: Test262FrontMatter): String =
        "expected ${meta.negativeType} at ${meta.negativePhase} but nothing was thrown"

    /** Where the port crashed, counted per site, so the report groups by cause not by test. */
    private val portCrashSites = HashMap<String, Int>()

    /** The site for each crashing test, so a site can be traced back to something runnable. */
    private val portCrashByTest = mutableListOf<Pair<String, String>>()

    /** Set while a case runs, so [crash] knows which test it belongs to. */
    private var currentTest = ""

    /**
     * A crash is not a JavaScript outcome, so it is compared by type alone and never by message or
     * line: the two engines are different code and would never agree on a frame. The port's crash
     * site is recorded on the side, which is what makes 100 failures readable as three bugs.
     */
    private fun crash(e: Throwable, record: Boolean = false): String {
        if (record) {
            val frame = e.stackTrace.firstOrNull { it.className.startsWith("io.github.yuroyami.kitejs") }
                ?: e.stackTrace.firstOrNull()
            val where = frame?.let {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            } ?: "unknown"
            val key = "${e::class.simpleName} at $where"
            portCrashSites[key] = (portCrashSites[key] ?: 0) + 1
            portCrashByTest.add(key to currentTest)
        }
        return "crashed with ${e::class.simpleName}"
    }

    private fun errorNameUpstream(e: org.mozilla.javascript.RhinoException): String {
        if (e is org.mozilla.javascript.EvaluatorException) return "SyntaxError"
        return e.details().substringBefore(":")
    }

    private fun errorNamePorted(e: RhinoException): String {
        if (e is EvaluatorException) return "SyntaxError"
        return e.details().substringBefore(":")
    }

    private val harnessCache = HashMap<String, String>()

    private fun harnessSource(name: String): String =
        harnessCache.getOrPut(name) { File(harnessRoot, name).readText() }

    private companion object {
        val PASS = Test262Execution.PASS

        /** Enough differences to see the shape of a problem without an unreadable failure. */
        const val MAX_REPORTED = 40
    }
}
