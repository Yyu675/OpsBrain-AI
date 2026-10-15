#!/usr/bin/env python3
"""grayscale_metrics 的纯逻辑单测。

为什么值得单测：这个工具的价值在「口径对不对」——而口径错误**不会报错，只会安静地给出
错的数**，比崩掉危险得多（台账要拿它去关 DoD）。以下几处是最容易写错且错了看不出来的：

  · 累计快照列被 SUM（重复相加）而不是取「末读数 − 窗口前读数」；
  · 「连续 0-P1 天数」算成窗口内合格天数总和（中间断一天必须重新起算）；
  · Prometheus 指标名误配（`opsbrain_notify_total` 与 `opsbrain_notify_delivery_total`）；
  · 分母为 0 时谎报 100%/0%，而不是「无样本」。

运行：python3 tools/verify/test_grayscale_metrics.py
"""

from __future__ import annotations

import datetime as dt
import sys
import unittest
from unittest import mock

import grayscale_metrics
from grayscale_metrics import (
    consecutive_zero_days,
    cumulative_delta,
    daterange,
    fmt_hours,
    median,
    parse_counter,
    rate,
    render_table,
)

PROM = """# HELP opsbrain_notify_delivery_total 通知送达
# TYPE opsbrain_notify_delivery_total counter
opsbrain_notify_delivery_total{channel="dingtalk",result="ok",} 7.0
opsbrain_notify_delivery_total{channel="wecom",result="ok",} 3.0
opsbrain_notify_delivery_total{channel="dingtalk",result="error",} 2.0
# HELP opsbrain_notify_total 通知受理（**不同指标**，不得被误配）
opsbrain_notify_total{result="ok",} 99.0
# 下面这行是 Prometheus 对 counter 自动产出的 _created 序列——
# 指标名以 opsbrain_notify_delivery_total 开头，若按前缀匹配会被当成送达数，
# 把 1.76e9 加进分子（送达率会算成天文数字且不报错）。
opsbrain_notify_delivery_total_created{channel="dingtalk",result="ok",} 1.760000000E9
"""


class TestCumulativeDelta(unittest.TestCase):
    def test_窗口增量是末读数减窗口前读数(self):
        self.assertEqual(cumulative_delta(120, 100), 20)

    def test_窗口前无快照不可从零起算(self):
        self.assertIsNone(cumulative_delta(42, None))

    def test_无末读数返回None而不是0(self):
        # 0 会被当成「本期零诊断」写进台账——那是谎报，必须区分「没数」
        self.assertIsNone(cumulative_delta(None, 100))

    def test_绝不是SUM语义(self):
        # 累计列若被相加，两个读数会得到 220 而不是 20
        self.assertNotEqual(cumulative_delta(120, 100), 120 + 100)


class TestConsecutiveZeroDays(unittest.TestCase):
    def setUp(self):
        self.days = daterange(dt.date(2026, 10, 13), dt.date(2026, 10, 19))  # 7 天

    def test_全窗口无误伤则等于窗口长度(self):
        self.assertEqual(consecutive_zero_days(self.days, set()), 7)

    def test_尾部连续段语义_中间断了要重新起算(self):
        # 10-15 有误伤 → 只有 16/17/18/19 四天连续
        bad = {dt.date(2026, 10, 15)}
        self.assertEqual(consecutive_zero_days(self.days, bad), 4)

    def test_最后一天有误伤则连续为0(self):
        # 关键：DoD 要的是「连续到当前」，最后一天破功就是 0，不是 6
        bad = {dt.date(2026, 10, 19)}
        self.assertEqual(consecutive_zero_days(self.days, bad), 0)

    def test_不是窗口内合格天数总和(self):
        # 若实现成「窗口内没误伤的天数」，10-15 破功会得到 6 而不是 4
        bad = {dt.date(2026, 10, 15)}
        self.assertNotEqual(consecutive_zero_days(self.days, bad), 6)

    def test_窗口外日期不影响(self):
        bad = {dt.date(2026, 10, 1)}  # 窗口之前
        self.assertEqual(consecutive_zero_days(self.days, bad), 7)


