# 批次 2 审计报告：RAG 知识库模块

> 所属：全范围深度审计（分阶段逐批） | 批次 2（共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：M5 RAG 知识检索 / 知识文档管理（CRUD、生命周期、去重、版本历史、回滚、向量化）
> 检查维度：静态缺陷 · 契约一致性 · 生命周期治理 · 测试覆盖

## TL;DR

- 知识库模块（M5）**代码工程质量高，未发现新的 P0/P1 缺陷**。
- 核心业务逻辑（去重 / 回滚 / 生命周期状态机 / 检索熔断 / 孤儿切片清理）均成熟且**有测试覆盖**。
- 批 88-B2 的回滚 NPE 修复**正确且有测试**（`restoreForcesPublishedStatus` / `restoresContentAndTitle`）。

---

## 一、静态审计结论——质量高，无新 P0/P1

| 文件 | 结论 | 亮点 |
|---|---|---|
| `KnowledgeManageController` | ✅ 正确 | 废弃 `ingest` 返回 410（防孤儿切片）；分页参数兜底（防 page=0 异常 / size 拉全表）；移除有害 `@CrossOrigin` |
| `KnowledgeDocController` | ✅ 正确 | 生命周期语义（废弃 vs 物理删）；乐观锁更新；回滚 NPE 修复（版本必填）；版本 diff；按源工单反查（L1.5 回链） |
| `KnowledgeWriteGuard` | ✅ 正确 | 写权限分级：`requireEdit`=ADMIN+OPS（可逆）、`requireDestructive`=仅 ADMIN（不可逆）；显式方法规避 `SaMode` 静默失效风险 |
| `KnowledgeDocService.restore`（批 88-B2 区域） | ✅ 正确 | 版本内容占用预检（防撞唯一索引）；回滚后强制重置 PUBLISHED（防"回滚了却检索不到"）；统一走 update 保留版本历史 |
| `HybridRetrieverService` | ✅ 正确 | 向量+关键词混合检索；**L4 熔断（Score<0.73）在 SQL WHERE 生效**（保持 topK 语义）；按内容 hash 去重保父切片 |
| `OrphanChunkCleanupScheduler` | ✅ 正确 | 清理孤儿切片后清语义缓存（防旧答案残留命中）；幂等；单事务 |

## 二、测试覆盖

- ✅ 回滚：`restoreForcesPublishedStatus` / `restoresContentAndTitle`（`KnowledgeDocServiceWriteTest`）
- ✅ 去重：`ContentFingerprintTest`（SimHash）
- ✅ 检索/索引/可见性：`KnowledgeDocVisibilityTest`、`KnowledgeDocControllerWebTest`、`KnowledgeCategoryMoveRenameTest`

## 三、权限一致性核查

抽查 `purge`（物理删，`@SaCheckRole("ADMIN")`）与 `requireDestructive()`（仅 ADMIN）、`restore`/`deprecate` 等权限语义，**一致，无绕过**。回滚属可逆（版本号 +1 保留现场），OPS 可做合理。

## 四、结论

知识库模块静态层**无新增 P0/P1**，多轮治理（6.21/批 88-B2 等）已收敛主要正确性问题。本轮按"深层次、多维"原则对去重 / 回滚 / 生命周期 / 检索 / 孤儿清理逐项核查，结论全部健康。

## 附：本批涉及文件

- `src/main/java/com/devops/agent/controller/KnowledgeManageController.java`
- `src/main/java/com/devops/agent/controller/KnowledgeDocController.java`
- `src/main/java/com/devops/agent/common/guard/KnowledgeWriteGuard.java`
- `src/main/java/com/devops/agent/domain/rag/KnowledgeDocService.java`
- `src/main/java/com/devops/agent/domain/rag/HybridRetrieverService.java`
- `src/main/java/com/devops/agent/application/runtime/OrphanChunkCleanupScheduler.java`
