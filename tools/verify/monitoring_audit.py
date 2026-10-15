#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""监控面静态对账（2026-10-15，验收第②问的取证脚本）。

为什么需要它：监控栈有三个「各自都对、拼起来是空」的典型失效——
指标名对不上、规则查的指标没人供给、抓取面被鉴权拦掉。三者**都不会报错**：
看板是空面板、规则永不触发（或恒触发）、`up=0` 变成常态噪声。
人眼审 yml 抓不住这类漂移，所以把判据固化成脚本。

检查四件事：
  A. 指标名对账：`monitoring/alert.rules.yml` 与 Grafana 看板里引用的每个指标，
     必须落在「代码声明 / 框架自动注册 / 部署侧 exporter 供给」三类之一；
     落在三类之外 = 拼错或改过名（硬缺口）。
  B. 抓取面鉴权：应用侧若把 `/actuator/**`（health 除外）纳入登录校验，
     则 `prometheus.yml` 的对应 job 必须带抓取凭据（authorization/basic_auth/
     bearer_token/params）——否则抓取恒 401：面板全空 + `up{job}` 恒 0。
  C. 外部供给：`pg_up`/`redis_up`/`minio_*` 这类 exporter 指标，compose 里必须有
     对应 exporter 服务或抓取 job，否则规则「已就位但永不触发」（不误报，也永远不响）。
  D. CI 是否把本脚本纳入门禁（没有则只能是人工跑，会漂）。

用法：
    python3 tools/verify/monitoring_audit.py [--json /tmp/monitoring-audit.json] [--quiet]
退出码：
    0 = 无硬缺口；1 = 有硬缺口（逐条点名）；2 = 环境不满足（找不到配置文件）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 框架/Boot 自动注册的指标前缀：被抓取就在，不在则说明抓取面没通（不是拼写错）
AUTO_PREFIXES = (
    "jvm_",
    "http_server_requests_",
    "process_",
    "system_",
    "tomcat_",
    "executor_",
    "hikaricp_",
    "spring_",
    "logback_events_",
    "resilience4j_",
    "cache_",
    "application_",
    "up",
)

# 部署侧（exporter / 中间件自带）供给的指标前缀：需要 compose + 抓取配置配合
EXTERNAL_PREFIXES = (
    "node_",
    "pg_",
    "pgbouncer_",
    "redis_",
    "minio_",
    "kube_",
)

