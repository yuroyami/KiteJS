/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

/*
 * The JavaScript between Kotlin and the WebAssembly build of QuickJS: it instantiates the module,
 * gives it the handful of WASI calls libc makes, routes the "kitejs" imports to the host, and
 * moves strings in and out of linear memory as UTF-16. Kotlin sees only numbers and strings.
 */
internal const val GLUE: String = """(function () {
    var exports = null;
    var memory = null;
    var host = null;

    // WebAssembly stack switching (JSPI), where the runtime has it: a run that starts through
    // `promising` can wait inside the "pause" import while the page draws (KiteJS#130).
    var Suspending = typeof WebAssembly.Suspending === 'function' && typeof WebAssembly.promising === 'function'
        ? WebAssembly.Suspending : null;
    var pausingRun = null, pausingDrain = null, pausedAnswer = 0;
    function nextTask() { return new Promise(function (resolve) { setTimeout(resolve, 0); }); }

    function view() { return new DataView(memory.buffer); }

    function toMemory(s) {
        var n = s.length;
        var p = exports.kite_alloc(n * 2);
        var a = new Uint16Array(memory.buffer, p, n);
        for (var i = 0; i < n; i++) a[i] = s.charCodeAt(i);
        return p;
    }

    function fromMemory(p, n) {
        var a = new Uint16Array(memory.buffer, p, n);
        var out = '';
        for (var i = 0; i < n; i += 8192) out += String.fromCharCode.apply(null, a.subarray(i, Math.min(n, i + 8192)));
        return out;
    }

    function withString(s, body) {
        var p = toMemory(s);
        try { return body(p); } finally { exports.kite_dealloc(p); }
    }

    // What the module writes to stdout and stderr, such as a failed assertion, goes to the console a line at a time.
    var output = {};

    function print(fd, bytes) {
        var o = output[fd] || (output[fd] = { decoder: new TextDecoder(), line: '' });
        var lines = (o.line + o.decoder.decode(bytes, { stream: true })).split('\n');
        o.line = lines.pop();
        for (var i = 0; i < lines.length; i++) (fd === 2 ? console.error : console.log)('QuickJS: ' + lines[i]);
    }

    var EBADF = 8;
    var wasi = {
        clock_time_get: function (id, precision, out) {
            var ms = id === 0 ? Date.now() : (typeof performance !== 'undefined' ? performance.now() : Date.now());
            view().setBigUint64(out, BigInt(Math.round(ms * 1000000)), true);
            return 0;
        },
        fd_write: function (fd, iovs, count, written) {
            var dv = view();
            var total = 0;
            for (var i = 0; i < count; i++) {
                var length = dv.getUint32(iovs + i * 8 + 4, true);
                if (fd === 1 || fd === 2) print(fd, new Uint8Array(memory.buffer, dv.getUint32(iovs + i * 8, true), length).slice());
                total += length;
            }
            dv.setUint32(written, total, true);
            return 0;
        },
        fd_close: function () { return EBADF; },
        fd_seek: function () { return EBADF; },
        fd_fdstat_get: function () { return EBADF; },
    };

    function decode(base64) {
        if (typeof atob === 'function') {
            var text = atob(base64);
            var bytes = new Uint8Array(text.length);
            for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
            return bytes;
        }
        return new Uint8Array(Buffer.from(base64, 'base64'));
    }

    function gunzip(bytes) {
        var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
        return new Response(stream).arrayBuffer();
    }

    var glue = {
        load: function (gzippedBase64, call, interrupt, now, tzOffset) {
            if (exports !== null) return Promise.resolve(null);
            host = { call: call, interrupt: interrupt, now: now, tzOffset: tzOffset };
            var imports = {
                wasi_snapshot_preview1: wasi,
                kitejs: {
                    call: function (e, fn, argc) { return host.call(e, fn, argc); },
                    interrupt: function (e) { return host.interrupt(e); },
                    now: function (e) { return host.now(e); },
                    tz_offset: function (e, t) { return host.tzOffset(e, t); },
                    // Without stack switching the script goes on at once; with it, after a macrotask.
                    pause: Suspending ? new Suspending(function (e) { return nextTask(); }) : function (e) {},
                },
            };
            return gunzip(decode(gzippedBase64))
                .then(function (wasm) { return WebAssembly.instantiate(wasm, imports); })
                .then(function (result) {
                    if (exports !== null) return null;
                    exports = result.instance.exports;
                    memory = exports.memory;
                    exports._initialize();
                    if (Suspending) {
                        pausingRun = WebAssembly.promising(exports.kite_run);
                        pausingDrain = WebAssembly.promising(exports.kite_drain);
                    }
                    return null;
                });
        },
        loaded: function () { return exports !== null; },

        newEngine: function (id, m, s, o) { return exports.kite_new(id, m, s, o); },
        free: function (e) { exports.kite_free(e); },
        enter: function (e) { exports.kite_enter(e); },
        setBudget: function (e, b) { exports.kite_set_budget(e, b); },
        askHost: function (e, a) { exports.kite_ask_host(e, a ? 1 : 0); },
        stopReason: function (e) { return exports.kite_stop_reason(e); },
        version: function () {
            var p = exports.kite_version();
            var bytes = new Uint8Array(memory.buffer, p);
            var n = 0;
            while (bytes[n] !== 0) n++;
            var s = '';
            for (var i = 0; i < n; i++) s += String.fromCharCode(bytes[i]);
            return s;
        },

        type: function (e, h) { return exports.kite_type(e, h); },
        number: function (e, h) { return exports.kite_number(e, h); },
        string: function (e, h) {
            var p = exports.kite_string(e, h);
            if (p === 0) return null;
            var s = fromMemory(p, exports.kite_string_length(e));
            exports.kite_string_free(e);
            return s;
        },
        identity: function (e, h) { return exports.kite_identity(e, h); },
        isPromise: function (e, h) { return exports.kite_is_promise(e, h) !== 0; },
        dup: function (e, h) { return exports.kite_dup(e, h); },
        release: function (e, h) { exports.kite_release(e, h); },

        newNumber: function (e, d) { return exports.kite_new_number(e, d); },
        newString: function (e, s) {
            return withString(s, function (p) { return exports.kite_new_string(e, p, s.length); });
        },
        newObject: function (e) { return exports.kite_new_object(e); },
        newArray: function (e) { return exports.kite_new_array(e); },
        arrayPush: function (e, a, v) { exports.kite_array_push(e, a, v); },
        objectPut: function (e, o, k, v) {
            withString(k, function (p) { exports.kite_object_put(e, o, p, k.length, v); });
        },
        arrayLength: function (e, a) { return exports.kite_array_length(e, a); },
        arrayGet: function (e, a, i) { return exports.kite_array_get(e, a, i); },
        get: function (e, o, k) {
            return withString(k, function (p) { return exports.kite_get(e, o, p, k.length); });
        },
        global: function (e) { return exports.kite_global(e); },

        compile: function (e, source, file) {
            return withString(source, function (sp) {
                return withString(file, function (fp) {
                    return exports.kite_compile(e, sp, source.length, fp, file.length);
                });
            });
        },
        writeScript: function (e, c) {
            var p = exports.kite_write_script(e, c);
            if (p === 0) return null;
            var n = exports.kite_bytes_length(e), a = new Uint8Array(memory.buffer, p, n), out = '';
            for (var i = 0; i < n; i += 8192) out += String.fromCharCode.apply(null, a.subarray(i, Math.min(n, i + 8192)));
            exports.kite_bytes_free(e);
            return out;
        },
        readScript: function (e, bytes) {
            var n = bytes.length, p = exports.kite_alloc(n || 1);
            var a = new Uint8Array(memory.buffer, p, n);
            for (var i = 0; i < n; i++) a[i] = bytes.charCodeAt(i);
            try { return exports.kite_read_script(e, p, n); } finally { exports.kite_dealloc(p); }
        },
        run: function (e, c) { return exports.kite_run(e, c); },
        canPause: function () { return pausingRun !== null; },
        // Each settles once the run or the drain has ended; pausedAnswer then holds what it answered.
        runPausing: function (e, c) { return pausingRun(e, c).then(function (h) { pausedAnswer = h; return null; }); },
        drainPausing: function (e) { return pausingDrain(e).then(function (r) { pausedAnswer = r; return null; }); },
        pausedAnswer: function () { return pausedAnswer; },
        pushArg: function (e, h) { exports.kite_push_arg(e, h); },
        call: function (e, fn, self) { return exports.kite_call(e, fn, self); },
        construct: function (e, fn) { return exports.kite_construct(e, fn); },
        drain: function (e) { return exports.kite_drain(e); },
        discardJobs: function (e) { exports.kite_discard_jobs(e); },
        exception: function (e) { return exports.kite_exception(e); },

        newFunction: function (e, fn, name, arity) {
            return withString(name, function (p) { return exports.kite_new_function(e, fn, p, name.length, arity); });
        },
        deadFunction: function (e) { return exports.kite_dead_function(e); },
        cbThis: function (e) { return exports.kite_cb_this(e); },
        cbArg: function (e, i) { return exports.kite_cb_arg(e, i); },

        watch: function (registry, owner, handle) { registry.register(owner, handle); },
        newRegistry: function (release) {
            return typeof FinalizationRegistry === 'function' ? new FinalizationRegistry(release) : null;
        },
    };
    return glue;
})()"""
