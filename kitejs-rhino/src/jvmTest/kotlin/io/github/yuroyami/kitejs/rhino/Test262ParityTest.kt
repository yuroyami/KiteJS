/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

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
 * The suite is fetched by `tools/fetch-test262.sh` at the commit upstream pins. This dedicated
 * task fails if the corpus is missing; ordinary development tests do not run it.
 *
 * Run with `./gradlew test262Parity`. `-Dtest262.filter=built-ins/Array` narrows it while working.
 */
class Test262ParityTest {

    private val corpusRoot = File(System.getProperty("test262.root", "../reference/test262"))
    private val testRoot = File(corpusRoot, "test")
    private val harnessRoot = File(corpusRoot, "harness")
    private val propertiesFile = File("src/jvmTest/resources/test262.properties")

    /** Upstream's list, except default arguments which this port implements (D-116). */
    private val unsupportedFeatures = setOf(
        "Atomics", "IsHTMLDDA", "async-iteration", "decorators",
        "object-rest", "regexp-dotall", "regexp-unicode-property-escapes",
        "resizable-arraybuffer", "SharedArrayBuffer", "tail-call-optimization", "Temporal",
        "upsert", "u180e",
    )

    /**
     * Folders upstream's properties file skips whole because upstream has no class syntax (D-95)
     * and no async functions (D-97), which the port runs anyway. Upstream fails to parse every
     * file in them, so they only add to the port's own outcomes and to the cannot-parse list.
     */
    private val portOnlyFolders = listOf(
        "built-ins/AsyncFunction",
        "language/expressions/async-function",
        "language/expressions/await",
        "language/expressions/class",
        "language/expressions/new.target",
        "language/statements/async-function",
        "language/statements/class",
    )

