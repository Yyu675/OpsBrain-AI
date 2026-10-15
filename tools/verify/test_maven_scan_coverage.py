#!/usr/bin/env python3
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from maven_scan_coverage import compare, main


def node(name="a", version="1", scope="compile", **kwargs):
    return {"groupId": "example", "artifactId": name, "version": version,
            "scope": scope, "type": "jar", **kwargs}


def tree():
    return node("app", children=[node("a", children=[node("b", scope="runtime")])])


def report(*packages):
    if not packages:
        packages = [("a", "1"), ("b", "1")]
    return {"SchemaVersion": 2, "ArtifactName": ".", "Results": [{
        "Target": "pom.xml", "Type": "pom", "Packages": [
            {"Name": "example:" + name, "Version": version} for name, version in packages]}]}


class TestCoverage(unittest.TestCase):
    def test_逐坐标覆盖而非数量相同(self):
        self.assertTrue(compare(tree(), report())["coverage_complete"])
        result = compare(tree(), report(("a", "1"), ("c", "1")))
        self.assertFalse(result["coverage_complete"])
        self.assertEqual(result["counts"]["matched_gavs"], 1)
        self.assertEqual(result["missing"], [{"name": "example:b", "version": "1"}])

    def test_同名不同版本不能充数(self):
        result = compare(tree(), report(("a", "2"), ("b", "1")))
        self.assertEqual(result["version_mismatches"], [{"name": "example:a", "expected": ["1"], "scanned": ["2"]}])
        self.assertFalse(result["coverage_complete"])

    def test_正确版本之外的额外版本不作漏扫(self):
        result = compare(tree(), report(("a", "1"), ("a", "2"), ("b", "1")))
        self.assertTrue(result["coverage_complete"])
        self.assertFalse(result["exact_gav_match"])
        self.assertEqual(result["counts"]["extra_gavs"], 1)

    def test_根项目不属于外部依赖(self):
        result = compare(tree(), report(("a", "1"), ("b", "1"), ("app", "1")))
        self.assertTrue(result["exact_gav_match"])

    def test_排除testprovidedsystem整个子树(self):
        model = tree()
        for scope in ("test", "provided", "system", "import"):
            model["children"].append(node(scope, scope=scope, children=[node("test-child")]))
        self.assertEqual(compare(model, report())["counts"]["runtime_gavs"], 2)

    def test_optional直依赖不可豁免(self):
        model = tree()
        model["children"].append(node("optional", optional=True))
        self.assertFalse(compare(model, report())["coverage_complete"])

    def test_classifier记录但只宣称GAV覆盖(self):
        model = tree()
        model["children"].append(node("a", classifier="linux"))
        result = compare(model, report())
        self.assertEqual(result["counts"]["runtime_variants"], 3)
        self.assertEqual(result["counts"]["runtime_gavs"], 2)
        self.assertIn("字节", result["boundary"])

    def test_重复路径不能膨胀覆盖(self):
        model = tree()
        model["children"].append(node("a"))
        self.assertEqual(compare(model, report())["counts"]["runtime_gavs"], 2)

    def test_空基线不是百分百(self):
        with self.assertRaisesRegex(ValueError, "0/0"):
            compare(node("app"), report())

    def test_无包无目标及重复目标必须失败(self):
        for change in ("empty", "absent", "duplicate", "wrong-type"):
            doc = report()
            if change == "empty":
                doc["Results"][0]["Packages"] = []
            elif change == "absent":
                doc["Results"] = []
            elif change == "duplicate":
                doc["Results"] *= 2
            else:
                doc["Results"][0]["Type"] = "npm"
            with self.assertRaises(ValueError):
                compare(tree(), doc)

    def test_前端包及漏洞Secret不参与且不泄露(self):
        doc = report()
        doc["Results"].append({"Target": "package-lock.json", "Type": "npm", "Packages": [{"Name": "a", "Version": "1"}]})
        doc["Results"][0]["Secrets"] = [{"Match": "SECRET_NEVER_COPY"}]
        result = compare(tree(), doc)
        self.assertTrue(result["coverage_complete"])
        self.assertNotIn("SECRET_NEVER_COPY", json.dumps(result))

    def test_未知scope与未解析版本及坏结构失败(self):
        bad = [node("app", children=[node(scope="future")]),
               node("app", children=[node(version="${version}")]),
               node("app", children={}), [], node("app", children=[None])]
        for doc in bad:
            with self.assertRaises(ValueError):
                compare(doc, report())

    def test_输入数据不变(self):
        model, doc = tree(), report()
        before = copy.deepcopy((model, doc))
        compare(model, doc)
        self.assertEqual((model, doc), before)

    def invoke(self, root, strict=False):
        argv = ["coverage", "--tree", str(root / "tree.json"), "--report", str(root / "report.json"),
                "--pom", str(root / "pom.xml"), "--json", str(root / "audit.json"), "--checkout-sha", "a" * 40]
        if strict:
            argv.append("--strict")
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as stream:
            rc = main()
        return rc, stream.getvalue()

    def prepare(self, root):
        (root / "tree.json").write_text(json.dumps(tree()))
        (root / "report.json").write_text(json.dumps(report(("a", "1"))))
        (root / "pom.xml").write_text("project-input")

    def test_审计模式warning不替代漏洞门禁且strict可红(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.prepare(root)
            rc, output = self.invoke(root)
            self.assertEqual(rc, 0)
            self.assertIn("::warning::", output)
            self.assertEqual(json.loads((root / "audit.json").read_text())["status"], "incomplete")
            self.assertEqual(self.invoke(root, True)[0], 1)

    def test_三份输入哈希绑定(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.prepare(root)
            self.invoke(root)
            result = json.loads((root / "audit.json").read_text())
            for name, file in (("tree", "tree.json"), ("report", "report.json"), ("pom", "pom.xml")):
                self.assertEqual(result["sha256"][name], hashlib.sha256((root / file).read_bytes()).hexdigest())

    def test_缺输入或坏JSON清除旧绿证据且失败(self):
        for missing in (False, True):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.prepare(root)
                (root / "audit.json").write_text('{"status":"complete"}')
                if missing:
                    (root / "tree.json").unlink()
                else:
                    (root / "tree.json").write_text("broken")
                rc, output = self.invoke(root)
                self.assertEqual(rc, 1)
                self.assertIn("::error::", output)
                self.assertFalse((root / "audit.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
