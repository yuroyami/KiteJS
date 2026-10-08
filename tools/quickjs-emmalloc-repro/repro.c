/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
