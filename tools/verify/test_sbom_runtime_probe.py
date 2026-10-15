#!/usr/bin/env python3
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import zlib
from contextlib import redirect_stdout

from sbom_runtime_probe import components, emit, inspect, maven_purl


def fixtures():
    library = b"explicit fake library bytes, not a real Maven binary"
    tree = {"groupId": "x", "artifactId": "app", "version": "1", "children": [
        {"groupId": "x", "artifactId": "a", "version": "1", "type": "jar", "scope": "compile"}]}
    component = {"group": "x", "name": "a", "version": "1", "purl": "pkg:maven/x/a@1?type=jar",
                 "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(library).hexdigest()}]}
    bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [component]}
    report = {"SchemaVersion": 2, "ArtifactName": "fixture", "Results": [{"Target": "Java", "Type": "jar",
        "Packages": [{"Name": "x:a", "Version": "1"}], "Vulnerabilities": [
            {"VulnerabilityID": "CVE-PROBE", "PkgName": "x:a", "InstalledVersion": "1", "Severity": "HIGH", "Status": "fixed", "FixedVersion": "2"}]}]}
    return library, tree, bom, report


class TestSBOMProbe(unittest.TestCase):
    def run_probe(self, tree, bom, report, gate=None, rc=1, libs=None):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "app.jar"
            with zipfile.ZipFile(jar, "w") as out:
                for name, value in (libs or {"a-1.jar": fixtures()[0]}).items():
                    out.writestr("BOOT-INF/lib/" + name, value)
            return inspect(tree, bom, report, gate if gate is not None else copy.deepcopy(report), rc, jar)

    def test_真实字节摘要与逐GAV一致才成为候选(self):
        _, tree, bom, report = fixtures()
        out = self.run_probe(tree, bom, report)
        self.assertTrue(out["input_candidate_complete"])
        self.assertFalse(out["production_gate_replaced"])
        self.assertEqual(out["counts"]["fixed_rows"], 1)
        self.assertEqual(out["native_gate_rc"], 1)

    def test_同文件名不同字节不可冒充(self):
        _, tree, bom, report = fixtures()
        out = self.run_probe(tree, bom, report, libs={"a-1.jar": b"different bytes"})
        self.assertFalse(out["input_candidate_complete"])
        self.assertEqual(out["counts"]["packaged_unknown"], 1)

    def test_重命名但相同字节能绑定无需猜坐标(self):
        library, tree, bom, report = fixtures()
        out = self.run_probe(tree, bom, report, libs={"renamed.jar": library})
        self.assertEqual(out["counts"]["packaged_hash_matched"], 1)

    def test_Boot额外工具库必须显式暴露(self):
        library, tree, bom, report = fixtures()
        out = self.run_probe(tree, bom, report, libs={"a-1.jar": library, "boot-added.jar": b"additional library"})
        self.assertFalse(out["input_candidate_complete"])
        self.assertEqual(out["packaged_unknown"][0]["path"], "BOOT-INF/lib/boot-added.jar")

    def test_打包排除不偷改Maven分母(self):
        _, tree, bom, report = fixtures()
        tree["children"].append({"groupId": "x", "artifactId": "lombok", "version": "1", "type": "jar", "scope": "compile"})
        component = copy.deepcopy(bom["components"][0])
        component.update({"name": "lombok", "purl": "pkg:maven/x/lombok@1"})
        component["hashes"][0]["content"] = "0" * 64
        bom["components"].append(component)
        report["Results"][0]["Packages"].append({"Name": "x:lombok", "Version": "1"})
        out = self.run_probe(tree, bom, report)
        self.assertEqual(out["counts"]["runtime_gavs"], 2)
        self.assertIn(["x:lombok", "1", "jar", ""], out["runtime_not_packaged"])

    def test_同GAV错误classifier不能充variant(self):
        _, tree, bom, report = fixtures()
        bom["components"][0]["purl"] += "&classifier=linux"
        out = self.run_probe(tree, bom, report)
        self.assertEqual(out["counts"]["runtime_missing_from_sbom"], 1)
        self.assertFalse(out["input_candidate_complete"])

    def test_同名错误扫描版本不能充GAV覆盖(self):
        _, tree, bom, report = fixtures()
        report["Results"][0]["Packages"][0]["Version"] = "2"
        out = self.run_probe(tree, bom, report)
        self.assertEqual(out["counts"]["sbom_missing_from_scan"], 1)

    def test_吞失败码或丢发现必须失败(self):
        _, tree, bom, report = fixtures()
        for rc in (0, 7):
            with self.assertRaisesRegex(ValueError, "门禁"):
                self.run_probe(tree, bom, report, rc=rc)
        gate = copy.deepcopy(report)
        gate["Results"][0]["Vulnerabilities"] = []
        with self.assertRaisesRegex(ValueError, "门禁"):
            self.run_probe(tree, bom, report, gate=gate)

    def test_无fixed时合法零码但坏gate不绿(self):
        _, tree, bom, report = fixtures()
        report["Results"][0]["Vulnerabilities"] = []
        self.assertEqual(self.run_probe(tree, bom, report, rc=0)["native_gate_rc"], 0)
        with self.assertRaises(ValueError):
            self.run_probe(tree, bom, report, gate={}, rc=0)

    def test_SBOM缺哈希或假坐标及重复组件拒绝(self):
        for mode in ("hash", "name", "duplicate"):
            _, _, bom, _ = fixtures()
            if mode == "hash":
                bom["components"][0]["hashes"] = []
            elif mode == "name":
                bom["components"][0]["name"] = "wrong"
            else:
                bom["components"] *= 2
            with self.assertRaises(ValueError):
                components(bom)

    def test_PURL百分号解码与重复参数拒绝(self):
        self.assertEqual(maven_purl("pkg:maven/x/a@1%2B2?classifier=linux&type=jar"), ("x:a", "1+2", "jar", "linux"))
        for value in ("pkg:npm/a@1", "pkg:maven/a@1", "pkg:maven/x/a", "pkg:maven/x/a@1?type=jar&type=war"):
            with self.assertRaises(ValueError):
                maven_purl(value)

    def test_分片回放与文件哈希相符(self):
        payload = json.dumps({"findings": [{"VulnerabilityID": "CVE-PROBE"}]}).encode()
        with redirect_stdout(io.StringIO()) as stream:
            emit(payload)
        frames = [json.loads(s.split("实验压缩JSON：", 1)[1]) for s in stream.getvalue().splitlines()]
        self.assertTrue(all(len(json.dumps(s).encode()) < 4000 for s in frames))
        data = zlib.decompress(base64.b64decode("".join(s["data"] for s in frames)))
        self.assertEqual(data, payload)
        self.assertEqual(frames[0]["sha256"], hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