class TestParseCounter(unittest.TestCase):
    def test_同指标多标签组求和(self):
        self.assertEqual(parse_counter(PROM, "opsbrain_notify_delivery_total", "result", "ok"), 10.0)

    def test_不误配前缀相同的其它指标(self):
        # opsbrain_notify_total 是「受理」账，若被算进送达账，送达率会虚高
        self.assertIsNone(parse_counter(PROM, "opsbrain_notify_total", "result", "error"))

    def test_不把_created序列算进计数(self):
        # Prometheus 对每个 counter 会额外产出 `<metric>_created` 序列，其指标名以本指标名
        # 开头。若按前缀匹配，1.76e9 会被加进分子——送达率变成天文数字**而且不报错**。
        # （上一版这条没咬住：夹具里没有同前缀序列，删掉前缀守卫测试照样绿。）
        self.assertEqual(parse_counter(PROM, "opsbrain_notify_delivery_total", "result", "ok"), 10.0)

    def test_指标不存在返回None(self):
        self.assertIsNone(parse_counter(PROM, "opsbrain_not_a_metric", "result", "ok"))

    def test_注释行不参与(self):
        self.assertEqual(parse_counter("# opsbrain_notify_delivery_total{result=\"ok\"} 999\n",
                                       "opsbrain_notify_delivery_total", "result", "ok"), None)


    def test_标签名必须完整匹配且可带时间戳(self):
        text = 'm{not_result="ok"} 99\nm{result="ok"} 2 1234567890\n'
        self.assertEqual(parse_counter(text, "m", "result", "ok"), 2)


class TestRate(unittest.TestCase):
    def test_正常百分比带分子分母(self):
        self.assertEqual(rate(8, 10), "80.0%（8/10）")

    def test_分母为0不谎报(self):
        self.assertIn("无样本", rate(0, 0))

    def test_缺数据显示无数据(self):
        self.assertEqual(rate(None, 10), "无数据")
        self.assertEqual(rate(5, None), "无数据")


class TestMedianAndFormat(unittest.TestCase):
    def test_奇数个(self):
        self.assertEqual(median([3, 1, 2]), 2)

    def test_偶数个取中间均值(self):
        self.assertEqual(median([1, 2, 3, 4]), 2.5)

    def test_空样本返回None(self):
        self.assertIsNone(median([]))

    def test_秒转小时(self):
        self.assertEqual(fmt_hours(5400), "1.5 小时")
        self.assertEqual(fmt_hours(None), "无数据")


class TestRenderTable(unittest.TestCase):
    def test_输出可直接粘进台账(self):
        rows = [{"name": "连续 0-P1 天数", "value": "7 天 —— 达标", "source": "sys_alert"}]
        out = render_table(rows)
        self.assertIn("| 指标 | 本期读数 | 来源/口径 |", out)
        self.assertIn("| 连续 0-P1 天数 | 7 天 —— 达标 | sys_alert |", out)


class TestDaterange(unittest.TestCase):
    def test_闭区间(self):
        self.assertEqual(len(daterange(dt.date(2026, 10, 13), dt.date(2026, 10, 19))), 7)

    def test_单天(self):
        self.assertEqual(len(daterange(dt.date(2026, 10, 13), dt.date(2026, 10, 13))), 1)

    def test_逆向窗口为空(self):
        self.assertEqual(daterange(dt.date(2026, 10, 19), dt.date(2026, 10, 13)), [])


PROM_OK = """# TYPE opsbrain_notify_delivery_total counter
opsbrain_notify_delivery_total{channel="dingtalk",result="ok"} 95.0
opsbrain_notify_delivery_total{channel="dingtalk",result="error"} 5.0
opsbrain_notify_delivery_total_created{channel="dingtalk",result="ok"} 1.760000000E9
opsbrain_notify_total{result="ok"} 999.0
"""


class FakeTaker:
    """替身取数器：按 SQL 里出现的表名回罐头结果，用来跑通 collect() 的胶水层。

    为什么非要有这层：批104 首轮 CI 红在一个解包错误上（`t.q()` 返二元组、调用方按
    三元组解包）。它只存在于「有库」分支里，而单测当时只覆盖纯函数，于是 25 例全绿、
    CI 一上来就崩。**取数胶水必须自己有测试**，否则「本地全绿」这句话没有意义。
    """

    def __init__(self, results=None, prom=(True, PROM_OK), psql="psql", pgurl="postgresql://x"):
        self.results = results or {}
        self.prom = prom
        self.psql, self.pgurl = psql, pgurl
        self.sqls = []

    def _route(self, sql):
        self.sqls.append(sql)
        for key in ("sys_alert", "sys_effectiveness_snapshot", "sys_devops_ticket", "sys_healing_execution"):
            if key in sql:
                return key
        return None

    def q(self, sql):
        key = self._route(sql)
        if key in self.results and isinstance(self.results[key], tuple):
            return self.results[key]
        return True, self.results.get(key, ""), None

    def num(self, sql):
        key = self._route(sql)
        if key in self.results and isinstance(self.results[key], tuple):
            return self.results[key]
        return True, float(self.results.get(key, 0) or 0), None

    def prometheus(self):
        return self.prom


