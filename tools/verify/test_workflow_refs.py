#!/usr/bin/env python3
"""递归引用闸自测：包括父 action 有效但内部引用消失（本次真故障）。"""
import base64
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from workflow_refs import Checker, NotFound, RefError, gh_api, uses

SHA = "a" * 40


def metadata(text):
    return {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}


class Api:
    def __init__(self):
        self.docs = {"repos/acme/parent/commits/v1": {"sha": SHA},
                     f"repos/acme/parent/contents/action.yaml?ref={SHA}": metadata(
                         "runs:\n  using: composite\n  steps:\n    - uses: acme/child@deleted\n")}
        self.calls = []

    def __call__(self, endpoint):
        self.calls.append(endpoint)
        if endpoint not in self.docs:
            raise NotFound(endpoint)
        value = self.docs[endpoint]
        if isinstance(value, Exception):
            raise value
        return value


class TestWorkflowRefs(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        directory = self.root / ".github/workflows"
        directory.mkdir(parents=True)
        (directory / "test.yml").write_text("jobs:\n  test:\n    steps:\n      - uses: acme/parent@v1\n")
        self.api = Api()
        self.checker = Checker(self.root, self.api)

    def child(self):
        self.api.docs["repos/acme/child/commits/deleted"] = {"sha": SHA}
        self.api.docs[f"repos/acme/child/contents/action.yml?ref={SHA}"] = metadata("runs:\n  using: node24\n  main: index.js")

    def test_外层存在内层丢失也必须红(self):
        errors = self.checker.check()
        self.assertEqual(len(errors), 1)
        self.assertIn("acme/parent@v1 ->", errors[0])
        self.assertIn("acme/child/commits/deleted", errors[0])

    def test_内层修好全链通过且yaml后缀回退(self):
        self.child()
        self.assertEqual(self.checker.check(), [])
        self.assertEqual(len(self.checker.done), 2)
        self.assertIn(f"repos/acme/parent/contents/action.yml?ref={SHA}", self.api.calls)
        self.assertIn(f"repos/acme/parent/contents/action.yaml?ref={SHA}", self.api.calls)

    def test_403不可当404吞掉(self):
        self.api.docs[f"repos/acme/parent/contents/action.yml?ref={SHA}"] = RefError("403 denied")
        errors = self.checker.check()
        self.assertIn("403", errors[0])
        self.assertNotIn(f"repos/acme/parent/contents/action.yaml?ref={SHA}", self.api.calls)

    def test_完整SHA与子目录action(self):
        self.api.docs[f"repos/acme/cache/commits/{SHA}"] = {"sha": SHA}
        self.api.docs[f"repos/acme/cache/contents/restore/action.yml?ref={SHA}"] = metadata("runs: {using: node24, main: index.js}")
        self.checker.visit(f"acme/cache/restore@{SHA}")
        self.assertEqual(len(self.checker.done), 1)

    def test_分支斜杠编码且读取固定commit(self):
        self.api.docs["repos/acme/cache/commits/release%2Fv1"] = {"sha": SHA}
        self.api.docs[f"repos/acme/cache/contents/action.yml?ref={SHA}"] = metadata("runs: {using: node24}")
        self.checker.visit("acme/cache@release/v1")
        self.assertEqual(len(self.checker.done), 1)

    def test_重复引用只查一次(self):
        self.child()
        self.checker.visit("acme/parent@v1")
        count = len(self.api.calls)
        self.checker.visit("acme/parent@v1")
        self.assertEqual(len(self.api.calls), count)

    def test_循环明确报错(self):
        self.child()
        self.api.docs[f"repos/acme/child/contents/action.yml?ref={SHA}"] = metadata("runs:\n  using: composite\n  steps:\n    - uses: acme/parent@v1")
        self.assertIn("循环", self.checker.check()[0])

    def test_元数据缺失失败(self):
        self.api.docs.pop(f"repos/acme/parent/contents/action.yaml?ref={SHA}")
        self.assertIn("元数据缺失", self.checker.check()[0])

    def test_本地action与越界(self):
        directory = self.root / ".github/actions/local"
        directory.mkdir(parents=True)
        (directory / "action.yml").write_text("runs: {using: composite, steps: []}")
        self.checker.visit("./.github/actions/local")
        self.assertEqual(len(self.checker.done), 1)
        with self.assertRaisesRegex(RefError, "越界"):
            self.checker.visit("./../../outside")

    def test_本地reusable_workflow继续递归(self):
        file = self.root / ".github/workflows/reuse.yml"
        file.write_text("jobs:\n  sub:\n    steps:\n      - uses: acme/parent@v1")
        self.child()
        self.checker.visit("./.github/workflows/reuse.yml")
        self.assertEqual(len(self.checker.done), 3)

    def test_reusable_workflow内部继续检查(self):
        self.api.docs["repos/acme/parent/contents/.github/workflows/reuse.yml?ref=" + SHA] = metadata(
            "jobs:\n  sub:\n    steps:\n      - uses: acme/child@deleted")
        self.child()
        self.checker.visit("acme/parent/.github/workflows/reuse.yml@v1")
        self.assertEqual(len(self.checker.done), 2)

    def test_空检查不假绿(self):
        (self.root / ".github/workflows/test.yml").write_text("jobs: {}")
        with self.assertRaisesRegex(RefError, "未找到"):
            self.checker.check()

    def test_不误扫run注释示例(self):
        self.assertEqual(uses({"jobs": {"a": {"steps": [{"run": "uses: fake/repo@missing"}]}}}), [])

    def test_表达式引用拒绝(self):
        with self.assertRaisesRegex(RefError, "静态"):
            uses({"jobs": {"a": {"uses": "${{ inputs.action }}"}}})

    def test_非法YAML失败(self):
        self.api.docs[f"repos/acme/parent/contents/action.yaml?ref={SHA}"] = metadata("runs: [unterminated")
        self.assertIn("非法 YAML", self.checker.check()[0])

    def test_docker镜像不冒充Git校验(self):
        with self.assertRaisesRegex(RefError, "镜像校验"):
            self.checker.visit("docker://alpine:latest")

    def test_真实API错误分类(self):
        with mock.patch("workflow_refs.subprocess.run") as run:
            run.return_value = mock.Mock(returncode=1, stdout="", stderr="HTTP 404")
            with self.assertRaises(NotFound):
                gh_api("repos/x")
            run.return_value = mock.Mock(returncode=1, stdout="", stderr="HTTP 403")
            with self.assertRaises(RefError) as error:
                gh_api("repos/x")
            self.assertNotIsInstance(error.exception, NotFound)
            run.return_value = mock.Mock(returncode=0, stdout="bad json", stderr="")
            with self.assertRaises(RefError):
                gh_api("repos/x")


if __name__ == "__main__":
    unittest.main(verbosity=2)
