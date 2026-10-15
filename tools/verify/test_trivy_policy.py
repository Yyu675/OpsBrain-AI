#!/usr/bin/env python3
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from trivy_policy import STATUSES, derive, main


def fixture():
    return {"SchemaVersion": 2, "ArtifactName": ".", "ArtifactType": "filesystem",
            "Results": [{"Target": "pom.xml", "Type": "pom", "Packages": [{"Name": "probe", "Version": "1"}],
                         "Vulnerabilities": [{"VulnerabilityID": "CVE-FIX", "Status": "fixed", "Severity": "HIGH"}]}]}


class TestTrivyPolicy(unittest.TestCase):
    def test_严格复刻Status规则而非修复版本字段(self):
        doc = fixture()
        doc["Results"][0]["Vulnerabilities"] = [
            {"VulnerabilityID": s, "Status": s, "FixedVersion": "exists-even-for-non-fixed"}
            for s in sorted(STATUSES)] + [{"VulnerabilityID": "missing-status", "FixedVersion": "2"}]
        gate, manifest = derive(doc)
        self.assertEqual([v["Status"] for v in gate["Results"][0]["Vulnerabilities"]], ["fixed"])
        self.assertEqual(manifest["kept_vulnerabilities"], 1)
        self.assertEqual(manifest["unfixed_vulnerabilities"], len(STATUSES))

    def test_fixed没有FixedVersion也不能被豁免(self):
        gate, _ = derive(fixture())
        self.assertEqual(len(gate["Results"][0]["Vulnerabilities"]), 1)

    def test_未知状态不静默放过(self):
        for status in ("future-state", None, 1, []):
            doc = fixture()
            doc["Results"][0]["Vulnerabilities"][0]["Status"] = status
            with self.assertRaises(ValueError):
                derive(doc)

    def test_源文档不变且其他发现元数据原样保留(self):
        doc = fixture()
        r = doc["Results"][0]
        r.update({"Secrets": [{"RuleID": "probe", "Severity": "HIGH", "Match": "fake"}],
                  "Misconfigurations": [{"ID": "config", "Status": "FAIL"}],
                  "Licenses": [{"Name": "probe-license"}], "VendorField": {"a": 1}})
        before = json.dumps(doc)
        gate, _ = derive(doc)
        self.assertEqual(json.dumps(doc), before)
        self.assertEqual(gate, doc)
        gate["Results"][0]["Packages"][0]["Name"] = "changed"
        self.assertEqual(doc["Results"][0]["Packages"][0]["Name"], "probe")

    def test_零漏洞有效覆盖可用(self):
        for vulns in (None, []):
            doc = fixture()
            doc["Results"][0]["Vulnerabilities"] = vulns
            _, manifest = derive(doc, ["pom.xml"])
            self.assertEqual(manifest["kept_vulnerabilities"], 0)

    def test_缺预期目标失败(self):
        with self.assertRaisesRegex(ValueError, "缺预期扫描目标"):
            derive(fixture(), ["pom.xml", "devops-platform-frontend/package-lock.json"])

    def test_空覆盖或非法Schema失败(self):
        for doc in ({}, [], {"SchemaVersion": 2, "ArtifactName": ".", "Results": []},
                    {"SchemaVersion": 3, "ArtifactName": ".", "Results": [{"Target": "x"}]}):
            with self.assertRaises(ValueError):
                derive(doc)

    def test_无漏洞身份的损坏对象不许变绿(self):
        for value in ({}, {"VulnerabilityID": None}, {"VulnerabilityID": ""}):
            doc = fixture()
            doc["Results"][0]["Vulnerabilities"] = [value]
            with self.assertRaisesRegex(ValueError, "VulnerabilityID"):
                derive(doc)

    def test_各发现字段不接受错误类型(self):
        for field in ("Vulnerabilities", "Packages", "Secrets", "Misconfigurations", "Licenses"):
            for value in ({"bad": 1}, [1]):
                doc = fixture()
                doc["Results"][0][field] = value
                with self.assertRaises(ValueError):
                    derive(doc)

    def invoke(self, argv):
        with mock.patch.object(sys, "argv", ["trivy_policy"] + argv), contextlib.redirect_stdout(io.StringIO()):
            return main()

    def test_CLI哈希及原文件不被覆盖(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src, out, manifest = [root / n for n in ("full.json", "gate-input.json", "policy.json")]
            raw = json.dumps(fixture()).encode()
            src.write_bytes(raw)
            rc = self.invoke(["--input", str(src), "--output", str(out), "--manifest", str(manifest)])
            self.assertEqual(rc, 0)
            self.assertEqual(src.read_bytes(), raw)
            evidence = json.loads(manifest.read_text())
            self.assertEqual(evidence["source_sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(evidence["derived_sha256"], hashlib.sha256(out.read_bytes()).hexdigest())

    def test_坏输入清除旧绿证据(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src, out, manifest = [root / n for n in ("full.json", "gate.json", "policy.json")]
            src.write_text("not JSON")
            out.write_text("stale")
            manifest.write_text("stale")
            self.assertEqual(self.invoke(["--input", str(src), "--output", str(out), "--manifest", str(manifest)]), 1)
            self.assertFalse(out.exists())
            self.assertFalse(manifest.exists())

    def test_禁止覆盖源报告(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "full.json"
            path.write_text(json.dumps(fixture()))
            before = path.read_bytes()
            self.assertEqual(self.invoke(["--input", str(path), "--output", str(path),
                                          "--manifest", str(Path(tmp) / "policy.json")]), 1)
            self.assertEqual(path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