class TestCollectGlue(unittest.TestCase):
    """collect() 的胶水层：六行是否都出、失败如何降级、出口元数是否同构。"""

    FROM, TO = dt.date(2026, 10, 13), dt.date(2026, 10, 19)

    def _run(self, taker):
        rows = []
        ok = grayscale_metrics.collect(taker, self.FROM, self.TO, rows, today=dt.date(2026, 10, 20))
        return ok, rows

    def test_有库有抓取面时六行全出且判为完整(self):
        ok, rows = self._run(FakeTaker({"sys_alert": "2026-10-14\n",
                                        "sys_devops_ticket": "7200\n28800\n",
                                        "sys_healing_execution": "4|3"}))
        self.assertTrue(ok)
        self.assertEqual([r["name"] for r in rows], grayscale_metrics.ROWS)
        self.assertEqual([r["name"] for r in rows if r.get("failed")], [])

    def test_取数器三个出口都返三元组(self):
        """首版崩溃的根因：q() 返二元组，调用方按三元组解包。"""
        self.assertEqual(len(grayscale_metrics.Taker(None, None, "b", None).q("select 1")), 3)
        # 不依赖 /usr/bin/false 或 /bin/echo：单测本身也要能在 Windows 跑。
        t = grayscale_metrics.Taker("psql.exe", "postgresql://x", "b", None)
        with mock.patch.object(grayscale_metrics.subprocess, "run") as run:
            run.return_value = mock.Mock(returncode=1, stdout="", stderr="SQL error")
            self.assertEqual(t.q("select 1"), (False, None, "ERR: SQL error"))
            run.return_value = mock.Mock(returncode=0, stdout="1\n", stderr="")
            self.assertEqual(t.q("select 1"), (True, "1", None))
            run.side_effect = FileNotFoundError("psql missing")
            out = t.q("select 1")
            self.assertEqual(len(out), 3)
            self.assertFalse(out[0])
            self.assertIn("FileNotFoundError", out[2])

    def test_单行SQL失败只让该行降级不影响其它行(self):
        taker = FakeTaker({"sys_alert": (False, None, "ERR: column \"severity\" does not exist"),
                           "sys_devops_ticket": "7200\n", "sys_healing_execution": "1|1"})
        ok, rows = self._run(taker)
        self.assertFalse(ok)
        self.assertEqual(len(rows), 6, "失败也必须占位，否则台账少一行没人发现")
        bad = [r for r in rows if r.get("failed")]
        self.assertEqual([r["name"] for r in bad], [grayscale_metrics.ROWS[0]])
        self.assertIn("severity", bad[0]["value"], "SQL 报错原文要带出来，不能只说失败")

    def test_没有库时四行库内指标标未取且判为不完整(self):
        """库内只有四行（①④⑤⑥）；送达率走抓取面、ACK 演练是人工登记。"""
        ok, rows = self._run(FakeTaker(psql=None, pgurl=None))
        self.assertFalse(ok)
        self.assertEqual([r["name"] for r in rows if r.get("failed")],
                         [grayscale_metrics.ROWS[i] for i in (0, 3, 4, 5)])
        self.assertEqual(rows[1]["value"], "人工登记", "人工登记项不算失败")
        self.assertEqual(rows[2]["value"], "95.0%（95/100）", "没库也该能从抓取面取到送达率")

    def test_抓取面挂了只让送达率那一行失败(self):
        ok, rows = self._run(FakeTaker({"sys_devops_ticket": "", "sys_healing_execution": "0|0"},
                                       prom=(False, "ERR: 401")))
        self.assertFalse(ok)
        self.assertEqual([r["name"] for r in rows if r.get("failed")], [grayscale_metrics.ROWS[2]])
        self.assertIn("401", rows[2]["value"], "取不到就点名原因，不能显示成 0% 或 100%")
        self.assertNotIn("95.0%", rows[2]["value"])

    def test_抓取面正常时送达率排除_created序列(self):
        _, rows = self._run(FakeTaker({"sys_devops_ticket": "", "sys_healing_execution": "0|0"}))
        self.assertEqual(rows[2]["value"], "95.0%（95/100）")


    def test_抓取面不暴露指标不能判取数完整(self):
        ok, rows = self._run(FakeTaker(prom=(True, "# no samples")))
        self.assertFalse(ok)
        self.assertTrue(rows[2]["failed"])
        self.assertIn("无数据", rows[2]["value"])

    def test_只有失败回执时送达率为零(self):
        ok, rows = self._run(FakeTaker(prom=(True, 'opsbrain_notify_delivery_total{result="error"} 2')))
        self.assertTrue(ok)
        self.assertEqual(rows[2]["value"], "0.0%（0/2）")


