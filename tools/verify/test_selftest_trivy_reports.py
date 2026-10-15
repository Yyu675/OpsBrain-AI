#!/usr/bin/env python3
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from selftest_trivy_reports import CVE, investigate, main, run_scan


class TestTrivyProbe(unittest.TestCase):
    def test_仅保留指定CVE不算一致必须包含所有gate发现(self):
        def fake(binary, target, output, *, fixed_only, memory, exit_code):
            self.assertTrue((target / "pom.xml").is_file())
            return {"rc": exit_code, "ids": [CVE, "CVE-EXTRA"] if fixed_only else [CVE], "targets": ["pom.xml"]}
        with mock.patch("selftest_trivy_reports.run_scan", side_effect=fake):
            rows = investigate("trivy")
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(not row["consistent"] for row in rows))

    def test_full可多出未修复项(self):
        def fake(binary, target, output, *, fixed_only, memory, exit_code):
            return {"rc": exit_code, "ids": [CVE] if fixed_only else [CVE, "CVE-UNFIXED"], "targets": ["pom.xml"]}
        with mock.patch("selftest_trivy_reports.run_scan", side_effect=fake):
            rows = investigate("trivy")
        self.assertTrue(all(row["consistent"] for row in rows))

    def test_探针正确退出码不能省(self):
        with mock.patch("selftest_trivy_reports.run_scan", return_value={"rc": 0, "ids": [CVE], "targets": ["pom.xml"]}):
            self.assertTrue(all(not row["consistent"] for row in investigate("trivy")))

    def test_运行参数保留完整退出码与缓存对照(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.json"
            def fake(cmd, **kwargs):
                out.write_text(json.dumps({"Results": [{"Target": "pom.xml", "Vulnerabilities": [{"VulnerabilityID": CVE}]}]}))
                self.assertIn("--ignore-unfixed", cmd)
                self.assertIn("--cache-backend", cmd)
                self.assertNotIn("shell", kwargs)
                return mock.Mock(returncode=1, stderr="")
            with mock.patch("selftest_trivy_reports.subprocess.run", side_effect=fake):
                result = run_scan("trivy", Path(tmp), out, fixed_only=True, memory=True, exit_code=1)
            self.assertEqual(result["ids"], [CVE])
            self.assertEqual(result["rc"], 1)

    def test_无报告不能当作空发现(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch("selftest_trivy_reports.subprocess.run", return_value=mock.Mock(returncode=0, stderr="")):
                with self.assertRaisesRegex(RuntimeError, "未产证据"):
                    run_scan("trivy", Path(tmp), Path(tmp) / "absent.json", fixed_only=False, memory=False, exit_code=0)

    def test_缺工具不跳过且证据明确失败(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "probe.json"
            with mock.patch.object(sys, "argv", ["probe", "--json", str(path)]), \
                    mock.patch("selftest_trivy_reports.controlled_probe", side_effect=FileNotFoundError("trivy missing")), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 1)
            self.assertIn("trivy missing", json.loads(path.read_text())["error"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
