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

from verify_runtime_patches import REQUIRED, VERSIONS, main, verify


def component(name, version):
    group, artifact = name.split(":")
    return {"group": group, "name": artifact, "version": version,
            "purl": f"pkg:maven/{group}/{artifact}@{version}?type=jar",
            "hashes": [{"alg": "SHA-256", "content": "a" * 64}]}


def fixture():
    bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [component(n, VERSIONS[n.split(":")[0]]) for n in sorted(REQUIRED)]}
    experiment = {"schema_version": 1, "counts": {"runtime_missing_from_sbom": 0, "sbom_missing_from_scan": 0},
                  "findings": [{"PkgName": "org.springframework:spring-webmvc", "Severity": "CRITICAL"}],
                  "native_gate_rc": 1, "checkout_sha": "b" * 40}
    experiment["packaged_libraries"] = [{"variant": [c["group"] + ":" + c["name"], c["version"], "jar", ""],
                                         "sha256": c["hashes"][0]["content"]} for c in bom["components"]]
    return bom, experiment


class TestRuntimePatches(unittest.TestCase):
    def test_仅目标组通过不能冒充整体安全(self):
        result = verify(*fixture())
        self.assertTrue(result["target_groups_passed"])
        self.assertEqual(result["remaining_hc_rows"], 1)
        self.assertFalse(result["full_security_passed"])
        self.assertEqual(result["native_gate_rc"], 1)

    def test_缺目标及扫描覆盖不能绿(self):
        bom, probe = fixture()
        bom["components"].pop()
        with self.assertRaises(ValueError):
            verify(bom, probe)
        bom, probe = fixture()
        probe["counts"]["sbom_missing_from_scan"] = 1
        with self.assertRaises(ValueError):
            verify(bom, probe)

    def test_其他Tika模块混版也必须红(self):
        bom, probe = fixture()
        bom["components"].append(component("org.apache.tika:tika-parser-xml-module", "3.0.0"))
        with self.assertRaisesRegex(ValueError, "混版"):
            verify(bom, probe)

    def test_版本对齐但仍有目标漏洞也必须红(self):
        bom, probe = fixture()
        probe["findings"].append({"PkgName": "org.apache.tika:tika-core", "Severity": "HIGH"})
        with self.assertRaisesRegex(ValueError, "仍有"):
            verify(bom, probe)

    def test_tcnative独立版本不是漏洞豁免(self):
        bom, probe = fixture()
        bom["components"].append(component("io.netty:netty-tcnative-classes", "2.0.75.Final"))
        probe["packaged_libraries"].append({"variant": ["io.netty:netty-tcnative-classes", "2.0.75.Final", "jar", ""], "sha256": "a" * 64})
        verify(bom, probe)
        probe["findings"].append({"PkgName": "io.netty:netty-tcnative-classes", "Severity": "HIGH"})
        with self.assertRaises(ValueError):
            verify(bom, probe)

    def test_SBOM已补但Boot仍旧字节不能绿(self):
        bom, probe = fixture()
        probe["packaged_libraries"][0]["sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "实际Boot字节"):
            verify(bom, probe)
        probe.pop("packaged_libraries")
        with self.assertRaises(ValueError):
            verify(bom, probe)

    def invoke(self, root, severity="HIGH,CRITICAL"):
        argv = ["guard", "--sbom", str(root / "bom.json"), "--experiment", str(root / "probe.json"),
                "--json", str(root / "out.json"), "--scan-severity", severity]
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
            return main()

    def prepare(self, root):
        bom, probe = fixture()
        raw = json.dumps(bom).encode()
        (root / "bom.json").write_bytes(raw)
        probe["input_sha256"] = {"sbom": hashlib.sha256(raw).hexdigest()}
        (root / "probe.json").write_text(json.dumps(probe))

    def test_CLI哈希绑定及整体安全仍不通过(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.prepare(root)
            self.assertEqual(self.invoke(root), 0)
            out = json.loads((root / "out.json").read_text())
            self.assertEqual(out["sha256"]["experiment"], hashlib.sha256((root / "probe.json").read_bytes()).hexdigest())
            self.assertFalse(out["full_security_passed"])

    def test_不接受低口径及哈希漂移并清除陈旧证据(self):
        for mode in ("severity", "hash", "missing"):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.prepare(root)
                (root / "out.json").write_text("stale green")
                if mode == "hash":
                    (root / "bom.json").write_bytes((root / "bom.json").read_bytes() + b"\n")
                elif mode == "missing":
                    (root / "probe.json").unlink()
                self.assertEqual(self.invoke(root, "LOW" if mode == "severity" else "HIGH,CRITICAL"), 1)
                self.assertFalse((root / "out.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
