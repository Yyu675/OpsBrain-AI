#!/usr/bin/env python3
import contextlib
import io
import hashlib
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
        binary = jar()
        result = probe.probe("x", "a", "1", get=lambda url: (200, pom() if url.endswith(".pom") else binary))
        self.assertEqual(result["availability"], "available")
        self.assertEqual(len(result["files"]), 2)
        self.assertEqual(result["files"][0]["sha256"], hashlib.sha256(pom()).hexdigest())
        self.assertEqual(result["files"][1]["sha256"], hashlib.sha256(binary).hexdigest())
        self.assertTrue(all(len(f["sha256"]) == 64 and f["bytes"] > 0 for f in result["files"]))

    def test_404必须保留而不是升级成功(self):
        result = probe.probe("x", "a", "1", get=lambda url: (404, b"") if url.endswith(".jar") else (200, pom()))
        self.assertEqual(result["availability"], "not_found")
        self.assertEqual(result["files"][1]["http_status"], 404)

    def test_错误页与错坐标不能冒充供应(self):
        for body in (b"<html>404</html>", pom(version="2")):
            with self.assertRaises(ValueError):
                probe.probe("x", "a", "1", get=lambda url: (200, body if url.endswith(".pom") else jar()))
        with self.assertRaises(zipfile.BadZipFile):
            probe.probe("x", "a", "1", get=lambda url: (200, pom() if url.endswith(".pom") else b"html error"))

    def test_空ZIP也不能冒充有效JAR(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w"):
            pass
        with self.assertRaises(ValueError):
            probe.probe("x", "a", "1", get=lambda url: (200, pom() if url.endswith(".pom") else out.getvalue()))

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

    def test_无namespace合法POM仍必须严格验坐标(self):
        for body in (b'<project><groupId>x</groupId><artifactId>a</artifactId><version>1</version></project>',
                     b'<project><parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>a</artifactId></project>'):
            result = probe.probe("x", "a", "1", "pom", lambda url: (200, body))
            self.assertEqual(result["availability"], "available")
            with self.assertRaisesRegex(ValueError, "坐标不符"):
                probe.probe("x", "a", "2", "pom", lambda url: (200, body))
        with self.assertRaisesRegex(ValueError, "根元素非法"):
            probe.probe("x", "a", "1", "pom", lambda url: (200, b'<project xmlns="urn:wrong"><groupId>x</groupId><artifactId>a</artifactId><version>1</version></project>'))

    def test_BC必须逐模块实取POM和JAR(self):
        metadata = {"last_versions_up_to_10_1_58": ["10.1.57"]}
        with mock.patch.object(probe, "tomcat_versions", return_value=metadata), mock.patch.object(probe, "probe", return_value={}) as call:
            probe.inspect()
        bc = [c.args for c in call.call_args_list if c.args[0] == "org.bouncycastle"]
        self.assertEqual(bc, [("org.bouncycastle", a, "1.85") for a in ("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on")])

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
