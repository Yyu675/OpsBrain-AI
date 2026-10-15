#!/usr/bin/env python3
"""对照 Maven dependency-plugin 3.8.1 JSON runtime tree 与同 run 的 Trivy raw JSON。

只审计 GAV 覆盖，不重新扫描，不替代漏洞门禁，不证明 classifier/字节级覆盖。
合法差异留 warning + JSON，输入/工具错误失败；--strict 可用于反例测试或未来门禁。
"""
import argparse
import base64
import zlib
import hashlib
import json
from pathlib import Path
import sys

from trivy_summary import escape


def text(obj, key):
    value = obj.get(key)
    if not isinstance(value, str) or not value or value.strip() != value:
        raise ValueError(f"缺失/非法 {key}")
    if "${" in value:
        raise ValueError(f"{key} 尚未解析")
    return value


def runtime_packages(tree):
    if not isinstance(tree, dict):
        raise ValueError("Maven tree 必须是对象")
    root = (text(tree, "groupId") + ":" + text(tree, "artifactId"), text(tree, "version"))
    packages, variants = set(), set()

    def visit(node, is_root=False):
        if not isinstance(node, dict):
            raise ValueError("Maven node 必须是对象")
        scope = node.get("scope", "")
        if not is_root:
            if scope in ("test", "provided", "system", "import"):
                return  # 子树也不属于运行时；optional=true 直依赖仍属于本项目运行时。
            if scope not in ("compile", "runtime"):
                raise ValueError(f"未知/缺失 Maven scope: {scope!r}")
            name = text(node, "groupId") + ":" + text(node, "artifactId")
            version = text(node, "version")
            kind = text(node, "type")
            classifier = node.get("classifier", "")
            if not isinstance(classifier, str):
                raise ValueError("非法 classifier")
            packages.add((name, version))
            variants.add((name, version, kind, classifier))
        children = node.get("children", [])
        if not isinstance(children, list):
            raise ValueError("Maven children 必须是数组")
        for child in children:
            visit(child)

    visit(tree, True)
    if not packages:
        raise ValueError("运行时依赖为空，拒绝 0/0 假绿")
    return root, packages, variants


def scanned_packages(report, target):
    if not isinstance(report, dict) or report.get("SchemaVersion") != 2 or not report.get("ArtifactName"):
        raise ValueError("需 Trivy SchemaVersion=2 / ArtifactName")
    results = report.get("Results")
    if not isinstance(results, list):
        raise ValueError("Trivy Results 必须为数组")
    rows = [r for r in results if isinstance(r, dict) and r.get("Target") == target and r.get("Type") == "pom"]
    if len(rows) != 1:
        raise ValueError("需唯一对应的 pom 扫描目标")
    packages = rows[0].get("Packages")
    if not isinstance(packages, list) or not packages:
        raise ValueError("缺 Java 包清单，不能用零漏洞代替覆盖证据")
    found = set()
    for pkg in packages:
        if not isinstance(pkg, dict):
            raise ValueError("非法 Trivy Package")
        name, version = text(pkg, "Name"), text(pkg, "Version")
        if len(name.split(":")) != 2 or any(not s for s in name.split(":")):
            raise ValueError("Trivy Maven Name 不是 groupId:artifactId")
        found.add((name, version))
    return found


