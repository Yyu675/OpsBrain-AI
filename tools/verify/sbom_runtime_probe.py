#!/usr/bin/env python3
"""独立实验：Maven tree → 原生 CycloneDX → Trivy；Boot 内嵌 JAR 按 SBOM SHA256 核对。

差异/漏洞有发现不把实验伪装生产放行；损坏输入/退出码不符失败。绝不改写生产报告。
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import parse_qs, unquote, urlsplit
import zipfile
import zlib

from maven_scan_coverage import runtime_packages, text
from trivy_policy import derive
from trivy_summary import escape
from selftest_trivy_reports import finding_keys


def maven_purl(value):
    if not isinstance(value, str) or not value.startswith("pkg:maven/"):
        raise ValueError("需 Maven PURL")
    parsed = urlsplit(value)
    package, sep, version = parsed.path[len("maven/"):].rpartition("@")
    parts = package.split("/")
    if not sep or len(parts) != 2 or not all(parts) or not version or parsed.fragment:
        raise ValueError("非法 Maven PURL")
    qualifiers = parse_qs(parsed.query, keep_blank_values=True)
    if any(len(v) != 1 for v in qualifiers.values()):
        raise ValueError("重复 PURL qualifier")
    return (unquote(parts[0]) + ":" + unquote(parts[1]), unquote(version),
            qualifiers.get("type", ["jar"])[0], unquote(qualifiers.get("classifier", [""])[0]))


def components(bom):
    if bom.get("bomFormat") != "CycloneDX" or bom.get("specVersion") != "1.6":
        raise ValueError("需原生 CycloneDX 1.6")
    entries = bom.get("components")
    if not isinstance(entries, list) or not entries:
        raise ValueError("SBOM components 为空")
    found = {}
    for entry in entries:
        variant = maven_purl(entry.get("purl"))
        for key, expected in (("group", variant[0].split(":")[0]),
                              ("name", variant[0].split(":")[1]), ("version", variant[1])):
            if entry.get(key) != expected:
                raise ValueError("SBOM PURL 与组件坐标不一致")
        if variant in found:
            raise ValueError("重复 SBOM variant")
        hashes = [h.get("content") for h in entry.get("hashes", []) if h.get("alg") == "SHA-256"]
        if len(hashes) != 1 or not isinstance(hashes[0], str) or len(hashes[0]) != 64 or any(c not in "0123456789abcdefABCDEF" for c in hashes[0]):
            raise ValueError("SBOM 缺唯一有效 SHA-256")
        found[variant] = hashes[0].lower()
    return found


def packaged_libraries(path, registry):
    matched, unknown, evidence = set(), [], []
    with zipfile.ZipFile(path) as jar:
        libs = [i for i in jar.infolist() if i.filename.startswith("BOOT-INF/lib/") and i.filename.endswith(".jar")]
        if not libs or len({i.filename for i in libs}) != len(libs):
            raise ValueError("缺/重复 Boot 内嵌库，不是受支持的 repackage JAR")
        for lib in libs:
            if lib.file_size > 256 * 1024 * 1024:
                raise ValueError("内嵌库超过实验读取上限")
            digest = hashlib.sha256(jar.read(lib)).hexdigest()
            candidates = [v for v, sha in registry.items() if sha == digest]
            if len(candidates) > 1:
                filename = Path(lib.filename).name
                candidates = [v for v in candidates if filename == v[0].split(":")[1] + "-" + v[1] + ("-" + v[3] if v[3] else "") + ".jar"]
            row = {"path": lib.filename, "sha256": digest}
            if len(candidates) == 1:
                v = candidates[0]
                matched.add(v)
                row["variant"] = list(v)
            else:
                row["reason"] = "SBOM 无匹配字节" if not candidates else "相同字节对应多个组件，无法唯一绑定"
                unknown.append(row)
            evidence.append(row)
    return matched, unknown, evidence


def inspect(tree, bom, report, gate, gate_rc, boot_jar):
    root, runtime, variants = runtime_packages(tree)
    registry = components(bom)
    bom_gavs = {v[:2] for v in registry} - {root}
    if report.get("SchemaVersion") != 2 or not report.get("Results"):
        raise ValueError("缺 SBOM 扫描目标")
    scanned = set()
    findings = []
    for result in report["Results"]:
        for pkg in result.get("Packages", []):
            name, version = text(pkg, "Name"), text(pkg, "Version")
            if len(name.split(":")) != 2:
                raise ValueError("SBOM 扫描出现非 Maven 包名")
            scanned.add((name, version))
        for vuln in result.get("Vulnerabilities", []):
            findings.append({key: vuln.get(key) for key in ("VulnerabilityID", "PkgName", "InstalledVersion", "Severity", "Status", "FixedVersion")})
    scanned.discard(root)
    if not scanned:
        raise ValueError("缺 SBOM 扫描包清单")
    derive(gate)  # 即使零发现，也不能把坏/空 gate 当作合法零结果。
    candidate, policy = derive(report)
    expected = finding_keys(candidate)
    if gate_rc not in (0, 1) or gate_rc != int(bool(expected)) or expected != finding_keys(gate):
        raise ValueError("Trivy 原生门禁退出码/发现与同源 fixed 策略不符")
    packaged, unknown, evidence = packaged_libraries(boot_jar, registry)
    complete = variants <= registry.keys() and bom_gavs <= scanned and not unknown
    encode = lambda values: [list(v) for v in sorted(values)]
    return {"schema_version": 1, "input_candidate_complete": complete,
            "status": "candidate" if complete else "incomplete", "production_gate_replaced": False,
            "counts": {"runtime_gavs": len(runtime), "runtime_variants": len(variants),
                       "sbom_gavs": len(bom_gavs), "sbom_variants": len(registry), "scanned_gavs": len(scanned),
                       "runtime_missing_from_sbom": len(variants - registry.keys()),
                       "sbom_missing_from_scan": len(bom_gavs - scanned),
                       "packaged_libraries": len(evidence), "packaged_hash_matched": len(evidence) - len(unknown),
                       "packaged_unknown": len(unknown), "finding_rows": len(findings),
                       "unique_cves": len({f["VulnerabilityID"] for f in findings}),
                       "fixed_rows": policy["kept_vulnerabilities"]},
            "native_gate_rc": gate_rc, "findings": findings,
            "runtime_missing_from_sbom": encode(variants - registry.keys()),
            "sbom_extra_to_runtime": encode(registry.keys() - variants),
            "sbom_missing_from_scan": encode(bom_gavs - scanned),
            "runtime_not_packaged": encode(variants - packaged),
            "packaged_unknown": unknown, "packaged_libraries": evidence,
            "boundary": "独立实验非生产放行；Boot库按SBOM SHA256绑定，不按文件名猜；只核SBOM扫描GAV，不证明scanner逐classifier覆盖；新增发现不是已修补/已确认可利用"}


def emit(payload):
    data = base64.b64encode(zlib.compress(payload)).decode()
    chunks = [data[i:i + 2700] for i in range(0, len(data), 2700)]
    if len(chunks) <= 6:
        for i, chunk in enumerate(chunks, 1):
            frame = {"part": i, "parts": len(chunks), "sha256": hashlib.sha256(payload).hexdigest(), "codec": "zlib+base64", "data": chunk}
            print("::notice::SBOM 实验压缩JSON：" + json.dumps(frame))
    else:
        # 库哈希列表可能很大。完整文件照常artifact，紧凑风险与缺口证据另发，不声称包含全部库。
        print("::notice::SBOM 实验全量过大，完整库哈希仅见 artifact")
        result = json.loads(payload)
        compact = {k: v for k, v in result.items() if k != "packaged_libraries"}
        compact_payload = json.dumps(compact, ensure_ascii=False).encode()
        packed = base64.b64encode(zlib.compress(compact_payload)).decode()
        if len(packed) <= 6 * 2700:
            blobs = [packed[i:i + 2700] for i in range(0, len(packed), 2700)]
            for i, chunk in enumerate(blobs, 1):
                print("::notice::SBOM 实验紧凑压缩JSON：" + json.dumps({"part": i, "parts": len(blobs), "codec": "zlib+base64", "full_sha256": hashlib.sha256(payload).hexdigest(), "sha256": hashlib.sha256(compact_payload).hexdigest(), "data": chunk}))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for name in ("tree", "sbom", "report", "gate", "gate-rc", "boot-jar", "json"):
        ap.add_argument("--" + name, required=True, type=Path)
    ap.add_argument("--checkout-sha", required=True)
    args = ap.parse_args()
    paths = {name: getattr(args, name) for name in ("tree", "sbom", "report", "gate", "gate_rc", "boot_jar")}
    try:
        if len({p.resolve() for p in [*paths.values(), args.json]}) != 7:
            raise ValueError("输入/输出路径必须不同")
        args.json.unlink(missing_ok=True)
        if len(args.checkout_sha) != 40 or any(c not in "0123456789abcdef" for c in args.checkout_sha):
            raise ValueError("需完整 checkout SHA")
        docs = {n: json.loads(paths[n].read_bytes()) for n in ("tree", "sbom", "report", "gate")}
        result = inspect(docs["tree"], docs["sbom"], docs["report"], docs["gate"], int(args.gate_rc.read_text()), args.boot_jar)
        result["checkout_sha"] = args.checkout_sha
        result["input_sha256"] = {}
        for name, path in paths.items():
            with path.open("rb") as f:
                result["input_sha256"][name] = hashlib.file_digest(f, "sha256").hexdigest()
        payload = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode()
        args.json.write_bytes(payload)
    except (OSError, ValueError, TypeError, KeyError, AttributeError, zipfile.BadZipFile, RecursionError) as e:
        print("::error::SBOM 实验未完成：" + escape(e))
        return 1
    level = "warning" if result["counts"]["finding_rows"] or not result["input_candidate_complete"] else "notice"
    print(f"::{level}::SBOM 独立实验：{escape(json.dumps(result['counts']))}；status={result['status']}；native_gate_rc={result['native_gate_rc']}；非生产放行")
    emit(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
