#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RBAC 矩阵实弹走查（DoD#7 第二列 / 灰度 Day1 动作 2）

为什么要有这个脚本
==================
`docs/09-operations/上线放行Runbook-回滚-密钥-看板-HMAC-RBAC.md` §5 给了矩阵，但当时
只有 admin 凭据在手（ops-tester 密码未知），所以那栏一直是「源码静态实证 + 待实弹」。
本脚本把矩阵变成**可复跑的双账号（多账号）实弹走查**：真登录、真请求、真状态码。

判据从哪来（重要）
==================
下表每一行的期望值都写在 `source` 字段里，**取自源码执法点**（注解 / 守卫实现），
不是「跑出来什么就写什么」——否则测试只是把现实抄一遍。
实跑与期望不一致时，是**发现**（要么源码执法点漏了，要么 Runbook 矩阵写错了），
脚本以非零码退出并逐行点名。

用法
====
    python3 tools/verify/rbac_walkthrough.py \
        --base http://localhost:8088/ai \
        --account admin:admin123:ADMIN \
        --account e2e_operator:'E2E_OP_Pwd2026!':OPS \
        --account e2e_viewer:'E2E_VW_Pwd2026!':VIEWER \
        [--json /tmp/rbac-walkthrough.json]

退出码：0=全格与期望一致；1=有偏差（逐行打印）；2=环境问题（登录失败等）。
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import urllib.error
import urllib.request

TIMEOUT = 10


# ---------------------------------------------------------------- 探针表
# deny: 该角色应当被拒（403/401）；未列入的角色应当放行（2xx，或资源不存在 4xx 非 403）
PROBES: list[dict] = [
    {
        "key": "approvals.pending",
        "label": "审批待办（读）",
        "method": "GET",
        "path": "/api/v1/approvals/pending?page=1&size=1",
        "deny": {"OPS", "VIEWER"},
        "source": "ApprovalController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "approvals.pending_count",
        "label": "审批待办计数",
        "method": "GET",
        "path": "/api/v1/approvals/pending/count",
        "deny": {"OPS", "VIEWER"},
        "source": "同上（ApprovalController 类级）",
    },
    {
        "key": "healing.stats",
        "label": "自愈台账统计",
        "method": "GET",
        "path": "/api/v1/healing/stats",
        "deny": {"OPS", "VIEWER"},
        "source": "HealingController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "audit.operations",
        "label": "审计日志",
        "method": "GET",
        "path": "/api/v1/audit/operations?page=1&size=1",
        "deny": {"OPS", "VIEWER"},
        "source": "AuditLogController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "governance.risk_policies",
        "label": "治理配置（风险策略）",
        "method": "GET",
        "path": "/api/v1/governance/risk-policies",
        "deny": {"OPS", "VIEWER"},
        "source": "AutomationGovernanceController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "model_channels.list",
        "label": "模型渠道（含密钥面）",
        "method": "GET",
        "path": "/api/v1/model-channels",
        "deny": {"OPS", "VIEWER"},
        "source": "ModelChannelController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "notify_channels.list",
        "label": "通知渠道（含密钥面）",
        "method": "GET",
        "path": "/api/v1/notify-channels",
        "deny": {"OPS", "VIEWER"},
        "source": "NotifyChannelController 类级 @SaCheckRole(\"ADMIN\")",
    },
    {
        "key": "alerts.read",
        "label": "告警列表（读）",
        "method": "GET",
        "path": "/api/v1/alerts?page=1&size=1",
        "deny": set(),
        "source": "AlertController 无角色约束（登录即可读）",
    },
    {
        "key": "tickets.read",
        "label": "工单列表（读）",
        "method": "GET",
        "path": "/api/v1/tickets?page=1&size=1",
        "deny": set(),
        "source": "TicketController 读面无角色约束",
    },
    {
        "key": "knowledge.docs.read",
        "label": "知识文档列表（读）",
        "method": "GET",
        "path": "/api/v1/knowledge/docs?page=1&size=1",
        "deny": set(),
        "source": "KnowledgeDocController 读面仅需登录",
    },
    {
        "key": "knowledge.write",
        "label": "知识库写入（新建文档）",
        "method": "POST",
        "path": "/api/v1/knowledge/docs",
        "body": {
            "title": "RBAC 走查探针（可删）",
            "category": "运维手册",
            "author": "rbac-walkthrough",
            "content": "本行文档由 tools/verify/rbac_walkthrough.py 自动创建，用于验证写面权限。"
                       "若出现在库中，可安全删除。",
        },
        "deny": {"VIEWER"},
        "source": "KnowledgeWriteGuard.requireEdit() = ADMIN | OPS",
    },
    {
        "key": "knowledge.purge",
        "label": "知识库不可逆操作（purge）",
        "method": "DELETE",
        "path": "/api/v1/knowledge/docs/999999999/purge",
        "deny": {"OPS", "VIEWER"},
        "source": "KnowledgeWriteGuard.requireDestructive() = 仅 ADMIN",
    },
    {
        "key": "alerts.acknowledge",
        "label": "告警处置（写，OpsWriteGuard）",
        "method": "POST",
        "path": "/api/v1/alerts/999999999/acknowledge",
        "body": {"operator": "rbac-walkthrough"},
        "deny": {"VIEWER"},
        "source": "OpsWriteGuard.requireWrite() = ADMIN | OPS（VIEWER 由此被拒）",
    },
    {
        "key": "tickets.delete",
        "label": "工单删除（不可逆）",
        "method": "DELETE",
        "path": "/api/v1/tickets/999999999",
        "deny": {"OPS", "VIEWER"},
        "source": "OpsWriteGuard 删单限 ADMIN（批90 T3）",
    },
]

