#!/usr/bin/env python3
"""灰度台账数据取数器（DoD「真实值班质量数」的可复跑口径）。

为什么要有这个工具
------------------
灰度台账 §三 有六个数据槽位要按周填。Week1（10-06~10-12）的窗口**已经过去且填不出来**，
台账如实记了「不可回溯，属实际缺口」。根因不是忘了记，而是**没有取数口径**：

  · 「0-P1 误伤」的数据源 `sys_alert.false_positive` 是 V17（2026-10-12）才加的列，
    Week1 有六天这列根本不存在——结构性不可回溯；
  · 台账写「送达率读 notify_delivery 口径」，但**库里没有这张表**——它是 Prometheus
    计数器 `opsbrain_notify_delivery_total{result=ok|error}`，照台账去查 SQL 永远查不到；
  · 其余几项其实**早就有日快照列**（`sys_effectiveness_snapshot`），只是没人把它们
    拼成台账要的那几行。

所以 Week2（10-13~10-19）如果还靠手记，会变成第二个不可回溯的窗口。本工具把六行
一次性取出来：**能算的算，算不了的明确标「人工登记」并说明为什么**——不含糊、不猜。

口径要点（写错就取错数，故逐条注明来源）
----------------------------------------
  · 告警分级列叫 **`level`**（P0/P1/P2/P3/P4），不是 severity；
  · 工单创建时刻是 **`create_time`**，不是 created_at；
  · `sys_effectiveness_snapshot` 的列是**累计值**（见 EffectivenessWeeklyReportScheduler
    的注释「周报口径全部取自快照行（累计值），不实时重算」）——所以窗口内增量要用
    「窗口末读数 − 窗口前一读数」，**不能 SUM**（SUM 会把累计值重复相加）；
  · 误报按 **`false_positive_marked_at`** 取窗口（V17 注释：上周到的告警这周被标误报，
    应计入这周的反馈），不按告警到达时间。

用法
----
    python3 tools/verify/grayscale_metrics.py --from 2026-10-13 --to 2026-10-19 \\
        --psql /usr/bin/psql --pgurl postgresql://user:pwd@host:5432/devops \\
        [--base http://localhost:8088/ai] [--scrape-token-file /run/secrets/scrape_token] \\
        [--json /tmp/grayscale.json]

退出码：0 = 该出的数都出来了（人工登记项不算失败）；1 = 有本可算的行取数失败（SQL 错/连不上）。
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import urllib.request

# 台账 §三 的六行。顺序即输出顺序。
ROWS = [
    "连续 0-P1 天数",
    "ACK 演练次数（目标 ×3）",
    "通知送达率",
    "诊断触发率/产报率",
    "故障修复时长对比（前 vs 后）",
    "自愈执行次数（台账读数）",
]

MANUAL_ROWS = {
    # DoD#3 要求「真人 + 真钉钉渠道 15 分钟口径 ×3」——这是人的动作，库里没有也不该有。
    "ACK 演练次数（目标 ×3）": "人工登记（DoD#3：真人 + 真渠道演练，非系统可派生）",
}


# ───────────────────────── 纯函数（可单测，见 test_grayscale_metrics.py） ─────────────────────────


def daterange(day_from: dt.date, day_to: dt.date) -> list[dt.date]:
    """闭区间日期列表。from > to 时返回空（调用方据此判「窗口非法」）。"""
    if day_from > day_to:
        return []
    return [day_from + dt.timedelta(days=i) for i in range((day_to - day_from).days + 1)]


def consecutive_zero_days(days: list[dt.date], bad: set[dt.date]) -> int:
    """从窗口末尾往回数，连续「当天 0 起 P1 误伤」的天数。

    台账 DoD 的判据是「**连续** 7 天 0-P1 误伤」——所以要的是尾部连续段长度，
    不是窗口内合格天数总和（中间断一天就得重新起算）。
    """
    n = 0
    for d in reversed(days):
        if d in bad:
            break
        n += 1
    return n


def cumulative_delta(latest: float | None, before: float | None) -> float | None:
    """累计快照的窗口增量 = 窗口末读数 − 窗口前最后一次读数。

    快照列是累计值，SUM 会重复相加（这是最容易写错的地方）。
    窗口前没有任何快照时，窗口末读数本身就是增量（从 0 起算）。
    """
    if latest is None:
        return None
    if before is None:
        return latest
    return latest - before


def rate(part: float | None, whole: float | None) -> str:
    """百分比文本。分母为 0 时不谎报 100%/0%，而是明确「无样本」。"""
    if part is None or whole is None:
        return "无数据"
    if whole == 0:
        return "无样本（分母 0）"
    return f"{part / whole * 100:.1f}%（{int(part)}/{int(whole)}）"


def parse_counter(text: str, metric: str, label: str, value: str) -> float | None:
    """从 Prometheus 文本里取 `metric{...label="value"...}` 的值并求和。

    自己解析而不是引 prometheus_client：本仓 tools/verify 一律零第三方依赖
    （沙箱与 runner 都能直接跑）。按行匹配 + 忽略注释行，够用于计数器等值读取。
    """
    total = 0.0
    found = False
    needle = f'{label}="{value}"'
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if not s.startswith(metric):
            continue
        # 必须是完整指标名（避免 notify_total 误配 notify_delivery_total）
        rest = s[len(metric):]
        if not (rest.startswith("{") or rest.startswith(" ")):
            continue
        if needle not in s:
            continue
        try:
            total += float(s.rsplit(" ", 1)[1])
            found = True
        except (IndexError, ValueError):
            continue
    return total if found else None


def median(xs: list[float]) -> float | None:
    if not xs:
        return None
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2


def fmt_hours(seconds: float | None) -> str:
    if seconds is None:
        return "无数据"
    return f"{seconds / 3600:.1f} 小时"


def render_table(rows: list[dict]) -> str:
    """渲染成台账 §三 可直接粘的表格（含来源列，便于复核）。"""
    out = ["| 指标 | 本期读数 | 来源/口径 |", "| - | - | - |"]
    for r in rows:
        out.append(f"| {r['name']} | {r['value']} | {r['source']} |")
    return "\n".join(out)


# ─────────────────────────────── 取数（要真库/真指标端点） ───────────────────────────────


class Taker:
    def __init__(self, psql: str | None, pgurl: str | None, base: str, token_file: str | None):
        self.psql, self.pgurl, self.base, self.token_file = psql, pgurl, base, token_file

    def q(self, sql: str) -> tuple[bool, str | None]:
        """执行 SQL。返回 (成功?, 单值文本)。没给 --psql/--pgurl 时返回 (False, None)。"""
        if not self.psql or not self.pgurl:
            return False, None
        try:
            out = subprocess.run([self.psql, self.pgurl, "-t", "-A", "-c", sql],
                                 capture_output=True, text=True, timeout=30)
        except Exception as e:  # noqa: BLE001 - 取数失败要把原因带出来，不能让栈淹掉判词
            return False, f"ERR: {type(e).__name__}: {e}"
        if out.returncode != 0:
            return False, f"ERR: {out.stderr.strip()[:200]}"
        return True, out.stdout.strip()

    def num(self, sql: str) -> tuple[bool, float | None, str | None]:
        ok, raw = self.q(sql)
        if not ok:
            return False, None, raw
        if raw in ("", None):
            return True, None, None
        try:
            return True, float(raw), None
        except ValueError:
            return False, None, f"ERR: 非数值返回 {raw[:80]}"

    def prometheus(self) -> tuple[bool, str | None]:
        url = f"{self.base.rstrip('/')}/actuator/prometheus"
        req = urllib.request.Request(url)
        token = None
        if self.token_file:
            try:
                with open(self.token_file, encoding="utf-8") as f:
                    token = f.read().strip()
            except OSError as e:
                return False, f"ERR: 读不到令牌文件 {self.token_file}: {e}"
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return True, r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            return False, f"ERR: 抓取面取不到 {url}: {type(e).__name__}: {e}"


def snapshot_delta(t: Taker, col: str, day_from: dt.date, day_to: dt.date) -> tuple[bool, float | None, str | None]:
    """累计快照列的窗口增量（末读数 − 窗口前读数）。"""
    sql = f"""
