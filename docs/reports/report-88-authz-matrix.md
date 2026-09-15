# 批 88 · 越权安全实测矩阵报告（H：三身份 × 端点矩阵真机验证）

> 所属：全范围深度审计 · 追加维度（用户选定三方向之三）
> 日期：2026-09-16 | 方式：真机实测（dev 8088/ai，REAL 模式）
> 身份矩阵：ADMIN / OPS（新建测试号）/ 匿名

## TL;DR

**越权拦截 15/15 全部符合预期**（静态设计 → 真机证据）。过程中**抓到并修复 1 个 P1 真 bug**：
知识库创建文档在真实库（存在 simhash 候选）时必炸 50001——SELECT 列表与 row mapper 脱节。

## 一、实测矩阵结果

### OPS 越权 ADMIN 端点（预期 403）

| 端点 | 结果 |
|---|---|
| GET /saga/attention | ✅ 403 |
| GET /audit/operations | ✅ 403 |
| GET /agent/traces/stats | ✅ 403 |
| GET /approvals | ✅ 403 |
| GET /healing/executions | ✅ 403 |
| GET /governance/policies | ✅ 403 |

### 匿名访问受保护端点（预期 401）

| 端点 | 结果 |
|---|---|
| /tickets、/saga/attention、/approvals、/knowledge/docs、/chat/history | ✅ 全部 401 |

### OPS 合法边界（防误伤，预期 200）

| 端点 | 结果 |
|---|---|
| GET /knowledge/docs、GET /tickets | ✅ 200 |

### 知识库写操作分级（KnowledgeWriteGuard）

| 操作 | 权限 | 结果 |
|---|---|---|
| OPS 创建文档 | requireEdit（ADMIN+OPS） | ✅ code:0（**修复后**，见 §二） |
| OPS 废弃文档 | requireDestructive（仅 ADMIN） | ✅ **40103「该操作不可逆，需要 ADMIN 角色」**——错误信息可读 |
| ADMIN 废弃文档 | — | ✅ 成功 |
| OPS 物理删除 purge | @SaCheckRole("ADMIN") | ✅ 403 |

### 匿名 webhook

| 场景 | 结果 |
|---|---|
| dev（无密钥）POST /alerts/webhook | ✅ 200——设计放行（批 88-C E1 已验证 429 闸） |
| prod 密钥强制 | 静态验证 fail-closed（批 4 报告），本机为 dev 不复测 |

## 二、P1 真 bug：创建文档 50001（已修复，真机回归通过）

### 现象

OPS 创建文档 → `50001 创建文档失败: bad SQL grammar [SELECT … WHERE simhash IS NOT NULL …]`
——但该 SQL 在 psql 直接执行**完全正常**，极具误导性。

### 根因

`KnowledgeDocRepository.findSimhashCandidates` 的 **SELECT 列表漏了 `category_id`**，
而 row mapper 里 `rs.getLong("category_id")` 读不存在的列 → SQLException →
Spring 包装成 `BadSqlGrammarException`。**错误不在 SQL，在 mapper 读了没查的列。**

### 为什么此前从未暴露

- 只有库里**已存在 simhash 候选**（PUBLISHED/DRAFT 且 simhash 非空）时才走到 mapper；
  测试 mock 了 repo / 空库无候选 → mapper 永不执行。
- 一旦生产库有任何已发布文档，**创建第二篇文档必然 50001**。

### 修复与验证

- SELECT 补 `category_id` 列（带注释说明误导性）。
- 真机回归：修复前 50001 → 修复后 code:0（id=20 创建成功，nearDuplicates 正常）。
- `KnowledgeDocServiceWriteTest` 0 失败。

> 教训：**「bad SQL grammar」不一定是 SQL 错**——row mapper 读了 SELECT 外的列
> 也会报同样的错。psql 能跑通 + Java 报 grammar 错 = 优先查 mapper 与 SELECT 的列对齐。

## 三、测试数据清理说明

- `ops-tester`（OPS 测试号）：**保留**（后续安全回归可复用；密码为本地默认 admin123）。
- 测试文档 #20（越权测试-OPS可编辑验证）：已 DEPRECATED。

## 四、结论

三身份越权矩阵 15/15 符合预期，权限体系（Sa-Token 拦截 + @SaCheckRole + KnowledgeWriteGuard 分级）真机证据闭环。P1 修复已提交。
