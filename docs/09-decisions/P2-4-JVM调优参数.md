# P2-4：JVM 调优参数（生产环境）

**批次**：86（2026-09-14）  
**优先级**：P2（性能优化）

---

## 推荐配置

```bash
# 堆内存：4GB（根据实际负载调整）
-Xms4g -Xmx4g

# GC：使用 G1（JDK 21 默认，适合低延迟场景）
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200          # 目标暂停时间 200ms
-XX:G1HeapRegionSize=16m          # Region 大小 16MB（适合 4GB 堆）
-XX:InitiatingHeapOccupancyPercent=45  # 堆占用 45% 时触发并发标记

# GC 日志（生产环境诊断必备）
-Xlog:gc*:file=/var/log/opsbrain/gc.log:time,level,tags:filecount=5,filesize=100M

# 堆转储（OOM 时自动生成，便于事后分析）
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/opsbrain/heapdump.hprof

# 禁用不必要的 GC 暂停（减少延迟毛刺）
-XX:+DisableExplicitGC            # 禁用 System.gc()

# JIT 编译优化
-XX:+TieredCompilation            # 分层编译（默认开启）
-XX:ReservedCodeCacheSize=512m    # 代码缓存 512MB（AI 推理代码路径多）
```

---

## 启动方式

### Docker Compose（推荐）

编辑 `docker-compose.yml`：

```yaml
services:
  devops-platform-backend:
    environment:
      JAVA_OPTS: >-
        -Xms4g -Xmx4g
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
        -XX:+HeapDumpOnOutOfMemoryError
        -XX:HeapDumpPath=/var/log/opsbrain/heapdump.hprof
        -Xlog:gc*:file=/var/log/opsbrain/gc.log:time,level,tags:filecount=5,filesize=100M
    volumes:
      - ./logs:/var/log/opsbrain  # 挂载日志目录
```

### 直接启动（开发环境）

```bash
java -Xms2g -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar devops-platform-backend.jar
```

---

## 监控验证

启动后检查 GC 日志：

```bash
tail -f /var/log/opsbrain/gc.log
```

关注指标：
- **Pause Time**：单次 GC 暂停时间应 < 200ms
- **Frequency**：Full GC 频率应 < 1 次/小时
- **Heap Usage**：堆使用率保持在 60%-80%（过高触发 Full GC，过低浪费内存）

---

## 调优建议

### 场景 1：延迟敏感（AI 推理响应要求 < 2s）

```bash
-XX:MaxGCPauseMillis=100          # 降低暂停目标至 100ms
-XX:G1NewSizePercent=30           # 增大年轻代（减少 Minor GC 频率）
```

### 场景 2：吞吐优先（批量知识库摄取）

```bash
-XX:+UseParallelGC                # 切换到 Parallel GC（吞吐优先）
-XX:ParallelGCThreads=8           # 并行 GC 线程数（根据 CPU 核心数调整）
```

### 场景 3：低内存环境（< 2GB）

```bash
-Xms1g -Xmx1g
-XX:+UseSerialGC                  # 串行 GC（单线程，低开销）
```

---

## 验证清单

- [ ] 启动日志确认 JVM 参数生效（搜索 `MaxHeapSize`）
- [ ] GC 日志文件生成（`/var/log/opsbrain/gc.log`）
- [ ] 无 OOM 错误（运行 24 小时压测）
- [ ] P99 延迟 < 2s（Prometheus + Grafana 监控）