    /**
     * Files where the two engines are known to disagree, with the reason. Each one is a ledger
     * entry, and each is asserted to still differ: if upstream changes, the entry goes stale and
     * this test says so rather than quietly passing.
     */
    private val knownDifferences = buildMap {
        for (path in listOf(
            "built-ins/Function/prototype/arguments/prop-desc.js",
            "built-ins/Function/prototype/bind/BoundFunction_restricted-properties.js",
            "built-ins/Function/prototype/caller-arguments/accessor-properties.js",
            "built-ins/Function/prototype/caller/prop-desc.js",
        )) {
            put(path, "D-109: modern restricted properties share the realm thrower here")
        }
        for (path in listOf(
            "built-ins/Function/call-bind-this-realm-undef.js",
        )) {
            put(path, "D-112: sloppy calls use the callee's own global here")
        }
        for (path in listOf(
            "built-ins/Function/prototype/apply/argarray-not-object-realm.js",
            "built-ins/Function/prototype/apply/this-not-callable-realm.js",
        )) {
            put(path, "D-117: apply validation errors use the built-in execution realm here")
        }
        for (path in listOf(
            "language/expressions/arrow-function/ArrowFunction_restricted-properties.js",
            "language/expressions/arrow-function/forbidden-ext/b1/arrow-function-forbidden-ext-direct-access-prop-arguments.js",
            "language/expressions/generators/forbidden-ext/b1/gen-func-expr-forbidden-ext-direct-access-prop-arguments.js",
            "language/expressions/object/method-definition/forbidden-ext/b1/gen-meth-forbidden-ext-direct-access-prop-arguments.js",
            "language/expressions/object/method-definition/forbidden-ext/b1/meth-forbidden-ext-direct-access-prop-arguments.js",
            "language/statements/function/13.2-10-s.js",
            "language/statements/function/13.2-13-s.js",
            "language/statements/function/13.2-14-s.js",
            "language/statements/function/13.2-17-s.js",
            "language/statements/function/13.2-18-s.js",
            "language/statements/function/13.2-19-b-3gs.js",
            "language/statements/function/13.2-2-s.js",
            "language/statements/function/13.2-21-s.js",
            "language/statements/function/13.2-22-s.js",
            "language/statements/function/13.2-25-s.js",
            "language/statements/function/13.2-26-s.js",
            "language/statements/function/13.2-30-s.js",
            "language/statements/function/13.2-4-s.js",
            "language/statements/function/13.2-5-s.js",
            "language/statements/function/13.2-6-s.js",
            "language/statements/function/13.2-9-s.js",
            "language/statements/generators/forbidden-ext/b1/gen-func-decl-forbidden-ext-direct-access-prop-arguments.js",
            "language/statements/generators/restricted-properties.js",
        )) {
            put(path, "D-109: modern function restrictions use the realm thrower here")
        }
        for (path in listOf(
            "language/eval-code/direct/block-decl-eval-source-is-strict-nostrict.js",
            "language/eval-code/direct/block-decl-eval-source-is-strict-onlystrict.js",
            "language/eval-code/direct/block-decl-onlystrict.js",
            "language/eval-code/direct/lex-env-distinct-const.js",
            "language/eval-code/direct/lex-env-distinct-let.js",
            "language/eval-code/direct/non-definable-global-var.js",
            "language/eval-code/direct/switch-case-decl-eval-source-is-strict-nostrict.js",
            "language/eval-code/direct/switch-case-decl-eval-source-is-strict-onlystrict.js",
            "language/eval-code/direct/switch-case-decl-onlystrict.js",
            "language/eval-code/direct/switch-dflt-decl-eval-source-is-strict-nostrict.js",
            "language/eval-code/direct/switch-dflt-decl-eval-source-is-strict-onlystrict.js",
            "language/eval-code/direct/switch-dflt-decl-onlystrict.js",
            "language/eval-code/direct/this-value-func-strict-caller.js",
            "language/eval-code/direct/var-env-func-init-global-update-configurable.js",
            "language/eval-code/direct/var-env-func-strict-caller-2.js",
            "language/eval-code/direct/var-env-func-strict-caller.js",
            "language/eval-code/direct/var-env-func-strict-source.js",
            "language/eval-code/direct/var-env-global-lex-non-strict.js",
            "language/eval-code/direct/var-env-lower-lex-non-strict.js",
            "language/eval-code/direct/var-env-var-strict-caller-2.js",
            "language/eval-code/direct/var-env-var-strict-caller-3.js",
            "language/eval-code/direct/var-env-var-strict-caller.js",
            "language/eval-code/direct/var-env-var-strict-source.js",
            "language/eval-code/indirect/block-decl-strict.js",
            "language/eval-code/indirect/lex-env-distinct-const.js",
            "language/eval-code/indirect/lex-env-distinct-let.js",
            "language/eval-code/indirect/non-definable-function-with-function.js",
            "language/eval-code/indirect/non-definable-function-with-variable.js",
            "language/eval-code/indirect/switch-case-decl-strict.js",
            "language/eval-code/indirect/switch-dflt-decl-strict.js",
            "language/eval-code/indirect/var-env-func-init-global-update-configurable.js",
            "language/eval-code/indirect/var-env-func-strict.js",
            "language/eval-code/indirect/var-env-global-lex-non-strict.js",
            "language/eval-code/indirect/var-env-var-strict.js",
            "language/expressions/arrow-function/eval-var-scope-syntax-err.js",
            "language/expressions/arrow-function/scope-body-lex-distinct.js",
            "language/expressions/function/eval-var-scope-syntax-err.js",
            "language/expressions/function/scope-body-lex-distinct.js",
            "language/expressions/generators/eval-var-scope-syntax-err.js",
            "language/expressions/generators/scope-body-lex-distinct.js",
            "language/statements/function/eval-var-scope-syntax-err.js",
            "language/statements/function/scope-body-lex-distinct.js",
            "language/statements/generators/eval-var-scope-syntax-err.js",
            "language/statements/generators/scope-body-lex-distinct.js",
        )) {
            put(path, "D-115: eval isolates declarations and validates intervening lexical and global conflicts here")
        }
        for (path in listOf(
            "language/eval-code/direct/arrow-fn-a-following-parameter-is-named-arguments-arrow-func-declare-arguments-assign-incl-def-param-arrow-arguments.js",
            "language/eval-code/direct/arrow-fn-a-following-parameter-is-named-arguments-arrow-func-declare-arguments-assign.js",
            "language/eval-code/direct/arrow-fn-a-preceding-parameter-is-named-arguments-arrow-func-declare-arguments-assign-incl-def-param-arrow-arguments.js",
            "language/eval-code/direct/arrow-fn-a-preceding-parameter-is-named-arguments-arrow-func-declare-arguments-assign.js",
            "language/eval-code/direct/arrow-fn-body-cntns-arguments-func-decl-arrow-func-declare-arguments-assign-incl-def-param-arrow-arguments.js",
            "language/eval-code/direct/arrow-fn-body-cntns-arguments-func-decl-arrow-func-declare-arguments-assign.js",
            "language/eval-code/direct/arrow-fn-body-cntns-arguments-lex-bind-arrow-func-declare-arguments-assign-incl-def-param-arrow-arguments.js",
            "language/eval-code/direct/arrow-fn-body-cntns-arguments-var-bind-arrow-func-declare-arguments-assign-incl-def-param-arrow-arguments.js",
            "language/eval-code/direct/func-decl-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/func-decl-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/func-decl-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/func-decl-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/func-expr-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/func-expr-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/func-expr-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/func-expr-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-decl-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-decl-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-decl-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-decl-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-expr-named-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-expr-named-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-expr-named-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-expr-named-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-expr-nameless-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-expr-nameless-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-func-expr-nameless-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-func-expr-nameless-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-meth-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-meth-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/gen-meth-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/gen-meth-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/meth-a-following-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/meth-a-following-parameter-is-named-arguments-declare-arguments.js",
            "language/eval-code/direct/meth-a-preceding-parameter-is-named-arguments-declare-arguments-and-assign.js",
            "language/eval-code/direct/meth-a-preceding-parameter-is-named-arguments-declare-arguments.js",
            "language/expressions/arrow-function/dflt-params-ref-later.js",
            "language/expressions/arrow-function/dflt-params-ref-self.js",
            "language/expressions/arrow-function/scope-paramsbody-var-open.js",
            "language/expressions/function/arguments-with-arguments-fn.js",
            "language/expressions/function/arguments-with-arguments-lex.js",
            "language/expressions/function/dflt-params-ref-later.js",
            "language/expressions/function/dflt-params-ref-self.js",
            "language/expressions/function/params-dflt-ref-arguments.js",
            "language/expressions/function/scope-paramsbody-var-open.js",
            "language/expressions/generators/dflt-params-ref-later.js",
            "language/expressions/generators/dflt-params-ref-self.js",
            "language/expressions/generators/dstr/ary-init-iter-get-err-array-prototype.js",
            "language/expressions/generators/dstr/ary-init-iter-get-err.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-ary-val-null.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-id-init-throws.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-id-init-unresolvable.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-id-iter-step-err.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-id-iter-val-err.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-obj-val-null.js",
            "language/expressions/generators/dstr/ary-ptrn-elem-obj-val-undef.js",
            "language/expressions/generators/dstr/dflt-ary-init-iter-get-err-array-prototype.js",
            "language/expressions/generators/dstr/dflt-ary-init-iter-get-err.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-ary-val-null.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-id-init-throws.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-id-init-unresolvable.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-id-iter-step-err.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-id-iter-val-err.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-obj-val-null.js",
            "language/expressions/generators/dstr/dflt-ary-ptrn-elem-obj-val-undef.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-id-get-value-err.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-id-init-throws.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-id-init-unresolvable.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-list-err.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-ary-value-null.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-id-get-value-err.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-id-init-throws.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-id-init-unresolvable.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-obj-value-null.js",
            "language/expressions/generators/dstr/dflt-obj-ptrn-prop-obj-value-undef.js",
            "language/expressions/generators/dstr/obj-ptrn-id-get-value-err.js",
            "language/expressions/generators/dstr/obj-ptrn-id-init-throws.js",
            "language/expressions/generators/dstr/obj-ptrn-id-init-unresolvable.js",
            "language/expressions/generators/dstr/obj-ptrn-list-err.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-ary-value-null.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-id-get-value-err.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-id-init-throws.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-id-init-unresolvable.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-obj-value-null.js",
            "language/expressions/generators/dstr/obj-ptrn-prop-obj-value-undef.js",
            "language/expressions/generators/scope-paramsbody-var-open.js",
            "language/expressions/object/method-definition/gen-meth-dflt-params-ref-later.js",
            "language/expressions/object/method-definition/gen-meth-dflt-params-ref-self.js",
            "language/expressions/object/method-definition/gen-meth-eval-var-scope-syntax-err.js",
            "language/expressions/object/method-definition/generator-invoke-fn-strict.js",
            "language/expressions/object/method-definition/generator-param-init-yield.js",
            "language/expressions/object/method-definition/meth-dflt-params-ref-later.js",
            "language/expressions/object/method-definition/meth-dflt-params-ref-self.js",
            "language/expressions/object/method-definition/meth-eval-var-scope-syntax-err.js",
            "language/expressions/object/method-definition/name-invoke-fn-strict.js",
            "language/expressions/object/method-definition/params-dflt-meth-ref-arguments.js",
            "language/statements/function/arguments-with-arguments-fn.js",
            "language/statements/function/arguments-with-arguments-lex.js",
            "language/statements/function/dflt-params-ref-later.js",
            "language/statements/function/dflt-params-ref-self.js",
            "language/statements/function/params-dflt-ref-arguments.js",
            "language/statements/function/scope-paramsbody-var-open.js",
            "language/statements/generators/dflt-params-ref-later.js",
            "language/statements/generators/dflt-params-ref-self.js",
            "language/statements/generators/dstr/ary-init-iter-get-err-array-prototype.js",
            "language/statements/generators/dstr/ary-init-iter-get-err.js",
            "language/statements/generators/dstr/ary-ptrn-elem-ary-val-null.js",
            "language/statements/generators/dstr/ary-ptrn-elem-id-init-throws.js",
            "language/statements/generators/dstr/ary-ptrn-elem-id-init-unresolvable.js",
            "language/statements/generators/dstr/ary-ptrn-elem-id-iter-step-err.js",
            "language/statements/generators/dstr/ary-ptrn-elem-id-iter-val-err.js",
            "language/statements/generators/dstr/ary-ptrn-elem-obj-val-null.js",
            "language/statements/generators/dstr/ary-ptrn-elem-obj-val-undef.js",
            "language/statements/generators/dstr/dflt-ary-init-iter-get-err-array-prototype.js",
            "language/statements/generators/dstr/dflt-ary-init-iter-get-err.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-ary-val-null.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-id-init-throws.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-id-init-unresolvable.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-id-iter-step-err.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-id-iter-val-err.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-obj-val-null.js",
            "language/statements/generators/dstr/dflt-ary-ptrn-elem-obj-val-undef.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-id-get-value-err.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-id-init-throws.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-id-init-unresolvable.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-list-err.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-ary-value-null.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-id-get-value-err.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-id-init-throws.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-id-init-unresolvable.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-obj-value-null.js",
            "language/statements/generators/dstr/dflt-obj-ptrn-prop-obj-value-undef.js",
            "language/statements/generators/dstr/obj-ptrn-id-get-value-err.js",
            "language/statements/generators/dstr/obj-ptrn-id-init-throws.js",
            "language/statements/generators/dstr/obj-ptrn-id-init-unresolvable.js",
            "language/statements/generators/dstr/obj-ptrn-list-err.js",
            "language/statements/generators/dstr/obj-ptrn-prop-ary-value-null.js",
            "language/statements/generators/dstr/obj-ptrn-prop-id-get-value-err.js",
            "language/statements/generators/dstr/obj-ptrn-prop-id-init-throws.js",
            "language/statements/generators/dstr/obj-ptrn-prop-id-init-unresolvable.js",
            "language/statements/generators/dstr/obj-ptrn-prop-obj-value-null.js",
            "language/statements/generators/dstr/obj-ptrn-prop-obj-value-undef.js",
            "language/statements/generators/scope-paramsbody-var-open.js",
        )) {
            put(path, "D-116: sequential parameter initialization precedes body declarations and generator creation here")
        }
        for (path in listOf(
            "language/expressions/function/param-dflt-yield-strict.js",
            "language/statements/function/param-dflt-yield-strict.js",
        )) {
            put(path, "D-86: yield is reserved in strict parameter initializers here")
        }
        for (path in listOf(
            "built-ins/Object/defineProperties/15.2.3.7-6-a-164.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-165.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-175.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-176.js",
        )) {
            put(path, "D-114: ArraySetLength restores length and read-only attributes after refusal here")
        }
        put("built-ins/Array/length/define-own-prop-length-coercion-order-set.js",
            "D-114: array length converts through ToUint32 before ToNumber here")
        for (path in listOf(
            "built-ins/Object/defineProperty/15.2.3.6-4-168.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-169.js",
        )) {
            put(path, "D-114: ArraySetLength stops at a refused deletion and restores length here")
        }
        for (path in listOf(
            "built-ins/Array/prototype/concat/create-proxy.js",
            "built-ins/Array/prototype/copyWithin/coerced-values-start-change-start.js",
            "built-ins/Array/prototype/copyWithin/coerced-values-start-change-target.js",
            "built-ins/Array/prototype/filter/create-proxy.js",
            "built-ins/Array/prototype/map/create-proxy.js",
            "built-ins/Array/prototype/pop/set-length-array-is-frozen.js",
            "built-ins/Array/prototype/pop/set-length-array-length-is-non-writable.js",
            "built-ins/Array/prototype/push/S15.4.4.7_A2_T2.js",
            "built-ins/Array/prototype/push/set-length-array-is-frozen.js",
            "built-ins/Array/prototype/push/set-length-array-length-is-non-writable.js",
            "built-ins/Array/prototype/push/set-length-zero-array-length-is-non-writable.js",
            "built-ins/Array/prototype/push/throws-if-integer-limit-exceeded.js",
            "built-ins/Array/prototype/reverse/length-exceeding-integer-limit-with-proxy.js",
            "built-ins/Array/prototype/shift/set-length-array-is-frozen.js",
            "built-ins/Array/prototype/shift/set-length-array-length-is-non-writable.js",
            "built-ins/Array/prototype/slice/create-proxy.js",
            "built-ins/Array/prototype/splice/S15.4.4.12_A6.1_T2.js",
            "built-ins/Array/prototype/splice/clamps-length-to-integer-limit.js",
            "built-ins/Array/prototype/splice/create-proto-from-ctor-realm-non-array.js",
            "built-ins/Array/prototype/splice/create-proxy.js",
            "built-ins/Array/prototype/splice/create-revoked-proxy.js",
            "built-ins/Array/prototype/splice/create-species.js",
            "built-ins/Array/prototype/splice/set_length_no_args.js",
            "built-ins/Array/prototype/unshift/set-length-array-is-frozen.js",
            "built-ins/Array/prototype/unshift/set-length-array-length-is-non-writable.js",
            "built-ins/Array/prototype/unshift/set-length-zero-array-length-is-non-writable.js",
        )) {
            put(path, "D-114: array mutations and species definitions report refused writes and deletions here")
        }
        for (initializers in listOf("with-initialisers", "without-initialisers")) {
            for (position in listOf("if-expression-statement-else-statement", "if-expression-statement", "label-statement")) {
                put("language/statements/let/syntax/$initializers-in-statement-positions-$position.js",
                    "D-113: lexical declarations are rejected in Statement-only contexts here")
            }
        }
        put("built-ins/Array/prototype/methods-called-as-functions.js",
            "D-112: extracted array methods receive undefined here")
        for (path in listOf(
            "built-ins/Promise/reject/capability-invocation.js",
            "built-ins/Promise/resolve/resolve-from-promise-capability.js",
        )) {
            put(path, "D-112: Promise callbacks bind this using the callee's own strictness here")
        }
        for (path in listOf(
            "built-ins/TypedArray/prototype/subarray/infinity.js",
            "built-ins/TypedArray/prototype/subarray/BigInt/infinity.js",
        )) {
            put(path, "D-58: subarray clamps infinite indices here; upstream fails")
        }
        for (number in listOf(62, 63, 64, 65, 76, 77, 78)) {
            for (suffix in listOf("-s", "gs")) {
                put("language/function-code/10.4.3-1-$number$suffix.js",
                    "D-112: bare calls preserve undefined for strict callees here")
            }
        }
        put("built-ins/ThrowTypeError/unique-per-realm-function-proto.js",
            "D-109: Function.prototype restrictions share the realm intrinsic here")
        // D-111: iterable/array-like dispatch and validation follow ECMAScript here.
        for (path in listOf(
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/is-infinity-throws-rangeerror.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/is-negative-integer-throws-rangeerror.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/is-symbol-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/length-arg/toindex-length.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/as-generator-iterable-returns.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/iterating-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/iterator-not-callable-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/iterator-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/length-excessive-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/length-is-symbol-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/length-throws.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/new-instance-extensibility.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/throws-from-property.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/throws-setting-property.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/throws-setting-symbol-property.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/is-infinity-throws-rangeerror.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/is-negative-integer-throws-rangeerror.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/is-symbol-throws.js",
            "built-ins/TypedArrayConstructors/ctors/length-arg/toindex-length.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/as-generator-iterable-returns.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/custom-proto-access-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterated-array-changed-by-tonumber.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterated-array-with-modified-array-iterator.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterating-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterator-is-null-as-array-like.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterator-not-callable-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/iterator-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/length-excessive-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/length-is-symbol-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/length-throws.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/new-instance-extensibility.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/proto-from-ctor-realm.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/returns.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/throws-from-property.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/throws-setting-property.js",
            "built-ins/TypedArrayConstructors/ctors/object-arg/throws-setting-symbol-property.js",
            "built-ins/TypedArrayConstructors/ctors/typedarray-arg/throw-type-error-before-custom-proto-access.js",
            "built-ins/TypedArrayConstructors/from/BigInt/mapfn-this-without-thisarg-non-strict.js",
            "built-ins/TypedArrayConstructors/from/mapfn-this-without-thisarg-non-strict.js",
        )) {
            put(path, "D-111: typed array construction/from observe input contracts here; upstream fails")
        }

        // ES6 for-in patterns destructure key strings instead of legacy key/value pairs (D-105).
        for (path in listOf("head-var-bound-names-dup.js", "scope-body-lex-close.js")) {
            put("language/statements/for-in/$path", "D-105: for-in destructures the key here")
        }

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

        // The iterator prototypes inherit one %IteratorPrototype%, and the generator prototypes
        // name each other (D-93).
        for (path in listOf(
            "built-ins/GeneratorFunction/prototype/prototype.js",
            "built-ins/GeneratorPrototype/constructor.js",
            "built-ins/Iterator/prototype/Symbol.iterator/is-function.js",
            "built-ins/Iterator/prototype/Symbol.iterator/length.js",
            "built-ins/Iterator/prototype/Symbol.iterator/name.js",
            "built-ins/Iterator/prototype/Symbol.iterator/prop-desc.js",
        )) {
            put(path, "D-93: the iterator prototypes inherit %IteratorPrototype% here")
        }

        // Class syntax runs here; upstream cannot parse it (D-95).
        for (path in listOf(
            "language/statements/switch/scope-lex-class.js",
            "staging/sm/fields/bug1587574.js",
        )) {
            put(path, "D-95: classes run here")
        }
        // A method, getter or setter of an object literal keeps its source text from its name on,
        // which upstream cuts short (D-95).
        for (path in listOf(
            "built-ins/Function/prototype/toString/generator-method.js",
            "built-ins/Function/prototype/toString/getter-object.js",
            "built-ins/Function/prototype/toString/method-computed-property-name.js",
            "built-ins/Function/prototype/toString/method-object.js",
            "built-ins/Function/prototype/toString/setter-object.js",
        )) {
            put(path, "D-95: a method's source text is the whole definition here")
        }
        // A let conflicts with a var or function of its scope, with a var in a block inside it and
        // with a parameter, a var with the let, const, class or block function of every block it
        // hoists out of, and a catch parameter with a lexical name of its block; upstream lets them
        // all through (D-95).
        for (path in listOf(
            "language/block-scope/syntax/redeclaration/fn-scope-var-name-redeclaration-attempt-with-function.js",
            "language/block-scope/syntax/redeclaration/fn-scope-var-name-redeclaration-attempt-with-generator.js",
            "language/block-scope/syntax/redeclaration/function-declaration-attempt-to-redeclare-with-var-declaration-nested-in-function.js",
            "language/block-scope/syntax/redeclaration/function-name-redeclaration-attempt-with-let.js",
            "language/block-scope/syntax/redeclaration/function-name-redeclaration-attempt-with-var.js",
            "language/block-scope/syntax/redeclaration/generator-name-redeclaration-attempt-with-let.js",
            "language/block-scope/syntax/redeclaration/generator-name-redeclaration-attempt-with-var.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-name-redeclaration-attempt-with-function.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-name-redeclaration-attempt-with-generator.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-name-redeclaration-attempt-with-let.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-redeclaration-attempt-after-function.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-redeclaration-attempt-after-generator.js",
            "language/block-scope/syntax/redeclaration/inner-block-var-redeclaration-attempt-after-let.js",
            "language/block-scope/syntax/redeclaration/var-name-redeclaration-attempt-with-function.js",
            "language/block-scope/syntax/redeclaration/var-name-redeclaration-attempt-with-generator.js",
            "language/block-scope/syntax/redeclaration/var-name-redeclaration-attempt-with-let.js",
            "language/block-scope/syntax/redeclaration/var-redeclaration-attempt-after-function.js",
            "language/block-scope/syntax/redeclaration/var-redeclaration-attempt-after-generator.js",
            "language/expressions/object/method-definition/generator-param-redecl-let.js",
            "language/expressions/object/method-definition/name-param-redecl.js",
            "language/statements/for-in/head-let-bound-names-in-stmt.js",
            "language/statements/for-of/head-let-bound-names-in-stmt.js",
            "language/statements/for/head-let-bound-names-in-stmt.js",
            "language/statements/switch/syntax/redeclaration/function-name-redeclaration-attempt-with-let.js",
            "language/statements/switch/syntax/redeclaration/function-name-redeclaration-attempt-with-var.js",
            "language/statements/switch/syntax/redeclaration/generator-name-redeclaration-attempt-with-let.js",
            "language/statements/switch/syntax/redeclaration/generator-name-redeclaration-attempt-with-var.js",
            "language/statements/switch/syntax/redeclaration/var-name-redeclaration-attempt-with-function.js",
            "language/statements/switch/syntax/redeclaration/var-name-redeclaration-attempt-with-generator.js",
            "language/statements/switch/syntax/redeclaration/var-name-redeclaration-attempt-with-let.js",
            "language/statements/try/early-catch-function.js",
            "language/statements/try/early-catch-lex.js",
        )) {
            put(path, "D-95: lexical declarations conflict with vars, functions and parameters here")
        }
        // A strict function has no own arguments property, which upstream gives every function (D-95).
        for (path in listOf(
            "language/expressions/function/forbidden-ext/b1/func-expr-strict-forbidden-ext-direct-access-prop-arguments.js",
            "language/statements/function/forbidden-ext/b1/cls-expr-meth-forbidden-ext-direct-access-prop-arguments.js",
        )) {
            put(path, "D-95: a strict function has no own arguments here")
        }
        // A destructuring default names the anonymous function it gets, as a plain initializer
        // does; upstream leaves it nameless (D-95). Each pattern comes as an arrow, a
        // parenthesized function, a function and a generator.
        for ((folder, stems) in listOf(
            "language/expressions/arrow-function/dstr" to listOf("ary-ptrn-elem-id-init", "dflt-ary-ptrn-elem-id-init", "dflt-obj-ptrn-id-init"),
            "language/expressions/assignment/dstr" to listOf("array-elem-init", "obj-id-init", "obj-prop-elem-init"),
            "language/expressions/function/dstr" to listOf("ary-ptrn-elem-id-init", "dflt-ary-ptrn-elem-id-init", "dflt-obj-ptrn-id-init", "obj-ptrn-id-init"),
            "language/expressions/generators/dstr" to listOf("ary-ptrn-elem-id-init", "dflt-ary-ptrn-elem-id-init", "dflt-obj-ptrn-id-init", "obj-ptrn-id-init"),
            "language/expressions/object/dstr" to listOf("gen-meth-ary-ptrn-elem-id-init", "gen-meth-dflt-ary-ptrn-elem-id-init", "gen-meth-dflt-obj-ptrn-id-init", "gen-meth-obj-ptrn-id-init", "meth-ary-ptrn-elem-id-init", "meth-dflt-ary-ptrn-elem-id-init", "meth-dflt-obj-ptrn-id-init", "meth-obj-ptrn-id-init"),
            "language/statements/const/dstr" to listOf("ary-ptrn-elem-id-init", "obj-ptrn-id-init"),
            "language/statements/for-of/dstr" to listOf("array-elem-init", "let-ary-ptrn-elem-id-init", "let-obj-ptrn-id-init", "obj-prop-elem-init", "var-ary-ptrn-elem-id-init", "var-obj-ptrn-id-init"),
            "language/statements/for/dstr" to listOf("let-ary-ptrn-elem-id-init", "let-obj-ptrn-id-init", "var-ary-ptrn-elem-id-init", "var-obj-ptrn-id-init"),
            "language/statements/function/dstr" to listOf("ary-ptrn-elem-id-init", "dflt-ary-ptrn-elem-id-init", "dflt-obj-ptrn-id-init", "obj-ptrn-id-init"),
            "language/statements/generators/dstr" to listOf("ary-ptrn-elem-id-init", "dflt-ary-ptrn-elem-id-init", "dflt-obj-ptrn-id-init", "obj-ptrn-id-init"),
            "language/statements/let/dstr" to listOf("ary-ptrn-elem-id-init", "obj-ptrn-id-init"),
            "language/statements/try/dstr" to listOf("ary-ptrn-elem-id-init", "obj-ptrn-id-init"),
            "language/statements/variable/dstr" to listOf("ary-ptrn-elem-id-init", "obj-ptrn-id-init"),
        )) {
            for (stem in stems) {
                for (kind in listOf("arrow", "cover", "fn", "gen")) {
                    put("$folder/$stem-fn-name-$kind.js", "D-95: a destructuring default names its function here")
                }
            }
        }
        // ArrayBuffer has its Symbol.species getter, which upstream lacks (D-95).
        for (path in listOf(
            "built-ins/ArrayBuffer/Symbol.species/length.js",
            "built-ins/ArrayBuffer/Symbol.species/return-value.js",
            "built-ins/ArrayBuffer/Symbol.species/symbol-species-name.js",
            "built-ins/ArrayBuffer/Symbol.species/symbol-species.js",
        )) {
            put(path, "D-95: ArrayBuffer[Symbol.species] is there here")
        }
        // A "use strict" body needs a simple parameter list, which upstream checks for defaults only (D-95).
        for (path in listOf(
            "language/expressions/arrow-function/array-destructuring-param-strict-body.js",
            "language/expressions/arrow-function/object-destructuring-param-strict-body.js",
            "language/expressions/function/array-destructuring-param-strict-body.js",
            "language/expressions/function/object-destructuring-param-strict-body.js",
            "language/expressions/function/rest-param-strict-body.js",
            "language/expressions/generators/array-destructuring-param-strict-body.js",
            "language/expressions/generators/object-destructuring-param-strict-body.js",
            "language/expressions/generators/rest-param-strict-body.js",
            "language/expressions/object/method-definition/gen-meth-array-destructuring-param-strict-body.js",
            "language/expressions/object/method-definition/gen-meth-object-destructuring-param-strict-body.js",
            "language/expressions/object/method-definition/gen-meth-rest-param-strict-body.js",
            "language/expressions/object/method-definition/meth-array-destructuring-param-strict-body.js",
            "language/expressions/object/method-definition/meth-object-destructuring-param-strict-body.js",
            "language/expressions/object/method-definition/meth-rest-param-strict-body.js",
            "language/statements/function/array-destructuring-param-strict-body.js",
            "language/statements/function/object-destructuring-param-strict-body.js",
            "language/statements/function/rest-param-strict-body.js",
            "language/statements/generators/array-destructuring-param-strict-body.js",
            "language/statements/generators/object-destructuring-param-strict-body.js",
            "language/statements/generators/rest-param-strict-body.js",
        )) {
            put(path, "D-95: \"use strict\" needs a simple parameter list here")
        }
        // A rest parameter takes no default, which upstream allows (D-95).
        for (path in listOf(
            "language/expressions/function/dflt-params-rest.js",
            "language/expressions/generators/dflt-params-rest.js",
            "language/expressions/object/method-definition/gen-meth-dflt-params-rest.js",
            "language/expressions/object/method-definition/meth-dflt-params-rest.js",
            "language/statements/function/dflt-params-rest.js",
            "language/statements/generators/dflt-params-rest.js",
        )) {
            put(path, "D-95: a rest parameter has no default here")
        }
        // A generator function's prototype has no constructor, which upstream gives it (D-95).
        for (path in listOf(
            "language/expressions/generators/prototype-own-properties.js",
            "language/statements/generators/prototype-own-properties.js",
        )) {
            put(path, "D-95: a generator's prototype has no constructor here")
        }
        // `yield *` may go on after a line break, and `yield` then a line break then `*` is no `yield*`; upstream gets both wrong (D-95).
        for (path in listOf(
            "language/expressions/generators/yield-star-after-newline.js",
            "language/expressions/generators/yield-star-before-newline.js",
            "language/expressions/object/method-definition/yield-star-after-newline.js",
            "language/expressions/object/method-definition/yield-star-before-newline.js",
            "language/statements/generators/yield-star-after-newline.js",
            "language/statements/generators/yield-star-before-newline.js",
        )) {
            put(path, "D-95: yield* follows the line break rules here")
        }
        // A reserved word spelled with an escape is no keyword, which upstream takes as the keyword itself (D-95).
        for (path in listOf(
            "language/expressions/object/method-definition/escaped-get-e.js",
            "language/expressions/object/method-definition/escaped-get-g.js",
            "language/expressions/object/method-definition/escaped-get-t.js",
            "language/expressions/object/method-definition/escaped-get.js",
            "language/expressions/object/method-definition/escaped-set-e.js",
            "language/expressions/object/method-definition/escaped-set-s.js",
            "language/expressions/object/method-definition/escaped-set-t.js",
            "language/expressions/object/method-definition/escaped-set.js",
            "language/literals/boolean/false-with-unicode.js",
            "language/literals/boolean/true-with-unicode.js",
            "language/literals/null/null-with-unicode.js",
            "language/statements/for-of/escaped-of.js",
        )) {
            put(path, "D-95: an escaped keyword is no keyword here")
        }
        // A getter takes no parameter, which upstream allows (D-95).
        for (path in listOf(
            "language/expressions/object/getter-param-dflt.js",
        )) {
            put(path, "D-95: a getter takes no parameter here")
        }
        // Format-control characters stay in the source, which upstream drops outside strings (D-95).
        for (path in listOf(
            "language/literals/regexp/S7.8.5_A1.1_T2.js",
            "language/literals/regexp/S7.8.5_A2.1_T2.js",
        )) {
            put(path, "D-95: format-control characters are kept here")
        }
        // for-in remembers every own key of an object it passed, so a non-enumerable property hides
        // an inherited enumerable one of the same name (D-96).
        for (path in listOf(
            "language/statements/for-in/12.6.4-2.js",
            "language/statements/for-in/order-enumerable-shadowed.js",
        )) {
            put(path, "D-96: for-in follows EnumerateObjectProperties here")
        }
        // Async functions, which upstream cannot parse inside a test that otherwise runs (D-97).
        for (path in listOf(
            "built-ins/AsyncFunction/is-a-constructor.js",
            "built-ins/Function/prototype/toString/async-function-expression.js",
        ) + listOf(
            "a-following-parameter-is-named-arguments", "a-preceding-parameter-is-named-arguments",
            "fn-body-cntns-arguments-func-decl", "fn-body-cntns-arguments-lex-bind",
            "fn-body-cntns-arguments-var-bind", "no-pre-existing-arguments-bindings-are-present",
        ).flatMap { listOf("language/eval-code/direct/async-func-expr-nameless-$it-declare-arguments.js", "language/eval-code/direct/async-func-expr-nameless-$it-declare-arguments-and-assign.js") }) {
            put(path, "D-97: async functions run here")
        }
        // `for (async of` is an early error, which upstream takes as a loop over a name (D-97).
        put("language/statements/for-of/head-lhs-async-invalid.js", "D-97: `for (async of` is an early error here")
        // An async test now runs to the end of its microtasks on both engines, and a resolve
        // function whose `then` getter throws rejects its promise only here (D-81).
        for (path in listOf(
            "built-ins/Promise/all/resolve-poisoned-then.js",
            "built-ins/Promise/allSettled/resolve-poisoned-then.js",
            "built-ins/Promise/prototype/then/resolve-pending-fulfilled-poisoned-then.js",
            "built-ins/Promise/prototype/then/resolve-pending-rejected-poisoned-then.js",
            "built-ins/Promise/prototype/then/resolve-settled-fulfilled-poisoned-then.js",
            "built-ins/Promise/prototype/then/resolve-settled-rejected-poisoned-then.js",
            "built-ins/Promise/race/resolve-poisoned-then.js",
            "built-ins/Promise/resolve-poisoned-then-deferred.js",
            "built-ins/Promise/resolve-poisoned-then-immed.js",
            "built-ins/Promise/resolve/arg-poisoned-then.js",
            "built-ins/Promise/resolve/resolve-poisoned-then.js",
        )) {
            put(path, "D-81: a resolution whose then getter throws rejects the promise here")
        }
        // A "use strict" body makes its own function's name and parameters strict too, which
        // upstream checks only in code that was strict already (D-97).
        for (path in listOf(
            "built-ins/Function/15.3.2.1-10-6gs.js",
            "built-ins/Function/15.3.2.1-11-1-s.js",
            "built-ins/Function/15.3.2.1-11-3-s.js",
            "built-ins/Function/15.3.2.1-11-5-s.js",
            "language/expressions/function/name-arguments-strict-body.js",
            "language/expressions/function/param-duplicated-strict-body-1.js",
            "language/expressions/function/param-duplicated-strict-body-2.js",
            "language/expressions/function/param-duplicated-strict-body-3.js",
            "language/expressions/function/param-eval-strict-body.js",
            "language/expressions/object/setter-param-arguments-strict-inside.js",
            "language/expressions/object/setter-param-eval-strict-inside.js",
            "language/statements/function/13.1-22-s.js",
            "language/statements/function/name-arguments-strict-body.js",
            "language/statements/function/name-eval-strict-body.js",
            "language/statements/function/param-arguments-strict-body.js",
            "language/statements/function/param-duplicated-strict-body-1.js",
            "language/statements/function/param-duplicated-strict-body-2.js",
            "language/statements/function/param-duplicated-strict-body-3.js",
            "language/statements/function/param-eval-strict-body.js",
        )) {
            put(path, "D-97: a use strict body reaches back to the name and parameters here")
        }
        // A block takes two functions of one name only when both are plain ones in sloppy code;
        // upstream takes any two (D-97).
        for (scope in listOf("block-scope", "statements/switch")) {
            for (pair in listOf("function-name-redeclaration-attempt-with-function", "function-name-redeclaration-attempt-with-generator", "generator-name-redeclaration-attempt-with-function", "generator-name-redeclaration-attempt-with-generator")) {
                put("language/$scope/syntax/redeclaration/$pair.js", "D-97: a block takes two functions of one name only when both are plain sloppy ones here")
            }
        }
        // Duplicate names in an arrow's destructured parameters are an early error in any mode (D-97).
        for (path in listOf("array-1", "array-2", "object-1", "object-2", "object-3", "object-6")) {
            put("language/expressions/arrow-function/syntax/early-errors/arrowparameters-cover-no-duplicates-binding-$path.js", "D-97: duplicate parameters are an early error where the list is not plain here")
        }
        // A parenthesized object literal is no assignment target (D-97).
        for (path in listOf(
            "language/expressions/assignmenttargettype/direct-arrowfunction-1.js",
            "language/expressions/assignmenttargettype/parenthesized-primaryexpression-objectliteral.js",
        )) {
            put(path, "D-97: a parenthesized literal is no assignment target here")
        }
        // Async generators are a SyntaxError here until async iteration lands (#91), and upstream
        // cannot parse them either; the two fail differently.
        for (path in listOf(
            "a-following-parameter-is-named-arguments", "a-preceding-parameter-is-named-arguments",
            "fn-body-cntns-arguments-func-decl", "fn-body-cntns-arguments-lex-bind",
            "fn-body-cntns-arguments-var-bind", "no-pre-existing-arguments-bindings-are-present",
        ).flatMap { listOf("language/eval-code/direct/async-gen-func-expr-$it-declare-arguments.js", "language/eval-code/direct/async-gen-func-expr-$it-declare-arguments-and-assign.js") }) {
            put(path, "#91: async generators are not supported yet here, and upstream cannot parse them")
        }
        // An async function in a switch case is not block-scoped here yet (#85), and upstream
        // cannot parse it; the two fail differently.
        put("language/statements/switch/scope-lex-async-function.js", "#85: functions in a switch case are not block-scoped yet here")
    }

