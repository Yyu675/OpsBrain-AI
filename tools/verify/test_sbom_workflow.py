#!/usr/bin/env python3
"""实际 YAML shell 协议；fake Maven/Trivy 不冒充真实构建/扫描。"""
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


def steps():
    return yaml.safe_load((ROOT / ".github/workflows/vulnerability-scan.yml").read_text())["jobs"]["maven-runtime-coverage"]["steps"]


class TestSBOMWorkflow(unittest.TestCase):
    def test_原生插件范围固定且生产单扫不变(self):
        scripts = "\n".join(s.get("run", "") for s in steps())
        for flag in ("cyclonedx-maven-plugin:2.9.1:makeBom", "-DincludeCompileScope=true", "-DincludeRuntimeScope=true",
                     "-DincludeTestScope=false", "-DincludeProvidedScope=false", "-DincludeSystemScope=false",
                     "-DschemaVersion=1.6", "trivy sbom", "--exit-code 0", "trivy convert", "--exit-code 1"):
            self.assertIn(flag, scripts)
        self.assertNotIn("trivy fs", scripts)
        setup = next(s for s in steps() if s.get("name", "").startswith("安装实验 Trivy"))
        self.assertEqual(setup["with"]["version"], "v0.70.0")
        self.assertIn("--boot-jar", scripts)

    def run_scan(self, gate_rc, scan_rc=0, write=True):
        script = next(s["run"] for s in steps() if s.get("name", "").startswith("SBOM 真扫"))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "tools/verify").mkdir(parents=True)
            for name in ("trivy_summary.py", "trivy_policy.py"):
                shutil.copy(ROOT / "tools/verify" / name, root / "tools/verify" / name)
            exe = root / "trivy"
            exe.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
print('fake Trivy protocol diagnostic')
if os.environ['WRITE_REPORT'] == 'yes':
    data = {'SchemaVersion':2, 'ArtifactName':'probe', 'Results':[{'Target':'Java', 'Type':'jar',
            'Packages':[{'Name':'x:a', 'Version':'1'}], 'Vulnerabilities':[{'VulnerabilityID':'CVE-PROBE', 'Status':'fixed'}]}]}
    pathlib.Path(sys.argv[sys.argv.index('--output')+1]).write_text(json.dumps(data))
sys.exit(int(os.environ['SCAN_RC'] if sys.argv[1]=='sbom' else os.environ['GATE_RC']))
''')
            exe.chmod(0o755)
            # 仅替换本步所有 /tmp 路径，不触碰生产路径和实际工作区。
            script = script.replace("/tmp/", str(root) + "/")
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"], "SCAN_SEVERITY": "HIGH,CRITICAL",
                   "SCAN_RC": str(scan_rc), "GATE_RC": str(gate_rc), "WRITE_REPORT": "yes" if write else "no"}
            proc = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script], cwd=root,
                                  env=env, capture_output=True, text=True, timeout=15)
            rc_file = root / "sbom-native-gate.rc"
            return proc, rc_file.read_text().strip() if rc_file.exists() else None

    def test_实验保留原生红码不冒充生产放行(self):
        proc, rc = self.run_scan(1)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(rc, "1")

    def test_工具七退出不能当作漏洞红码接受(self):
        proc, rc = self.run_scan(7)
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(rc, "7")

    def test_首扫错误或缺报告必须红(self):
        for scan_rc, write in ((7, True), (0, False)):
            proc, _ = self.run_scan(0, scan_rc, write)
            self.assertNotEqual(proc.returncode, 0)

    def test_构建假绿缺产物及工具错误都拒绝(self):
        script = next(s["run"] for s in steps() if s.get("name", "").startswith("构建 Boot JAR"))
        for status in (0, 7):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / "tools/verify").mkdir(parents=True)
                shutil.copy(ROOT / "tools/verify/trivy_summary.py", root / "tools/verify/trivy_summary.py")
                (root / "runtime-bom.json").write_text('stale')
                exe = root / "mvnw"
                exe.write_text(f"#!/bin/sh\necho 'fake Maven diagnostic'\nexit {status}\n")
                exe.chmod(0o755)
                proc = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script.replace("/tmp/", str(root) + "/")],
                                      cwd=root, capture_output=True, text=True, timeout=15)
                self.assertEqual(proc.returncode, status or 1, proc.stderr)
                self.assertFalse((root / "runtime-bom.json").exists())
                self.assertIn("::error::", proc.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
