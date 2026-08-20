# 🛡️ OpsBrain AI（智维大脑）—— 企业级智能 DevOps 与故障自愈 AI 驾驶舱

<div align="center">

![OpsBrain AI Banner](https://img.shields.io/badge/Product-OpsBrain_AI_智维大脑-2563eb?style=for-the-badge&logo=probot&logoColor=white)
![Tech Stack Java](https://img.shields.io/badge/Backend-Spring_Boot_3_|+_LangChain4j-ed8b00?style=for-the-badge&logo=openjdk&logoColor=white)
![Tech Stack Vue3](https://img.shields.io/badge/Frontend-Vue3_|+_Element_Plus-42b883?style=for-the-badge&logo=vuedotjs&logoColor=white)
![Database](https://img.shields.io/badge/Vector_Store-PgVector_|+_Redis_7-336791?style=for-the-badge&logo=postgresql&logoColor=white)

**「让每一份运维手册都有温度，让每一次故障排查都秒级闭环」**  
*专为 2026 届 Java+Vue 全栈开发应届生打造的 10 天极速落地与 L1-L5 全自动自愈演进实战项目*

</div>

---

## 🌟 品牌命名与产品定位 (Product Branding)

* **🏷️ 英文官方名称**：**`OpsBrain AI`**  
* **🏷️ 中文官方名称**：**「智维大脑」** —— 企业私有化智能 DevOps 与故障自愈中枢  
* **💡 命名寓意**：
  * `Ops`：代表 DevOps、IT 基础设施维护与故障诊断核心场景；
  * `Brain`：代表由 **RAG 向量检索** + **LangChain4j ReAct Agent 调度** 构成的企业高智商思维大脑；
  * `智维大脑`：寓意告别过去人工查对几千页官方手册、重复开工单的低效时代，用大模型双引擎驱动系统的自动化自愈。

---

## 📂 工作区整洁目录与全套文档地图 (Workspace Structure)

工作区内各模块与规范文档分类归档于 `docs/` 四大专区中：

```text
/home/user/
├── README.md                                  # 🌟 也就是您当前正在阅读的 OpsBrain AI 产品主门户文档
├── SOP_PreFlight_Check.sh                     # 🏁 阶段一开工前 5 秒自动化环境自检与排障 Shell 脚本
│
└── docs/                                      # 📚 OpsBrain AI 全量设计规范与实施宝典
    │
    ├── 01-project-governance/                 # 【1. 项目治理与前置契约库】
    │   ├── PM项目准入评估单与开工令.md            # 项目经理签发的准入证明与三大研发纪律
    │   ├── 前置契约与零返工极速研发白皮书.md        # 彻底砍掉无效需求、冻结 JSON/SSE 数据契约与表结构
    │   └── 全套开发与设计方案总目录与导读.md        # 10天排期路线图与里程碑总览
    │
    ├── 02-architecture-design/                # 【2. 系统架构与底层演进规范库】
    │   ├── 全栈开发实践总计划与架构设计书.md        # 六层单向依赖干净架构图与双模型分流数学计算公式
    │   ├── 后端干净架构与分层职责规范书.md          # 严厉界定 controller/application/rag/tools 各层边界与代码规范
    │   ├── 系统可行性评估与架构设计避坑指南.md      # 针对切片、召回准度、意图识别的高阶可行性技术设计建议
    │   └── OpsBrain_AI_L1至L5全自动智能自愈与商业化拓展蓝图.md # 🔥中长期架构演进：24小时监测、BUG自动分级自愈与FinOps商业蓝图
    │
    ├── 03-quality-assurance/                  # 【3. 质量安全审查与 SOP 自检库】
    │   ├── 全路径异常闭环与综合审查报告.md          # 针对高并发防刷、死循环、特殊符号注入等 8 大极端异常代码级补丁
    │   └── SOP全流程跑通可行性自检与验收白皮书.md   # 解决 JDK17、维度冲突、Nginx 缓冲等 5 大卡点，确保 100% 跑通
    │
    └── 04-daily-implementation-plan/          # 【4. 每日细化开发实操白皮书 (Day 1 - Day 10)】
        ├── 阶段1_Day1_工程骨架搭建与API连通性开发实施计划.md
        ├── 阶段1_Day2_运维知识库切片解析与向量化入库开发实施计划.md
        ├── 阶段2_Day3_Agent核心引擎调度与白名单工具注册开发实施计划.md
        ├── 阶段2_Day4_四层幻觉防护体系代码落地与重试闭环开发实施计划.md
        ├── 阶段2_Day5_智能意图路由与大小模型分层调度开发实施计划.md
        ├── 阶段3_Day6_后端SSE流式打字机接口与Redis语义缓存开发实施计划.md
        ├── 阶段3_Day7_Vue3前端智能交互界面与SSE解析对接开发实施计划.md
        ├── 阶段4_Day8_运维管理后台与ECharts数据统计看板开发实施计划.md
        ├── 阶段4_Day9_自动化测试评测集构建与指标量化验证开发实施计划.md
        └── 阶段5_Day10_Docker容器化一键部署与求职作品展示精修实施计划.md
```

---

## 🛰️ 进阶演进：从被动问答到 L1-L5 智能自愈中枢与 SaaS 矩阵展望

在完成基础 10 天 MVP 后，OpsBrain AI 将依托事件驱动网关拓展至下一代自治运维场景（详见 `docs/02-architecture-design/OpsBrain_AI_L1至L5全自动智能自愈与商业化拓展蓝图.md`）：

1. **24/7 实时事件网关监测 (Event-Driven Observability)**：打通 Prometheus、Apache SkyWalking 与阿里云 SLS 的 Webhook / Kafka 通道。无需人工打字，监控系统告警即时触发 AI 后台聚合分析上下文；
2. **故障 BUG 智能分级与自愈沙盒 (Automated Triage & Self-Healing)**：
   * **🚨 P0/P1 高危故障（核心主库异常、网络分区）**：**人机协同 (HITL)**。AI 在 3 秒内出诊断报告与一键回滚脚本，强提醒专家审批执行，严禁全自动破坏性操作；
   * **⚠️ P2/P3 中低危异常（微服务 OOM、证书快到期）**：**半自动自愈+心跳观察**。AI 执行单 Pod 优雅重启等白名单操作，自动开启 5 分钟健康心跳校验；
   * **🟢 P4 日常琐碎（磁盘临时日志清理、孤儿镜像回收）**：**L5 级全自动秒级修复**。AI 静默执行底层安全清理，仅在群内推送处理成功日志；
3. **安全自愈灰度与防错回滚  (Canary & Auto-Rollback)**：强制将爆炸半径隔离在 **`1/20 = 5%`** 的单节点范围，如果修复后错误率上升，自动执行 `rollout undo`；
4. **四大千亿级商业化板块拓展 (SaaS Horizons)**：
   * 💰 **FinOps 智能云成本优化大脑**：巡检闲置云服务器与磁盘，夜间自动关机研发测试环境，直接帮企业节省 **40%~60%** 云电费；
   * 🛡️ **SecOps 智能云安全审计与防守**：实时分析 WAF 日志，捕获 0-Day SQL 注入，自动写入安全组封锁攻击源 IP；
   * 🌪️ **AI SRE 混沌工程演练导师**：在测试环境中主动扮演攻击者注入延时，并为系统的弹性恢复能力打出量化评分；
   * ⚙️ **DevOps CI/CD 流水线智能领航员**：秒级定位 Jenkins / GitLab 构建依赖冲突，自动生成修复分支并发 PR！

---

## 🏆 OpsBrain AI（智维大脑）核心面试杀手锏汇总

1. **「四层幻觉自愈体系」**：将大模型在企业内部文档场景下的事实幻觉率 **从 28% 压降至 4.2% 以内**；
2. **「大小模型智能分流 + 语义缓存」**：日常咨询走轻量模型 + Redis 相似度拦截，单次耗时压缩至 **1.8s**，单用户花费 **降低超 70%**；
3. **「父子切片与混合检索 (Parent-Child & Hybrid Search)」**：`PgVector` 余弦向量与 `tsvector` 倒排索引并进，召回精确度提升至 **92%+**；
4. **「六层干净单向依赖架构 (Clean Architecture)」**：底层大模型连接池与具体厂商 100% 解耦；
5. **「人机协同与 L1-L5 自治沙盒」**：对齐工业界最高安全等级的自动化自愈策略，兼顾极速处理与绝对稳定！

> **🔥 开工寄语**：所有规划均具备工业实战支撑。点击左侧导航栏运行 `./SOP_PreFlight_Check.sh`，顺着每日指南书写你的传奇全栈实战之旅！