SELECT COALESCE((SELECT {col} FROM sys_effectiveness_snapshot
                 WHERE snapshot_date <= DATE '{day_to}' ORDER BY snapshot_date DESC LIMIT 1), 0)
     - COALESCE((SELECT {col} FROM sys_effectiveness_snapshot
                 WHERE snapshot_date <  DATE '{day_from}' ORDER BY snapshot_date DESC LIMIT 1), 0)
"""
    return t.num(sql)


def collect(t: Taker, day_from: dt.date, day_to: dt.date, rows: list[dict]) -> bool:
    """逐行取数，把结果写进 rows。返回是否全部「该出的都出来了」。"""
    db_ok = bool(t.psql and t.pgurl)
    all_ok = True

    def add(name: str, value: str, source: str, failed: bool = False):
        rows.append({"name": name, "value": value, "source": source, "failed": failed})

    # ① 连续 0-P1 天数：按 false_positive_marked_at 落窗，level ∈ {P0,P1}
    days = daterange(day_from, day_to)
    if not db_ok:
        add(ROWS[0], "未取（缺 --psql/--pgurl）", "sys_alert.false_positive + level（V17 起有此列）", failed=True)
        all_ok = False
    else:
        ok, raw, err = t.q(f"""
SELECT DISTINCT false_positive_marked_at::date FROM sys_alert
WHERE false_positive AND level IN ('P0','P1')
  AND false_positive_marked_at::date BETWEEN DATE '{day_from}' AND DATE '{day_to}'
