#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z-监控面 哨兵自测：证明这个哨兵**不会永远 PASS**

为什么要有它
============
`closed_loop_drill.py` 的 Z-监控面 组是「元监控的哨兵」：它断言抓取面
（无凭据必须 401 / 带凭据必须 200 且真含 `opsbrain_*`）。但哨兵本身也会坏——
坏法通常是**恒 PASS**（比如凭据读不到就默默放过、或响应读取窗口太小导致
"有没有业务指标"这个判断根本没看全）。恒 PASS 的哨兵比没有哨兵更坏：
它让人以为「监控面有人看着」。

所以这里用桩服务把后端摆成 6 种形态，逐一核对哨兵的判词：
该红的必须红、该绿的必须绿。首版自测就是靠这个抓出过一个真 bug
（`ctx` 用了另一函数的局部变量 → 带凭据抓取恒连接失败）。

本文件从 `.local-verify/scripts/`（沙箱重置后已丢失、不可复现）收进仓库，
并接进 CI（e2e 腿），使"哨兵可信"这件事本身也成为门禁。

用法
====
    python3 tools/verify/selftest_scrape_face.py [--json /tmp/scrape-face-selftest.json]

退出码：0=六态判词全部符合预期；1=有形态判错（哨兵不可信）。
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from closed_loop_drill import Drill  # noqa: E402

TOKEN = "selftest-token-2f8a1c9d"

# 真件形态：Prometheus 文本按指标族名排序，应用族 opsbrain_* 排在
# application_/executor_/http_/jvm_/logback_ 之后（这正是 4KB 窗口假阳的成因）
PAD_FAMILY = "# HELP zzz_padding_metric 填充族\n# TYPE zzz_padding_metric gauge\n"
BIZ_FAMILY = "# HELP opsbrain_alert_total 告警总量\n# TYPE opsbrain_alert_total counter\nopsbrain_alert_total 7.0\n"
JVM_ONLY = "# HELP jvm_memory_used_bytes JVM 内存\n# TYPE jvm_memory_used_bytes gauge\njvm_memory_used_bytes 1.0\n"

BODY_CORRECT = JVM_ONLY + BIZ_FAMILY
BODY_FAR = JVM_ONLY + (PAD_FAMILY + "zzz_padding_metric 1.0\n") * 400 + BIZ_FAMILY  # opsbrain_* 在 ~30KB 之后
BODY_NO_BIZ = JVM_ONLY * 50


class Stub(BaseHTTPRequestHandler):
    """按 STATE 扮演不同"坏法"的后端。"""

    STATE = "correct"

    def _send(self, code: int, body: str = "") -> None:
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/ai/actuator/prometheus":
            self._send(404)
            return
        authed = self.headers.get("Authorization") == f"Bearer {TOKEN}"
        state = Stub.STATE
        if state == "naked":                      # 指标面裸奔：不带凭据也 200
            self._send(200, BODY_CORRECT)
        elif state == "correct" or state == "far":
            self._send(200 if authed else 401,
                       BODY_FAR if state == "far" else BODY_CORRECT)
        elif state == "wrong_token":              # 凭据配错：带了也 401
            self._send(401)
        elif state == "no_metrics":               # 端点在，但一个业务指标都没有
            self._send(200 if authed else 401, JVM_ONLY if authed else "")
        else:
            self._send(500, "unknown state")

    def log_message(self, *args) -> None:  # 静音
        pass


# 每态的期望判词：must_fail = 必须出现的失败跳关键字；must_pass = 必须通过的跳关键字
CASES = [
    ("correct", "正确接线（基线）",
     {"must_fail": [], "must_pass": ["无凭据抓取被拒", "带凭据抓取成功"]}),
    ("naked", "指标面裸奔（不带凭据也 200）",
     {"must_fail": ["无凭据抓取被拒"], "must_pass": []}),
    ("wrong_token", "凭据配错（带令牌仍 401）",
     {"must_fail": ["带凭据抓取成功"], "must_pass": ["无凭据抓取被拒"]}),
    ("no_metrics", "端点在但计量器没接上（200 却无 opsbrain_*）",
     {"must_fail": ["带凭据抓取成功"], "must_pass": ["无凭据抓取被拒"]}),
    ("far", "业务指标排在 4KB 之后（真件形态，读取窗口回归）",
     {"must_fail": [], "must_pass": ["无凭据抓取被拒", "带凭据抓取成功"]}),
    ("missing_file", "令牌文件缺失（必须 fail-closed，不许静默放过）",
     {"must_fail": ["读取令牌文件"], "must_pass": []}),
]


def run_state(state: str, base: str, token_file: str | None) -> list[dict]:
    Stub.STATE = state
    d = Drill(base, "", None, None)
    d.check_scrape_face(base, token_file)
    return d.hops


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None, help="证据落盘路径")
    args = ap.parse_args()

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}/ai"

    tok_path = Path("/tmp/selftest-scrape-token")
    tok_path.write_text(TOKEN + "\n", encoding="utf-8")

    results, bad = [], 0
    print("=== Z-监控面 哨兵自测（桩服务六态）===")
    for state, label, expect in CASES:
        token_file = str(tok_path) if state != "missing_file" else "/tmp/selftest-does-not-exist"
        hops = run_state(state, base, token_file)
        failed = [h["key"] for h in hops if h["verdict"] == "FAIL"]
        passed = [h["key"] for h in hops if h["verdict"] == "PASS"]

        problems = []
        for needle in expect["must_fail"]:
            if not any(needle in k for k in failed):
                problems.append(f"该红没红：{needle}（实得失败={failed or '无'}）")
        for needle in expect["must_pass"]:
            if not any(needle in k for k in passed):
                problems.append(f"该绿没绿：{needle}（实得通过={passed or '无'}）")

        mark = "✅" if not problems else "❌"
        print(f"  {mark} [{state}] {label} → 失败={failed or '无'} 通过={passed or '无'}")
        for p in problems:
            print(f"       ↳ {p}")
        if problems:
            bad += 1
        results.append({"state": state, "label": label, "failed": failed,
                        "passed": passed, "problems": problems,
                        "hops": hops})

    srv.shutdown()
    print(f"\n=== 汇总：{len(CASES) - bad}/{len(CASES)} 态判词符合预期 ===")
    if bad:
        print("哨兵不可信：有形态被判错，先修哨兵再谈监控面")

    if args.json:
        Path(args.json).write_text(
            json.dumps({"total": len(CASES), "bad": bad, "results": results},
                       ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"证据已写入 {args.json}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
