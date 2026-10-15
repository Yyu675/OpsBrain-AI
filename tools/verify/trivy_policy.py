#!/usr/bin/env python3
"""从一次 Trivy 0.70 JSON 离线派生 ignore-unfixed 输入，交给 trivy convert 判退出码。

0.70.0 pkg/flag/vulnerability_flags.go：ignore-unfixed 只保留 Status=fixed，
不是按 FixedVersion 是否为空判断。Status=unknown 的零值可因 omitempty 缺省。
不改 Secrets/Misconfigurations/Licenses、包清单或任何元数据，不发网络请求。
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys

from trivy_summary import escape

STATUSES = {"unknown", "not_affected", "affected", "fixed", "under_investigation",
            "will_not_fix", "fix_deferred", "end_of_life"}


def derive(report, required_targets=()):
    if not isinstance(report, dict) or report.get("SchemaVersion") != 2:
        raise ValueError("需 Trivy SchemaVersion=2")
    if not isinstance(report.get("ArtifactName"), str) or not report["ArtifactName"]:
        raise ValueError("缺 ArtifactName")
    results = report.get("Results")
    if not isinstance(results, list) or not results:
        raise ValueError("缺扫描目标，拒绝空覆盖假绿")
    targets = []
    for result in results:
        if not isinstance(result, dict) or not isinstance(result.get("Target"), str):
            raise ValueError("非法扫描目标")
        targets.append(result["Target"])
        for field in ("Vulnerabilities", "Secrets", "Misconfigurations", "Licenses", "Packages"):
            items = result.get(field)
            if items is not None and (not isinstance(items, list) or any(not isinstance(x, dict) for x in items)):
                raise ValueError(f"{field} 必须为对象数组/null")
    missing = set(required_targets) - set(targets)
    if missing:
        raise ValueError("缺预期扫描目标：" + ", ".join(sorted(missing)))
    gate = copy.deepcopy(report)
    kept, dropped = 0, 0
    for result in gate["Results"]:
        if "Vulnerabilities" not in result or result["Vulnerabilities"] is None:
            continue
        selected = []
        for vuln in result["Vulnerabilities"]:
            status = vuln.get("Status", "unknown")
            if not isinstance(status, str) or status not in STATUSES:
                raise ValueError(f"不支持的漏洞 Status：{status!r}，需复核工具契约")
            if status == "fixed":
                selected.append(vuln)
                kept += 1
            else:
                dropped += 1
        result["Vulnerabilities"] = selected
    manifest = {"policy": "trivy-0.70.0-ignore-unfixed-status-fixed", "targets": targets,
                "kept_vulnerabilities": kept, "unfixed_vulnerabilities": dropped,
                "packages": sum(len(r.get("Packages") or []) for r in results),
                "notice": "同一原始报告离线派生；未删除 secrets/config/licenses，不代表依赖覆盖完整"}
    return gate, manifest


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    ap.add_argument("--manifest", required=True, type=Path)
    ap.add_argument("--require-target", action="append", default=[])
    args = ap.parse_args()
    try:
        paths = [p.resolve() for p in (args.input, args.output, args.manifest)]
        if len(set(paths)) != 3:
            raise ValueError("源报告、派生输入、manifest 必须是不同文件")
        # 清除旧的派生物，任何失败不能留上一次绿证据。
        args.output.unlink(missing_ok=True)
        args.manifest.unlink(missing_ok=True)
        source = args.input.read_bytes()
        gate, manifest = derive(json.loads(source), args.require_target)
        manifest["source_sha256"] = hashlib.sha256(source).hexdigest()
        payload = (json.dumps(gate, ensure_ascii=False, indent=2) + "\n").encode()
        manifest["derived_sha256"] = hashlib.sha256(payload).hexdigest()
        args.output.write_bytes(payload)
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError) as e:
        print(f"::error::Trivy 离线派生失败：{escape(e)}")
        return 1
    print(f"::notice::Trivy 同源门禁：{escape(json.dumps(manifest, ensure_ascii=False))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
