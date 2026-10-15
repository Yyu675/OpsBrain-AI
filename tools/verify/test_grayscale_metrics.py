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
import unittest

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

    def test_窗口前无快照时末读数即增量(self):
        self.assertEqual(cumulative_delta(42, None), 42)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
