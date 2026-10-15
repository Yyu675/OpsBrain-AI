#!/usr/bin/env python3
"""真实 Trivy 重复扫描对照；故意有漏洞的 POM 只生成于临时目录。

不把二次扫描的空报告当作全量证据。记录扫描顺序/输出路径/缓存维度的结果，
缺二进制/数据库/JSON 都失败，不 skip。诊断夹具不是本仓生产漏洞报告。
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from trivy_summary import escape

CVE = "CVE-2025-59952"
POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>opsbrain.verify</groupId>
<artifactId>isolated-scanner-probe</artifactId><version>1</version>
<dependencies><dependency><groupId>io.minio</groupId><artifactId>minio</artifactId>
<version>8.5.12</version></dependency></dependencies></project>
"""


def finding_ids(report):
    return {v.get("VulnerabilityID") for result in report.get("Results", [])
            for v in result.get("Vulnerabilities", [])}


def run_scan(binary, target, output, *, fixed_only, memory, exit_code):
    cmd = [binary, "fs", "--no-progress", "--scanners", "vuln", "--severity", "HIGH,CRITICAL",
           "--exit-code", str(exit_code), "--format", "json", "--output", str(output)]
    if fixed_only:
        cmd.append("--ignore-unfixed")
    if memory:
        cmd += ["--cache-backend", "memory"]
    cmd.append(str(target))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
    if result.returncode not in (0, 1) or not output.is_file():
        raise RuntimeError(f"扫描未产证据：rc={result.returncode}，{result.stderr[-1200:]}")
    report = json.loads(output.read_text(encoding="utf-8"))
    ids = finding_ids(report)
    return {"rc": result.returncode, "ids": sorted(ids), "targets": [r.get("Target") for r in report.get("Results", [])]}


def investigate(binary):
    matrix = []
    for name, inside, memory, reverse in (
            ("outside-default", False, False, False),
            ("inside-default", True, False, False),
            ("outside-memory", False, True, False),
            ("outside-reverse", False, False, True)):
        with tempfile.TemporaryDirectory(prefix="opsbrain-trivy-probe-") as tmp:
            root = Path(tmp)
            target = root / "input"
            target.mkdir()
            (target / "pom.xml").write_text(POM, encoding="utf-8")
            output_dir = target if inside else root
            order = [("gate", True, 1), ("full", False, 0)]
            if reverse:
                order.reverse()
            results = {}
            for label, fixed_only, code in order:
                results[label] = run_scan(binary, target, output_dir / f"{label}.json",
                                          fixed_only=fixed_only, memory=memory, exit_code=code)
            row = {"case": name, "inside": inside, "memory": memory, "reverse": reverse, **results}
            row["consistent"] = CVE in results["gate"]["ids"] and CVE in results["full"]["ids"]
            row["consistent"] &= results["gate"]["rc"] == 1 and results["full"]["rc"] == 0
            matrix.append(row)
    return matrix


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--trivy", default="trivy")
    ap.add_argument("--json", type=Path, required=True)
    args = ap.parse_args()
    matrix, error = [], None
    try:
        matrix = investigate(args.trivy)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as e:
        error = str(e)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps({"fixture_cve": CVE, "matrix": matrix, "error": error},
                                    ensure_ascii=False, indent=2), encoding="utf-8")
    for row in matrix:
        level = "notice" if row["consistent"] else "error"
        print(f"::{level}::Trivy 隔离对照 {escape(json.dumps(row, ensure_ascii=False))}")
    if error:
        print(f"::error::Trivy 对照无法完成：{escape(error)}")
    return int(bool(error) or not matrix or not all(r["consistent"] for r in matrix))


if __name__ == "__main__":
    raise SystemExit(main())
