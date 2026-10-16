#!/usr/bin/env python3
"""官方Central实际POM/JAR供应取证；不是Maven传递解析或安全放行证明。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile

from trivy_summary import escape
from verify_runtime_patches import evidence_frames

BASE = "https://repo.maven.apache.org/maven2/"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fetch(url):
    try:
        with urlopen(url, timeout=30) as response:
            if response.status != 200:
                raise ValueError(f"非200响应：{url}: {response.status}")
            body = response.read(64 * 1024 * 1024 + 1)
            if len(body) > 64 * 1024 * 1024:
                raise ValueError("响应超过64MiB")
            return 200, body
    except HTTPError as e:
        if e.code == 404:
            return 404, b""
        raise


def tomcat_versions(get=fetch):
    url = BASE + "org/apache/tomcat/embed/tomcat-embed-core/maven-metadata.xml"
    status, body = get(url)
    if status != 200:
        raise ValueError("Tomcat版本元数据不可用，不能猜最新发布")
    root = ET.fromstring(body)
    if root.findtext("groupId") != "org.apache.tomcat.embed" or root.findtext("artifactId") != "tomcat-embed-core":
        raise ValueError("Tomcat元数据坐标错误")
    versions = sorted({v.text for v in root.findall("./versioning/versions/version")
                       if v.text and re.fullmatch(r"10\.1\.\d+", v.text) and int(v.text.split(".")[-1]) <= 58},
                      key=lambda v: int(v.split(".")[-1]))
    if not versions:
        raise ValueError("缺Tomcat10.1稳定发布记录")
    return {"url": url, "sha256": hashlib.sha256(body).hexdigest(), "last_versions_up_to_10_1_58": versions[-8:]}


def probe(group, artifact, version, packaging="jar", get=fetch):
    if packaging not in ("jar", "pom") or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", v) for v in (group, artifact, version)):
        raise ValueError("非法候选坐标")
    prefix = BASE + group.replace(".", "/") + f"/{artifact}/{version}/{artifact}-{version}"
    files = []
    for ext in (("pom", "jar") if packaging == "jar" else ("pom",)):
        url = prefix + "." + ext
        status, body = get(url)
        if status not in (200, 404):
            raise ValueError("不能把非404故障当构件未发布")
        row = {"url": url, "http_status": status}
        if status == 200:
            if ext == "pom":
                root = ET.fromstring(body)
                # Maven合法POM可省略XML namespace；只接受标准namespace或无namespace，不接受任意XML。
                if root.tag not in ("project", "{" + NS["m"] + "}project"):
                    raise ValueError(f"下载POM根元素非法：{url}: {root.tag}")
                ns = "{" + NS["m"] + "}" if root.tag.startswith("{") else ""
                actual = tuple(root.findtext(ns + k) or (root.findtext(ns + "parent/" + ns + k) if k != "artifactId" else None)
                               for k in ("groupId", "artifactId", "version"))
                if actual != (group, artifact, version):
                    raise ValueError(f"下载POM坐标不符：{url}: 实际{actual}，预期{(group, artifact, version)}")
            else:
                with zipfile.ZipFile(io.BytesIO(body)) as jar:
                    if "META-INF/MANIFEST.MF" not in jar.namelist() or not any(n.endswith(".class") for n in jar.namelist()):
                        raise ValueError("JAR缺manifest或class，不能把错误页当构件")
            row.update({"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
        files.append(row)
    return {"coordinate": f"{group}:{artifact}:{version}", "packaging": packaging,
            "availability": "available" if all(f["http_status"] == 200 for f in files) else "not_found", "files": files}


def inspect(get=fetch):
    metadata = tomcat_versions(get)
    # 明确补丁候选58；另核元数据内最后两个稳定版本，允许发现可交付的部分减量方案，不自动改POM。
    versions = sorted(set(["10.1.58", *metadata["last_versions_up_to_10_1_58"][-2:]]), key=lambda v: int(v.split(".")[-1]))
    candidates = [probe("org.apache.tomcat.embed", "tomcat-embed-" + artifact, version, get=get)
                  for version in versions for artifact in ("core", "el", "websocket")]
    for group, artifact, version, kind in (("io.micrometer", "micrometer-bom", "1.15.12", "pom"),
                                          ("org.apache.opennlp", "opennlp-tools", "2.5.9", "jar"),
                                          ("org.jsoup", "jsoup", "1.23.2", "jar")):
        candidates.append(probe(group, artifact, version, kind, get))
    # BC版本族同升；不仅核bcprov，PKIX/ASN.1工具也须有正式供应，不能只改扫描器可见版本。
    for artifact in ("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on", "bcjmail-jdk18on"):
        candidates.append(probe("org.bouncycastle", artifact, "1.85", get=get))
    candidates.append(probe("com.github.junrar", "junrar", "7.6.1", get=get))
    # 批120：同6.2维护线；完整Framework族（包括BOM）实际供应，不引Spring7/Boot4。
    for artifact in ("spring-aop", "spring-aspects", "spring-beans", "spring-context", "spring-context-support",
                     "spring-core", "spring-expression", "spring-jcl", "spring-jdbc", "spring-messaging",
                     "spring-orm", "spring-oxm", "spring-test", "spring-tx", "spring-web", "spring-webmvc", "spring-websocket"):
        candidates.append(probe("org.springframework", artifact, "6.2.19", get=get))
    candidates.append(probe("org.springframework", "spring-framework-bom", "6.2.19", "pom", get))
    return {"schema_version": 1, "repository": BASE, "tomcat_metadata": metadata, "candidates": candidates,
            "boundary": "200且有效POM/JAR只证明本轮可获取，不证明传递解析/二进制兼容/漏洞已修；404保留，网络/坏内容失败，不自动选版本或加豁免"}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--json", required=True, type=Path)
    ap.add_argument("--checkout-sha", required=True)
    args = ap.parse_args()
    try:
        args.json.unlink(missing_ok=True)
        if not re.fullmatch(r"[0-9a-f]{40}", args.checkout_sha):
            raise ValueError("需完整checkout SHA")
        result = inspect()
        result["checkout_sha"] = args.checkout_sha
        payload = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode()
        frames = evidence_frames(payload)
        args.json.write_bytes(payload)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile) as e:
        print("::error::Central补丁供应取证失败：" + escape(e))
        return 2
    for frame in frames:
        print("::notice::Central补丁供应压缩JSON：" + json.dumps(frame))
    print("::notice::Central候选供应（非修复证明）：" + escape(json.dumps({c["coordinate"]: c["availability"] for c in result["candidates"]})))
    return 0


if __name__ == "__main__":
    sys.exit(main())