    /**
     * Like [knownDifferences], for files whose two engines disagree only outside strict mode. In
     * strict mode they agree, so a file-wide entry would read as stale there.
     */
    private val knownSloppyDifferences = buildMap {
        for (path in listOf(
            "language/eval-code/indirect/non-definable-global-var.js",
        )) {
            put(path, "D-115: eval isolates declarations and validates intervening lexical and global conflicts here")
        }
        for (path in listOf(
            "built-ins/Object/defineProperties/15.2.3.7-6-a-112.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-113.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-166.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-168.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-169.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-170.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-172.js",
            "built-ins/Object/defineProperties/15.2.3.7-6-a-173.js",
        )) {
            put(path, "D-114: ArraySetLength restores length and read-only attributes after refusal here")
        }
        for (path in listOf(
            "built-ins/Object/defineProperty/15.2.3.6-4-116.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-117.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-170.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-172.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-173.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-174.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-176.js",
            "built-ins/Object/defineProperty/15.2.3.6-4-177.js",
        )) {
            put(path, "D-114: ArraySetLength stops at a refused deletion and restores length here")
        }
        for (path in listOf(
            "built-ins/Array/prototype/copyWithin/return-abrupt-from-delete-target.js",
            "built-ins/Array/prototype/pop/set-length-zero-array-is-frozen.js",
            "built-ins/Array/prototype/pop/set-length-zero-array-length-is-non-writable.js",
            "built-ins/Array/prototype/pop/throws-with-string-receiver.js",
            "built-ins/Array/prototype/push/length-near-integer-limit-set-failure.js",
            "built-ins/Array/prototype/push/set-length-zero-array-is-frozen.js",
            "built-ins/Array/prototype/push/throws-with-string-receiver.js",
            "built-ins/Array/prototype/shift/set-length-zero-array-is-frozen.js",
            "built-ins/Array/prototype/shift/set-length-zero-array-length-is-non-writable.js",
            "built-ins/Array/prototype/shift/throws-when-this-value-length-is-writable-false.js",
            "built-ins/Array/prototype/splice/S15.4.4.12_A6.1_T3.js",
            "built-ins/Array/prototype/unshift/set-length-zero-array-is-frozen.js",
            "built-ins/Array/prototype/unshift/throws-with-string-receiver.js",
        )) {
            put(path, "D-114: array mutation refusal throws independently of caller strictness here")
        }
        for (loop in listOf("for-in", "for-of")) {
            put("language/statements/$loop/let-array-with-newline.js",
                "D-113: let [ remains forbidden as an expression statement across a newline here")
        }
        for (state in listOf("fulfilled", "rejected")) {
            put("built-ins/Promise/prototype/then/rxn-handler-$state-invoke-nonstrict.js",
                "D-112: sloppy Promise handlers substitute their own global here")
        }
        for (path in listOf(
            "built-ins/Array/prototype/every/15.4.4.16-5-1-s.js",
            "built-ins/Array/prototype/filter/15.4.4.20-5-1-s.js",
            "built-ins/Array/prototype/forEach/15.4.4.18-5-1-s.js",
            "built-ins/Array/prototype/map/15.4.4.19-5-1-s.js",
            "built-ins/Array/prototype/reduce/15.4.4.21-9-c-ii-4-s.js",
            "built-ins/Array/prototype/reduceRight/15.4.4.22-9-c-ii-4-s.js",
            "built-ins/Array/prototype/some/15.4.4.17-5-1-s.js",
            "built-ins/Array/prototype/sort/S15.4.4.11_A8.js",
        )) {
            put(path, "D-112: callbacks use the callee's own strictness and realm here")
        }
        put("built-ins/ThrowTypeError/unique-per-realm-non-simple.js",
            "D-109: non-simple parameters use unmapped arguments and the realm intrinsic here")
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
        // Duplicate parameters are an early error where the list is not plain, in any mode; in
        // strict code they are one on both engines (D-97).
        for (path in listOf(
            "language/expressions/arrow-function/dflt-params-duplicates.js",
            "language/expressions/arrow-function/params-duplicate.js",
            "language/expressions/arrow-function/syntax/early-errors/arrowparameters-cover-no-duplicates.js",
            "language/expressions/function/dflt-params-duplicates.js",
            "language/expressions/generators/dflt-params-duplicates.js",
            "language/expressions/object/method-definition/early-errors-object-method-duplicate-parameters.js",
            "language/expressions/object/method-definition/gen-meth-dflt-params-duplicates.js",
            "language/expressions/object/method-definition/meth-dflt-params-duplicates.js",
            "language/statements/function/dflt-params-duplicates.js",
            "language/statements/generators/dflt-params-duplicates.js",
        )) {
            put(path, "D-97: duplicate parameters are an early error where the list is not plain here")
        }
    }

