# K8s 进阶故障手册（分卷）

> 本卷与《K8s故障排查手册》分卷：那卷管 CrashLoop/OOM/日志，本卷管
> 内存配额、探针、挂载、镜像、节点 NotReady、资源占用、事件查阅、Pod 内存泄漏、磁盘清理。
> 元戒不变：**事件先看**——`kubectl describe` + `kubectl get events` 能在 80% 的
> K8s 现象里直接说出答案，先别翻日志。

---

## 1. K8s 内存配额（requests/limits）合理设置

### 口径先正
- **requests** 是调度器账本（放哪台机由它定）与 QoS 分级的入场券；
- **limits** 是运行期天花板：超 CPU limit 被 throttle（降频不履行），
  超内存 limit 被 **OOMKill（直接杀）——二者命运完全不同**。

### 建议姿势（按工作负载）

| 类型 | requests | limits | 理由 |
| --- | --- | --- | --- |
| JVM/长驻服务 | 实测 P95 堆+非堆 ≈ 总量的 85% | 内存 = requests 或 ×1.2;CPU **不设限** | JVM 堆自适应容器（MaxRAMPercentage=75)——内存限取决 OOM 余量；CPU 谷峰型服务限 CPU=人为制造 P99 尾巴 |
| 计算型批任务 | 基线平均值 | =请求值（Guaranteed QoS) | Guaranteed 最不容易被驱逐，重要批任务首选 |
| 突发型网关 | 折叠型（高 requests) | 内存 ×2 | 突发靠 limit 灵活，调度靠 requests 真实 |

三戒律：
1. **requests 先量再写**：不量写 2g 实际吃 200m，调度账本全是空洞——节点「满了」却在闲；
2. CPU limit 是**自愿 throttle**：几乎所有延迟敏感型服务都不该设;抢 CPU 的邻居问题用 requests/cpuset/监控解决，不用互掐解决；
3. QoS 决定驱逐序：Burstable 先于 Guaranteed 被 evict——核心服务想要活，先给自己 Guaranteed。

---

## 2. Liveness 探针配置过严会怎样

### 现象
Pod 反复重启（RESTARTS 上涨）但应用日志「还没来得及错」，CPU/内存平平。

### 机理
liveness 失败→kubelet 重启容器。**判死活的标准配置过严，是把「慢」判成「死」**：
- `initialDelaySeconds` 小于应用冷启动耗时（JVM 预热/懒加载全量）→ 启动中即被杀，永远起不来；
- `timeoutSeconds=1` + `periodSeconds=5` 在 GC 停顿窗口必误杀；
- 探针实现里调了 DB/下游——下游抖 = 全副本连环殉。

### 合理姿势
- liveness 只证「进程活着且未死锁」——**只答 /ping 的答案，不查依赖**（依赖归 readiness);
- readiness 探依赖，失败=摘流不重启——两种错误的命运不同，别用反；
- JVM 类应用起步参考：`initialDelaySeconds ≥ 60`、`timeoutSeconds ≥ 5`、
  `failureThreshold ≥ 3`；冷启更长的用 `startupProbe` 把 liveness 起判时间延后。
- 见本仓 Dockerfile HEALTHCHECK 同宗设计：/ping 只活，不探 DB——编排层的同款戒律。

---

## 3. FailedMount 排查

```bash
kubectl describe pod <pod> | tail -30   # 事件区通常直接点名
```
按事件指纹定病：

| 事件指纹 | 病因 | 处置 |
| --- | --- | --- |
| `MountVolume.SetUp failed: configmap "x" not found` | 引用不存在的配置 | 创建/Pod 改名对账（名字大小写全对） |
| `secret "y" not found` | Secret 未建/跨 namespace 引用 | 同 ns 验证 `kubectl get secret` |
| `timed out waiting for...attachdetach` | 卷控制器/云盘挂载超时 | `kubectl get pvc`(Bound?)、云盘是否挂着他机（ReadWriteOnce 冲突） |
| `failed to prepare subPath` | subPath 目标在卷内不存在 | 预建目录或 init 容器创建 |
| NFS 超时 | 网络/exports 权限 | 节点上手动 mount 同参数复现 |

口径：pvc Pending ≠ FailedMount 直接病因——先看 pvc 为什么没 Bound（没 SC?容量?访问模式？)。

---

## 4. 容器镜像拉取失败排查

| 事件指纹 | 病因 | 处置 |
| --- | --- | --- |
| `ErrImagePull: manifest unknown` | tag 打错/没推上仓 | 本地核 tag digest：别信 latest，用推进仓后的真 sha |
| `pull access denied / 401` | imagePullSecret 缺/过期 | ns 内核 secret;拉取权限按仓 ACL 不回用个人 token |
| `failed to resolve reference ... i/o timeout` | 节点出网/仓墙 | 节点 curl 仓地址；内网仓换 mirror;proxy 变量进 containerd |
| `ImagePullBackOff` 循环 | 上型失败重试退避 | 只治上面一型，别无脑删 Pod——删除只是换一个 Pod 撞同一堵墙 |
| 大层首拉超时 | 镜像层太大+冷节点 | 镜像瘦身、预热节点、仓近线部署 |

