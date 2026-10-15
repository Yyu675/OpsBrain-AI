#!/usr/bin/env python3
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError
import zipfile

import maven_patch_availability as probe


def pom(group="x", artifact="a", version="1"):
    return f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>'.encode()


def jar():
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        z.writestr("x/A.class", b"explicit fake class fixture, not real Java bytecode")
    return out.getvalue()


class TestAvailability(unittest.TestCase):
    def test_POM和实际JAR都可获取才算available(self):
        result = probe.probe("x", "a", "1", get=lambda url: (200, pom() if url.endswith(".pom") else jar()))
        self.assertEqual(result["availability"], "available")
        self.assertEqual(len(result["files"]), 2)
        self.assertTrue(all(len(f["sha256"]) == 64 and f["bytes"] > 0 for f in result["files"]))

    def test_404必须保留而不是升级成功(self):
        result = probe.probe("x", "a", "1", get=lambda url: (404, b"") if url.endswith(".jar") else (200, pom()))
        self.assertEqual(result["availability"], "not_found")
        self.assertEqual(result["files"][1]["http_status"], 404)

    def test_错误页与错坐标不能冒充供应(self):
        for body in (b"<html>404</html>", pom(version="2")):
            with self.assertRaises(ValueError):
                probe.probe("x", "a", "1", get=lambda url: (200, body))
        with self.assertRaises(zipfile.BadZipFile):
            probe.probe("x", "a", "1", get=lambda url: (200, pom() if url.endswith(".pom") else b"html error"))

    def test_网络和服务故障不能伪装未发布(self):
        with self.assertRaises(ValueError):
            probe.probe("x", "a", "1", get=lambda url: (503, b""))
        with mock.patch.object(probe, "urlopen", side_effect=HTTPError("url", 503, "down", {}, None)):
            with self.assertRaises(HTTPError):
                probe.fetch("https://example.invalid")
        with mock.patch.object(probe, "urlopen", side_effect=HTTPError("url", 404, "missing", {}, None)):
            self.assertEqual(probe.fetch("https://example.invalid"), (404, b""))

    def test_元数据限定同线稳定版本不跨11或猜不存在版本(self):
        xml = b'<metadata><groupId>org.apache.tomcat.embed</groupId><artifactId>tomcat-embed-core</artifactId><versioning><versions><version>11.0.25</version><version>10.1.54</version><version>10.1.58-RC1</version><version>10.1.59</version><version>10.1.9</version></versions></versioning></metadata>'
        result = probe.tomcat_versions(lambda url: (200, xml))
        self.assertEqual(result["last_versions_up_to_10_1_58"], ["10.1.9", "10.1.54"])
        with self.assertRaises(ValueError):
            probe.tomcat_versions(lambda url: (404, b""))

    def test_CLI网络失败响红且清除陈旧证据(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "probe.json"
            path.write_text("stale")
            with mock.patch.object(sys, "argv", ["probe", "--json", str(path), "--checkout-sha", "a" * 40]), mock.patch.object(probe, "inspect", side_effect=URLError("unreachable")), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(probe.main(), 2)
            self.assertFalse(path.exists())

    def test_CLI可获取和404同存不冒充修复(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "probe.json"
            source = {"candidates": [{"coordinate": "x:a:1", "availability": "not_found"}], "boundary": "not a fix"}
            with mock.patch.object(sys, "argv", ["probe", "--json", str(path), "--checkout-sha", "a" * 40]), mock.patch.object(probe, "inspect", return_value=source), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(probe.main(), 0)
            self.assertEqual(json.loads(path.read_text())["candidates"][0]["availability"], "not_found")


if __name__ == "__main__":
    unittest.main(verbosity=2)
