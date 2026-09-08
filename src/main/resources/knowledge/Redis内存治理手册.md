# Redis 内存治理手册

> 适用范围：内存使用率过高治理、大 key 危害与处置、内存淘汰策略选型、内存碎片。
> 口径先行：`used_memory`(引擎真实占用）与 RSS（含碎片）是两回事——
> 对着 RSS 治碎片、对着 used_memory 治数据，别治反了。

---

## 1. 内存使用率过高治理

### 问题描述
`used_memory` 逼近 `maxmemory`，或实例 RSS 超物理内存告警。

### 常见原因
1. **maxmemory 未设或未过压**：默认 0 不设限，直到 OOM 才发现
2. **key 无 TTL**:session/缓存只写不过期，慢性堆积
3. **大 key 横行**:zset/hash 单 key 上千万成员
4. **缓存穿透长尾**：空结果不缓存，热点空 key 重复打穿到 DB 且空值占位
5. **碎片率偏高**:`mem_fragmentation_ratio` > 1.5 长期不降
6. **淘汰策略选错**:`noeviction` 下写满即写失败

### 排查步骤
```bash
redis-cli INFO memory
# used_memory_human / maxmemory_human / mem_fragmentation_ratio
# / used_memory_peak_human / evicted_keys(累计被淘汰数,持续增长=容量不匹配)
redis-cli INFO keyspace        # 各库 key 总量与 TTL 分布(avg_ttl=0 的多=无过期纪律)

# 内部分布归因(4.0+ 自带,勿用 dump 全量导)
redis-cli MEMORY USAGE "some:big:key"
redis-cli MEMORY DOCTOR         # 自带的诊断口音,第一轮必跑
```

### 解决方案（按优先级）
1. **补容量纪律**:maxmemory 设为物理内存 60-75%（留 fork 写时复制余量）；
2. **选型淘汰策略**（见 §3);
3. **TTL 审计**：无 TTL key 清单（`SCAN`+`TTL`)，业务确认后批量补 `EXPIRE`——
   「当时忘了设」的 key 是无声地雷;
4. **删除/打散大 key**(§2);
5. **碎片治理**(§4);
6. 穿透治理：空值缓存（短 TTL)+ 布隆过滤器。

### 验证方法
治理后观测三账：`used_memory` 峰值线、`evicted_keys` 增速、`hit_rate`
（`keyspace_hits/(hits+misses)`)——只降 used 不动 hit 的治理才算成功，
容量降了命中率也掉了等于把压力转嫁给 DB。

---

## 2. 大 key 的危害与处置

### 危害（为什么大 key 是事故种子，不只是「占地方」）
1. **单线程阻塞**:DEL 一个千万成员 zset 是秒级全实例卡顿；
2. **网络强拆**:GET 一个 100MB 大 key 占满带宽，其他请求排队；
3. **内存不均**:cluster 模式下大 key 所在槽位节点先炸，扩容救不了；
4. **持久化抖动**:fork COW 期间大页复制，RDB 保存变 OOM 触发器；
5. **迁移长尾**:cluster 重均衡搬大 key 卡顿集群。

### 排查
```bash
# 在线粗筛(rdbtools 离线 rdb 分析最准;在线只用采样别全扫)
redis-cli --bigkeys -i 0.1        # 注意它「采样类型最大」不是全部大 key
redis-cli SCAN 0 COUNT 500        # 渐进遍历代替 KEYS * —— KEYS 是生产禁词
redis-cli MEMORY USAGE "key"      # 逐 key 精确数
```

### 处置（在线安全姿势）
```bash
# 1. 渐进删除代替 DEL(4.0+ 天然惰性)
redis-cli UNLINK "big:key"              # 异步释放,主线程只摘指针

# 2. 集合类超大 key:分批清(HDEL/SPOP/ZREMRANGEBYRANK 分批)
redis-cli HSCAN "big:hash" 0 COUNT 1000  # 边扫边删,每批 ≤1000

# 3. 打散治本:key 内加分片尾(hash→128 个子 key),或改数据结构
```

### 纪律
- DEL 大 key 高峰禁手——UNLINK 是唯一容忍姿势;
- 业务侧立「key 体量预算」:String ≤100KB、集合成员 ≤10万，超预算进设计评审不进生产。

---

## 3. 内存淘汰策略选型

`maxmemory-policy` 八选一，选型矩阵一张表：

| 策略 | 行为 | 适用 |
| --- | --- | --- |
| `noeviction` | 写满即拒读不写 | 元数据/配置等「宁报失败不丢数据」场景 |
| `allkeys-lru` | 全体 key 中淘汰最久未用 | **通用缓存首推** |
| `allkeys-lfu` | 全体 key 中淘汰访问频率最低 | 热点稳定且访问频次差异大 |
| `volatile-lru/lfu` | 只在有 TTL 的 key 中淘汰 | 持久数据与缓存混库（不推荐混库，同库才用） |
| `volatile-ttl` | 优先淘汰剩余 TTL 短的 | 短会话池 |
| `allkeys-random` / `volatile-random` | 随机淘汰 | 无访问模式可循 |
| `volatile-ttl` | 同上 | — |

选型三问：丢数据是否可接受（可→LRU/LFU 族）？有无 TTL 纪律（无→只能 allkeys 族）？
访问有无热点（有→LFU，无→LRU)。**答完再写配置，别看名字像就选。**

---

## 4. 内存碎片处理

### 特征与判读
```bash
redis-cli INFO memory | grep fragmentation
# mem_fragmentation_ratio > 1.5 且长期不降 = 碎片治值return阎值
# < 1.0 = swap 已在走,比碎片更严重,先治 swap
```
成因：频繁写删不同体量 key、jemalloc 尺寸桶空置。RSS 高 ≠ 数据多——
先用 MEMORY DOCTOR 区分「碎片高」与「数据多」。

> 判读钩玄：ratio > 1.5 且长期不降 = 值得整理的碎片阈值；< 1.0 = swap 已
> 在走，比碎片更严重，先治 swap 再谈碎片。

### 处置
```bash
# 在线平滑整理(4.0+):CPU 换空间,低峰开
redis-cli CONFIG SET activedefrag yes
# 整理参数按默认起步:阈值 100MB 碎片量/比例 10%,别一次拧到底
```
- 开 defrag 期间观测 CPU 与延迟，`latency reset` 后采样；撑不住即收尾（回 no);
- 根治姿势仍是体量纪律（§2 大 key 与 TTL 审计）——碎片是病征不是病因;
- 极端情形备放缓存重建式迁移：新实例 BGSAVE 替换接流（冷启动缓存预热清单在案）。
