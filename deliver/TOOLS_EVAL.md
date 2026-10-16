# S1-1 指标取证工具评测集（JSON Lines，每行一条）

# 字段：service(必填) / range(缺省 30m) / metrics(缺省=目录全部)
# 以 # 或 ``` 开头的行与空行会被忽略（兼容 markdown 围栏粘贴）

{"service": "order-service", "range": "30m"}
{"service": "order-service", "range": "2h", "metrics": "cpu,memory"}
{"service": "payment-service", "range": "1h", "metrics": "error-rate,latency-p99"}
{"service": "inventory-service", "metrics": "cpu"}
{"service": "no-such-service-probe", "range": "30m", "metrics": "memory"}