class TestSqlShape(unittest.TestCase):
    """静态守卫：口径最容易写错的是列名与聚合方式，写错不报错、只给错数。"""

    def _sqls(self):
        taker = FakeTaker({"sys_devops_ticket": "", "sys_healing_execution": "0|0"})
        rows = []
        grayscale_metrics.collect(taker, dt.date(2026, 10, 13), dt.date(2026, 10, 19), rows, today=dt.date(2026, 10, 20))
        return taker.sqls

    def test_告警分级用level不是severity(self):
        sql = [s for s in self._sqls() if "sys_alert" in s][0]
        self.assertIn("level", sql)
        self.assertNotIn("severity", sql)
        self.assertIn("false_positive_marked_at", sql, "误报按标记时刻落窗，不按告警到达时刻")

    def test_工单创建列是create_time(self):
        sql = [s for s in self._sqls() if "sys_devops_ticket" in s][0]
        self.assertIn("create_time", sql)
        self.assertNotIn("created_at", sql)

    def test_累计快照列绝不用SUM(self):
        for s in [s for s in self._sqls() if "sys_effectiveness_snapshot" in s]:
            self.assertNotIn("SUM(", s.upper())


class FixedClock(dt.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 10, 15, 12, tzinfo=dt.timezone.utc)


class TestMainExitCodes(unittest.TestCase):
    """main() 的退出码是 CI 唯一读得到的判词，必须逐个钉住。"""

    def _main(self, argv):
        import io
        import contextlib
        with mock.patch.object(sys, "argv", ["grayscale_metrics.py"] + argv), \
                mock.patch.object(grayscale_metrics.dt, "datetime", FixedClock):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = grayscale_metrics.main()
        return rc, buf.getvalue()

    def test_窗口非法退2(self):
        rc, out = self._main(["--from", "2026-10-19", "--to", "2026-10-13"])
        self.assertEqual(rc, 2)
        self.assertIn("::error::", out)

    def test_日期格式错退2(self):
        # 用「月 13 日 45」这种一眼就假的日期：早先这里写的是一个渲染出来像合法日期的
        # 串（字节里其实是斜杠），断言虽然过了，但读者会以为它在测合法日期。
        rc, out = self._main(["--from", "2026-13-45", "--to", "2026-10-19"])
        self.assertEqual(rc, 2)
        self.assertIn("YYYY-MM-DD", out)

    def test_没库退1且逐行点名(self):
        rc, out = self._main(["--from", "2026-10-13", "--to", "2026-10-19",
                              "--base", "http://127.0.0.1:9"])
        self.assertEqual(rc, 1)
        self.assertIn("未取到", out)

    def test_内部异常退3并喊出声(self):
        """沙箱/CI 读不到作业日志：崩了不喊，看到的只有一个 exit code 1。"""
        def boom(*a, **k):
            raise ValueError("not enough values to unpack")

        with mock.patch.object(grayscale_metrics, "collect", boom):
            rc, out = self._main(["--from", "2026-10-13", "--to", "2026-10-19"])
        self.assertEqual(rc, 3)
        self.assertIn("::error::取数器内部异常", out)