def compare(tree, report, target="pom.xml"):
    root, expected, variants = runtime_packages(tree)
    scanned = scanned_packages(report, target) - {root}
    missing = expected - scanned
    extras = scanned - expected
    actual_versions = {}
    expected_versions = {}
    for name, version in scanned:
        actual_versions.setdefault(name, set()).add(version)
    for name, version in expected:
        expected_versions.setdefault(name, set()).add(version)
    mismatches = [{"name": name, "expected": sorted(expected_versions[name]),
                   "scanned": sorted(actual_versions[name])}
                  for name in sorted({n for n, _ in missing} & actual_versions.keys())]
    encode = lambda rows: [{"name": n, "version": v} for n, v in sorted(rows)]
    return {"schema_version": 1, "status": "complete" if not missing else "incomplete",
            "coverage_complete": not missing, "exact_gav_match": scanned == expected,
            "target": target, "project": {"name": root[0], "version": root[1]},
            "counts": {"runtime_gavs": len(expected), "scanned_gavs": len(scanned),
                       "matched_gavs": len(expected & scanned), "missing_gavs": len(missing),
                       "extra_gavs": len(extras), "runtime_variants": len(variants)},
            "missing": encode(missing), "extra": encode(extras), "version_mismatches": mismatches,
            "runtime": encode(expected), "scanned": encode(scanned),
            "variants": [{"name": n, "version": v, "type": t, "classifier": c}
                         for n, v, t, c in sorted(variants)],
            "boundary": "只审计当前 Maven runtime GAV：不证明 classifier/字节完整，不等于最终Boot repackage清单；不含 test/provided/system、构建插件及前端；extra 不作漏扫；不替代漏洞门禁"}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tree", required=True, type=Path)
    ap.add_argument("--report", required=True, type=Path)
    ap.add_argument("--pom", required=True, type=Path)
    ap.add_argument("--json", required=True, type=Path)
    ap.add_argument("--checkout-sha", required=True)
    ap.add_argument("--target", default="pom.xml")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    try:
        paths = [p.resolve() for p in (args.tree, args.report, args.pom, args.json)]
        if len(set(paths)) != 4:
            raise ValueError("输入与输出必须是不同文件")
        args.json.unlink(missing_ok=True)
        if len(args.checkout_sha) != 40 or any(c not in "0123456789abcdef" for c in args.checkout_sha):
            raise ValueError("checkout-sha 必须是完整 commit SHA")
        blobs = {"tree": args.tree.read_bytes(), "report": args.report.read_bytes(), "pom": args.pom.read_bytes()}
        result = compare(json.loads(blobs["tree"]), json.loads(blobs["report"]), args.target)
        result["checkout_sha"] = args.checkout_sha
        result["sha256"] = {name: hashlib.sha256(blob).hexdigest() for name, blob in blobs.items()}
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError, RecursionError) as e:
        print(f"::error::Maven 扫描覆盖审计失败：{escape(e)}")
        return 1
    level = "notice" if result["coverage_complete"] else "warning"
    print(f"::{level}::Maven runtime GAV 覆盖：{escape(json.dumps(result['counts']))}；"
          f"status={result['status']}，审计差异不替代漏洞门禁；checkout={args.checkout_sha}")
    fingerprint = {"checkout_sha": args.checkout_sha, "sha256": result["sha256"], "project": result["project"]}
    print(f"::notice::Maven 覆盖审计指纹：{escape(json.dumps(fingerprint, ensure_ascii=False))}")
    # GitHub 实测单条 annotation 截断到约 4KB。完整审计压缩分片，不能把截断 JSON 当证据。
    payload = args.json.read_bytes()
    encoded = base64.b64encode(zlib.compress(payload)).decode("ascii")
    chunks = [encoded[i:i + 2700] for i in range(0, len(encoded), 2700)]
    if len(chunks) <= 6:  # 每步 notice 配额留余量；超大件仍以 artifact 为准。
        for i, chunk in enumerate(chunks, 1):
            frame = {"part": i, "parts": len(chunks), "codec": "zlib+base64",
                     "sha256": hashlib.sha256(payload).hexdigest(), "data": chunk}
            print(f"::notice::Maven 覆盖审计压缩JSON：{json.dumps(frame)}")
    else:
        print("::notice::Maven 覆盖审计过大，完整 JSON 仅见 artifact，未声称注解已包含全量")
    if not result["coverage_complete"]:
        details = {"missing_first_10": result["missing"][:10], "version_mismatches_first_5": result["version_mismatches"][:5]}
        print(f"::warning::Maven 覆盖差异（全量见 artifact）：{escape(json.dumps(details, ensure_ascii=False))}")
    return int(args.strict and not result["coverage_complete"])


if __name__ == "__main__":
    sys.exit(main())
