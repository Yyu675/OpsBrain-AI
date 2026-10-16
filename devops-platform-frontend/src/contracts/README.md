# 前后端契约

## 这是什么

`backend-contract.json` 由后端测试 `ContractExportTest` **用反射从真实代码导出**：

| 契约段 | 后端来源 | 导出方式 |
|---|---|---|
| `ticketStatus` | `TicketEnums.Status` | `nextStates()` 逐状态调用 |
| `fieldLimits` | `TicketController.CreateTicketRequest` | 反射读 `@Size(max=...)` |
| `bizCodes` | `BizError` 枚举 | 遍历 `values()` |

前端 `__tests__/backendContract.test.ts` 读它做断言。

## 为什么不是手工镜像

此前前端把后端的值抄一份再断言。这能防住「改了一侧忘另一侧」，
**但防不住「镜像本身抄错」**——镜像仍是手写的。

导出后单一真相源是后端代码本身：改了后端不重新导出，前端测试失败；
导出了但前端没跟上，前端测试也失败。

## 为什么提交进版本库

前端 CI 不应依赖「先跑一遍后端测试」。文件进库后前端可独立运行，
且契约变更会出现在 PR diff 里——评审时一眼能看到「这次改了状态机」，
而不是埋在某个 Java 文件第 60 行。

## 怎么更新

改了后端的状态机 / `@Size` / `BizError` 之后：

```bash
./mvnw test -Dtest=ContractExportTest
git diff devops-platform-frontend/src/contracts/backend-contract.json
```

diff 非空说明契约变了，此时前端契约测试大概率会失败——**这是预期行为**，
按失败信息同步前端实现即可。

## 已经用它抓到过什么

状态机曾漂移出 8 处不一致（前端 UI 手写枚举 vs 后端 `canTransition`），
其中「已解决/已关闭 → 重新打开」被前端误禁用，导致故障复发只能新建工单，
同一故障历史被拆成两张单、MTTR 统计失真。

## 当前限制

- 只覆盖三处已知契约，不是全量 API 契约。全量需要 OpenAPI（springdoc），
  投入 3-5 天且改动面覆盖所有 Controller，暂未做。
- `ContractExportTest` **尚未在 CI 中运行过**（CI 未启用，见仓库根 `ci/README.md`）。
  当前 JSON 是按后端源码的真实取值生成的，字段来源与该测试一致；
  CI 启用后首次运行应确认 diff 为空。
