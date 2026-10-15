#!/usr/bin/env python3
"""将完整 Trivy JSON 压到 annotations；不替代 trivy 的 exit-code/ignore-unfixed 判据。"""
import argparse
import json
from pathlib import Path
import sys


def escape(value):
    return str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def summarize(report):
    if not isinstance(report, dict) or report.get("SchemaVersion") != 2 or "ArtifactName" not in report:
        raise ValueError("不是受支持的 Trivy JSON（需 SchemaVersion=2 与 ArtifactName）")
    results = report.get("Results") or []
    if not isinstance(results, list):
        raise ValueError("Results 必须是数组")
    findings, targets = [], []
    for result in results:
        targets.append(f"{result.get('Target', '?')} ({result.get('Type', result.get('Class', '?'))})")
        for vuln in result.get("Vulnerabilities") or []:
            if vuln.get("Severity") in ("HIGH", "CRITICAL"):
                findings.append((bool(vuln.get("FixedVersion")),
                                 f"{result.get('Target')}: {vuln.get('VulnerabilityID')} "
                                 f"{vuln.get('Severity')} {vuln.get('PkgName')} "
                                 f"{vuln.get('InstalledVersion')} -> {vuln.get('FixedVersion') or '无已知修复版本'}"))
        for key in ("Secrets", "Misconfigurations"):
            for finding in result.get(key) or []:
                if finding.get("Severity") in ("HIGH", "CRITICAL"):
                    findings.append((False, f"{result.get('Target')}: {key} "
                                     f"{finding.get('RuleID', finding.get('ID', '?'))} {finding.get('Severity')}"))
    fixed = sum(has_fix for has_fix, _ in findings)
    lines = [f"Trivy 报告：目标 {len(targets)}，HIGH/CRITICAL 条目 {len(findings)}（漏洞有修复版本 {fixed}）；"
             "是否拦截以扫描步退出码为准，完整 JSON 已留档"]
    lines.extend("扫描目标：" + target for target in targets)
    lines.extend(detail for _, detail in findings[:40])
    if len(findings) > 40:
        lines.append(f"其余 {len(findings) - 40} 条见完整 JSON")
    if not targets:
        lines.append("未列出扫描目标；不能据空报告推断依赖覆盖完整")
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--json", required=True, type=Path)
    ap.add_argument("--log", type=Path, help="CLI 诊断日志（不是报告匹配正文）")
    args = ap.parse_args()
    if args.log and args.log.is_file():
        diagnostic = args.log.read_text(encoding="utf-8", errors="replace")[-3000:]
        print(f"::notice::Trivy CLI 诊断：{escape(diagnostic)}")
    try:
        report = json.loads(args.json.read_text(encoding="utf-8"))
        lines = summarize(report)
    except (OSError, ValueError, TypeError, AttributeError) as e:
        print(f"::error::Trivy 报告缺失/非法：{escape(e)}")
        return 1
    for line in lines:
        print(f"::notice::{escape(line)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
