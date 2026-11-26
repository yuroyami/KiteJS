#!/bin/sh
# Builds and runs the C tests of the QuickJS glue under AddressSanitizer.
# Run it from any folder. CC picks the compiler, cc by default.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
native=$(dirname "$here")
out=${TMPDIR:-/tmp}/kitejs-native-test
mkdir -p "$out"
cc=${CC:-cc}
flags="-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -include $native/kitejs_config.h -I$native"

for f in quickjs dtoa libregexp libunicode; do
    $cc $flags -w -c "$native/quickjs/$f.c" -o "$out/$f.o"
done
# Only the glue's own allocations go through the test's allocator.
$cc $flags -Dmalloc=test_malloc -Dcalloc=test_calloc -Drealloc=test_realloc \
    -c "$native/kitejs_quickjs.c" -o "$out/kitejs_quickjs.o"
$cc $flags "$here/alloc_failure.c" "$out"/*.o -lm -lpthread -o "$out/alloc_failure"

# QuickJS keeps some blocks after a failed start, so leaks are not checked here.
ASAN_OPTIONS=detect_leaks=0 "$out/alloc_failure"
