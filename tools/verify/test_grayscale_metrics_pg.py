#!/usr/bin/env python3
"""真迁移库上的 SQL/CLI 集成回归；不跳过，缺库/抓取面即失败。

POSTGRES_PG_URL 必填；PSQL 默认 psql；GM_BASE 默认 http://localhost:8088/ai；
GM_SCRAPE_TOKEN_FILE 可选。仅写入随机隔离 schema，绝不修改业务表数据。
CI 使用真实 Spring Boot 抓取面；本地可显式接测试 HTTP 接收端（须说明）。
"""
from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid

from grayscale_metrics import Taker, collect, snapshot_delta


class TestPostgresMetrics(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        url = os.environ.get("POSTGRES_PG_URL")
        if not url:
            raise RuntimeError("POSTGRES_PG_URL 必填；集成回归不得静默跳过")
        cls.schema = "gm_test_" + uuid.uuid4().hex
        cls.t = Taker(os.environ.get("PSQL", "psql"), url,
                      os.environ.get("GM_BASE", "http://localhost:8088/ai"),
                      os.environ.get("GM_SCRAPE_TOKEN_FILE"))
        # LIKE 真实迁移表：列名/类型/NOT NULL 变更会真实影响本测试。
        tables = ("sys_alert", "sys_devops_ticket", "sys_healing_execution", "sys_effectiveness_snapshot")
        sql = f"CREATE SCHEMA {cls.schema};" + "".join(
            f"CREATE TABLE {cls.schema}.{table} (LIKE public.{table} INCLUDING DEFAULTS INCLUDING CONSTRAINTS);"
            for table in tables)
        # 原子创建，失败不会留下半套 schema。插入显式 id，不消费业务序列。
        ok, _, err = cls.t.q("BEGIN;" + sql + "COMMIT;")
        if not ok:
            raise RuntimeError(err)
        cls.env = mock.patch.dict(os.environ, {"PGOPTIONS": f"-c search_path={cls.schema}"})
        cls.env.start()

    @classmethod
    def tearDownClass(cls):
        try:
            ok, _, err = cls.t.q(f"DROP SCHEMA {cls.schema} CASCADE")
            if not ok:
                raise RuntimeError(err)
        finally:
            cls.env.stop()

    def sql(self, sql):
        ok, value, err = self.t.q(sql)
        self.assertTrue(ok, err)
        return value

    def setUp(self):
        self.sql("""
TRUNCATE sys_alert, sys_devops_ticket, sys_healing_execution, sys_effectiveness_snapshot;
INSERT INTO sys_effectiveness_snapshot
(id, snapshot_date, alerts_total, alert_sourced_tickets, diagnosis_total, diagnosis_sufficient)
VALUES (1, '2020-01-05', 10, 2, 4, 1), (2, '2020-01-12', 14, 4, 8, 3);
INSERT INTO sys_alert (id, alert_name, level, title, dedup_key, false_positive, false_positive_marked_at)
VALUES (1, 'test', 'P1', 'test', 'gm-test', true, '2020-01-08 12:00:00');
INSERT INTO sys_devops_ticket (id, title, priority, module, create_time, mitigated_at)
VALUES ('gm-test', 'test', 'P1', 'DB', '2020-01-10 10:00:00', '2020-01-10 12:00:00');
INSERT INTO sys_healing_execution (id, action_key, environment, gate_decision, status, created_at)
VALUES (1, 'gm-test', 'test', 'AUTO_EXECUTE', 'SUCCEEDED', '2020-01-10'),
       (2, 'gm-test', 'test', 'AUTO_EXECUTE', 'FAILED', '2020-01-11');
""")

    def delta(self):
        return snapshot_delta(self.t, "alerts_total", dt.date(2020, 1, 6), dt.date(2020, 1, 12))

    def test_真实SQL是差值而非累加(self):
        self.assertEqual(self.delta(), (True, 4.0, None))

    def test_缺窗口前基线必须失败(self):
        self.sql("DELETE FROM sys_effectiveness_snapshot WHERE id=1")
        self.assertFalse(self.delta()[0])
        self.assertIn("缺窗口内快照或窗口前基线", self.delta()[2])

    def test_仅窗口前或仅未来快照不得冒充本期读数(self):
        self.sql("UPDATE sys_effectiveness_snapshot SET snapshot_date='2020-01-13' WHERE id=2")
        self.assertFalse(self.delta()[0])

    def test_累计读数倒退必须失败(self):
        self.sql("UPDATE sys_effectiveness_snapshot SET alerts_total=1 WHERE id=2")
        self.assertFalse(self.delta()[0])

    def test_SQL错误不吞错(self):
        ok, value, err = self.t.q("SELECT missing_column FROM sys_alert")
        self.assertFalse(ok)
        self.assertIsNone(value)
        self.assertIn("missing_column", err)

    def test_六行真实SQL与抓取面(self):
        rows = []
        ok = collect(self.t, dt.date(2020, 1, 6), dt.date(2020, 1, 12), rows)
        self.assertTrue(ok, rows)
        self.assertEqual(len(rows), 6)
        self.assertIn("4 个完整日期", rows[0]["value"])
        self.assertIn("告警转单率 50.0%（2/4）", rows[3]["value"])
        self.assertIn("诊断充分率 50.0%（2/4）", rows[3]["value"])
        self.assertIn("2.0 小时", rows[4]["value"])
        self.assertEqual(rows[5]["value"], "2 次（成功 1）")

    def test_CLI与JSON完整路径(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "metrics.json"
            cmd = [sys.executable, str(Path(__file__).with_name("grayscale_metrics.py")),
                   "--from", "2020-01-06", "--to", "2020-01-12", "--psql", self.t.psql,
                   "--pgurl", self.t.pgurl, "--base", self.t.base, "--json", str(output)]
            if self.t.token_file:
                cmd += ["--scrape-token-file", self.t.token_file]
            proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            data = json.loads(output.read_text(encoding="utf-8"))
            print(proc.stdout)
        self.assertTrue(data["collection_complete"])
        self.assertTrue(data["window_closed"])
        self.assertIsNone(data["gate_passed"])
        self.assertEqual(data["schema_version"], 2)
        print("::notice::灰度取数隔离夹具：真实 SQL + HTTP + CLI/JSON 通过；非灰度生产达标证据")


if __name__ == "__main__":
    unittest.main(verbosity=2)
