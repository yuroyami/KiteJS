#!/usr/bin/env python3
"""Build and validate portable test262 replay artifacts. Requires only Python 3.11+."""
import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "kitejs-rhino/build/test262"
DEFAULT_BUNDLE = RESULTS / "corpus"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def sha(data):
    return hashlib.sha256(data).hexdigest()


def provenance():
    # Git normalizes checkout line endings. Include local edits and new files so a locally
    # produced artifact cannot be mistaken for the committed sources or a different edit.
    digest = hashlib.sha256(git(ROOT, "rev-parse", "HEAD^{tree}"))
    digest.update(git(ROOT, "diff", "--no-ext-diff", "--binary", "HEAD"))
    for name in sorted(set(git(ROOT, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")) - {b""}):
        digest.update(name + b"\0" + (ROOT / name.decode()).read_bytes() + b"\0")
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    return {
        "schema": 1,
        "engineCommit": git(ROOT, "rev-parse", "HEAD").decode().strip(),
        "engineSources": digest.hexdigest(),
        "corpusCommit": (ROOT / "tools/test262-revision.txt").read_text().strip(),
        "oracleVersion": versions["versions"]["rhino"],
    }


def check_corpus(corpus, expected):
    require(git(corpus, "rev-parse", "HEAD").decode().strip() == expected, "test262 revision does not match the pin")
    require(not git(corpus, "status", "--porcelain", "--untracked-files=all"), "test262 checkout has local changes")
    require((corpus / "test").is_dir() and (corpus / "harness").is_dir(), "test262 corpus is incomplete")


def begin(corpus, selection):
    metadata = provenance()
    check_corpus(corpus, metadata["corpusCommit"])
    metadata["filter"] = selection
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "complete.json").unlink(missing_ok=True)
    (RESULTS / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")


def complete():
    metadata = json.loads((RESULTS / "provenance.json").read_text())
    for key, value in provenance().items():
        require(metadata.get(key) == value, f"Producer {key} changed during parity")
    hashes = {name: sha((RESULTS / name).read_bytes())
              for name in ("provenance.json", "expectations.txt", "exclusions.tsv", "outcomes.tsv")}
    (RESULTS / "complete.json").write_text(json.dumps(hashes, sort_keys=True) + "\n")


def encoded(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def decoded(text):
    return base64.b64decode(text, validate=True).decode("utf-8")


def relative_path(name):
    p = PurePosixPath(name)
    require(name and not p.is_absolute() and ".." not in p.parts and "\\" not in name and ":" not in name and str(p) == name,
            f"Invalid corpus path: {name!r}")
    return name


def rows(text, columns):
    result = []
    for line in text.splitlines():
        require(bool(line), "Empty corpus record")
        fields = line.split("\t")
        require(len(fields) == columns, "Malformed corpus record")
        result.append(fields)
    return result


def pack(corpus):
    completion = json.loads((RESULTS / "complete.json").read_text())
    for name in ("provenance.json", "expectations.txt", "exclusions.tsv", "outcomes.tsv"):
        require(completion.get(name) == sha((RESULTS / name).read_bytes()), "Parity results changed or never completed")
    metadata = json.loads((RESULTS / "provenance.json").read_text())
    for key, value in provenance().items():
        require(metadata.get(key) == value, f"Producer {key} changed during parity; rerun parity")
    check_corpus(corpus, metadata["corpusCommit"])
    cases = rows((RESULTS / "expectations.txt").read_text(), 3)
    require(cases, "Cannot package an empty corpus selection")
    stage = RESULTS / "corpus.tmp"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    shards = []
    for start in range(0, len(cases), 100):
        name = f"cases-{start // 100:05d}.tsv"
        records = []
        for path, mode, expected in cases[start:start + 100]:
            source = (corpus / "test" / relative_path(path)).read_bytes().decode("utf-8")
            records.append("\t".join((path, mode, encoded(expected), encoded(source))))
        (stage / name).write_text("\n".join(records) + "\n", encoding="utf-8")
        shards.append(name)
    harness = []
    for path in sorted((corpus / "harness").rglob("*.js")):
        harness.append(path.relative_to(corpus / "harness").as_posix() + "\t" + encoded(path.read_bytes().decode("utf-8")))
    (stage / "harness.tsv").write_text("\n".join(harness) + "\n", encoding="utf-8")
    for name in ("exclusions.tsv", "outcomes.tsv"):
        shutil.copyfile(RESULTS / name, stage / name)
    metadata.update({
        "caseCount": len(cases),
        "strictCount": sum(mode == "strict" for _, mode, _ in cases),
        "sloppyCount": sum(mode == "sloppy" for _, mode, _ in cases),
        "excludedFiles": len(rows((stage / "exclusions.tsv").read_text(), 2)),
        "shards": shards,
        "files": {p.name: sha(p.read_bytes()) for p in sorted(stage.iterdir())},
    })
    (stage / "manifest.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    validate(stage, provenance())
    if DEFAULT_BUNDLE.exists():
        shutil.rmtree(DEFAULT_BUNDLE)
    stage.rename(DEFAULT_BUNDLE)
    print(f"Packaged {len(cases)} test262 cases in {len(shards)} shards")


def validate(bundle, expected):
    metadata = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    for key, value in expected.items():
        require(metadata.get(key) == value, f"Replay {key} mismatch; regenerate the corpus for this checkout")
    require(type(metadata.get("caseCount")) is int and metadata["caseCount"] > 0, "Replay denominator must be positive")
    require(metadata.get("shards") and len(set(metadata["shards"])) == len(metadata["shards"]), "Missing or duplicate shards")
    required = {"harness.tsv", "exclusions.tsv", "outcomes.tsv", *metadata["shards"]}
    require(set(metadata.get("files", {})) == required, "Manifest file inventory mismatch")
    for name, digest in metadata["files"].items():
        require(sha((bundle / relative_path(name)).read_bytes()) == digest, f"Replay checksum mismatch: {name}")
    seen = set()
    strict = 0
    for name in metadata["shards"]:
        records = rows((bundle / name).read_text(encoding="utf-8"), 4)
        require(records, f"Empty replay shard: {name}")
        for path, mode, outcome, source in records:
            relative_path(path)
            require(mode in ("strict", "sloppy"), f"Invalid execution mode: {mode}")
            require((path, mode) not in seen, f"Duplicate replay case: {path} [{mode}]")
            require(bool(decoded(outcome)), "Missing expected outcome")
            decoded(source)
            seen.add((path, mode))
            strict += mode == "strict"
    require(len(seen) == metadata["caseCount"], "Replay case count mismatch")
    require(strict == metadata["strictCount"] and len(seen) - strict == metadata["sloppyCount"], "Replay mode count mismatch")
    harness = rows((bundle / "harness.tsv").read_text(encoding="utf-8"), 2)
    require(harness and len({name for name, _ in harness}) == len(harness), "Missing or duplicate harness files")
    for name, source in harness:
        relative_path(name)
        decoded(source)
    require(len(rows((bundle / "exclusions.tsv").read_text(), 2)) == metadata["excludedFiles"], "Exclusion count mismatch")
    outcomes = rows((bundle / "outcomes.tsv").read_text(), 5)
    require(len(outcomes) == len(seen) and {(p, m) for p, m, *_ in outcomes} == seen, "Outcome inventory mismatch")
    return metadata


def kotlin_string(text):
    # JSON escaping is also valid in ordinary Kotlin string literals, except for '$'.
    return json.dumps(text, ensure_ascii=True).replace("$", "\\$")


def generate(bundle, output):
    metadata = validate(bundle, provenance())
    manifest = (bundle / "manifest.json").read_text(encoding="utf-8")
    output.mkdir(parents=True, exist_ok=True)
    chunks = ",\n".join(kotlin_string(manifest[i:i + 8000]) for i in range(0, len(manifest), 8000))
    source = "package io.github.yuroyami.kitejs.rhino\n\n"
    source += f"internal const val TEST262_BUNDLE_ROOT: String = {kotlin_string(str(bundle.resolve()))}\n"
    source += f"internal const val TEST262_CASE_COUNT: Int = {metadata['caseCount']}\n"
    for key in ("engineCommit", "engineSources", "corpusCommit", "oracleVersion"):
        source += f"internal const val TEST262_{key.upper()}: String = {kotlin_string(metadata[key])}\n"
    source += f"internal val TEST262_MANIFEST: String = listOf(\n{chunks}\n).joinToString(\"\")\n"
    source += "internal val TEST262_SHARDS: List<String> = listOf(" + ", ".join(map(kotlin_string, metadata["shards"])) + ")\n"
    (output / "Test262Paths.kt").write_text(source, encoding="utf-8")


def report(bundle, report_dir):
    metadata = validate(bundle, provenance())
    reports = []
    for path in report_dir.rglob("TEST-*.xml"):
        root = ET.parse(path).getroot()
        for output in root.iter("system-out"):
            for line in (output.text or "").splitlines():
                # Kotlin's Karma reporter preserves browser console output with this prefix.
                line = line.removeprefix("[log] ")
                if line.startswith("TEST262_RESULT="):
                    require(int(root.get("failures", "0")) == 0 and int(root.get("errors", "0")) == 0,
                            f"Replay test failed: {path}")
                    result = json.loads(line.removeprefix("TEST262_RESULT="))
                    require(result["executed"] == metadata["caseCount"] and result["mismatches"] == 0,
                            f"Incomplete or failing replay: {path}")
                    require(result["strict"] == metadata["strictCount"] and result["sloppy"] == metadata["sloppyCount"],
                            f"Replay execution modes mismatch: {path}")
                    for key in ("engineCommit", "engineSources", "corpusCommit", "oracleVersion"):
                        require(result[key] == metadata[key], f"Result provenance mismatch: {path}")
                    reports.append(result)
    require(len(reports) == 1, f"Expected one replay result, found {len(reports)} in {report_dir}")
    reports[0]["target"] = report_dir.name
    target = report_dir / "test262-result.json"
    target.write_text(json.dumps(reports[0], indent=2) + "\n")
    print(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("begin", "complete", "pack", "validate", "generate", "report"))
    parser.add_argument("--corpus", type=Path, default=ROOT / "reference/test262")
    parser.add_argument("--filter", default="")
    parser.add_argument("--bundle", type=Path, default=DEFAULT_BUNDLE)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "begin":
            begin(args.corpus, args.filter)
        elif args.command == "complete":
            complete()
        elif args.command == "pack":
            pack(args.corpus)
        elif args.command == "validate":
            print(f"Validated {validate(args.bundle, provenance())['caseCount']} replay cases")
        elif args.command == "generate":
            require(args.output is not None, "generate requires --output")
            generate(args.bundle, args.output)
        else:
            require(args.output is not None, "report requires --output (the target's XML result directory)")
            report(args.bundle, args.output)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"test262: {error}\n")


if __name__ == "__main__":
    main()