---

## 5. K8s 节点 NotReady 处理

### 排查顺序（按概率从大到小）
1. **kubelet 挂了/卡了**:`systemctl status kubelet; journalctl -u kubelet --since -30m | tail -50`
   ——最常见死法：磁盘满触发 image GC 循环、证书轮换失败、节点 OOM 连带；
2. **节点资源压力**（DiskPressure/MemoryPressure/PIDPressure 三污点）:
   `kubectl describe node <node> | grep -A20 Conditions`——taints 一读出真相；
3. **网络断**:controlplane 侧 `kubectl get nodes` 红心，节点上见不到 apiserver:
   查安全组/路由/CNI 插件 pod 是否这根节点独挂;
4. **容器运行时死**：containerd/dockerd 健康（`crictl ps` 通不通）;
5. 云虚拟机本身（宿主机迁移/底层事件）。

### 处置姿势
- 先 `kubectl cordon` 挡新调度，别急着驱逐——还在跑的业务 Pod 先不搅；
- 病灶确诊且短期修不好→`kubectl drain --ignore-daemonsets --delete-emptydir-data`
  逐出后修，修好 `kubectl uncordon` 回来;
- 反复 NotReady 是「病节点」证据：上污点+替换机比抢救快且便宜。

---

## 6. 容器资源占用查看

```bash
kubectl top pod -A --sort-by=memory              # 现况排序(metrics-server 必须健在)
docker stats $(docker ps -q)                     # 节点侧直采(容器名可对回)
kubectl top node                                  # 节点总量与配额压强
# 深查单容器(containerd):
crictl ps | grep <name>; crictl stats <id>
```
口径戒：`kubectl top` 取的是粒度粗的 cadvisor 数据——趋势判断够用，
峰值/P99 对齐看 Prometheus(rate(container_memory_working_set_bytes) 等),
别用 top 的瞬时值当容量账本。

---

## 7. K8s 集群事件查阅

```bash
kubectl get events -A --sort-by=.lastTimestamp | tail -30     # 全 ns 按时间
kubectl get events -n <ns> --field-selector involvedObject.name=<pod>
kubectl describe pod <pod>          # 事件=调度/kubelet 对该 Pod 的公开证言
```
阅读戒律三条：
1. 事件**有保鲜期**（默认 1h 聚合)——上午的事故下午没事件，找日志而找不到请找事件落库；
2. Warning 级别优先读，Normal 里的 Scheduled/Killing 时间线是事件对照骨架；
3. `FailedScheduling` 的 message 直接给不可调度原因（资源不足/污点/亲和冲突）——和它说话，别和它的 summary 说话。

---

## 8. Pod 内存持续增长（疑似泄漏）排查

```bash
# 1. 形快速判:kubectl top 时间序列单调爬坡不回;RSS 贴 limit 即被 OOMKill——
#    有 OOMKill 事件且 memory.max usage 贴顶 = 内漏大概率
kubectl describe pod <pod> | grep -A3 "Last State"
# 2. JVM:堆内 or 堆外分流(重启环常见堆外直存/metaspace)
#    堆内:dump(jmap -dump:live)+ MAT dominator tree
#    堆外:NMT(-XX:NativeMemoryTracking=summary,jcmd VM.native_memory)
# 3. 非 JVM:nmap+smem 驻留增长页归属;容器镜像原生日志先行
```
证据链规章（本仓 S3-3 同宗）:**有 dump 再重启**——重启拿不到的现场，OOMKill 前也拿不到;
常态处置 = 内存限额按 §1 口径 + 泄漏工单，别用无限内存养泄漏。

---

## 9. 磁盘清理（/var/log 占用过高）

```bash
# 1. 只读找出大户，别删着找:
du -sh /var/log/* 2>/dev/null | sort -rh | head
du -h --max-depth=1 /var/lib/docker 2>/dev/null | head
# 2. 有理可据的清:
journalctl --vacuum-time=7d                    # 系统日志保留 7 天
docker system prune                            # 悬空镜像/停容器(禁 --volumes)
kubectl 侧: kubelet image GC 默认自带,见 disk pressure 污点才手动 docker/crictl prune
# 3. 大文件不删只清零(进程持句柄,删了空间也不回):
truncate -s 0 /var/log/huge.log   # 或  > file,习惯成自然前者易审计
```
纪律三条：
1. **先通报后清**：生产操作走审批（L3 纪律/本仓高危操作同同款门禁）;
2. 只清有出处的空间——du 没找到大户就删，是把证据和空间一起埋;
3. 重复出现的清理动作=告警与自动化的候选（日志轮转/清理执行器），
   值三遍手动必成债（值班 SOP §5 同款）。
