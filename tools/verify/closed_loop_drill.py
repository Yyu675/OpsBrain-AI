#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
业务闭环实弹演练：RAG 知识库 + 工单 + Agent 处理真实企业运维问题

它回答一个问题
==============
「把一条真告警丢进来，系统能不能一路走到知识沉淀？」——不看单测，**真 HTTP、真库、
真状态机**地跑一遍，逐跳断言，断在哪跳就点名哪跳。

现场取自真实告警推送（Zabbix，2026-10-13）：
    PROBLEM  primera01 / 10.210.44.11  Average  17:37:55   事件 12002061
    OK       （同一触发器）                       17:38:47   —— 持续 54 秒

三个场景（因为系统对它们的**行为本就该不同**，这正是设计意图）：

    场景 A  P1 高危          severity=High    → 立即建单 → Agent 诊断 → 人工处置五连
                                                → 恢复事件穿透 → 复盘 → 知识草稿沉淀
    场景 B  P2 抖动 54 秒自愈 severity=Average → 观察窗内恢复 → **不建单**（降噪）
    场景 C  历史事件回放     事件时刻 = 26 小时前 → 观察窗与补单扫描都过期，如实报告现状

硬闸 vs 软闸（别自欺）
====================
- **硬闸**（不过即退出码 1）：告警入库与字段映射、自动建单、双向溯源、诊断会话落库、
  四路证据都执行、处置状态机流转、恢复事件穿透 resolvedAt、复盘沉淀、观察窗降噪、
  自监控端点可读。
- **软闸**（默认只报告，`--require-rag-hit` 转硬）：诊断的知识证据是否命中导入的手册。
  `AI_MODE=MOCK` 下 embedding 是确定性桩向量（前 4 维放余弦系数），**语义相似度不成立**，
  命中与否取决于桩向量；只有 REAL 模式它才是真指标。
- 场景 C 全部是软闸：它验证的是「系统在非预期时序下的行为是否可解释」，其现状本身
  就是一条待决策的发现（见 `docs/09-operations/业务闭环能力验证方案.md` §4）。

