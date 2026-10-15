#!/usr/bin/env python3
"""真实 Trivy 单次扫描+同源门禁自证；故意有漏洞的 POM 只生成于临时目录。

默认受控 Maven 429 注入，验证重扫静默丢失与离线门禁保全；--matrix 可选重跑
历史对照，逐包/路径比发现与包覆盖，不只认指定 CVE。缺二进制/库/JSON 都失败，
不 skip。诊断夹具不是本仓生产漏洞报告，也不能证明历史 HTTP 状态。
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import uuid
import json
from pathlib import Path
import subprocess
import tempfile

from trivy_summary import escape
from trivy_policy import derive

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


def finding_keys(report):
    """同 CVE 在不同包/版本/路径出现也必须逐项保全，不能只比 ID 总数。"""
    return {tuple([result.get("Target", "")] + [str(v.get(k, "")) for k in
            ("VulnerabilityID", "PkgID", "PkgName", "InstalledVersion", "PkgPath")])
            for result in report.get("Results", []) for v in result.get("Vulnerabilities", [])}


def package_keys(report):
    return {tuple([result.get("Target", "")] + [str(p.get(k, "")) for k in
            ("ID", "Name", "Version", "FilePath")])
            for result in report.get("Results", []) for p in result.get("Packages", [])}


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
    return {"rc": result.returncode, "ids": sorted(ids), "findings": sorted(finding_keys(report)), "packages": sorted(package_keys(report)), "targets": [r.get("Target") for r in report.get("Results", [])]}


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
            row["consistent"] = (CVE in results["gate"]["ids"] and
                                 set(map(tuple, results["gate"]["findings"])).issubset(map(tuple, results["full"]["findings"])) and
                                 set(map(tuple, results["gate"]["packages"])) == set(map(tuple, results["full"]["packages"])))
            row["consistent"] &= results["gate"]["rc"] == 1 and results["full"]["rc"] == 0
            matrix.append(row)
    return matrix


def controlled_probe(binary):
    """稳定本地 Maven 仓库 + 429 故障注入；Trivy 与漏洞 DB 均为真实实现。"""
    group = "opsbrain.probe." + uuid.uuid4().hex
    group_path = group.replace(".", "/")
    bridge_path = f"/{group_path}/bridge/1/bridge-1.pom"
    minio_path = "/io/minio/minio/8.5.12/minio-8.5.12.pom"
    failures, requests = threading.Event(), []
    contents = {
        bridge_path: f'<project><modelVersion>4.0.0</modelVersion><groupId>{group}</groupId>'
                     '<artifactId>bridge</artifactId><version>1</version><dependencies><dependency>'
                     '<groupId>io.minio</groupId><artifactId>minio</artifactId><version>8.5.12</version>'
                     '</dependency></dependencies></project>',
        minio_path: '<project><modelVersion>4.0.0</modelVersion><groupId>io.minio</groupId>'
                    '<artifactId>minio</artifactId><version>8.5.12</version></project>',
    }

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            code = 429 if failures.is_set() else (200 if self.path in contents else 404)
            requests.append(code)
            self.send_response(code)
            self.end_headers()
            if code == 200:
                self.wfile.write(contents[self.path].encode())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with tempfile.TemporaryDirectory(prefix="opsbrain-trivy-controlled-") as tmp:
            root = Path(tmp)
            target = root / "input"
            target.mkdir()
            (target / "pom.xml").write_text(
                '<project><modelVersion>4.0.0</modelVersion><groupId>opsbrain</groupId>'
                '<artifactId>probe</artifactId><version>1</version><repositories><repository><id>fixture</id>'
                f'<url>http://127.0.0.1:{server.server_port}/</url></repository></repositories>'
                f'<dependencies><dependency><groupId>{group}</groupId><artifactId>bridge</artifactId>'
                '<version>1</version></dependency></dependencies></project>', encoding="utf-8")
            healthy = run_scan(binary, target, root / "full.json", fixed_only=False, memory=True, exit_code=0)
            if CVE not in healthy["ids"]:
                raise RuntimeError("健康 Maven 仓库未识别已知传递漏洞，不能做假绿对照")
            failures.set()
            degraded = run_scan(binary, target, root / "degraded.json", fixed_only=False, memory=True, exit_code=0)
            if CVE in degraded["ids"] or degraded["rc"] != 0 or 429 not in requests:
                raise RuntimeError("未复现注入 429 后的静默图收缩，需重新核对机制")
            before_convert = len(requests)
            gate, manifest = derive(json.loads((root / "full.json").read_text()), ["pom.xml"])
            (root / "candidate.json").write_text(json.dumps(gate))
            proc = subprocess.run([binary, "convert", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
                                   "--format", "json", "--output", str(root / "gate.json"),
                                   str(root / "candidate.json")], capture_output=True, text=True, timeout=30)
            result = json.loads((root / "gate.json").read_text())
            expected = finding_keys(gate)
            actual = finding_keys(result)
            actual_ids = finding_ids(result)
            if proc.returncode != 1 or CVE not in actual_ids or expected != actual:
                raise RuntimeError(f"同源门禁丢发现或未红：rc={proc.returncode}，{proc.stderr[-1000:]}")
            if package_keys(gate) != package_keys(result):
                raise RuntimeError("convert 包清单发生变化")
            if len(requests) != before_convert:
                raise RuntimeError("convert 意外重解析 Maven，不能声称离线同源")
            return {"healthy_full": healthy, "injected_429_full": degraded,
                    "same_source_gate": {"rc": proc.returncode, "ids": sorted(actual_ids)},
                    "maven_requests_during_convert": len(requests) - before_convert,
                    "kept_vulnerabilities": manifest["kept_vulnerabilities"],
                    "notice": "已复现解析失败返回0且丢传递发现；不等于已证明历史网络状态"}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--trivy", default="trivy")
    ap.add_argument("--json", type=Path, required=True)
    ap.add_argument("--matrix", action="store_true", help="重跑历史 live-repo 矩阵；所有 gate 发现必须是 full 的子集")
    args = ap.parse_args()
    matrix, controlled, error = [], None, None
    try:
        if args.matrix:
            matrix = investigate(args.trivy)
        else:
            controlled = controlled_probe(args.trivy)
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, subprocess.TimeoutExpired) as e:
        error = str(e)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps({"fixture_cve": CVE, "matrix": matrix, "controlled": controlled, "error": error},
                                    ensure_ascii=False, indent=2), encoding="utf-8")
    for row in matrix:
        level = "notice" if row["consistent"] else "error"
        print(f"::{level}::Trivy 隔离对照 {escape(json.dumps(row, ensure_ascii=False))}")
    if controlled:
        print(f"::notice::Trivy 受控故障与同源修复 {escape(json.dumps(controlled, ensure_ascii=False))}")
    if error:
        print(f"::error::Trivy 对照无法完成：{escape(error)}")
    return int(bool(error) or (args.matrix and (not matrix or not all(r["consistent"] for r in matrix))))


if __name__ == "__main__":
    raise SystemExit(main())
