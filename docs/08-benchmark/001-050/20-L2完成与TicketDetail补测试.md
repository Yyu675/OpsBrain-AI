# 20 · L2 三页落地 + TicketDetail 补测试（第一批）

> 执行日期：2026-08-25
> 结论：**L2 阶段 B 全部完成**，占位路由 14 → 8 条；TicketDetail 从零测试到 26 例

---

## 一、L2 阶段 B 完成

| 页面 | 之前 | 现在 |
|---|---|---|
| `/integrations` | `ComingSoonPanel` | **接入管理**（健康探测 + 排查建议 + 指标目录） |
| `/monitoring` | `ComingSoonPanel` | **实时监控**（5 张卡片 + 迷你趋势 + 掉线提醒） |
| `/trends` | `ComingSoonPanel` | **趋势分析**（5 档时间范围 + 多实例对比 + 区间统计） |

加上上一轮的后端 B1/B2（`PrometheusClient` + `MetricsCatalog` + 5 个端点），
**阶段 B 收官**。

### 三页各自最值得说的一个设计

**接入管理 —— 把技术错误翻译成「下一步做什么」**

直接显示 `ConnectException: Connection refused` 对运维没有帮助，
他知道连不上，需要的是「去哪查」。按错误特征分流：

| 错误特征 | 给出的动作 |
|---|---|
| 未启用 | 设 `PROMETHEUS_ENABLED`，不是让他查网络 |
| 连接被拒 | `docker compose up -d prometheus` + `curl /-/healthy` |
| 超时 | 看 `/targets` + 调 `TIMEOUT`（**不提起容器**——超时说明服务是通的） |
| 非 JSON | base-url 可能指向了错误的服务/端口 |

分流错了用户就会照着错误方向查，这几条各有一例测试钉住。

**实时监控 —— 多实例取最大值，不是平均**

「一台 100% + 三台 0%」取平均显示 25%，会把真正的故障完全抹平。
监控关心的是「最糟的那台」。

**趋势分析 —— 步长由范围推导，不让用户填**

用户关心「看多久」，不是「采样多密」。step 填太小在 7 天窗口产出几十万点，
填太大则把尖峰抹平——而尖峰恰恰是排障最需要看到的。
五档预设的点数都落在 120~720 之间，有测试钉住这个区间。

### 一条贯穿三页的不变式：null 不能伪装成 0

Prometheus 对刚重启的实例、除零的 rate 返回 NaN（后端转 null）。

- 卡片数值：全 null 显示「—」，不是 0。
  **「CPU 0%」和「取不到 CPU」是完全不同的两件事**
- 趋势图：null 转 NaN 让 ECharts 断线。用 0 会画出假的「跌到底」，
  看起来像服务挂了
- 区间统计：null 排除在均值之外。`[10, null, 20]` 当 0 算均值会变成 10
  而不是 15，**而扩容决策就是照着这个数做的**
- 反过来，**0 本身是有效读数**，不能用 `if (!value)` 判空

---

## 二、TicketDetail 补测试（第一批 26 例）

### 测试当场抓出一个真实缺陷

```vue
<!-- 修复前 -->
{{ reply.author.charAt(0) }}
```

而后端 DTO 里 `author: string | null`（系统生成的记录可能无作者）。
一条 null author 会让**整条时间线渲染崩溃**——用户看到空白页，
而不是少一个头像。Vue 的渲染错误不会被 try/catch 兜住。

已抽 `initialOf()` 统一处理，补三例回归。

**这正是补测试的意义**：这个缺陷在 2722 行里肉眼极难发现，
但只要把组件挂起来渲染一次就暴露了。

### 首批覆盖的选择理由

优先覆盖**纯派生逻辑与状态机**——它们是拆分时最容易被搬错位置的部分，
且错了不抛异常，只会让进度条停在错误阶段、按钮显示错误文案。

两条值得单说的不变式：
- **至多一个 current 阶段**：多个会让用户不知道现在该做哪一步
- **已解决但未验证时验证阶段标 skipped**：否则进度条永远停在「验证」

### 搭脚手架踩的两个坑

1. `useTicketAnalysis` 的桩必须按实际解构的 25 个字段全列，
   少一个就在 `onMounted` 报 `xxx is not a function`，报错离真实原因很远。
2. `structured` 桩成 null 会让模板报错——但真实实现是 computed 恒返回对象。
   **这是桩错了，不是产品缺陷**。核对源码后才下的结论。

---

## 三、当前状态

```
后端  mvn verify   ✅  330+ tests
前端  980 tests    ✅  49 文件（本轮 +98：L2 三页 73 + TicketDetail 26）
CI    连续 8 次绿
```

| 项 | 进展 |
|---|---|
| 占位路由 | 14 → **8**（剩余均为 L4 自愈任务，依赖执行引擎） |
| Controller 测试 | 2/18 |
| TicketDetail | 0 → **26 例** |

---

## 四、剩余工作

### TicketDetail 还需要覆盖的部分（拆分前）

首批只覆盖了派生逻辑。要安全拆分，还差：

| 区块 | 内容 | 估计 |
|---|---|---|
| 写操作 | 十几个动作的防重入、乐观更新、失败回滚 | 1 轮 |
| SSE 流 | AI 分析 ×2、知识沉淀的断流与取消 | 0.5 轮 |
| 表单 | 验证/根因/转派/附件上传的校验与禁用态 | 0.5 轮 |

**再约 2 轮补测试，然后 2 轮拆分。**

### 拆分方案（补完测试后执行）

当前结构：script 958 行 / template 628 行 / style 1135 行。

建议拆成：
```
views/TicketDetail.vue              编排 + 布局（目标 < 400 行）
composables/useTicketActions.ts     十几个写操作 + 防重入
composables/useTicketClosure.ts     闭环阶段 + SLA 派生
components/ticket/TicketTimeline.vue    时间线
components/ticket/TicketProperties.vue  属性栏 + 标签
components/ticket/TicketAttachments.vue 附件
```

style 的 1135 行大部分可随组件一起搬走，这是拆分收益最大的部分。

---

## 五、建议

按原顺序继续：**TicketDetail 补测试（2 轮）→ 拆分（2 轮）**。

如果你希望中途插入别的，两个候选：
- 补其余 16 个 Controller 测试（CI 已通，坑已趟平，可批量做）
- L4 自愈任务页（但仍受限于执行引擎不存在，建议维持占位）
