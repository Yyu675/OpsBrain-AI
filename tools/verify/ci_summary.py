#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把演练证据压成一行 ::notice:: / ::error::（CI 取证通道）

为什么需要它
============
托管 runner 的作业日志归档在 azure blob 域，开发沙箱 egress 不通该域
（`gh run view --log` 与 artifact 下载在沙箱里都是 EOF，2026-10-13 已实证）。
于是「演练到底过没过、断在哪一跳」如果只写在 stdout 里，**沙箱侧就取不到证**，
排查只能靠猜——本仓已经为「失败诊断回帖」建过一条同类通道，这是给「成功的演练」
也补上可读通道：workflow 命令 `::notice::` 会落成 check-run annotations，
`gh api repos/.../check-runs/<id>/annotations` 即可读。

用法
====
    python3 tools/verify/ci_summary.py --kind closed-loop --json /tmp/closed-loop.json
    python3 tools/verify/ci_summary.py --kind rbac       --json /tmp/rbac.json

退出码：0=证据显示全过；1=有失败或缺证据（**缺证据也算失败**——静默跳过比红更坏）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys

MAX_DETAIL = 240


def read_tail(path: str | None, n: int = 200_000) -> str | None:
    """原样读尾部（**不做空白折叠**）。

    与 tail() 的区别是本函数的存在理由：tail() 为了压成一行会 `split()` 再 join，
    换行没了 —— 而 playwright 类的判据全靠行首锚点（`^  N) [chromium] › …`）。
    第一版直接复用 tail()，于是失败清单永远解析不出来，红案被报成
    `::notice::e2e 汇总 2 failed` 且退出码 0（本机四态自测当场抓到）。
    """
    if not path or not os.path.exists(path):
        return None
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()[-n:]


def clip(text: str, limit: int = MAX_DETAIL) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def tail(path: str | None, n: int = 600) -> str:
    if not path or not os.path.exists(path):
        return "（无日志文件）"
    with open(path, encoding="utf-8", errors="replace") as f:
        return clip(f.read()[-n:], n)


def closed_loop(path: str, log: str | None) -> tuple[bool, str]:
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    hard = [h for h in d["hops"] if h["verdict"] == "FAIL"]
    soft = [h for h in d["hops"] if h["verdict"] == "SOFT-FAIL"]
    skip = [h for h in d["hops"] if h["verdict"] == "SKIPPED"]
    msg = (f"业务闭环实弹 PASS {d['passed']}/{d['total']}"
           f"（硬失败 {len(hard)}｜软失败 {len(soft)}｜SKIPPED {len(skip)}）")
    if hard:
        msg += "｜断点=" + "；".join(f"[{h['phase']}]{h['key']}: {clip(h['detail'], 120)}" for h in hard[:5])
    if soft:
        msg += "｜软失败=" + "；".join(f"[{h['phase']}]{h['key']}" for h in soft[:5])
    if skip:
        msg += "｜跳过=" + "；".join(f"[{h['phase']}]{h['key']}" for h in skip[:5])
    return (not hard), msg


def rbac(path: str, log: str | None) -> tuple[bool, str]:
    """RBAC 走查的证据结构（见 rbac_walkthrough.py 的 json.dump）：

    rows[i].cells 是**以角色为键的字典**（不是列表），每格带 ok/expected/verdict。
    """
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    bad = []
    for row in d["rows"]:
        for role, cell in row["cells"].items():
            if not cell.get("ok"):
                bad.append((row.get("label") or row.get("key"), role, cell))
    msg = (f"RBAC 实弹 {d['total'] - d['mismatches']}/{d['total']} 格与源码执法点一致"
           f"（账号 {','.join(d['accounts'])}）")
    if bad:
        msg += "｜不一致=" + "；".join(
            f"{clip(label, 30)}×{role}（期望 {cell.get('expected')} 实得 {cell.get('verdict')}"
            f"/http {cell.get('http')}）" for label, role, cell in bad[:5])
    return (d["mismatches"] == 0), msg


def playwright(path: str, log: str | None) -> tuple[bool, str]:
    """Playwright 输出 → 一行汇总/失败清单（这一类没有 JSON 证据，直接读 stdout）。

    为什么必须有它：作业日志在沙箱读不到的域上，而原来那条 `::notice::` 写在
    `npx playwright test` 之后——`bash -e` + pipefail 下红案在汇总行之前就中断了，
    于是**红案反而没有任何 annotation**（run 38667020710 实测：失败、零 annotation、
    零回帖，只能靠猜）。现在无论红绿都先出汇总，红案带失败用例名与首条报错。
    """
    text = read_tail(log)
    if text is None:
        return False, "e2e 未产出日志（/tmp/e2e-run.log 缺失）——步骤在 tee 前即炸"

    import re
    names = re.findall(r'^\s*\d+\) \[\w+\] › (.+?)\s*$', text, re.M)
    counts = re.findall(r'(\d+) (passed|failed|flaky|skipped)', text)
    tally = "、".join(f"{n} {w}" for n, w in counts[-4:]) or "（未找到汇总行）"
    first_err = ""
    m = re.search(r"Error: (.+)", text)
    if m:
        first_err = clip(m.group(1), 300)

    if names:
        msg = f"e2e 红 {len(names)} 例（{tally}）｜用例=" + "；".join(clip(n, 90) for n in names[:4])
        if first_err:
            msg += f"｜首条报错={first_err}"
        return False, msg
    if not counts:
        return False, f"e2e 输出无法判读（既无失败清单也无汇总行）｜尾部：{clip(text[-300:])}"
    return True, f"e2e 汇总 {tally}"


KINDS = {"closed-loop": closed_loop, "rbac": rbac, "playwright": playwright}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", choices=sorted(KINDS), required=True)
    ap.add_argument("--json", default=None, help="演练产出的证据文件（playwright 类不需要）")
    ap.add_argument("--log", default=None, help="演练 stdout 落盘文件（缺证据时取尾部）")
    args = ap.parse_args()

    if args.kind != "playwright" and not args.json:
        print("::error::--json 证据文件缺失（%s 类必须要）" % args.kind)
        return 1
    try:
        ok, msg = KINDS[args.kind](args.json, args.log)
    except Exception as e:                      # 缺证据/结构不符都按失败处理，不静默放过
        ok = False
        msg = f"{args.kind} 演练未产出可读证据（{type(e).__name__}: {e}）｜日志尾：{tail(args.log)}"

    print(("::notice::" if ok else "::error::") + msg)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