""")
        if not ok:
            add(ROWS[0], f"取数失败（{err}）", "sys_alert（level/false_positive_marked_at）", failed=True)
            all_ok = False
        else:
            bad = set()
            for line in (raw or "").splitlines():
                line = line.strip()
                if line:
                    try:
                        bad.add(dt.date.fromisoformat(line))
                    except ValueError:
                        pass
            n = consecutive_zero_days(days, bad)
            verdict = "达标" if n >= 7 else f"未达标（需连续 7 天）"
            add(ROWS[0], f"{n} 天 —— {verdict}",
                f"sys_alert：窗口内 P0/P1 误报标记日 {len(bad)} 天，从 {day_to} 往回连续计数")

    # ② ACK 演练次数：人工
    add(ROWS[1], "人工登记", MANUAL_ROWS[ROWS[1]])

    # ③ 通知送达率：Prometheus 计数器（不是表！台账原写「读 notify_delivery 口径」易误导）
    ok, text = t.prometheus()
    if not ok:
        add(ROWS[2], f"未取（{text}）",
            "Prometheus opsbrain_notify_delivery_total{result}（**不是数据库表**）", failed=True)
        all_ok = False
    else:
        okn = parse_counter(text, "opsbrain_notify_delivery_total", "result", "ok")
        errn = parse_counter(text, "opsbrain_notify_delivery_total", "result", "error")
        whole = None if (okn is None and errn is None) else (okn or 0) + (errn or 0)
        add(ROWS[2], rate(okn, whole),
            "opsbrain_notify_delivery_total{result=ok|error}：ok/(ok+error)；受理≠送达，此为渠道亲口回执")

    # ④ 诊断触发率 / 产报率：快照累计列取增量
    if not db_ok:
        add(ROWS[3], "未取（缺 --psql/--pgurl）", "sys_effectiveness_snapshot（累计列取增量）", failed=True)
        all_ok = False
    else:
        res, parts, bad = [], [], None
        for col, label in (("alerts_total", "告警"), ("alert_sourced_tickets", "告警转单"),
                           ("diagnosis_total", "诊断"), ("diagnosis_sufficient", "诊断充分")):
            ok, v, err = snapshot_delta(t, col, day_from, day_to)
            if not ok:
                bad = err
                break
            parts.append((label, v))
            res.append(v)
        if bad:
            add(ROWS[3], f"取数失败（{bad}）", "sys_effectiveness_snapshot", failed=True)
            all_ok = False
        else:
            alerts, sourced, diag, suff = res
            add(ROWS[3],
                f"触发率 {rate(sourced, alerts)}｜产报率(充分) {rate(suff, diag)}",
                "sys_effectiveness_snapshot 增量：alert_sourced_tickets/alerts_total、"
                "diagnosis_sufficient/diagnosis_total（累计列，末读数−窗口前读数）")

    # ⑤ 故障修复时长：工单 create_time → mitigated_at（止损=业务恢复，台账关心的口径）
    if not db_ok:
        add(ROWS[4], "未取（缺 --psql/--pgurl）", "sys_devops_ticket（create_time/mitigated_at）", failed=True)
        all_ok = False
    else:
        ok, raw, err = t.q(f"""
