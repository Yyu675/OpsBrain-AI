#!/usr/bin/env python3
"""执行工作流中的原 gate shell，证明 bash -e 下也不吞 CLI 退出码。

这是 Linux runner 的协议测试，Trivy 用显式可执行夹具；不是漏洞实扫证据。
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class TestTrivyGateExit(unittest.TestCase):
    def run_gate(self, status, report_exists):
        workflow = yaml.safe_load((ROOT / ".github/workflows/vulnerability-scan.yml").read_text())
        steps = workflow["jobs"]["trivy-repo-scan"]["steps"]
        script = next(s["run"] for s in steps if s.get("name") == "Trivy 扫描（HIGH,CRITICAL 即红）")
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "tools/verify").mkdir(parents=True)
            for name in ("trivy_summary.py", "trivy_policy.py"):
                shutil.copy(ROOT / "tools/verify" / name, tmp / "tools/verify" / name)
            (tmp / "trivy-report.json").write_text(json.dumps({
                'SchemaVersion': 2, 'ArtifactName': '.', 'Results': [
                    {'Target': 'pom.xml'}, {'Target': 'devops-platform-frontend/package-lock.json'}]}))
            executable = tmp / "trivy"
            executable.write_text(f"#!{sys.executable}\n" + """
import json, os, pathlib, sys
print('fake Trivy diagnostic only')
if os.environ['FAKE_REPORT'] == 'yes':
    output = sys.argv[sys.argv.index('--output') + 1]
    pathlib.Path(output).write_text(json.dumps({'SchemaVersion': 2, 'ArtifactName': '.', 'Results': []}))
sys.exit(int(os.environ['FAKE_STATUS']))
""")
            executable.chmod(0o755)
            for name in ("trivy-report.json", "trivy-gate.json", "trivy-policy-input.json", "trivy-policy.json", "trivy-gate.log"):
                script = script.replace("/tmp/" + name, str(tmp / name))
            env = {**os.environ, "PATH": str(tmp) + os.pathsep + os.environ["PATH"],
                   "FAKE_STATUS": str(status), "FAKE_REPORT": "yes" if report_exists else "no",
                   "SCAN_SEVERITY": "HIGH,CRITICAL"}
            proc = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script],
                                  cwd=tmp, env=env, capture_output=True, text=True, timeout=15)
        return proc

    def test_零退出与有效报告才绿(self):
        proc = self.run_gate(0, True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("fake Trivy diagnostic only", proc.stdout)

    def test_有报告也不能掩盖扫描红灯(self):
        proc = self.run_gate(1, True)
        self.assertEqual(proc.returncode, 1)

    def test_缺报告不能假绿(self):
        proc = self.run_gate(0, False)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("报告缺失/非法", proc.stdout)

    def test_缺报告时仍保留工具原始错误码(self):
        proc = self.run_gate(7, False)
        self.assertEqual(proc.returncode, 7)
        self.assertIn("fake Trivy diagnostic only", proc.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