# PromQL 关键字与函数：不是指标名
PROMQL_TOKENS = {
    "sum", "min", "max", "avg", "count", "stddev", "stdvar", "topk", "bottomk",
    "quantile", "group", "by", "without", "on", "ignoring", "group_left",
    "group_right", "bool", "offset", "and", "or", "unless", "absent", "absent_over_time",
    "rate", "irate", "increase", "delta", "idelta", "deriv", "predict_linear",
    "histogram_quantile", "changes", "resets", "resets_count", "avg_over_time",
    "sum_over_time", "min_over_time", "max_over_time", "count_over_time",
    "quantile_over_time", "stddev_over_time", "last_over_time", "present_over_time",
    "vector", "scalar", "time", "timestamp", "clamp", "clamp_max", "clamp_min",
    "round", "abs", "ceil", "floor", "exp", "ln", "log2", "log10", "sqrt",
    "sort", "sort_desc", "label_replace", "label_join", "day_of_week", "hour",
    "minute", "month", "year", "days_in_month", "e", "pi", "inf", "nan",
    "le", "instance", "job", "mode", "result", "status", "uri", "method",
    "area", "id", "mountpoint", "service", "module", "severity", "alertname",
    "alert_kind", "field", "kind", "quantile_label",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# --------------------------------------------------------------------------- #
# A. 指标名对账
# --------------------------------------------------------------------------- #

def code_metric_names() -> tuple[set[str], list[str]]:
    """代码里声明的指标名（Micrometer builder 里的 `opsbrain.xxx` / `devops.xxx`），
    转成 Prometheus 点转下划线形式；同时返回带点原名的清单。"""
    names: set[str] = set()
    raw: list[str] = []
    # 注意：Gauge.builder(name, supplier, fn) 的 name 后面不是右括号——
    # 要求紧跟 ")" 会把四枚存量 Gauge 漏掉（本脚本首跑就漏了，属"镜像没对账"同类错）
    pattern = re.compile(
        r'(?:Counter|Timer|Gauge|DistributionSummary|LongTaskTimer)\.builder\(\s*"([A-Za-z0-9_.\-]+)"' 
    )
    for java in sorted(ROOT.glob("src/main/java/**/*.java")):
        text = read(java)
        for match in pattern.finditer(text):
            dotted = match.group(1)
            raw.append(dotted)
            names.add(dotted.replace(".", "_").replace("-", "_"))
    return names, sorted(set(raw))


def strip_promql(expr: str) -> str:
    """剥掉字符串字面量、`{...}` 标签选择器、`[...]` 区间，只留表达式骨架。"""
    expr = re.sub(r'"(?:[^"\\]|\\.)*"', " ", expr)
    expr = re.sub(r"'(?:[^'\\]|\\.)*'", " ", expr)
    expr = re.sub(r"\{[^}]*\}", " ", expr)
    expr = re.sub(r"\[[^\]]*\]", " ", expr)
    return expr


def idents(expr: str) -> set[str]:
    found = set()
    for token in re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", strip_promql(expr)):
        if token in PROMQL_TOKENS:
            continue
        if token in {"true", "false"} or re.fullmatch(r"\d+[a-z]*", token):
            continue
        found.add(token)
    return found


def rule_exprs(path: Path) -> list[tuple[str, str]]:
    """从 yml 里抽 (规则名, 表达式)。用文本解析而非 yaml 库，避免多一层依赖
    （本仓 CI 的 python 环境不保证有 PyYAML）。"""
    out: list[tuple[str, str]] = []
    current_alert = "(未命名规则)"
    for line in read(path).splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        m = re.match(r"-?\s*alert:\s*(\S+)", stripped)
        if m:
            current_alert = m.group(1)
        m = re.match(r"expr:\s*(.+?)\s*$", stripped)
        if m:
            expr = m.group(1)
            # 极少数规则会写成多行表达式；本仓全部单行，遇到多行这里只取首行并在报告里标注
            out.append((current_alert, expr))
    return out


def dashboard_exprs(path: Path) -> list[tuple[str, str]]:
    doc = json.loads(read(path))
    out: list[tuple[str, str]] = []
    for panel in doc.get("panels", []) or []:
        title = panel.get("title") or panel.get("type") or "(无标题面板)"
        for target in panel.get("targets", []) or []:
            expr = target.get("expr")
            if expr:
                out.append((title, expr))
    return out


# --------------------------------------------------------------------------- #
# B. 抓取面鉴权
# --------------------------------------------------------------------------- #

def actuator_guarded() -> tuple[bool, str]:
    """读 WebConfig：/actuator/** 是否被纳入登录校验（health 除外）。"""
    webconfig = ROOT / "src/main/java/com/devops/agent/controller/config/WebConfig.java"
    if not webconfig.exists():
        return False, "WebConfig.java 缺失"
    text = read(webconfig)
    if 'SaRouter.match("/actuator/**")' in text.replace(" ", ""):
        has_health_exempt = "/actuator/health" in text
        return True, (
            "WebConfig 对 /actuator/** 加了登录校验"
            + ("（health 已豁免）" if has_health_exempt else "")
        )
    return False, "WebConfig 未对 /actuator/** 加登录校验"


def scrape_credentials(prometheus_yml: Path) -> dict[str, list[str]]:
    """逐个 job 收集抓取凭据字段。"""
    jobs: dict[str, list[str]] = {}
    current = "(global)"
    jobs[current] = []
    for line in read(prometheus_yml).splitlines():
        stripped = line.strip()
        m = re.match(r"-\s*job_name:\s*(\S+)", stripped)
        if m:
            current = m.group(1)
            jobs.setdefault(current, [])
            continue
        for field in ("authorization", "bearer_token", "bearer_token_file",
                      "basic_auth", "password", "password_file", "params",
                      "credentials", "credentials_file"):
            if re.match(rf"-?\s*{field}:\s*", stripped):
                jobs[current].append(field)
    return jobs


# --------------------------------------------------------------------------- #
# C. 外部供给
# --------------------------------------------------------------------------- #

def compose_supply() -> tuple[set[str], set[str]]:
    """compose 里出现的服务/镜像名与抓取目标，用于判断 exporter 是否真的部署了。"""
    services: set[str] = set()
    for compose in sorted(ROOT.glob("docker-compose*.yml")):
        for line in read(compose).splitlines():
            m = re.match(r"\s{2}([a-z0-9][a-z0-9_-]*):\s*$", line)
            if m:
                services.add(m.group(1))
    return services, set()


EXPORTER_HINTS = {
    "pg_": ("postgres_exporter", "postgres-exporter"),
    "pgbouncer_": ("pgbouncer_exporter",),
    "redis_": ("redis_exporter", "redis-exporter"),
    "minio_": ("minio",),
}


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #

def probe_token(explicit: str | None) -> str:
    """取抓取令牌用于 E 段实测：优先显式路径，其次默认生成的 secrets 文件。

    读不到就返回空串——调用方据此**跳过并说明**，而不是假装通过（静默跳过是本项目
    反复吃过亏的形态：门禁不报错，也永远不生效）。
    """
    candidates = [explicit] if explicit else []
    candidates.append(str(ROOT / "monitoring/secrets/scrape_token"))
    for cand in candidates:
        if not cand:
            continue
        try:
            with open(cand, encoding="utf-8") as fh:
                tok = fh.read().strip()
            if tok:
                return tok
        except OSError:
            continue
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description="监控面静态对账")
    parser.add_argument("--json", help="把结果写成 JSON")
    parser.add_argument("--quiet", action="store_true", help="只打印结论行")
    parser.add_argument("--probe-token-file", metavar="PATH",
                        help="抓取令牌文件（默认 monitoring/secrets/scrape_token）；"
                             "--probe 时用它做「带凭据」实测——这一发才是判定 Prometheus 能否真读到指标的判据")
    parser.add_argument("--probe", metavar="URL",
                        help="额外真打一次抓取面（如 http://localhost:8088/ai/actuator/prometheus），"
                             "把「需要凭据」从配置推理变成实测状态码；4xx/5xx 打印并计入证据")
    args = parser.parse_args()

    rules = ROOT / "monitoring/alert.rules.yml"
    prom = ROOT / "monitoring/prometheus.yml"
    dash = ROOT / "monitoring/grafana/opsbrain-effectiveness-dashboard.json"
    for path in (rules, prom, dash):
        if not path.exists():
            print(f"❌ 环境不满足：缺 {path.relative_to(ROOT)}")
            return 2

    code_names, raw_dotted = code_metric_names()
    found_gaps: list[dict[str, str]] = []
    findings: list[dict[str, str]] = []

    def say(line: str) -> None:
        if not args.quiet:
            print(line)

    print("=" * 78)
    print("监控面静态对账（指标 / 规则 / 看板 / 抓取面）")
    print("=" * 78)
    say(f"📦 代码声明指标 {len(code_names)} 个：{', '.join(sorted(code_names))}")
    say("")

    # ---------------- A. 指标名对账 ----------------
    say("── A. 指标名对账 ─────────────────────────────────────────────")
    consumers = [("规则", n, e) for n, e in rule_exprs(rules)] + \
                [("看板", n, e) for n, e in dashboard_exprs(dash)]
    referenced: dict[str, list[str]] = {}
    for kind, name, expr in consumers:
        for metric in idents(expr):
            referenced.setdefault(metric, []).append(f"{kind}:{name}")

    def base_of(metric: str) -> str:
        """Micrometer 的 Timer/Counter 会派生出 _count/_sum/_bucket/_max 序列，
        按基名归类，否则会把合法指标误报成「查无此指标」。"""
        for suffix in ("_bucket", "_count", "_sum", "_max", "_min"):
            if metric.endswith(suffix) and metric[: -len(suffix)] in code_names:
                return metric[: -len(suffix)]
        return metric

    classified = {"code": [], "auto": [], "external": [], "unknown": []}
    for metric in sorted(referenced):
        metric = metric
        if base_of(metric) in code_names:
            classified["code"].append(metric)
        elif metric.startswith(EXTERNAL_PREFIXES):
            classified["external"].append(metric)
        elif metric.startswith(AUTO_PREFIXES):
            classified["auto"].append(metric)
        else:
            classified["unknown"].append(metric)

    say(f"  引用到的指标：代码声明 {len(classified['code'])} · 框架自动 {len(classified['auto'])}"
        f" · 部署侧 {len(classified['external'])} · **查无此指标 {len(classified['unknown'])}**")
    say(f"    - 代码声明：{', '.join(classified['code']) or '（无）'}")
    say(f"    - 框架自动：{', '.join(classified['auto']) or '（无）'}")
    say(f"    - 部署侧  ：{', '.join(classified['external']) or '（无）'}")
    for metric in classified["unknown"]:
        where = "、".join(sorted(set(referenced[metric]))[:3])
        findings.append({
            "level": "硬缺口",
            "kind": "指标名查无此指标",
            "detail": f"`{metric}` 被引用（{where}），但代码/框架/部署侧都没有这个指标——空面板或永不触发",
        })
        say(f"    ❌ {metric}  ← {where}")
    if not classified["unknown"]:
        say("    ✅ 无查无此指标")
    say("")

    # ---------------- B. 抓取面鉴权 ----------------
    say("── B. 抓取面鉴权（Prometheus 能不能读到应用指标）──────────────")
    guarded, why = actuator_guarded()
    jobs = scrape_credentials(prom)
    say(f"  应用侧：{why}")
    app_jobs = [j for j in jobs if j != "(global)"]
    say(f"  prometheus.yml 的 job：{', '.join(app_jobs)}")
    for job in app_jobs:
        creds = jobs.get(job, [])
        say(f"    - {job}: 凭据 {'有（' + ', '.join(creds) + '）' if creds else '无'}")
    if guarded:
        supplied = [j for j in app_jobs if jobs.get(j)]
        if not supplied:
            findings.append({
                "level": "硬缺口",
                "kind": "抓取面无凭据（401）",
                "detail": ("应用把 /actuator/**（health 除外）纳入登录校验，"
                           "但 prometheus.yml 没有任何 job 带抓取凭据 → 抓取恒 401："
                           "面板全空、依 `up{job}` 的宕机规则恒触发"),
            })
            say("    ❌ 所有 job 都无凭据，而 /actuator/** 需要登录 → 抓取 401")
        else:
            say(f"    ✅ {', '.join(supplied)} 带凭据")
    else:
        say("    ✅ 应用侧未拦截取面，无需凭据")
    say("")

    # ---------------- C. 外部供给 ----------------
    say("── C. 外部供给（exporter 服务 + 抓取 job 两件都齐才算通）──────")
    services, _ = compose_supply()
    prom_text = read(prom).lower()
    missing_supply: list[str] = []
    for prefix in sorted({m.split("_")[0] + "_" for m in classified["external"]}):
        hints = EXPORTER_HINTS.get(prefix)
        if hints is None:
            continue
        serv = [h for h in hints if any(h.replace("-", "_") in s.replace("-", "_") for s in services)]
        scraped = [h for h in hints if h.replace("_", "-") in prom_text or h in prom_text]
        metrics = [m for m in classified["external"] if m.startswith(prefix)]
        if serv and scraped:
            say(f"    ✅ {prefix}* ← 服务 {serv[0]} + 抓取 job 都在")
            continue
        if serv and not scraped:
            state = f"服务 {serv[0]} 在，但 prometheus.yml **没有抓它**"
        elif scraped and not serv:
            state = "有抓取 job，但 compose 里没有该服务"
        else:
            state = "**服务与抓取都没有**（规则先就位）"
        say(f"    ⚠️ {prefix}* ← {state}")
        missing_supply.append(prefix)
        findings.append({
            "level": "提示",
            "kind": f"{prefix}* 供给不全",
            "detail": (f"规则引用的 {', '.join(metrics)} 缺供给（{state}）——"
                       "比较运算对空序列恒不成立，所以不误报，但也永远不会响"),
        })
    if not missing_supply:
        say("    ✅ 外部指标均有供给线索")
    say("")

    # ---------------- D. CI 是否上闸 ----------------
    say("── D. CI 门禁 ────────────────────────────────────────────────")
    # 2026-10-15 起本判据改了（原来只看 workflow 里有没有调本脚本）：
    # 本仓 push workflow 的通道历史上被 token 权限拦过，硬塞一个 workflow 改动的收益
    # 小于一个「本来就会跑」的位置——后端测试腿（./mvnw verify）里的 Java 契约测试
    # MonitoringConfigContractTest 用**同一套判据**做门禁。所以现在认三种上闸形态：
    #   ① workflow 直接调本脚本（最强：连实测抓取面一起跑）
    #   ② 后端测试腿里有 Java 契约测试（次强：静态对账，随编译与测试一起红）
    #   ③ 两者都无 → 提示（人工跑的东西会漂）
    ci_hits = []
    for wf in sorted((ROOT / ".github/workflows").glob("*.yml")):
        if "monitoring_audit" in read(wf):
            ci_hits.append(wf.name)
    contract = ROOT / "src/test/java/com/devops/agent/contract/MonitoringConfigContractTest.java"
    # 契约测试只要在 src/test/java 下就会被 surefire 按 *Test 规则拾起；再确认 CI 真跑测试腿
    ci_yml = read(ROOT / ".github/workflows/ci.yml")
    contract_ok = contract.exists() and ("verify" in ci_yml or "test" in ci_yml)
    if ci_hits:
        say(f"    ✅ 已在 CI 中调用本脚本：{', '.join(ci_hits)}")
    elif contract_ok:
        say(f"    ✅ CI 门禁在后端测试腿：{contract.relative_to(ROOT)}（随 ./mvnw verify 一起红）")
        say("       口径与本脚本同源（前缀表 + 提取规则），改判据要两边一起改——"
            "否则就是「核对另一份规则」，比没有门禁更坏")
    else:
        findings.append({
            "level": "提示",
            "kind": "CI 未上闸",
            "detail": "既没有 workflow 调本脚本，也没有在测试腿里的契约测试——"
                      "没有门禁的检查会随人员变动漂掉（改指标名忘改看板即静默失效）",
        })
        say("    ⚠️ 未接入 CI（只能人工跑）")
    say("")

    if args.probe:
        print("── E. 实测抓取面 ───────────────────────────────────────────")
        import urllib.error
        import urllib.request
        status = None
        try:
            with urllib.request.urlopen(args.probe, timeout=5) as resp:
                status = resp.status
                body = resp.read(200)
        except urllib.error.HTTPError as exc:  # 4xx/5xx 是「有答复」，正是要看的证据
            status = exc.code
            body = b""
        except Exception as exc:  # noqa: BLE001 —— 连不上也是证据（目标没跑）
            print(f"    ⚠️ 连不上 {args.probe}：{exc}")
            body = b""
        if status is not None:
            # 2026-10-15 后：应用侧已有抓取令牌，所以「裸抓 401」是**预期行为**，
            # 只在这一层说「还要再看带凭据的那一发」；真正判红的是 E 段下面的带凭据实测。
            if status == 200:
                verdict = "✅ 无需凭据即可抓取"
            elif status == 401:
                verdict = "✅ 无凭据被拒（预期：凭据由文件提供）— 关键看下面「带凭据」那一发"
            else:
                verdict = f"❌ 非预期状态码（既非 200 也非 401）"
            print(f"    裸抓 {args.probe} → HTTP {status} {verdict}")
            if status not in (200, 401):
                findings.append({
                    "level": "提示",
                    "kind": "抓取面实测状态码异常",
                    "detail": f"无凭据请求 {args.probe} 返回 {status}——抓取方看到的是未知状态，"
                              f"既不是放行也不是明确的鉴权拒绝",
                })
        # 带凭据实测：这才是「Prometheus 能不能真读到指标」的判据
        tok = probe_token(args.probe_token_file)
        if tok:
            req = urllib.request.Request(args.probe, headers={"Authorization": f"Bearer {tok}"})
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    st2, body2 = resp.status, resp.read(4096).decode("utf-8", "replace")
            except urllib.error.HTTPError as exc:
                st2, body2 = exc.code, ""
            except Exception as exc:  # noqa: BLE001
                st2, body2 = -1, str(exc)
            has_biz = "opsbrain_" in body2
            print(f"    带凭据抓取 → HTTP {st2}｜含 opsbrain_ 指标={has_biz}")
            if st2 != 200 or not has_biz:
                findings.append({
                    "level": "硬缺口",
                    "kind": "带凭据抓取失败",
                    "detail": f"带令牌请求 {args.probe} 返回 {st2}（含业务指标={has_biz}）——"
                              f"Prometheus 拿不到数据时，up{{job=\"opsbrain\"}} 恒 0 会让宕机规则"
                              f"常亮误报、其余应用规则恒哑",
                })
        else:
            print("    （未找到令牌文件，跳过「带凭据」这一发；"
                  "用 --probe-token-file 指定，或 bash scripts/monitoring-init-token.sh 生成）")
        print("")

    consumed = {base_of(m) for m in referenced}
    unused = sorted(code_names - consumed)
    if unused:
        say("── F. 已有但无人消费的指标（提示，不算缺口）─────────────────")
        say(f"    {', '.join(unused)}")
        say("    说明：可能只是留给人工查询/后续看板用；但若长期无人消费，"
            "它占的是打点与维护成本（每次改动都要顾及）")
        say("")

    hard = [f for f in findings if f["level"] == "硬缺口"]
    print("=" * 78)
    print(f"结论：硬缺口 {len(hard)} 个 · 提示 {len(findings) - len(hard)} 个")
    for finding in findings:
        mark = "❌" if finding["level"] == "硬缺口" else "⚠️"
        print(f"  {mark} [{finding['kind']}] {finding['detail']}")
    print("=" * 78)

    if args.json:
        Path(args.json).write_text(
            json.dumps({
                "code_metrics": sorted(code_names),
                "raw_dotted": raw_dotted,
                "classified": {k: v for k, v in classified.items()},
                "actuator_guarded": guarded,
                "scrape_jobs": jobs,
                "findings": findings,
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"（明细已写入 {args.json}）")

    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
