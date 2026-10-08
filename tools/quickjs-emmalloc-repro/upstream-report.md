# Default allocator aborts at JS_FreeRuntime with wasm32-wasi/emmalloc (Zig 0.14.1)

The default allocator aborts during `JS_FreeRuntime` on wasm32-wasi built with Zig 0.14.1 (emmalloc), after successful evaluation of an ordinary string concatenation. This reproducer calls the public QuickJS API directly, without an embedding wrapper or custom allocator.

Tested against master at `60984dc07cbb6b7885303b9542944b114203e918` with Zig 0.14.1 and Node 26.10.0. The same program built for native Linux with the same compiler and source exits successfully.

### Reproducer

Save as `repro.c` beside the QuickJS sources:

```c
#include <stdio.h>
#include <string.h>
#include "quickjs.h"

int main(void) {
    JSRuntime *rt = JS_NewRuntime();
    JSContext *ctx = JS_NewContext(rt);
    const char *source = "var s = ''; for (var i = 0; i < 2000; i++) s += 'part ' + i + ';'; s";
    JSValue value = JS_Eval(ctx, source, strlen(source), "repro.js", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(value)) {
        JSValue exception = JS_GetException(ctx);
        const char *message = JS_ToCString(ctx, exception);
        fprintf(stderr, "evaluation failed: %s\n", message);
        JS_FreeCString(ctx, message);
        JS_FreeValue(ctx, exception);
        return 1;
    }
    size_t length;
    const char *text = JS_ToCStringLen(ctx, &length, value);
    fprintf(stderr, "result length: %zu\n", length);
    JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, value);
    JS_FreeContext(ctx);
    fprintf(stderr, "freeing runtime\n");
    JS_FreeRuntime(rt);
    fprintf(stderr, "freed runtime\n");
    return 0;
}
```

Build:

```sh
zig cc -target wasm32-wasi -O2 -D_GNU_SOURCE -DNDEBUG \
  repro.c quickjs.c dtoa.c libregexp.c libunicode.c -lm \
  -Wl,-z,stack-size=2097152 -o repro.wasm
```

Save as `run.mjs`:

```js
import { readFile } from 'node:fs/promises';
import { WASI } from 'node:wasi';
const wasi = new WASI({ version: 'preview1', args: [], env: {}, preopens: {} });
const module = await WebAssembly.compile(await readFile(process.argv[2]));
const instance = await WebAssembly.instantiate(module, wasi.getImportObject());
wasi.start(instance);
```

Run `node run.mjs repro.wasm`. Output:

```text
result length: 18890
freeing runtime
js_free_rt: malloc_size underflow: freeing 4104 but only 1480 tracked
RuntimeError: unreachable
```

For comparison, `zig cc -O2 -D_GNU_SOURCE -DNDEBUG repro.c quickjs.c dtoa.c libregexp.c libunicode.c -lm -o repro-native && ./repro-native` prints the result length and `freed runtime`, exiting with status 0.

### Cause

`js_malloc_rt` records `js_arena_usable_size(rt, ptr) + MALLOC_OVERHEAD` at allocation time; `js_free_rt` queries the usable size again and aborts if the new size exceeds the remaining accounting total. This assumes a live allocation's usable size is stable.

Zig 0.14.1's WASI libc uses emmalloc. In its `attempt_allocate`, aligning the next allocation can move the boundary and enlarge the previous live region:

```c
create_used_region(prevRegion, prevRegion->size + regionBoundaryBumpAmount);
```

Consequently, `malloc_usable_size` for a live block can increase without that block being reallocated by QuickJS. Re-querying it on free subtracts more than allocation recorded. The source is `lib/libc/wasi/emmalloc/emmalloc.c` in Zig 0.14.1, around lines 682–694. The default WASI usable-size hook in QuickJS calls this libc function.

### Expected behavior and workaround

Evaluation and runtime disposal should both complete, and memory accounting should remain consistent. Tracking a stable size per allocation would avoid reliance on this allocator assumption; simply disabling the underflow check leaves incorrect memory-limit accounting.

Downstream, [KiteJS #124](https://github.com/yuroyami/KiteJS/issues/124) remains open and gates removal of its workaround on an upstream correction. Its `JS_NewRuntime2` allocator stores the requested size in a header and returns that stable value from the usable-size hook on every platform. That avoids the abort without changing vendored QuickJS source, but a correction in the default upstream allocator/accounting would make the public `JS_NewRuntime` path work too.
