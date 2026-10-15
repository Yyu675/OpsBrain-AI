#!/usr/bin/env python3
"""批116三组补丁验收，不替代全量安全门禁，不豁免其他发现。"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

from sbom_runtime_probe import components
from trivy_summary import escape

VERSIONS = {"org.apache.tika": "3.2.2", "io.netty": "4.1.137.Final", "org.apache.tomcat.embed": "10.1.58"}
REQUIRED = {"org.apache.tika:tika-core", "org.apache.tika:tika-parser-pdf-module",
            "io.netty:netty-handler", "io.netty:netty-codec-http", "io.netty:netty-codec-http2",
            "org.apache.tomcat.embed:tomcat-embed-core", "org.apache.tomcat.embed:tomcat-embed-websocket"}


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
        selected.append({"name": name, "version": version, "type": kind, "classifier": classifier})
    target_findings = [f for f in experiment["findings"] if f.get("PkgName", "").split(":")[0] in VERSIONS
                       and f.get("Severity") in ("HIGH", "CRITICAL")]
    if target_findings:
        raise ValueError("三组仍有HIGH/CRITICAL：" + json.dumps(target_findings, ensure_ascii=False))
    return {"schema_version": 1, "target_groups_passed": True, "versions": VERSIONS,
            "verified_variants": sorted(selected, key=lambda v: (v["name"], v["classifier"])),
            "target_hc_rows": 0, "remaining_hc_rows": len(experiment["findings"]),
            "native_gate_rc": experiment.get("native_gate_rc"), "full_security_passed": False,
            "notice": "仅本批Tika/Netty/Tomcat通过；其他HC与Boot额外库覆盖仍待处置，不作为安全/灰度放行"}


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
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as e:
        print("::error::三组运行时补丁验收失败：" + escape(e))
        return 1
    print("::notice::三组运行时补丁通过：" + escape(json.dumps({k: v for k, v in result.items() if k != "verified_variants"}, ensure_ascii=False)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
