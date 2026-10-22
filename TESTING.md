# Tests and test262 coverage

`./kotlin test` runs the regression and API suites on the JVM, in the Android host tests and on the
native platforms that the machine can build. `tools/web-tests/run.py` runs the same suites on
JavaScript and Wasm in Node. Neither includes test262 corpus replay. A normal green build is not
evidence of corpus coverage.

## Run the tests

```sh
./kotlin test -p jvm -p android -p macosArm64  # the platforms you name
./kotlin test -m kitejs-rhino -p linuxX64      # one module on one platform
python3 tools/web-tests/run.py                 # JavaScript and Wasm, in Node
python3 tools/web-tests/run.py --platform js --module kitejs-rhino
```

Always name the platforms. Without `-p`, `./kotlin test` also runs the Wasm tests in headless Chrome.
There only kitejs-api passes: the other modules import the npm package `@js-joda/timezone`, and the
toolchain cannot add it to its browser page. The toolchain runs no JavaScript tests at all, so
`tools/web-tests/run.py` covers JavaScript and Wasm.

kitejs-quickjs compiles C code. It needs [zig](https://ziglang.org) on the `PATH` (or its path in
`ZIG`), and for Android an NDK in `ANDROID_NDK_HOME` or in the Android SDK. The web tests need Node
26 or later and npm.

## Produce a replay artifact

From the repository root, with Python 3.11 or later:

```sh
bash tools/fetch-test262.sh
python3 tools/test262.py parity
```

For a bounded local run, add `--filter language/statements/for-in`. The filter is recorded; a
filtered artifact never establishes full-corpus coverage. Parity compares Rhino 1.9.1 with the
port and records known differences separately. Agreement includes cases both engines fail.

`kitejs-rhino/build/test262/corpus/manifest.json` records the engine commit and local source
fingerprint, pinned corpus commit, oracle version, selection, strict/sloppy denominator and file
checksums. The artifact includes the selected source files, harness, expected outcomes and
exclusion reasons. Failed or interrupted parity runs cannot produce a completed artifact.

## Replay on a target

Copy the complete artifact directory to the same path in a checkout of the exact producer
revision, then run, for example:

```sh
python3 tools/test262.py replay --platform jvm
```

Replace `jvm` with another platform of kitejs-rhino, such as `android`, `js`, `wasmJs`, `linuxX64`
or `iosSimulatorArm64`. Use `--bundle /absolute/path` for another artifact location. The consumer
does not need a test262 checkout. JavaScript and Wasm replay in Node.

Both commands set the `KITEJS_TEST262` environment variable to `parity` or `replay`. Only then does
the build add the test262 sources (`kitejs-rhino/test262/`) to the tests of kitejs-rhino.

Validation rejects missing inputs, changed sources, an incorrect corpus or oracle revision,
modified files, duplicate cases and empty or incorrect denominators. The test reads the exact
validated manifest, executes every selected case and prints `TEST262_RESULT`. The replay command
keeps the output in `kitejs-rhino/build/test-results/<platform>/replay.log`, then requires one complete
successful result and writes `test262-result.json` next to it.
Expected non-passing outcomes and intentional platform differences are reported separately from
unexpected mismatches. The existing platform-difference list is not expanded by this pipeline.

## CI evidence

The nightly and manually dispatched `test262 parity` workflow creates one artifact and replays it
on JVM, Android host tests, JS and Wasm on Node, Linux x64, Windows x64, macOS arm64 and the iOS
arm64 simulator. Every consumer checks provenance and must produce the full denominator.
The workflow's producer and per-target result artifacts identify what actually ran. Declaring a
matrix entry is not proof of a successful run; inspect its result before claiming that coverage.
Android host tests are JVM tests, not Android device qualification; simulator tests do not qualify
physical iOS devices. Ordinary pull-request CI remains independent of this corpus pipeline.

### Recorded validation

At engine revision `f8622aeb23c9606d556cca2cbf50717d061d3845`,
[run 37954352802](https://github.com/yuroyami/KiteJS/actions/runs/37954352802) completed all ten
replay jobs from clean checkouts. Each target replayed the whole selected corpus of 78,402 cases
(37,604 strict, 40,798 sloppy) with no unexpected mismatches. The corpus revision was
`3fd4ec27f1798ebecafc73b354a45dcdda9bde29`, with Rhino 1.9.1 as oracle. The selection excluded
9,513 corpus files, each with a recorded reason.

| Targets | Passed | Expected non-passing | Intentional differences |
|:--|--:|--:|--:|
| JVM, Android host | 76,295 | 2,107 | 0 |
| Linux x64, Windows x64, macOS arm64, iOS simulator arm64 | 76,299 | 2,103 | 4 |
| Wasm on Node and Chrome | 76,299 | 2,103 | 5 |
| JS on Node and Chrome | 76,300 | 2,102 | 6 |

The intentional differences are these:

- Outside the JVM, `toLowerCase` and `toLocaleLowerCase` apply the conditional special casing that
  the JVM oracle misses. Each target passes the two `special_casing_conditional.js` files in both
  modes, four cases in all.
- `staging/sm/String/replace-math.js` crashes the JVM with `OutOfMemoryError`. On JS and Wasm it
  crashes too, with another error type.
- On JS, `staging/sm/String/string-code-point-upper-lower-mapping.js` passes where the JVM oracle
  fails.

Artifact-validation regressions run with:

```sh
python3 -B -m unittest discover -s tools -p 'test_test262_corpus.py'
```
