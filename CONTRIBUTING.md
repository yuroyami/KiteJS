# Contributing to KiteJS

## Ground rules

- Every file that is ported from Mozilla Rhino starts with the three-line MPL 2.0 header that upstream uses.
- A ported file keeps upstream's file name, class names and method names, so a reader can compare the two side by side. Change the structure only where common Kotlin has no equivalent of a JVM API.
- `org.mozilla:rhino:1.9.1` is a test dependency only. It is the oracle of the differential tests and never ships.
- Every difference in the test262 parity run gets a fix, or an entry in the expected-difference list of `Test262ParityTest` with its reason in words. That entry is the only accepted way to leave a difference in place.
- A difference that a script author can see also gets a section in `docs/differences.md`.
- Write the failing test first.
- No em dashes in any file, comment, string or commit message.

## Tests and test262 coverage

`./kotlin test` runs the regression and API suites on the JVM, in the Android host tests and on the native platforms that the machine can build. `tools/web-tests/run.py` runs the same suites on JavaScript and Wasm in Node. Neither one replays the test262 corpus. A green build is not evidence of corpus coverage.

### Run the tests

```sh
./kotlin test -p jvm -p android -p macosArm64  # the platforms you name
./kotlin test -m kitejs-rhino -p linuxX64      # one module on one platform
python3 tools/web-tests/run.py                 # JavaScript and Wasm, in Node
python3 tools/web-tests/run.py --platform js --module kitejs-rhino
```

Always name the platforms. Without `-p`, `./kotlin test` also runs the Wasm tests in headless Chrome. There only kitejs-api passes, because the other modules import the npm package `@js-joda/timezone` and the toolchain cannot add it to its browser page. The toolchain runs no JavaScript tests at all, so `tools/web-tests/run.py` covers JavaScript and Wasm.

kitejs-quickjs compiles C code. It needs [zig](https://ziglang.org) on the `PATH`, or its path in `ZIG`. For Android it also needs an NDK in `ANDROID_NDK_HOME` or in the Android SDK. The web tests need Node 26 or later and npm.

### Produce a replay artifact

Run these commands from the repository root, with Python 3.11 or later:

```sh
bash tools/fetch-test262.sh
python3 tools/test262.py parity
```

For a bounded local run, add `--filter language/statements/for-in`. The artifact records the filter. A filtered artifact never establishes full-corpus coverage.

The parity run compares Rhino 1.9.1 with the port and records the known differences separately. Agreement includes cases that both engines fail.

`kitejs-rhino/build/test262/corpus/manifest.json` records the engine commit, a fingerprint of the local sources, the pinned corpus commit, the oracle version, the selection, the strict and sloppy denominators and the file checksums. The artifact holds the selected source files, the harness, the expected outcomes and the reason for each exclusion. A failed or interrupted parity run cannot produce a complete artifact.

### Replay on a target

1. Copy the complete artifact directory to the same path in a checkout of the exact producer revision.
2. Run the replay, for example:

   ```sh
   python3 tools/test262.py replay --platform jvm
   ```

Replace `jvm` with another platform of kitejs-rhino, such as `android`, `js`, `wasmJs`, `linuxX64` or `iosSimulatorArm64`. Use `--bundle /absolute/path` for an artifact in another place. The consumer does not need a test262 checkout. JavaScript and Wasm replay in Node.

Both commands set the environment variable `KITEJS_TEST262` to `parity` or `replay`. Only then does the build add the test262 sources (`kitejs-rhino/test262/`) to the tests of kitejs-rhino.

Validation rejects missing inputs, changed sources, a wrong corpus or oracle revision, modified files, duplicate cases and empty or wrong denominators. The test reads the validated manifest, runs every selected case and prints `TEST262_RESULT`. The replay command keeps the output in `kitejs-rhino/build/test-results/<platform>/replay.log`. Then it needs one complete successful result and writes `test262-result.json` next to the log. It reports expected non-passing outcomes and intentional platform differences apart from unexpected mismatches.

### CI evidence

The `test262 parity` workflow runs nightly and on manual dispatch. It creates one artifact and replays it on the JVM, Android host tests, JS and Wasm on Node, Linux x64, Windows x64, macOS arm64 and the iOS arm64 simulator. Every consumer checks the provenance and must produce the full denominator.

- A matrix entry is not proof of a run. Read its result artifact before you claim that coverage.
- Android host tests are JVM tests. They do not qualify an Android device.
- Simulator tests do not qualify a physical iOS device.
- Pull-request CI does not run this corpus pipeline.

### Tests of the artifact validation

```sh
python3 -B -m unittest discover -s tools -p 'test_test262_corpus.py'
```