GREEN, RED, YELLOW, NC = "\033[0;32m", "\033[0;31m", "\033[1;33m", "\033[0m"


def http(method: str, url: str, token: str | None, body: dict | None) -> tuple[int, str]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("satoken", token)
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=ctx) as resp:
            return resp.status, resp.read(4000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read(2000).decode("utf-8", "replace")
    except Exception as e:  # 网络/解析类问题统一暴露，不静默
        return -1, f"{type(e).__name__}: {e}"


def login(base: str, username: str, password: str) -> str | None:
    code, text = http("POST", f"{base}/api/v1/auth/login", None,
                      {"username": username, "password": password})
    if code != 200:
        print(f"  {RED}✗ 登录失败 {username} → HTTP {code} {text[:200]}{NC}")
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    data = payload.get("data") or {}
    token = data.get("token") or payload.get("token")
    if not token:
        print(f"  {RED}✗ 登录响应无 token：{text[:200]}{NC}")
    return token


def classify(code: int) -> str:
    if 200 <= code < 300:
        return "allow"
    if code in (401, 403):
        return "deny"
    return "other"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8088/ai")
    ap.add_argument("--account", action="append", required=True,
                    metavar="USER:PASS:ROLE", help="可重复；ROLE 用于对号入座期望值")
    ap.add_argument("--json", dest="json_out", default=None)
    args = ap.parse_args()

    accounts: list[tuple[str, str, str]] = []
    for spec in args.account:
        parts = spec.split(":")
        if len(parts) < 3:
            print(f"{RED}--account 需要 USER:PASS:ROLE → {spec}{NC}")
            return 2
        accounts.append((parts[0], ":".join(parts[1:-1]), parts[-1].upper()))

    base = args.base.rstrip("/")
    print("=== RBAC 矩阵实弹走查 ===")
    print(f"BASE: {base} | 账号: {', '.join(f'{u}({r})' for u, _, r in accounts)}")

    tokens: dict[str, str | None] = {}
    for user, password, role in accounts:
        tokens[role] = login(base, user, password)
    if any(t is None for t in tokens.values()):
        return 2

    results = []
    mismatches = 0
    for probe in PROBES:
        row = {"key": probe["key"], "label": probe["label"], "cells": {}}
        print(f"\n▸ {probe['label']}  [{probe['method']} {probe['path']}]")
        print(f"    判据: {probe['source']}")
        for user, _, role in accounts:
            code, _ = http(probe["method"], base + probe["path"], tokens[role], probe.get("body"))
            actual = classify(code)
            expected = "deny" if role in probe["deny"] else "allow"
            # 「放行」允许 2xx 或 资源级拒绝（探针用的是假 id）；
            # 唯独 401/403 视为许可边界上的拒绝。
            # 409 首跑被漏收：守卫先过、再由业务层对不存在的告警/工单抛冲突
            # —— 这说明权限已通过（若被拒会是 403），属资源级而非权限级。
            RESOURCE_LEVEL = (400, 404, 405, 409, 422)
            ok = (actual == expected) or (expected == "allow" and code in RESOURCE_LEVEL)
            if expected == "allow" and code in RESOURCE_LEVEL:
                actual = "allow*"  # 权限已过，资源层拒绝
            if not ok:
                mismatches += 1
            mark = f"{GREEN}✓{NC}" if ok else f"{RED}✗{NC}"
            row["cells"][role] = {"http": code, "verdict": actual, "expected": expected, "ok": ok}
            print(f"    {mark} {role:7} http={code:<4} verdict={actual:<7} 期望={expected}")
        results.append(row)

    total = sum(len(r["cells"]) for r in results)
    print(f"\n=== 汇总：{total - mismatches}/{total} 格与源码执法点一致 ===")
    if mismatches:
        print(f"{RED}有 {mismatches} 格不一致：要么源码执法点漏了，要么 Runbook §5 矩阵需更正{NC}")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"base": base, "accounts": [a[0] for a in accounts],
                       "total": total, "mismatches": mismatches, "rows": results},
                      f, ensure_ascii=False, indent=2)
        print(f"证据已写入 {args.json_out}")

    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