SELECT EXTRACT(EPOCH FROM (mitigated_at - create_time)) FROM sys_devops_ticket
WHERE mitigated_at IS NOT NULL
  AND mitigated_at::date BETWEEN DATE '{day_from}' AND DATE '{day_to}'
""")
        if not ok:
            add(ROWS[4], f"取数失败（{err}）", "sys_devops_ticket", failed=True)
            all_ok = False
        else:
            vals = []
            for line in (raw or "").splitlines():
                line = line.strip()
                if line:
                    try:
                        vals.append(float(line))
                    except ValueError:
                        pass
            med = median(vals)
            note = "（灰度前基线需另取开跑日之前的同口径中位数对比）"
            add(ROWS[4], f"止损中位数 {fmt_hours(med)}｜样本 {len(vals)} 单 {note}",
                "sys_devops_ticket：mitigated_at − create_time，窗口内按 mitigated_at 取")

    # ⑥ 自愈执行次数：sys_healing_execution
    if not db_ok:
        add(ROWS[5], "未取（缺 --psql/--pgurl）", "sys_healing_execution（created_at/status）", failed=True)
        all_ok = False
    else:
        ok, raw, err = t.q(f"""
SELECT count(*), count(*) FILTER (WHERE status = 'SUCCEEDED') FROM sys_healing_execution
WHERE created_at::date BETWEEN DATE '{day_from}' AND DATE '{day_to}'
""")
        if not ok:
            add(ROWS[5], f"取数失败（{err}）", "sys_healing_execution", failed=True)
            all_ok = False
        else:
            fields = (raw or "|").split("|")
            total = int(fields[0] or 0)
            succ = int(fields[1] or 0) if len(fields) > 1 else 0
            add(ROWS[5], f"{total} 次（成功 {succ}）",
                "sys_healing_execution：窗口内按 created_at 计数，成功=status='SUCCEEDED'")

    return all_ok


def main() -> int:
    ap = argparse.ArgumentParser(description="灰度台账数据取数器（台账 §三 六行）")
    ap.add_argument("--from", dest="day_from", required=True, help="窗口起（含），YYYY-MM-DD")
    ap.add_argument("--to", dest="day_to", required=True, help="窗口止（含），YYYY-MM-DD")
    ap.add_argument("--psql", default=None)
    ap.add_argument("--pgurl", default=None)
    ap.add_argument("--base", default="http://localhost:8088/ai")
    ap.add_argument("--scrape-token-file", default=None,
                    help="抓取面令牌文件（配了 SCRAPE_TOKEN 时必需，否则 /actuator/prometheus 回 401）")
    ap.add_argument("--json", default=None, help="证据落盘路径")
    args = ap.parse_args()

    try:
        day_from = dt.date.fromisoformat(args.day_from)
        day_to = dt.date.fromisoformat(args.day_to)
    except ValueError as e:
        print(f"::error::日期格式应为 YYYY-MM-DD：{e}")
        return 2
    if not daterange(day_from, day_to):
        print(f"::error::窗口非法：--from {day_from} 晚于 --to {day_to}")
        return 2

    rows: list[dict] = []
    t = Taker(args.psql, args.pgurl, args.base, args.scrape_token_file)
    all_ok = collect(t, day_from, day_to, rows)

    print(f"=== 灰度台账取数：{day_from} ~ {day_to}（{(day_to - day_from).days + 1} 天）===")
    print(render_table(rows))
    failed = [r["name"] for r in rows if r.get("failed")]
    if failed:
        print(f"\n未取到：{len(failed)} 行 —— {', '.join(failed)}")
    if not (args.psql and args.pgurl):
        print("\n提示：未提供 --psql/--pgurl，五项库内指标未取（只有送达率走抓取面）。")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"window": {"from": str(day_from), "to": str(day_to)},
                       "rows": rows, "complete": all_ok}, f, ensure_ascii=False, indent=2)
        print(f"证据已落盘：{args.json}")

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
