#!/usr/bin/env python3
"""Run the JavaScript and WebAssembly (wasmJs) tests of KiteJS in Node.

`./kotlin test` runs no JavaScript tests, and it runs wasmJs tests only in a browser without the npm
packages that KiteJS needs. This script links each module's tests with the Kotlin Toolchain, installs the
npm packages next to the linked output, and runs the tests through runner.mjs.

Usage: python3 tools/web-tests/run.py [--platform js|wasmJs] [--module <name>] [--filter <text>]
"""
import argparse
import glob
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
# The modules with tests. kitejs-testkit has none of its own.
MODULES = ("kitejs-api", "kitejs-rhino", "kitejs-quickjs", "kitejs-coroutines", "kitejs")
BUILD = ROOT / "build"
ARTIFACTS = BUILD / "artifacts/CompiledWebArtifact"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
if sys.platform == "win32":
    CACHE = Path.home() / "AppData/Local/JetBrains/Kotlin"


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def run(*args, env=None):
    print("Running " + " ".join(str(a) for a in args[:4]) + (" ..." if len(args) > 4 else ""), flush=True)
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True, env=env)


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def npm_packages():
    """The npm packages the tests import: kotlinx-datetime's, read from its klib, and @js-joda/timezone."""
    versions = tomllib.loads((ROOT / "libs.versions.toml").read_text())["versions"]
    packages = {"@js-joda/timezone": versions["jsJodaTimezone"]}
    klibs = glob.glob(str(CACHE / f".m2.cache/org/jetbrains/kotlinx/kotlinx-datetime-js/{versions['kotlinxDatetime']}/*.klib"))
    if not klibs:
        raise RuntimeError("kotlinx-datetime is not in the toolchain cache yet; link the tests first")
    with zipfile.ZipFile(klibs[0]) as klib:
        packages.update(json.loads(klib.read("package.json"))["dependencies"])
    return packages


def install_npm_packages():
    """Installs the packages next to the linked tests, where Node finds them from every test file.

    Not into build/node_modules: pnpm, which the toolchain uses to install Playwright for its own wasmJs
    tests, then takes build/ as its project folder and the toolchain cannot find Playwright.
    """
    packages = npm_packages()
    modules = ARTIFACTS / "node_modules"
    installed = all((modules / name / "package.json").exists() and
                    json.loads((modules / name / "package.json").read_text())["version"] == version
                    for name, version in packages.items())
    if not installed:
        npm = shutil.which("npm") or "npm"
        run(npm, "install", "--prefix", ARTIFACTS, "--no-save", "--no-package-lock", "--no-audit", "--no-fund",
            *[f"{name}@{version}" for name, version in packages.items()])


def test(module, platform, executable, test_filter):
    if platform == "js":
        kotlin("task", f":{module}:linkJsTest")
        entry = ARTIFACTS / f"{module}jsTestrelease/kotlin-output/{module}_test.mjs"
    else:
        kotlin("task", f":{module}:linkWasmJsTestDebug")
        entry = ARTIFACTS / f"{module}wasmJsTestdebug/kotlin-output/{module}_test.mjs"
    install_npm_packages()
    env = None
    if test_filter:
        env = dict(os.environ, KITEJS_TEST_FILTER=test_filter)
    print(f"{module} {platform}:", flush=True)
    run(executable, HERE / "runner.mjs", entry, env=env)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("js", "wasmJs"), action="append",
                        help="run one platform; repeat it for both (default: both)")
    parser.add_argument("--module", action="append", choices=MODULES, help="run one module; repeat it for more")
    parser.add_argument("--filter", help="run only the tests whose class or test name contains this text")
    args = parser.parse_args()
    executable = node()
    failed = []
    # One failing module does not stop the others, so a run reports every failure at once.
    for module in args.module or MODULES:
        for platform in args.platform or ["js", "wasmJs"]:
            try:
                test(module, platform, executable, args.filter)
            except subprocess.CalledProcessError:
                failed.append(f"{module} {platform}")
    if failed:
        raise RuntimeError("Failed: " + ", ".join(failed))


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
