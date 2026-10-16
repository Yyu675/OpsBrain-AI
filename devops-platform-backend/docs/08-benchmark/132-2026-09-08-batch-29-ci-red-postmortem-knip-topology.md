# 批 29 CI 红腿案卷：knip `@public` 拓扑漂移与本地门禁口径升级（五闸）

日期：2026-09-08 ｜ 状态：**已修复（本批）** ｜ 关联：批 27（T43）、批 28（T44）、报告 130/131

---

## 一、事件账（先更正报账，再讲修复）

| SHA | 事件 | CI 真相 |
| --- | --- | --- |
| `8024db4` | 批 27（T43）推送 | **一腿 failure**（前端「死代码检测（门禁）」）。batch-27 时报账「双腿 success」系 **watch 取错 run 对**——按 `--limit 2` 取 run 未核对 `headSha` 归属，此处更正。 |
| `7d4d574` | 批 28（T44）推送 | **双腿 failure**，两 run 的红点都是同一前端「死代码检测（门禁）」｜后端「编译与测试」腿 **success**——**128 条强化评测数据集 + 39 条安全负例契约层在 CI 实证全绿**，批 28 主件战果成立、零倒转。 |

**两次红腿同源同根**：批 27 引入的前端 knip 议题。门禁自转正后这是第一次对真实 commit 出警。

## 二、根因：`@public` 注释-声明相邻拓扑被插入点破坏

knip 的 re-export 豁免依赖 JSDoc**紧贴**声明：

```ts
/** @public …豁免原因… */
export type { Foo } from './foo';   // ← 豁免有效
```

批 27 在 `dashboard.query.ts` 施工时，把 `useAiAnalysisStatsQuery` hook **插进了豁免注释与 `export type` 行之间**，形成「注释 → hook → 声明」的夹层，注释归属漂移、豁免静默失效；同时触发 `AiAnalysisStats` 类型被 knip 判为未用。后果：本地四闸（tsc/lint/vitest/build）全绿、CI 红——**口径断层**。

- 复现方法（零挤 annotations）：`cd devops-platform-frontend && npx knip`，本地与 CI 完全等价，5 条议题。

## 三、修复（本批，2 文件）

1. `src/api/queries/dashboard.query.ts`：hook 移到 re-export 行**之后**，原注释恢复紧贴声明；批 29 案卷注释立法：**@public 豁免注释与声明之间不得插入任何代码**。
2. `src/api/ticketAiAnalysis.ts`：`AiAnalysisStats` 补 `@public` **第四种豁免形态——「返回类型推断消费」**：hook 的 `queryFn` 返回类型经 TypeScript 类型推断被消费，不存在显式 import，knip 静态不可见；此类类型需在定义处补 @public。

> knip 豁免四形态（完整戒律）：
> ① re-export 出口（紧贴注释）；② 仅类型消费的副作用 import；③ 配置/契约外部消费；④ **返回类型推断消费**（新增）。

## 四、纪律升版：前端四闸 → 五闸

批 27 若本地跑过 knip 即可提前拦截。自本批起，前端任何 commit 前的本地门禁为：

```bash
npx vue-tsc -b && npm run lint && npx knip && npm test && npm run build
```

五闸全部 RC=0 方可推送（knip 已从「任选一」转正为与 CI 等价的本地口径，杜绝口径断层）。本批五闸实证：TSC=0 / LINT=0 / KNIP=0 / 测试 93 文件 1808 用例全过 / build ✓。

## 五、watch 报账纪律补丁

- 推送后先 `gh run list --json databaseId,status,conclusion,headSha` **过滤当前 SHA** 再报结论；`gh run watch` 拿到的 run 必须核对 `headSha` 与推送 SHA 一致才计入报账（批 27 乌龙根源）。

## 六、遗迹标注

批 28 报告 131 的 CI 行「双腿 success」应读作：后端腿绿（契约层实证）+ 前端腿红（本报告修复）。本批推送后两者应全绿，以本批 watch 结果为准。
