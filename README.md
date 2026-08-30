# OpsBrain AI（智维大脑）

**AI 运维知识中枢 + 智能工单系统**

以知识资产为核心、以治理与审计为边界的企业级运维协同平台。
让 SRE 从「翻手册、搬告警、开工单」的重复劳动里解放出来。

![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-6db33f?style=flat-square&logo=springboot&logoColor=white)
![LangChain4j](https://img.shields.io/badge/LangChain4j-Agent-000000?style=flat-square)
![Vue](https://img.shields.io/badge/Vue-3.5-42b883?style=flat-square&logo=vuedotjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?style=flat-square&logo=postgresql&logoColor=white)

---

## 一、这个产品是什么

**一句话**：运维团队的知识库 + 工单系统，中间加了一个能查知识、能开单、
能在受控授权下做诊断的 AI Agent。

### 它解决的三件事

| 痛点 | 做法 |
|---|---|
| 故障处置经验散落在个人脑子和聊天记录里 | 结构化知识库 + RAG 检索，AI 回答**强制带引用出处** |
| 告警刷屏，值班人淹没在重复通知里 | Prometheus 告警接入 → 去重 → 聚合抑制 → 自动建单 |
| AI 想在生产环境动手，但没人敢批 | 风险分级 → 动作白名单 → 审批流 → Saga 补偿 → 全量审计 |

### 它现在**不是**什么（重要）

- ❌ **不是全自动自愈系统**。执行器目前只有 K8s 只读诊断，
  「低危故障 AI 自动修复」是 roadmap，不是现有能力。
- ❌ **不是多租户 SaaS**。27 张表中仅 3 张带 `tenant_id`，无行级隔离，
  当前定位私有化部署。
- ❌ **不是云成本优化平台**。现有的成本统计是 **AI 调用成本**，
  不是云资源账单。

> 这一节是刻意写的。产品叙事与代码能力脱节，第一次 POC 就会被拆穿。
> 详细的能力盘点见 [`docs/08-benchmark/98-产品定位与技术栈评估.md`](docs/08-benchmark/98-产品定位与技术栈评估.md)。

---

## 二、能力现状（代码实测，2026-08-28）

| 级别 | 能力 | 状态 |
|:---:|---|:---:|
| **L1** | 辅助问答：答疑、查知识、开工单 | ✅ **可用** |
| **L2** | 主动感知：告警接入、去重、自动建单、实时推送 | ✅ **可用**（信号源限 Prometheus） |
| **L3** | 人机协同：诊断建议 → 审批 → 执行 → 补偿 | ✅ **可用**（治理对象目前为建单） |
| **L4** | 受控自愈：低危场景自动执行 | ⚠️ **治理骨架就绪，执行器待建** |
| **L5** | 端到端自治 | ⏳ 未启动 |

**设计原则**：AI 的自主权与它证明的可靠性严格挂钩。
每一级都在上一级被充分验证、安全边界清晰后才开放。

---

## 三、核心模块

| 模块 | 端点 | 主要能力 |
|---|---:|---|
| **AI 对话与 Agent** | 7 | SSE 流式问答、大小模型分流、三层记忆（Redis/PG/MinIO）、上下文预算裁剪、四层防幻觉、成本配额、全链路追踪 |
| **知识库** | 29 | 文档全生命周期、版本回滚、父子分块、混合检索（向量+关键词）、近重复检测、分类与标签体系 |
| **工单系统** | 43 | CRUD + 状态机 + SLA 首响扫描 + 回复/活动流 + 附件 + AI 分析 + **复盘与改进项看板** |
| **告警接入** | 5 | Alertmanager webhook → 去重窗口 → 聚合抑制 → 自动建单 → WebSocket 推送 → 钉钉通知 |
| **治理与审批** | 31 | 风险分级、动作白名单、自动化策略（含 dry-run）、审批流、Saga 补偿、全量审计 |
| **认证与团队** | 4 | Sa-Token + bcrypt、三级角色（admin/operator/viewer） |
| **看板与可观测** | 11 | 工单/告警统计、AI 成本与缓存趋势、Prometheus 指标代理、分项健康探针 |

**规模**：后端 197 文件 / 37.6K 行，前端 242 文件 / 73.7K 行，
130 个 REST 端点，27 张表，30 条前端路由。

---

## 四、技术栈

### 后端

| 领域 | 选型 |
|---|---|
| 框架 | Spring Boot 3.5.6 / Java 21（虚拟线程） |
| AI 编排 | LangChain4j |
| 模型 | 阿里云 DashScope（qwen-plus / qwen-max / text-embedding-v2） |
| 向量库 | PostgreSQL 16 + pgvector（1536 维） |
| 缓存/会话 | Redis 7（Sa-Token + 记忆热层） |
| 鉴权 | Sa-Token 3 + jbcrypt |
| 对象存储 | MinIO（附件 + 冷归档） |
| K8s | fabric8 kubernetes-client |
| 可观测 | Micrometer + Prometheus |
| 实时 | WebSocket（告警）+ SSE（AI 流式） |

### 前端

| 领域 | 选型 |
|---|---|
| 框架 | Vue 3.5 + TypeScript + Vite |
| 状态 | Pinia（客户端）+ TanStack Vue Query（服务端） |
| UI | Element Plus + ECharts（按需引入） |
| SSE | `@microsoft/fetch-event-source` |
| Markdown | marked + DOMPurify（统一 `safeMarkdown` 入口） |
| 测试 | Vitest + @vue/test-utils（1,756 例） |

---

## 五、快速开始

```bash
# 1. 起依赖（PostgreSQL + pgvector / Redis / MinIO）
docker compose up -d

# 2. 初始化库表
psql -h localhost -p 25432 -U devops -d devops_knowledge_db -f sql/init.sql

# 3. 配置模型密钥
export ALIBABA_API_KEY=your_key

# 4. 后端（:8088，context-path /ai）
./mvnw spring-boot:run

# 5. 前端（:5173）
cd devops-platform-frontend && npm ci && npm run dev
```

---

## 六、工程质量

这个项目在质量纪律上的投入超出常规：

- **测试**：后端 103 个测试文件，前端 1,756 例；
- **契约测试**：前后端错误码、状态流转守卫、分页钳制、事务边界、
  日志脱敏、部署时区等**跨端/跨层约定都有测试守着**；
- **注入-还原验证**（硬纪律）：每写一条测试，都要把它声称防住的缺陷
  真的注入产品代码、确认测试会红、再还原。**不做这一步等于不知道自己写的是不是假测试**；
- **审查报告**：`docs/08-benchmark/` 下 99 篇，每篇记录缺陷成因、
  用户可见后果、修复理由，以及**「判定不做」的事项及其理由**。

详见 [`AGENTS.md`](AGENTS.md)（硬约束）与 [`PROGRESS.md`](PROGRESS.md)（进度台账）。

---

## 七、文档地图

| 文档 | 内容 |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | **任务进度台账**——进行中/待验收/已验收/已决策不做 |
| [`AGENTS.md`](AGENTS.md) | 开发硬约束：分层依赖、响应契约、向量维度、测试纪律 |
| [`docs/PRD.md`](docs/PRD.md) | 产品需求文档（含目标态愿景） |
| `docs/02-architecture-design/` | 架构设计与分层职责 |
| `docs/08-benchmark/` | 99 篇审查报告，逐轮记录缺陷与决策 |
| `docs/08-benchmark/98-*` | **产品定位、技术栈选型与业务模块全景评估** |

---

## 八、Roadmap

**上生产门槛**（P0）：数据库迁移（Flyway）· API 契约（springdoc-openapi）
· 集成测试真实依赖（Testcontainers）· 熔断限流（Resilience4j）

**规模化**（P1）：分布式追踪 · 消息队列 · 多租户隔离决策 · 前端类型自动生成

**能力扩展**：真实执行器（先做「重启 Pod / 扩缩容」并全程走审批）
· 多告警源接入 · SSO/LDAP

> 进度实时更新在 [`PROGRESS.md`](PROGRESS.md)。
> **README 只登记已验收的能力**——正在做的事不写在这里。
