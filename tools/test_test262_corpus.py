"""Regression checks for replay inputs that previously could produce false green coverage."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("corpus", Path(__file__).with_name("test262-corpus.py"))
corpus = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus)


class ReplayArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name)
        self.expected = dict(schema=1, engineCommit="engine", engineSources="sources", corpusCommit="corpus", oracleVersion="oracle")
        self.metadata = dict(self.expected, caseCount=1, strictCount=0, sloppyCount=1, excludedFiles=0,
                             shards=["cases-00000.tsv"])
        source = base64.b64encode(b"assert.sameValue(1, 1);").decode()
        (self.bundle / "cases-00000.tsv").write_text(f"example.js\tsloppy\tcGFzc2Vk\t{source}\n")
        (self.bundle / "harness.tsv").write_text("assert.js\tLy8gaGFybmVzcw==\n")
        (self.bundle / "exclusions.tsv").write_text("")
        (self.bundle / "outcomes.tsv").write_text("example.js\tsloppy\tpassed\tpassed\tagreement\n")
        self.write_manifest()

    def write_manifest(self):
        self.metadata["files"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.bundle.glob("*.tsv")}
        (self.bundle / "manifest.json").write_text(json.dumps(self.metadata))

    def test_valid_nonempty_artifact_has_exact_denominator(self):
        self.assertEqual(1, corpus.validate(self.bundle, self.expected)["caseCount"])

    def test_missing_manifest_cannot_be_reported_as_success(self):
        (self.bundle / "manifest.json").unlink()
        with self.assertRaises(FileNotFoundError):
            corpus.validate(self.bundle, self.expected)

    def test_each_provenance_mismatch_is_rejected(self):
        for key in self.expected:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "mismatch"):
                corpus.validate(self.bundle, dict(self.expected, **{key: "wrong"}))

    def test_empty_selection_is_rejected_even_with_a_manifest(self):
        self.metadata.update(caseCount=0, sloppyCount=0)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "denominator"):
            corpus.validate(self.bundle, self.expected)

    def test_modified_or_truncated_fixture_is_rejected(self):
        (self.bundle / "cases-00000.tsv").write_text("")
        with self.assertRaisesRegex(ValueError, "checksum"):
            corpus.validate(self.bundle, self.expected)

    def test_missing_shard_is_rejected(self):
        (self.bundle / "cases-00000.tsv").unlink()
        with self.assertRaises(FileNotFoundError):
            corpus.validate(self.bundle, self.expected)

    def test_incorrect_case_count_is_rejected(self):
        self.metadata.update(caseCount=2, sloppyCount=2)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "case count"):
            corpus.validate(self.bundle, self.expected)

    def test_incorrect_execution_modes_are_rejected(self):
        self.metadata.update(strictCount=1, sloppyCount=0)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "mode count"):
            corpus.validate(self.bundle, self.expected)

    def test_duplicate_cases_do_not_satisfy_the_denominator(self):
        path = self.bundle / "cases-00000.tsv"
        path.write_text(path.read_text() * 2)
        self.metadata.update(caseCount=2, sloppyCount=2)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Duplicate replay case"):
            corpus.validate(self.bundle, self.expected)

    def test_malformed_record_is_not_silently_skipped(self):
        (self.bundle / "cases-00000.tsv").write_text("example.js\tsloppy\n")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Malformed"):
            corpus.validate(self.bundle, self.expected)

    def test_paths_cannot_escape_the_artifact(self):
        for name in ("../outside", "/outside", "C:/outside", "a\\b"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                corpus.relative_path(name)

    def write_report(self, prefix="", executed=1):
        directory = self.bundle / "results/jsBrowserTest"
        directory.mkdir(parents=True, exist_ok=True)
        result = dict(self.expected, executed=executed, strict=0, sloppy=1, mismatches=0)
        root = ET.Element("testsuite", tests="1", failures="0", errors="0")
        ET.SubElement(root, "system-out").text = prefix + "TEST262_RESULT=" + json.dumps(result) + "\n"
        ET.ElementTree(root).write(directory / "TEST-replay.xml", encoding="utf-8")
        return directory

    def test_jvm_and_browser_console_formats_produce_verified_results(self):
        for prefix in ("", "[log] "):
            directory = self.write_report(prefix)
            with patch.object(corpus, "provenance", return_value=self.expected):
                corpus.report(self.bundle, directory)
            result = json.loads((directory / "test262-result.json").read_text())
            self.assertEqual(1, result["executed"])
            self.assertEqual("jsBrowserTest", result["target"])

    def test_green_xml_without_an_execution_result_is_not_coverage(self):
        directory = self.write_report()
        (directory / "TEST-replay.xml").write_text('<testsuite tests="1" failures="0" errors="0"/>')
        with patch.object(corpus, "provenance", return_value=self.expected), self.assertRaisesRegex(ValueError, "found 0"):
            corpus.report(self.bundle, directory)

    def test_green_xml_with_an_incomplete_denominator_fails(self):
        directory = self.write_report(executed=0)
        with patch.object(corpus, "provenance", return_value=self.expected), self.assertRaisesRegex(ValueError, "Incomplete"):
            corpus.report(self.bundle, directory)


if __name__ == "__main__":
    unittest.main()