class TestWindowAndEvidence(unittest.TestCase):
    _main = TestMainExitCodes._main
    def test_未来和当天截到昨天(self):
        for end in (dt.date(2026, 10, 15), dt.date(2026, 10, 19)):
            self.assertEqual(grayscale_metrics.observation_window(
                dt.date(2026, 10, 13), end, dt.date(2026, 10, 15)), dt.date(2026, 10, 14))

    def test_起点尚未结束则拒绝且不取数(self):
        with mock.patch.object(grayscale_metrics, "collect") as collect:
            rc, out = self._main(["--from", "2026-10-15", "--to", "2026-10-19"])
        self.assertEqual(rc, 2)
        self.assertIn("尚无完整日期", out)
        collect.assert_not_called()

    def test_历史窗口不截断(self):
        self.assertEqual(grayscale_metrics.observation_window(
            dt.date(2026, 10, 6), dt.date(2026, 10, 12), dt.date(2026, 10, 15)), dt.date(2026, 10, 12))

    def test_collect直接调用也不能把未来计为七天(self):
        rows = []
        t = FakeTaker({"sys_healing_execution": "0|0"})
        grayscale_metrics.collect(t, dt.date(2026, 10, 13), dt.date(2026, 10, 19), rows,
                                  today=dt.date(2026, 10, 15))
        self.assertIn("2 个完整日期", rows[0]["value"])
        self.assertNotIn("—— 达标", rows[0]["value"])
        self.assertTrue(all("2026-10-19" not in sql for sql in t.sqls))
        self.assertIn("进程累计", rows[2]["source"])
        self.assertIn("告警转单率", rows[3]["value"])
        self.assertNotIn("触发率 ", rows[3]["value"])

    def test_JSON区分请求窗口实际窗口与验收(self):
        import json
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "metrics.json"
            with mock.patch.object(grayscale_metrics, "Taker", return_value=FakeTaker()):
                rc, out = self._main(["--from", "2026-10-13", "--to", "2026-10-19", "--json", str(path)])
            data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(rc, 0)
        self.assertEqual(data["requested_window"]["to"], "2026-10-19")
        self.assertEqual(data["window"]["to"], "2026-10-14")
        self.assertFalse(data["window_closed"])
        self.assertTrue(data["collection_complete"])
        self.assertIsNone(data["gate_passed"])
        self.assertIn("2026-10-15", data["collected_at"])
        self.assertIn("窗口进行中", out)

    def test_缺快照或负增量不得伪装零(self):
        for value in (None, -1, float("nan")):
            t = mock.Mock()
            t.num.return_value = (True, value, None)
            ok, result, err = grayscale_metrics.snapshot_delta(t, "alerts_total",
                                                              dt.date(2026, 10, 13), dt.date(2026, 10, 14))
            self.assertFalse(ok)
            self.assertIsNone(result)
            self.assertIn("ERR:", err)
            sql = t.num.call_args.args[0]
            self.assertNotIn("COALESCE", sql)
            self.assertIn("BETWEEN", sql)

    def test_psql选项显式数据库且禁止个人配置(self):
        with mock.patch.object(grayscale_metrics.subprocess, "run") as run:
            run.return_value = mock.Mock(returncode=0, stdout="1", stderr="")
            self.assertEqual(grayscale_metrics.Taker("psql.exe", "postgresql://db", "b", None).q("select 1"),
                             (True, "1", None))
        cmd = run.call_args.args[0]
        self.assertEqual(cmd[cmd.index("-d") + 1], "postgresql://db")
        self.assertIn("-X", cmd)
        self.assertIn("ON_ERROR_STOP=1", cmd)
        self.assertEqual(run.call_args.kwargs["encoding"], "utf-8")
        self.assertEqual(run.call_args.kwargs["env"]["PGCLIENTENCODING"], "UTF8")

    def test_stdout兼容GBK与无reconfigure(self):
        stream = mock.Mock()
        with mock.patch.object(sys, "stdout", stream):
            grayscale_metrics.configure_stdout()
        stream.reconfigure.assert_called_once_with(encoding="utf-8", errors="replace")
        import io
        with mock.patch.object(sys, "stdout", io.StringIO()):
            grayscale_metrics.configure_stdout()

    def test_markdown单元格管道不破坏列(self):
        text = render_table([{"name": "指标", "value": "1|2", "source": "ok|error"}])
        self.assertIn(r"1\|2", text)
        self.assertIn(r"ok\|error", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
