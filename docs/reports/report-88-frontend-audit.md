# 批 88 · 前端模块级审计报告（F1 对话 / F2 工单 / F3 知识库 / F4 治理）

> 所属：全范围深度审计 · 追加维度（用户选定三方向之二）
> 日期：2026-09-16 | 覆盖：151 个前端文件（视图 22146 行 + stores 2153 行）

## TL;DR

- 前端代码质量高：4 个业务域抽查均无新 P0/P1，成熟设计（乐观回滚、净化契约、错误边界）全覆盖。
- **实质发现 1 项并已修复**：前端测试套件完全跑不起来（33/95 文件级失败）——
  批88-B4 lock 清理的后遗症（jsdom 损坏 + sass-embedded 缺失），修复后 **95 文件 / 1834 用例全绿**（`e6103b7`）。

## F1 · 对话链路（chat.ts store 408 行 + api/chat.ts 146 行）

| 核查点 | 结论 |
|---|---|
| 会话桶模型（LRU 淘汰 / 'global' 伪键 / 跨 tab 同步） | ✅ 成熟；流式期间忽略跨 tab 覆盖（防 token 丢失） |
| 消息 ID 撞车修复（`nextMsgId` 单调序号） | ✅ 注释记录了真实 bug 场景（同毫秒删错消息） |
| 登出 `clearAll`（防下一位登录者读到他人对话=越权读取） | ✅ **有接线**（useSessionCleanup.ts:52） |
| SSE 接入（fetch-event-source） | ✅ onopen 读 ApiResponse message（错误可读化）、onClose 兜底防"永远生成中"、解析失败走 onError、throw 阻断重连 |
| 与后端 5 事件契约 | ✅ 与批88-C 真机联调事件结构完全匹配 |

## F2 · 工单域（TicketList 1918 行 / TicketDetail 1612 行 / tickets.ts 899 行）

| 核查点 | 结论 |
|---|---|
| 乐观回滚（CLAUDE.md #11 承诺） | ✅ appendReply：乐观插入→服务端校准→version 同步→**按索引回滚**（修复过 Pinia 代理比较陷阱）；updateTicket snapshot+版本号并发校验 |
| 回复契约对齐 | ✅ `{role,author,authorColor?,content}` ↔ 后端 AddReplyRequest 一致（真机已验） |
| 20 个 store 方法 catch 覆盖 | ✅ 全部有错误处理 |

## F3 · 知识库域（KnowledgeBase/Detail/Editor 4688 行 + knowledge.ts）

| 核查点 | 结论 |
|---|---|
| v-html XSS 净化（CLAUDE.md #12 承诺） | ✅ 超出承诺：不只 DOMPurify 实现，还有**契约测试**（`vHtmlSanitized.contract.test.ts` 扫描全部 .vue 源码，强制每个 v-html 绑定走净化函数）——测试驱动的安全保证 |
| safeMarkdown 全局唯一入口 | ✅ 禁止自行 marked+DOMPurify |

## F4 · 治理域（AutomationPolicies/ActionAllowlist/AuditLogs/RiskLevels 等）

| 核查点 | 结论 |
|---|---|
| DataStateBoundary 统一加载/错误/空态 | ✅ 全域接入，模式一致 |
| SagaCompensation 页 | ⚠️ 无 DataStateBoundary（占位页演进而来），有 catch 兜底——P3 风格统一候选，非缺陷 |

## 实质发现与修复：测试环境损坏（已修复，`e6103b7`）

```
现象：33/95 测试文件文件级失败；1236 个已加载用例全绿，但 598+ 用例被掩盖
根因1：node_modules/jsdom 安装损坏（只剩 lib/，无 package.json）——批88-B4
      lock 回退 347 行时 npm 未同步重装 → vitest forks worker 起不来
根因2：sass-embedded 从未被声明——dev server 按需懒编译不需要，测试环境
      全量编译 .vue lang.scss 必须
修复：npm install（恢复 jsdom）+ npm install -D sass-embedded
验证：95 文件 / 1834 用例全绿（含净化契约、乐观回滚、重新生成等被掩盖用例）
```

> **教训**：lock 文件清理后必须同步 `npm install` 并跑一次全量测试，
> 否则 node_modules 与 lock 的漂移会静默摧毁测试环境。

## 结论

前端 4 域审计完成，质量高。本轮最有价值产出不是新 UI 缺陷，而是**修复了被掩盖的整个前端测试环境**——1834 个用例从"跑不起来"恢复到"全绿"，此后前端回归有保障。