    /**
     * Like [knownDifferences], for files whose two engines disagree only in strict mode, where a
     * failed write throws. Outside strict mode both still let it pass silently, so a file-wide
     * entry would read as stale there.
     */
    private val knownStrictDifferences = buildMap {
        for (path in listOf(
            "built-ins/Function/15.3.5-1gs.js",
            "built-ins/Function/15.3.5-2gs.js",
            "built-ins/Function/15.3.5.4_2-11gs.js",
            "built-ins/Function/15.3.5.4_2-13gs.js",
            "built-ins/Function/15.3.5.4_2-15gs.js",
            "built-ins/Function/15.3.5.4_2-17gs.js",
            "built-ins/Function/15.3.5.4_2-19gs.js",
            "built-ins/Function/15.3.5.4_2-1gs.js",
            "built-ins/Function/15.3.5.4_2-21gs.js",
            "built-ins/Function/15.3.5.4_2-22gs.js",
            "built-ins/Function/15.3.5.4_2-23gs.js",
            "built-ins/Function/15.3.5.4_2-24gs.js",
            "built-ins/Function/15.3.5.4_2-25gs.js",
            "built-ins/Function/15.3.5.4_2-26gs.js",
            "built-ins/Function/15.3.5.4_2-27gs.js",
            "built-ins/Function/15.3.5.4_2-28gs.js",
            "built-ins/Function/15.3.5.4_2-29gs.js",
            "built-ins/Function/15.3.5.4_2-3gs.js",
            "built-ins/Function/15.3.5.4_2-48gs.js",
            "built-ins/Function/15.3.5.4_2-50gs.js",
            "built-ins/Function/15.3.5.4_2-52gs.js",
            "built-ins/Function/15.3.5.4_2-54gs.js",
            "built-ins/Function/15.3.5.4_2-5gs.js",
            "built-ins/Function/15.3.5.4_2-7gs.js",
            "built-ins/Function/15.3.5.4_2-9gs.js",
            "built-ins/Function/StrictFunction_restricted-properties.js",
        )) {
            put(path, "D-109: strict functions inherit the realm restricted-property accessors here")
        }
        put("language/statements/for-of/dstr/array-elem-target-simple-strict.js",
            "D-95: arguments is not a strict destructuring assignment target here")
        for (method in listOf("find", "findIndex", "findLast", "findLastIndex")) {
            put("built-ins/Array/prototype/$method/predicate-call-this-strict.js",
                "D-112: callbacks use the callee's own strictness and realm here")
            for (folder in listOf("", "BigInt/")) {
                put("built-ins/TypedArray/prototype/$method/${folder}predicate-call-this-strict.js",
                    "D-112: callbacks use the callee's own strictness and realm here")
            }
        }
        put("language/function-code/S10.4.3_A1.js",
            "D-112: bare calls preserve undefined for strict callees here")
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
        assertTrue(testRoot.isDirectory && harnessRoot.isDirectory,
            "test262 corpus missing at $corpusRoot; run tools/fetch-test262.sh")

        val properties = Test262Properties.load(propertiesFile, testRoot)
        val filter = System.getProperty("test262.filter") ?: ""
        val begin = ProcessBuilder("python3", "../tools/test262-corpus.py", "begin",
            "--corpus", corpusRoot.absolutePath, "--filter", filter).inheritIO().start()
        assertEquals(0, begin.waitFor(), "Could not record test262 producer provenance")

        val expectations = StringBuilder()
        val exclusions = StringBuilder()
        val outcomes = StringBuilder()
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
            if (relative.endsWith("_FIXTURE.js")) {
                exclusions.append(relative).append("\tfixture\n")
                skipped++
                continue
            }
            if (properties.isSkipped(relative) && portOnlyFolders.none { relative.startsWith("$it/") }) {
                exclusions.append(relative).append("\tupstream properties exclusion\n")
                skipped++
                continue
            }

            val source = file.readText()
            val meta = Test262FrontMatter.parse(source)
            if (meta.features.any { it in unsupportedFeatures }) {
                exclusions.append(relative).append("\tunsupported features: ")
                    .append(meta.features.filter { it in unsupportedFeatures }.joinToString(",")).append('\n')
                skipped++
                continue
            }
            // Neither engine has modules. An async test runs to the end of its microtasks and
            // reports through $DONE on both (D-97).
            if (meta.hasFlag("module")) {
                exclusions.append(relative).append("\tmodule\n")
                skipped++
                continue
            }

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
                val comparison = when {
                    upstream == ported -> "agreement"
                    known -> knownDifferences[relative] ?: (if (strict) knownStrictDifferences else knownSloppyDifferences)[relative]!!
                    upstreamFailedToParse(upstream) -> "upstream cannot parse"
                    else -> "unexpected difference"
                }
                outcomes.append(relative).append(if (strict) "\tstrict\t" else "\tsloppy\t")
                    .append(upstream).append('\t').append(ported).append('\t').append(comparison).append('\n')
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
        File("build/test262/exclusions.tsv").writeText(exclusions.toString())
        File("build/test262/outcomes.tsv").writeText(outcomes.toString())
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
        val complete = ProcessBuilder("python3", "../tools/test262-corpus.py", "complete").inheritIO().start()
        assertEquals(0, complete.waitFor(), "Could not finalize test262 producer provenance")
    }

    // ---- Running one case on each engine --------------------------------------------------------

    private fun runUpstream(path: String, source: String, meta: Test262FrontMatter, strict: Boolean): String {
        val cx = UContext.enter()
        try {
            cx.languageVersion = UContext.VERSION_ES6
            cx.isInterpretedMode = true
            val scope = cx.initSafeStandardObjects(org.mozilla.javascript.TopLevel(), false)
            val printed = StringBuilder()
            val print = org.mozilla.javascript.LambdaFunction(
                scope,
                "print",
                1,
                org.mozilla.javascript.SerializableCallable { _, _, _, args ->
                    printed.append(args.joinToString(" ") { org.mozilla.javascript.Context.toString(it) }).append('\n')
                    org.mozilla.javascript.Undefined.instance
                },
            )
            scope.defineProperty("print", print, org.mozilla.javascript.ScriptableObject.DONTENUM)
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
                cx.processMicrotasks()
                if (meta.isNegative) unexpectedPass(meta)
                else if (meta.hasFlag("async")) Test262Execution.asyncOutcome(printed.toString())
                else PASS
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
            val frame = e.stackTrace.firstOrNull { it.className.startsWith("io.github.yuroyami.kitejs.rhino") }
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
