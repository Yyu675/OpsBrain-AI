#!/usr/bin/env python3
"""Prometheus 桩：给 e2e 作业一个「健康但无数据」的监控数据源。

── 为什么需要它 ─────────────────────────────────────────────────────────
监控中心（`/monitoring`）是**代理查询**设计：OpsBrain 不存时序，页面数据全靠
后端转调 Prometheus。数据源不在时，后端按契约返回
`50020 / HTTP 503`（`MetricsUnavailableException` → `GlobalExceptionHandler`），
浏览器控制台于是出现 `Failed to load resource: ... 503`，而
`e2e/navigation.spec.ts` 的「访问 /monitoring 渲染正常且无控制台错误」正是
把控制台错误算作失败的哨兵——2026-10-14 实测 38432764009 / 38432769846
两次运行同一条用例同红。

这不是产品缺陷，也不该靠放宽断言解决：CI 的 e2e 作业只起 pg/redis，
本来就没有 Prometheus，是**环境缺件**冒充了产品缺陷。桩提供
Prometheus 的合法最小响应（空结果），页面走「数据源健康、当前无数据」的
真实分支——既不掩盖缺陷，也不再让缺件进控制台。

── 契约（对齐 infrastructure/metrics/PrometheusClient.java）──────────
    GET /-/healthy                → 200（health() 的存活探测）
    GET /api/v1/query             → {"status":"success","data":{"resultType":"vector","result":[]}}
    GET /api/v1/query_range       → {"status":"success","data":{"resultType":"matrix","result":[]}}
    其它路径                       → 404 JSON（不静默 200：桩不该假装什么都支持）

端口默认 **29090**，与 `devops.metrics.prometheus.base-url` 的默认值一致，
因此无需给后端加环境变量；要改端口时两处都要改（`--port` 与 `PROMETHEUS_BASE_URL`）。

── 用法 ────────────────────────────────────────────────────────────────
    python3 tools/seed/prom-stub.py --port 29090 &
    curl -sf http://localhost:29090/-/healthy && echo up

本地起后端 + 前端联调时同样适用（否则监控页会一路 503）。
"""

import argparse
import json
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

VECTOR_EMPTY = {"status": "success", "data": {"resultType": "vector", "result": []}}
MATRIX_EMPTY = {"status": "success", "data": {"resultType": "matrix", "result": []}}
BUILDINFO = {
    "status": "success",
    "data": {"version": "stub", "revision": "prom-stub", "goVersion": "n/a"},
}


class Handler(BaseHTTPRequestHandler):
    server_version = "OpsBrainPromStub/1.0"
    protocol_version = "HTTP/1.1"

    def _send(self, code: int, payload, content_type: str = "application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802（http.server 约定）
        path = urlparse(self.path).path
        if path == "/-/healthy":
            self._send(200, b"Prometheus Server is Healthy.\n", "text/plain; charset=utf-8")
        elif path == "/api/v1/query":
            self._send(200, VECTOR_EMPTY)
        elif path == "/api/v1/query_range":
            self._send(200, MATRIX_EMPTY)
        elif path == "/api/v1/status/buildinfo":
            self._send(200, BUILDINFO)
        else:
            self._send(404, {"status": "error", "errorType": "not_found", "error": path})
        if self.server.verbose:  # type: ignore[attr-defined]
            sys.stderr.write(f"[prom-stub] {self.command} {self.path} -> {self.responses[0][0]}\n")

    def log_message(self, fmt, *args):  # 默认每请求一行会淹掉 CI 日志
        if self.server.verbose:  # type: ignore[attr-defined]
            super().log_message(fmt, *args)


def main() -> int:
    parser = argparse.ArgumentParser(description="Prometheus 桩（空结果，供 e2e/联调）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=29090)
    parser.add_argument("--verbose", action="store_true", help="逐请求打印")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.verbose = args.verbose  # type: ignore[attr-defined]
    print(f"[prom-stub] listening on http://{args.host}:{args.port}", flush=True)

    def shutdown(signum, _frame):  # SIGTERM/SIGINT 优雅退出
        print(f"[prom-stub] signal {signum}, shutting down", flush=True)
        server.shutdown()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
