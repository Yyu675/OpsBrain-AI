#!/usr/bin/env python3
"""读取实际 YAML 执行 shell；Maven 可执行夹具只证明控制流，不冒充真实解析。"""
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


def workflow():
    return yaml.safe_load((ROOT / ".github/workflows/vulnerability-scan.yml").read_text())


class TestCoverageWorkflow(unittest.TestCase):
    def test_后置独立job取同run原始报告不污染生产(self):
        jobs = workflow()["jobs"]
        job = jobs["maven-runtime-coverage"]
        self.assertEqual(job["needs"], "trivy-repo-scan")
        self.assertEqual(job["if"], "always()")
        download = next(s for s in job["steps"] if "download-artifact@" in s.get("uses", ""))
        self.assertEqual(download["with"]["name"], "trivy-report")
        self.assertNotIn("run-id", download["with"])
        self.assertNotIn("./mvnw", "\n".join(s.get("run", "") for s in jobs["trivy-repo-scan"]["steps"]))

    def test_固定插件运行时JSON且审计非strict(self):
        steps = workflow()["jobs"]["maven-runtime-coverage"]["steps"]
        commands = "\n".join(s.get("run", "") for s in steps)
        for flag in ("maven-dependency-plugin:3.8.1:tree", "-Dscope=runtime", "-DoutputType=json",
                     "--report /tmp/coverage-input/trivy-report.json", '--checkout-sha "$CHECKOUT_SHA"'):
            self.assertIn(flag, commands)
        self.assertNotIn("--strict", commands)
        upload = next(s for s in steps if "upload-artifact@" in s.get("uses", ""))
        self.assertEqual(upload["if"], "always()")
        self.assertEqual(upload["with"]["if-no-files-found"], "error")

    def execute(self, status, writes_tree):
        steps = workflow()["jobs"]["maven-runtime-coverage"]["steps"]
        scripts = [s["run"] for s in steps if s.get("name") in ("解析 Maven 运行时依赖（固定 plugin JSON 契约）", "对照运行时 GAV 与原始 Java 包集（审计非放行）")]
        self.assertEqual(len(scripts), 2)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "tools/verify").mkdir(parents=True)
            for name in ("maven_scan_coverage.py", "trivy_summary.py"):
                shutil.copy(ROOT / "tools/verify" / name, root / "tools/verify" / name)
            (root / "coverage-input").mkdir()
            (root / "coverage-input/trivy-report.json").write_text(json.dumps({
                "SchemaVersion": 2, "ArtifactName": ".", "Results": [{"Target": "pom.xml", "Type": "pom",
                "Packages": [{"Name": "example:a", "Version": "1"}]}]}))
            (root / "pom.xml").write_text("fixture")
            (root / "maven-runtime-tree.json").write_text("stale evidence must be removed")
            exe = root / "mvnw"
            exe.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
print('fake Maven diagnostic')
if os.environ['WRITE_TREE'] == 'yes':
    path = next(s.split('=', 1)[1] for s in sys.argv if s.startswith('-DoutputFile='))
    root = {'groupId':'example', 'artifactId':'app', 'version':'1', 'type':'jar', 'children':[
        {'groupId':'example', 'artifactId':'a', 'version':'1', 'type':'jar', 'scope':'compile'}]}
    pathlib.Path(path).write_text(json.dumps(root))
sys.exit(int(os.environ['MAVEN_STATUS']))
''')
            exe.chmod(0o755)
            env = {**os.environ, "MAVEN_STATUS": str(status), "WRITE_TREE": "yes" if writes_tree else "no",
                   "CHECKOUT_SHA": "a" * 40}
            output = ""
            for script in scripts:
                for path in ("coverage-input/trivy-report.json", "maven-runtime-tree.json", "maven-runtime-tree.log", "maven-scan-coverage.json"):
                    script = script.replace("/tmp/" + path, str(root / path))
                proc = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script], cwd=root,
                                      env=env, capture_output=True, text=True, timeout=15)
                output += proc.stdout + proc.stderr
                if proc.returncode:
                    return proc.returncode, output
            return proc.returncode, output

    def test_有效tree与原始包集才完成审计(self):
        rc, output = self.execute(0, True)
        self.assertEqual(rc, 0, output)
        self.assertIn("status=complete", output)

    def test_Maven错误保留原始码并留可读诊断(self):
        rc, output = self.execute(7, False)
        self.assertEqual(rc, 7, output)
        self.assertIn("::error::Maven runtime tree 生成失败", output)
        self.assertIn("fake Maven diagnostic", output)

    def test_Maven零退出无报告不能用陈旧证据假绿(self):
        rc, output = self.execute(0, False)
        self.assertEqual(rc, 1, output)
        self.assertIn("::error::Maven 扫描覆盖审计失败", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
