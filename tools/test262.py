#!/usr/bin/env python3
"""Run the test262 parity test, or replay its corpus on one platform. CONTRIBUTING.md describes both.

  python3 tools/test262.py parity [--filter built-ins/Array]
  python3 tools/test262.py replay --platform jvm|android|js|wasmJs|linuxX64|macosArm64|iosSimulatorArm64|...

Both runs set KITEJS_TEST262, which makes build-plugins/test262 add the test262 sources to the tests of
kitejs-rhino. Requires Python 3.11 or later.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PYTHON = sys.executable or "python3"
KOTLIN = str(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"))
DEFAULT_BUNDLE = ROOT / "kitejs-rhino/build/test262/corpus"
# The producer's heap, so a case that fits there does not run out of memory in a replay.
HEAP = "-Xmx4g"


def parity(args):
    env = dict(os.environ, KITEJS_TEST262="parity")
    jvm_args = f"{HEAP} -Dtest262.filter={args.filter} -Dtest262.root={args.root}"
    subprocess.run([KOTLIN, "test", "-m", "kitejs-rhino", "-p", "jvm",
                    "--include-classes=io.github.yuroyami.kitejs.rhino.Test262ParityTest",
                    f"--jvm-args={jvm_args}"], cwd=ROOT, env=env, check=True)
    subprocess.run([PYTHON, str(ROOT / "tools/test262-corpus.py"), "pack", "--corpus", str(args.root)],
                   cwd=ROOT, check=True)


def replay(args):
    bundle = Path(args.bundle).resolve()
    corpus = str(ROOT / "tools/test262-corpus.py")
    subprocess.run([PYTHON, corpus, "validate", "--bundle", str(bundle)], cwd=ROOT, check=True)
    env = dict(os.environ, KITEJS_TEST262="replay", KITEJS_TEST262_BUNDLE=str(bundle))
    if args.platform in ("js", "wasmJs"):
        command = [PYTHON, str(ROOT / "tools/web-tests/run.py"), "--module", "kitejs-rhino",
                   "--platform", args.platform, "--filter", "Test262SliceTest"]
    else:
        command = [KOTLIN, "test", "-m", "kitejs-rhino", "-p", args.platform,
                   "--include-classes=*Test262SliceTest", f"--jvm-args={HEAP}"]
    results = ROOT / "kitejs-rhino/build/test-results" / args.platform
    results.mkdir(parents=True, exist_ok=True)
    log = results / "replay.log"
    # The console output carries the TEST262_RESULT line on every platform, so it is kept for the report.
    with log.open("w", encoding="utf-8") as out:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        for line in process.stdout:
            sys.stdout.write(line)
            out.write(line)
        code = process.wait()
        out.write(f"TEST262_REPLAY_EXIT={code}\n")
    if code != 0:
        sys.exit(code)
    subprocess.run([PYTHON, corpus, "report", "--bundle", str(bundle), "--output", str(results)], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("parity", help="compare both engines on test262 and package the corpus")
    p.add_argument("--filter", default="", help="a test262 path prefix, such as built-ins/Array")
    p.add_argument("--root", type=Path, default=ROOT / "reference/test262", help="the test262 checkout")
    p.set_defaults(run=parity)
    r = commands.add_parser("replay", help="replay the packaged corpus on one platform")
    r.add_argument("--platform", required=True, help="a platform of kitejs-rhino, such as jvm or linuxX64")
    r.add_argument("--bundle", default=str(DEFAULT_BUNDLE), help="the corpus that parity packaged")
    r.set_defaults(run=replay)
    args = parser.parse_args()
    try:
        args.run(args)
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode or 1)


if __name__ == "__main__":
    main()
