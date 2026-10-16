#!/usr/bin/env python3
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from trivy_summary import main, summarize


class TestTrivySummary(unittest.TestCase):
    def report(self, vulns=None):
        return {"SchemaVersion": 2, "ArtifactName": ".", "Results": [
            {"Target": "frontend/package-lock.json", "Type": "npm", "Vulnerabilities": vulns or []}]}

    def test_零漏洞也有目标覆盖(self):
        lines = summarize(self.report())
        self.assertIn("条目 0", lines[0])
        self.assertIn("package-lock.json", lines[1])

    def test_有补丁与无补丁都保留(self):
        lines = summarize(self.report([
            {"VulnerabilityID": "CVE-FIX", "Severity": "HIGH", "PkgName": "a", "FixedVersion": "2"},
            {"VulnerabilityID": "CVE-NOFIX", "Severity": "CRITICAL", "PkgName": "b"},
            {"VulnerabilityID": "CVE-LOW", "Severity": "LOW"}]))
        self.assertIn("条目 2", lines[0])
        self.assertIn("有修复版本 1", lines[0])
        self.assertIn("CVE-NOFIX", "\n".join(lines))
        self.assertNotIn("CVE-LOW", "\n".join(lines))

    def test_密钥摘要不泄露匹配内容(self):
        report = self.report()
        report["Results"][0]["Secrets"] = [{"RuleID": "token", "Severity": "HIGH", "Match": "PRIVATE-VALUE"}]
        lines = summarize(report)
        self.assertIn("Secrets token HIGH", "\n".join(lines))
        self.assertNotIn("PRIVATE-VALUE", "\n".join(lines))

    def test_空目标不冒充完整覆盖(self):
        self.assertIn("不能据空报告", "\n".join(summarize({"SchemaVersion": 2, "ArtifactName": "."})))

    def test_非法报告拒绝(self):
        for report in ({}, [], {"SchemaVersion": 2, "ArtifactName": ".", "Results": {"bad": 1}}):
            with self.assertRaises(ValueError):
                summarize(report)

    def test_报告缺失退出1(self):
        with mock.patch.object(sys, "argv", ["trivy_summary", "--json", "absent-report.json"]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 1)

    def test_摘要转义防annotation注入(self):
        report = self.report([{"VulnerabilityID": "CVE-x\n::error::injected%", "Severity": "HIGH"}])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.json"
            path.write_text(json.dumps(report))
            with mock.patch.object(sys, "argv", ["trivy_summary", "--json", str(path)]):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    self.assertEqual(main(), 0)
        self.assertNotIn("\n::error::injected", out.getvalue())
        self.assertIn("%0A::error::injected%25", out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
