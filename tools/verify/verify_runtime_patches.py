#!/usr/bin/env python3
"""批116/117/118/119/120/121补丁验收：完成组清零（含Boot/Data），Tomcat九条及Spring三条定向修补，不替代全量安全门禁，不豁免其他发现。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import zlib

from sbom_runtime_probe import components
from trivy_summary import escape

VERSIONS = {"org.apache.tika": "3.2.2", "io.netty": "4.1.137.Final",
            "io.micrometer": "1.15.12", "org.apache.opennlp": "2.5.9", "org.jsoup": "1.23.2",
            "org.apache.tomcat.embed": "10.1.57", "org.bouncycastle": "1.85", "com.github.junrar": "7.6.1",
            "org.springframework": "6.2.19", "org.springframework.boot": "3.5.15", "org.springframework.data": "3.5.12"}
ZERO_HC_GROUPS = set(VERSIONS) - {"org.apache.tomcat.embed", "org.springframework"}
# 正向列出本次必须关闭的9条；不是剩余漏洞忽略表，原生报告/门禁完整保留。
TOMCAT_REMEDIATED = {"CVE-2026-41293", "CVE-2026-43512", "CVE-2026-43515", "CVE-2026-29129",
                     "CVE-2026-34483", "CVE-2026-34487", "CVE-2026-41284", "CVE-2026-42498", "CVE-2026-43513"}
SPRING_REMEDIATED = {"CVE-2026-41850", "CVE-2026-41842", "CVE-2026-41845"}
SPRING_MODULES = {"spring-aop", "spring-aspects", "spring-beans", "spring-context", "spring-context-support",
                  "spring-core", "spring-expression", "spring-jcl", "spring-jdbc", "spring-messaging",
                  "spring-orm", "spring-oxm", "spring-tx", "spring-web", "spring-webmvc", "spring-websocket"}
BOOT_MODULES = {"spring-boot", "spring-boot-actuator", "spring-boot-actuator-autoconfigure", "spring-boot-autoconfigure",
                "spring-boot-starter", "spring-boot-starter-actuator", "spring-boot-starter-aop",
                "spring-boot-starter-data-jdbc", "spring-boot-starter-data-jpa", "spring-boot-starter-data-redis",
                "spring-boot-starter-jdbc", "spring-boot-starter-json", "spring-boot-starter-logging",
                "spring-boot-starter-tomcat", "spring-boot-starter-validation", "spring-boot-starter-web", "spring-boot-starter-websocket"}
DATA_MODULES = {"spring-data-commons", "spring-data-jdbc", "spring-data-jpa",
                "spring-data-keyvalue", "spring-data-redis", "spring-data-relational"}
REQUIRED = {"com.github.junrar:junrar", "org.bouncycastle:bcjmail-jdk18on", "org.bouncycastle:bcprov-jdk18on", "org.bouncycastle:bcpkix-jdk18on", "org.bouncycastle:bcutil-jdk18on",
            "org.apache.tika:tika-core", "org.apache.tika:tika-parser-pdf-module",
            "io.netty:netty-handler", "io.netty:netty-codec-http", "io.netty:netty-codec-http2",
            "io.micrometer:micrometer-core", "io.micrometer:micrometer-registry-prometheus", "io.micrometer:micrometer-registry-otlp",
            "org.apache.opennlp:opennlp-tools", "org.jsoup:jsoup",
            "org.apache.tomcat.embed:tomcat-embed-core", "org.apache.tomcat.embed:tomcat-embed-el", "org.apache.tomcat.embed:tomcat-embed-websocket"}

REQUIRED |= {"org.springframework:" + name for name in SPRING_MODULES}
REQUIRED |= {"org.springframework.boot:" + name for name in BOOT_MODULES}
REQUIRED |= {"org.springframework.data:" + name for name in DATA_MODULES}


def verify(bom, experiment):
    registry = components(bom)
    if experiment.get("schema_version") != 1 or not isinstance(experiment.get("findings"), list):
        raise ValueError("缺合法原生扫描实验结果")
    counts = experiment.get("counts", {})
    if any(counts.get(key) != 0 for key in ("runtime_missing_from_sbom", "sbom_missing_from_scan")):
        raise ValueError("原生SBOM/扫描缺覆盖，不能作补丁通过证据")
    missing = REQUIRED - {v[0] for v in registry}
    if missing:
        raise ValueError("缺必需目标模块：" + ", ".join(sorted(missing)))
    libraries = experiment.get("packaged_libraries")
    if not isinstance(libraries, list) or not libraries:
        raise ValueError("缺实际Boot内嵌库字节证据，不能仅凭SBOM验收")
    packaged = {tuple(row["variant"]): row["sha256"] for row in libraries if "variant" in row}
    selected = []
    for name, version, kind, classifier in registry:
        group = name.split(":")[0]
        if group not in VERSIONS:
            continue
        # tcnative有独立2.x版本流，随Netty BOM对齐；只排除版本相等断言，不豁免漏洞。
        if not name.startswith("io.netty:netty-tcnative") and version != VERSIONS[group]:
            raise ValueError(f"补丁模块混版/回退：{name}:{version}，需{VERSIONS[group]}")
        if packaged.get((name, version, kind, classifier)) != registry[(name, version, kind, classifier)]:
            raise ValueError(f"补丁未绑定实际Boot字节：{name}:{version}:{classifier}")
        selected.append({"name": name, "version": version, "type": kind, "classifier": classifier, "sha256": registry[(name, version, kind, classifier)], "byte_matched_packaged": True})
    target_findings = [f for f in experiment["findings"] if f.get("PkgName", "").split(":")[0] in ZERO_HC_GROUPS
                       and f.get("Severity") in ("HIGH", "CRITICAL")]
    tomcat_findings = [f for f in experiment["findings"] if f.get("PkgName", "").startswith("org.apache.tomcat.embed:")
                      and f.get("Severity") in ("HIGH", "CRITICAL")]
    if any(f.get("VulnerabilityID") in TOMCAT_REMEDIATED for f in tomcat_findings):
        raise ValueError("Tomcat本次承诺修补的9条仍有残留")
    spring_findings = [f for f in experiment["findings"] if f.get("PkgName", "").startswith("org.springframework:")
                       and f.get("Severity") in ("HIGH", "CRITICAL")]
    if any(f.get("VulnerabilityID") in SPRING_REMEDIATED for f in spring_findings):
        raise ValueError("Spring本次承诺修补的3条仍有残留")
    if target_findings:
        raise ValueError("完成组仍有HIGH/CRITICAL：" + json.dumps(target_findings, ensure_ascii=False))
    return {"schema_version": 1, "target_groups_passed": True, "versions": VERSIONS,
            "verified_variants": sorted(selected, key=lambda v: (v["name"], v["classifier"])),
            "verified_variants_count": len(selected), "target_hc_rows": 0, "zero_hc_groups": sorted(ZERO_HC_GROUPS),
            "partial_target_hc_rows": len(tomcat_findings) + len(spring_findings),
            "partial_targets": {"org.apache.tomcat.embed": {"required_absent_cves": sorted(TOMCAT_REMEDIATED), "remaining_findings": tomcat_findings}, "org.springframework": {"required_absent_cves": sorted(SPRING_REMEDIATED), "remaining_findings": spring_findings}}, "remaining_hc_rows": len(experiment["findings"]),
            "native_gate_rc": experiment.get("native_gate_rc"), "full_security_passed": False,
            "notice": "Tika/Netty/Micrometer/OpenNLP/jsoup/BC/Junrar/Boot/Data完成组清零；Tomcat仅验九条、Spring仅验三条关闭，剩余发现完整保留；非整体安全/灰度放行"}


def evidence_frames(payload):
    encoded = base64.b64encode(zlib.compress(payload)).decode()
    chunks = [encoded[i:i + 2700] for i in range(0, len(encoded), 2700)]
    if len(chunks) > 6:
        raise ValueError("补丁证据超过注解容量，不能无证据绿")
    return [{"part": i, "parts": len(chunks), "codec": "zlib+base64",
             "sha256": hashlib.sha256(payload).hexdigest(), "data": chunk} for i, chunk in enumerate(chunks, 1)]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for key in ("sbom", "experiment", "json"):
        ap.add_argument("--" + key, required=True, type=Path)
    ap.add_argument("--scan-severity", required=True)
    args = ap.parse_args()
    try:
        if len({p.resolve() for p in (args.sbom, args.experiment, args.json)}) != 3:
            raise ValueError("输入/输出必须不同")
        args.json.unlink(missing_ok=True)
        if not {"HIGH", "CRITICAL"} <= set(args.scan_severity.split(",")):
            raise ValueError("补丁验收必须包含HIGH与CRITICAL，不能用较低扫描口径冒充")
        source = args.sbom.read_bytes()
        probe = args.experiment.read_bytes()
        data = json.loads(probe)
        if hashlib.sha256(source).hexdigest() != data.get("input_sha256", {}).get("sbom"):
            raise ValueError("SBOM与实验输入SHA256不匹配")
        result = verify(json.loads(source), data)
        result["checkout_sha"] = data["checkout_sha"]
        result["sha256"] = {"sbom": hashlib.sha256(source).hexdigest(), "experiment": hashlib.sha256(probe).hexdigest()}
        payload = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode()
        frames = evidence_frames(payload)
        args.json.write_bytes(payload)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as e:
        print("::error::运行时补丁验收失败：" + escape(e))
        return 1
    for frame in frames:
        print("::notice::运行时补丁证据压缩JSON：" + json.dumps(frame))
    print("::notice::运行时补丁通过：" + escape(json.dumps({k: v for k, v in result.items() if k != "verified_variants"}, ensure_ascii=False)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