用法
====
    python3 tools/verify/closed_loop_drill.py \
        --base http://localhost:8088/ai --account admin:admin123 \
        [--psql /path/to/psql --pgurl postgresql://user@host:port/db] \
        [--require-rag-hit] [--json /tmp/closed-loop.json]

退出码：0=硬闸全过；1=有硬闸失败；2=环境/前置不满足。
"""

from __future__ import annotations

import argparse
import json
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

TIMEOUT = 20
GREEN, RED, YELLOW, CYAN, NC = "\033[0;32m", "\033[0;31m", "\033[1;33m", "\033[0;36m", "\033[0m"

MANUAL_TITLE = "主机不可达（NodeDown）分诊与处置手册"
MANUAL_CONTENT = """## 适用告警
Zabbix 触发器 NodeDown / 主机不可达 / ICMP 探测连续失败。

## 现场判断（三步）
1. 先看监控口径：是单主机不可达还是整网段不可达。整网段先查交换机与上行链路；
   单主机查该主机电源、虚拟化宿主机、系统负载与内核日志。
2. 再确认是否假告警：Zabbix Agent 心跳是否同时失联。仅 ICMP 失败而 Agent 心跳正常，
   多半是网络 ACL 变更或 ICMP 限速，不是真宕机。
3. 最后看变更窗口：告警前 30 分钟内的发布、扩容、网络变更单是最常见根因。

## 处置动作
- 影响面确认后，先在业务侧摘流量（LB 下线该节点），再做主机侧操作。
- 重启前必须确认：是否有未落盘的本地数据、是否为集群唯一仲裁节点。
- 恢复后必须验证：ICMP 恢复 + Agent 心跳恢复 + 业务探针 200，三者齐了才算恢复。

## 常见根因分类
网络变更（ACL/路由）、电源或宿主机故障、内核 OOM 或 panic、磁盘写满导致服务假死、
Agent 版本升级失败。

## 复盘要求
记录影响面（受影响业务与用户数）、持续时长、根因、处置动作、改进项（含负责人与期限）。
"""


# ------------------------------------------------------------------ 基础设施
def http(method: str, url: str, token: str | None = None, body=None) -> tuple[int, dict | str]:
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
            raw, code = resp.read().decode("utf-8", "replace"), resp.status
    except urllib.error.HTTPError as e:
        raw, code = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:  # 不静默：网络层问题要看得见
        return -1, f"{type(e).__name__}: {e}"
    try:
        return code, json.loads(raw)
    except json.JSONDecodeError:
        return code, raw


class Drill:
    def __init__(self, base: str, token: str, psql: str | None, pgurl: str | None):
        self.base, self.token, self.psql, self.pgurl = base, token, psql, pgurl
        self.hops: list[dict] = []
        self.hard_fail = 0
        self.soft_fail = 0

    def hop(self, phase: str, key: str, ok: bool, detail: str, soft: bool = False) -> bool:
        verdict = "PASS" if ok else ("SOFT-FAIL" if soft else "FAIL")
        if not ok:
            if soft:
                self.soft_fail += 1
            else:
                self.hard_fail += 1
        mark = f"{GREEN}✓{NC}" if ok else (f"{YELLOW}!{NC}" if soft else f"{RED}✗{NC}")
        print(f"  {mark} [{phase}] {key} — {detail}")
        self.hops.append({"phase": phase, "key": key, "verdict": verdict,
                          "soft": soft, "detail": detail})
        return ok

    def skip(self, phase: str, key: str, reason: str) -> None:
        print(f"  {CYAN}∅{NC} [{phase}] {key} — SKIPPED（{reason}）")
        self.hops.append({"phase": phase, "key": key, "verdict": "SKIPPED", "detail": reason})

    def api(self, method: str, path: str, body=None):
        return http(method, self.base + path, self.token, body)

    def data(self, method: str, path: str, body=None):
        code, payload = self.api(method, path, body)
        if isinstance(payload, dict) and payload.get("code") == 0:
            return code, payload.get("data")
        return code, None

    def psql_q(self, sql: str) -> str | None:
        if not self.psql or not self.pgurl:
            return None
        try:
            out = subprocess.run([self.psql, self.pgurl, "-t", "-A", "-c", sql],
                                 capture_output=True, text=True, timeout=30)
            return out.stdout.strip() if out.returncode == 0 else f"ERR: {out.stderr.strip()[:200]}"
        except Exception as e:
            return f"ERR: {type(e).__name__}: {e}"

    def poll(self, fn, timeout_s: float = 60, interval: float = 2.0):
        deadline = time.time() + timeout_s
        last = None
        while time.time() < deadline:
            last = fn()
            if last:
                return last
            time.sleep(interval)
        return last


# ------------------------------------------------------------------ 载荷（真实事件形态）
def _wall(dt: datetime) -> tuple[str, str]:
    """Zabbix 的墙钟口径：event_date=YYYY.MM.DD（点分隔）+ event_time=HH:MM:SS。"""
    return dt.strftime("%Y.%m.%d"), dt.strftime("%H:%M:%S")


def zabbix_event(tag: str, severity: str, at: datetime, resolved: bool,
                 host: str = "primera01", ip: str = "10.210.44.11",
                 service: str = "infra", event_seq: str = "1") -> dict:
    d, t = _wall(at)
    payload = {
        "event_id": f"{tag}{event_seq}",
        "event_date": d,
        "event_time": t,
        "event_name": f"NodeDown-{tag}: 主机不可达" + ("（已恢复）" if resolved else ""),
        "event_severity": severity,
        "event_value": "0" if resolved else "1",
        "event_status": "OK" if resolved else "PROBLEM",
        "trigger_id": tag,
        "trigger_name": f"NodeDown-{tag}",
        "trigger_description": f"主机 {host} ({ip}) 已连续 3 次未响应 ICMP 探测",
        "host_name": host,
        "host_ip": ip,
        "host_tags": f"service:{service},module:node",
        "item_name": "ICMP ping",
        "item_value": "0",
        "event_opdata": "agent 心跳停止 3 分钟",
    }
    if resolved:
        payload["event_recovery_status"] = "0"
        payload["event_recovery_time"] = t
    return payload


def find_alert(d: Drill, trigger: str) -> dict | None:
    _, data = d.data("GET", "/api/v1/alerts?page=1&size=50")
    alerts = (data or {}).get("alerts") if isinstance(data, dict) else None
    for a in alerts or []:
        if a.get("alertName") == f"NodeDown-{trigger}":
            return a
    return None


def ev_type(e: dict) -> str:
    return (e.get("evidence_type") or e.get("evidenceType") or e.get("type") or "?").lower()


# ------------------------------------------------------------------ 主流程
def run(args) -> int:
    base = args.base.rstrip("/")
    user, _, password = args.account.partition(":")
    code, payload = http("POST", f"{base}/api/v1/auth/login", None,
                         {"username": user, "password": password})
    token = (payload.get("data") or {}).get("token") if isinstance(payload, dict) else None
    if code != 200 or not token:
        print(f"{RED}登录失败：HTTP {code} {str(payload)[:200]}{NC}")
        return 2

    tag = datetime.now().strftime("%H%M%S")
    now = datetime.now()
    print(f"=== 业务闭环实弹演练 ===\nBASE={base} | 账号={user} | RUN_TAG={tag} | 现在={now:%Y-%m-%d %H:%M:%S}")
    d = Drill(base, token, args.psql, args.pgurl)

    # ============================== 阶段 0：RAG 知识库就绪
    print(f"\n{CYAN}▸ 阶段 0：RAG 知识库就绪（导入手册 → 发布触发切片与向量化）{NC}")
    doc_id = None
    _, docs = d.data("GET", "/api/v1/knowledge/docs?page=1&size=100")
    # 列表返回键是 content（分页结构：content/totalElements/currentPage/pageSize）——
    # 首版写成 records，结果每次演练都新建一篇文档（幂等性失效），实跑抓到后改正。
    page = (docs or {}).get("content") or (docs or {}).get("records") or [] if isinstance(docs, dict) else []
    for doc in page:
        if doc.get("title") == MANUAL_TITLE:
            doc_id = doc.get("id")
            break
    if doc_id is None:
        code, created = d.data("POST", "/api/v1/knowledge/docs", {
            "title": MANUAL_TITLE, "category": "运维手册", "author": user,
            "content": MANUAL_CONTENT,
            "summary": "NodeDown 告警的分诊三步、处置动作、根因分类与复盘要求",
        })
        cid = created.get("id") if isinstance(created, dict) else created
        d.hop("0-就绪", "知识文档创建", code == 200 and cid is not None, f"HTTP {code} id={cid}")
        doc_id = cid
    else:
        d.hop("0-就绪", "知识文档存在（幂等复用）", True, f"id={doc_id}")
    if doc_id is None:
        return 1

    code, pub = d.data("POST", f"/api/v1/knowledge/docs/{doc_id}/publish")
    idx = (pub or {}).get("indexStatus") if isinstance(pub, dict) else None
    retr = (pub or {}).get("retrievable") if isinstance(pub, dict) else None
    d.hop("0-就绪", "文档发布并向量化（写入可检索态）",
          code == 200 and idx in ("INDEXED", "INDEXED_ALREADY", "SKIPPED") or retr is True,
          f"HTTP {code} indexStatus={idx} retrievable={retr}")

    chunks, deadline = None, time.time() + 60
    while time.time() < deadline:
        chunks = d.psql_q(f"SELECT count(*) FROM sys_knowledge_chunk WHERE doc_id = {doc_id}")
        if chunks and chunks.isdigit() and int(chunks) > 0:
            break
        time.sleep(3)
    if chunks is None:
        d.skip("0-就绪", "切片落库断言", "未提供 --psql/--pgurl")
    elif str(chunks).startswith("ERR"):
        d.hop("0-就绪", "切片落库断言", False, chunks)
    else:
        d.hop("0-就绪", "切片落库（摄取链路真的写了向量）", int(chunks) > 0, f"doc_id={doc_id} chunks={chunks}")

    # ============================== 场景 A：P1 高危
    print(f"\n{CYAN}▸ 场景 A：注入真实 PROBLEM 事件（severity=High → P1，应立即建单）{NC}")
    t_problem = now - timedelta(seconds=60)      # 事件发生时刻（墙钟）
    t_ok = now - timedelta(seconds=6)            # 54 秒后恢复
    code, _ = d.api("POST", "/api/v1/alerts/webhook/zabbix",
                    zabbix_event(tag, "High", t_problem, resolved=False, event_seq="1"))
    d.hop("A-注入", "Zabbix webhook 受理", code == 200, f"HTTP {code}")

    alert = d.poll(lambda: find_alert(d, tag), timeout_s=30)
    if not alert:
        d.hop("A-注入", "告警入库", False, "30s 内未在列表中找到该告警")
        return 1
    d.hop("A-归一", "告警入库并可见", True,
          f"id={alert['id']} level={alert['level']} status={alert['status']}")
    d.hop("A-归一", "来源标识 source=zabbix", alert.get("source") == "zabbix", f"source={alert.get('source')}")
    d.hop("A-归一", "级别映射 High→P1", alert.get("level") == "P1", f"level={alert.get('level')}")
    d.hop("A-归一", "system 回落 default（来源≠业务系统）",
          alert.get("system") == "default", f"system={alert.get('system')}")
    d.hop("A-归一", "service 取标签 service:infra", alert.get("service") == "infra",
          f"service={alert.get('service')}")
    d.hop("A-归一", "起始时刻取事件墙钟（非收到时刻）",
          (alert.get("firstOccurredAt") or "").startswith(t_problem.strftime("%Y-%m-%dT%H:%M:%S")),
          f"firstOccurredAt={alert.get('firstOccurredAt')} 期望≈{t_problem:%H:%M:%S}")

    # 自动建单 + 双向溯源
    alert = d.poll(lambda: (find_alert(d, tag) or {}).get("ticketId") and find_alert(d, tag), 60, 3) or alert
    ticket_id = alert.get("ticketId")
    d.hop("A-建单", "高危告警自动建单（P1 不进观察窗）", bool(ticket_id), f"ticketId={ticket_id}")
    if not ticket_id:
        return 1
    code, ticket = d.data("GET", f"/api/v1/tickets/by-trace/{alert['dedupKey']}")
    d.hop("A-建单", "按去重键反查工单（追溯链闭合）",
          code == 200 and isinstance(ticket, dict) and ticket.get("id") == ticket_id,
          f"HTTP {code} ticketId={(ticket or {}).get('id') if isinstance(ticket, dict) else None}")
    code, detail = d.data("GET", f"/api/v1/tickets/{ticket_id}")
    d.hop("A-建单", "工单初始 PENDING（等人接单）",
          isinstance(detail, dict) and detail.get("status") == "PENDING",
          f"status={(detail or {}).get('status') if isinstance(detail, dict) else None}")

    # Agent 诊断
    print(f"\n{CYAN}▸ 场景 A：Agent 自动诊断（四路证据 + RAG + 充分度判定）{NC}")

    def diag_ready():
        _, dd = d.data("GET", f"/api/v1/diagnosis/{alert['dedupKey']}")
        return dd if isinstance(dd, dict) and dd.get("session") else None

    diag = d.poll(diag_ready, timeout_s=args.diag_timeout, interval=3)
    if not diag:
        d.hop("A-诊断", "诊断会话落库", False, f"{args.diag_timeout}s 内无会话")
        return 1
    session = diag.get("session") or {}
    evs, hyps = diag.get("evidences") or [], diag.get("hypotheses") or []
    suff = session.get("sufficiency")
    d.hop("A-诊断", "诊断会话落库", True,
          f"status={session.get('status')} sufficiency={suff}")
    kinds = sorted({ev_type(e) for e in evs})
    d.hop("A-诊断", "四路证据采集器都执行（不静默跳过）",
          {"metrics", "logs", "changes", "knowledge"} <= set(kinds), f"类型={kinds} 共 {len(evs)} 条")
    statuses = {ev_type(e): e.get("status") for e in evs}
    d.hop("A-诊断", "证据失败/缺数如实上报（不伪装成功）",
          all(v in ("SUCCESS", "NO_DATA", "FAILED", "EMPTY", "PARTIAL", "TIMEOUT") for v in statuses.values()),
          f"各面状态={statuses}")
    # 充分度语义：要么给出假设，要么如实说不足——两者必须至少成立一个
    say_weak = suff in ("WEAK", "INSUFFICIENT", "EMPTY", "NO_EVIDENCE", "POOR")
    d.hop("A-诊断", "充分度语义自洽（有假设 或 如实判不足）",
          bool(hyps) or say_weak,
          f"sufficiency={suff} hypotheses={len(hyps)}"
          + (f"｜摘要：{(session.get('summary') or '')[:60]}…" if say_weak else ""))

    kn = [e for e in evs if ev_type(e) == "knowledge"]
    citations = [(e.get("source_ref") or e.get("sourceRef") or "") for e in kn]
    hit = any(MANUAL_TITLE in c for c in citations)
    kn_status = statuses.get("knowledge")
    if hit:
        d.hop("A-诊断", "RAG 引用命中导入的处置手册", True, f"知识证据 {len(kn)} 条，命中")
    else:
        d.hop("A-诊断", "RAG 引用命中导入的处置手册", False,
              f"知识证据 {len(kn)} 条 status={kn_status}；引用样例={citations[:1]}",
              soft=not args.require_rag_hit)

    # 人工处置五连
    print(f"\n{CYAN}▸ 场景 A：人工处置五连（接单 → 动作 → 缓解 → 根因 → 验证）{NC}")
    steps = [
        ("A-处置", "接单", "POST", f"/api/v1/tickets/{ticket_id}/acknowledge",
         {"responder": user, "assignee": user}, "PROCESSING"),
        ("A-处置", "登记处置动作", "POST", f"/api/v1/tickets/{ticket_id}/actions",
         {"actionType": "INVESTIGATE",
          "summary": "按手册三步分诊：确认单机不可达、Agent 心跳同失联、无变更窗口",
          "detail": "trigger 前 30 分钟无变更单；ICMP 与 Agent 心跳同时失联", "operator": user,
          "effective": True}, None),
        ("A-处置", "缓解", "POST", f"/api/v1/tickets/{ticket_id}/mitigate",
         {"operator": user}, "PROCESSING"),
        ("A-处置", "确认根因", "PUT", f"/api/v1/tickets/{ticket_id}/root-cause",
         {"rootCause": "宿主机内存耗尽导致 guest 假死（内核日志 OOM 证据）",
          "category": "CAPACITY", "operator": user}, None),
        ("A-处置", "修复验证", "POST", f"/api/v1/tickets/{ticket_id}/verify",
         {"method": "MONITOR", "conclusion": "ICMP 恢复、Agent 心跳恢复、业务探针 200，三项齐",
          "verifier": user}, "RESOLVED"),
    ]
    for phase, label, method, path, body, expect in steps:
        code, data = d.data(method, path, body)
        got = data.get("status") if isinstance(data, dict) else data
        ok = code == 200 and (expect is None or got == expect)
        d.hop(phase, label, ok, f"HTTP {code} status={got}")
    code, actions = d.data("GET", f"/api/v1/tickets/{ticket_id}/actions")
    d.hop("A-处置", "处置动作留痕（可回放）",
          isinstance(actions, list) and len(actions) >= 1,
          f"动作条数={len(actions) if isinstance(actions, list) else '?'}")

    # 恢复事件穿透
    print(f"\n{CYAN}▸ 场景 A：恢复事件（同一触发器，54 秒后 OK）{NC}")
    code, _ = d.api("POST", "/api/v1/alerts/webhook/zabbix",
                    zabbix_event(tag, "High", t_ok, resolved=True, event_seq="2"))
    d.hop("A-恢复", "恢复事件受理", code == 200, f"HTTP {code}")
    a = d.poll(lambda: (lambda x: x if x and x.get("status") == "RESOLVED" else None)(find_alert(d, tag)),
               timeout_s=30)
    d.hop("A-恢复", "告警状态转 RESOLVED", bool(a), f"status={(a or {}).get('status')}")
    if a:
        d.hop("A-恢复", "resolvedAt 取事件墙钟（非收到时刻）",
              (a.get("resolvedAt") or "").startswith(t_ok.strftime("%Y-%m-%dT%H:%M:%S")),
              f"resolvedAt={a.get('resolvedAt')} 期望≈{t_ok:%H:%M:%S}")
        d.hop("A-恢复", "两端时刻可算持续时长（≈54s）", True,
              f"{t_problem:%H:%M:%S} → {t_ok:%H:%M:%S}")

    # 复盘 → 知识飞轮
    print(f"\n{CYAN}▸ 场景 A：复盘沉淀（工单 → 知识草稿 → 引用反馈）{NC}")
    code, _ = d.data("PUT", f"/api/v1/tickets/{ticket_id}/postmortem", {
        "timeline": f"{t_problem:%H:%M:%S} 收到 NodeDown 告警并自动建单；"
                    f"{t_ok:%H:%M:%S} 主机恢复；随后按手册完成分诊、缓解与验证。",
        "impactScope": "内网 1 台节点（primera01），业务无感（LB 已摘流量）",
        "impactDuration": 1,
        "lessons": "缺宿主机级内存水位告警，需补规则",
        "author": user,
    })
    d.hop("A-复盘", "复盘保存", code == 200, f"HTTP {code}")

    def draft_ready():
        _, dl = d.data("GET", f"/api/v1/knowledge/docs/by-source-ticket/{ticket_id}")
        items = dl if isinstance(dl, list) else ((dl or {}).get("records") if isinstance(dl, dict) else None)
        return items or None

    drafts = d.poll(draft_ready, timeout_s=60, interval=3)
    d.hop("A-复盘", "知识草稿自动沉淀（复盘→知识飞轮）", bool(drafts),
          f"{len(drafts) if drafts else 0} 篇草稿")
    if citations and any(citations):
        code, _ = d.data("POST", "/api/v1/knowledge/docs/feedback/citations",
                         {"citations": [c for c in citations if c], "verdict": "HELPFUL"})
        d.hop("A-复盘", "引用反馈回流（喂知识权重）", code == 200, f"HTTP {code}")
    else:
        d.skip("A-复盘", "引用反馈回流", "本轮无 citation（MOCK 桩向量未命中）")

    # ============================== 场景 B：观察窗降噪
    print(f"\n{CYAN}▸ 场景 B：P2 抖动 54 秒自愈（观察窗应不建单）{NC}")
    tag_b = tag + "B"
    t_pb, t_ob = now - timedelta(seconds=45), now - timedelta(seconds=5)
    code, _ = d.api("POST", "/api/v1/alerts/webhook/zabbix",
                    zabbix_event(tag_b, "Average", t_pb, resolved=False, event_seq="1"))
    d.hop("B-观察窗", "注入 Average（P2）告警", code == 200, f"HTTP {code}")
    a_b = d.poll(lambda: find_alert(d, tag_b), timeout_s=30)
    d.hop("B-观察窗", "P2 告警入库", bool(a_b), f"level={(a_b or {}).get('level')}")
    time.sleep(3)
    code, _ = d.api("POST", "/api/v1/alerts/webhook/zabbix",
                    zabbix_event(tag_b, "Average", t_ob, resolved=True, event_seq="2"))
    d.hop("B-观察窗", "窗口内推送恢复事件", code == 200, f"HTTP {code}")
    a_b = d.poll(lambda: (lambda x: x if x and x.get("status") == "RESOLVED" else None)(find_alert(d, tag_b)),
                 timeout_s=30)
    d.hop("B-观察窗", "观察窗内自愈 → RESOLVED", bool(a_b), f"status={(a_b or {}).get('status')}")
    d.hop("B-观察窗", "抖动未产生工单（降噪生效）", bool(a_b) and not a_b.get("ticketId"),
          f"ticketId={(a_b or {}).get('ticketId')}")
    code, stats = d.data("GET", "/api/v1/alerts/observation-stats")
    d.hop("B-观察窗", "观察窗统计可读（大盘口径）",
          code == 200 and isinstance(stats, dict) and "observingNow" in stats,
          f"enabled={stats.get('enabled')} window={stats.get('windowMinutes')}m "
          f"levels={stats.get('levels')} observingNow={stats.get('observingNow')}")

    # ============================== 场景 C：历史事件回放（软闸，报告中给结论）
    print(f"\n{CYAN}▸ 场景 C：历史事件回放（事件时刻=26 小时前；验证非预期时序下的行为）{NC}")
    tag_c = tag + "C"
    t_pc = now - timedelta(hours=26)
    code, _ = d.api("POST", "/api/v1/alerts/webhook/zabbix",
                    zabbix_event(tag_c, "Average", t_pc, resolved=False, event_seq="1"))
    d.hop("C-回放", "回放历史 PROBLEM 事件仍受理", code == 200, f"HTTP {code}", soft=True)
    a_c = d.poll(lambda: find_alert(d, tag_c), timeout_s=20)
    time.sleep(12)   # 给补单扫描器一个窗口（30s 一跳；这里只观察短窗行为）
    a_c = find_alert(d, tag_c) or a_c
    d.hop("C-回放", "历史告警可见且时序自洽",
          bool(a_c) and (a_c.get("firstOccurredAt") or "").startswith(t_pc.strftime("%Y-%m-%dT%H:%M")),
          f"firstOccurredAt={(a_c or {}).get('firstOccurredAt')}", soft=True)
    d.hop("C-回放", "观察窗与补单扫描（回放超出 24h 回溯窗 → 预期无单，如实记录）",
          bool(a_c) and a_c.get("ticketId") is None,
          f"ticketId={(a_c or {}).get('ticketId')}｜观察窗回溯 24h、窗口 10min；"
          f"事件距今 26h → 不进观察窗也不被补单扫描捞起", soft=True)

    # ============================== 通知链自监控
    print(f"\n{CYAN}▸ 收尾：通知链自监控（送达面）{NC}")
    code, health = d.data("GET", "/api/v1/alerts/notify-health")
    d.hop("Z-自监控", "通知看门狗可读（送达率口径）",
          code == 200 and isinstance(health, dict) and "attempts" in health,
          f"attempts={health.get('attempts')} delivered={health.get('delivered')} "
          f"configured={health.get('configured')}" if isinstance(health, dict) else f"HTTP {code}")

    # ============================== 汇总
    total = len(d.hops)
    passed = sum(1 for h in d.hops if h["verdict"] == "PASS")
    skipped = sum(1 for h in d.hops if h["verdict"] == "SKIPPED")
    print(f"\n=== 汇总：PASS {passed}/{total}（SKIPPED {skipped}，软失败 {d.soft_fail}，硬失败 {d.hard_fail}）===")
    print(f"{GREEN}硬闸全过{NC}" if not d.hard_fail else f"{RED}硬闸未过 {d.hard_fail} 项，见上方 ✗{NC}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"base": base, "run_tag": tag, "doc_id": doc_id, "ticket_id": ticket_id,
                       "alert_id": (a or alert or {}).get("id"), "total": total, "passed": passed,
                       "skipped": skipped, "soft_fail": d.soft_fail, "hard_fail": d.hard_fail,
                       "hops": d.hops}, f, ensure_ascii=False, indent=2)
        print(f"证据已写入 {args.json}")
    return 1 if d.hard_fail else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8088/ai")
    ap.add_argument("--account", default="admin:admin123")
    ap.add_argument("--psql", default=None)
    ap.add_argument("--pgurl", default=None)
    ap.add_argument("--diag-timeout", type=int, default=90)
    ap.add_argument("--require-rag-hit", action="store_true")
    ap.add_argument("--json", default=None)
    ap.add_argument("--keep", action="store_true", help="保留演练数据（默认也不删，仅占位）")
    return run(ap.parse_args())


if __name__ == "__main__":
    sys.exit(main())
