# 128 knip 死代码清零·第四波：types 真账清零与成对死链判例

> 报告性质：前端模块技术债（阶段 D），批次 25（T41），接续报告 122/125/127。
> **里程碑**：未用类型 91 → 14，叠加未用导出 7——**剩余 21 条全部为 knip 假阳
> 存证，真账双域归零**。至此 knip 真账三域全毕业：重复导出 ✅ / 导出 ✅ / 类型 ✅。

---

## 一、三簇施工（本批 57 条真账全收）

| 簇 | 域 | 条数 | 处置 |
|---|---|---|---|
| 1 | composables 21 + stores 3 | 23 去 export + 1 删 | `Options/Return` 型自建 API 面零消费全部去化；`DocStatus` 别名单行零消费整删 |
| 2 | utils 14 + config 5 | 19 去化 | http.ts 喉咙文件双类型（HttpRequestOptions/BizEnvelope）同文件 7 引用自证 |
| 3 | api 尾域/constants/components/api-queries | 13 去化 + 1 删 | `SSEEvent` 零引用接口整删……引来第四条判例 |

## 二、第四条判例（对决策轴的补丁）：成对死链必须双删

`SSEEventType`（矩阵 samefile=2）去 export 后，四闸当场 TS6196——
它的另一个「引用」正是刚删掉的零引用接口 `SSEEvent`。

> **矩阵的 samefile≥2 可能是「共同体死亡」**：A 只被 B 引用、B 又零引用——
> 去化 A 的 export 只会让 tsc 立刻揭发 B 为 never used。
> 判例：删前先问「我的同文件引用者自身消费面多大」，共同体死链必须一起删。

这条闸的价值不在抓错（tsc 本来就兜底），在于把「为什么 knip 的 samefile 计数
会骗你」写成了可复用的初审问题。

## 三、假阳总账（21 条，T42 校准批的完整清单自此封版）

| 域 | 条数 | 硬证物 |
|---|---|---|
| exports | 7 | `BASE_URL`/`navigationItems`/`getStatusClass` 等有 2~7 处真引用 |
| types | 14 | `FrontendTicket` **cross=46**（re-export 盲区最硬证物）、`BackendTicket` 13、双同名 `PagedResult`（auditLogs/governance 各 4-5）等 |

判读同一性：全部是「grep 实测有真跨文件引用、knip 5.88.1 仍报未用」——
对 re-export/barrel/`import type` 某些形态的已知解析盲区，不是我们代码的问题。

## 四、四闸与下一批（T42 内容定稿）

tsc 0 / lint RC=0 / vitest 93 文件 1806 绿 / build ✓。

T42（knip 战线终章）已在案：
1. `knip.config.ts` 21 条假阳逐条 ignore 校准，每条随证据注释（假阳不是秘密，
   校准后 knip 版本收敛时可随时删行复查）；
2. ci.yml 删 knip `continue-on-error` + 基线注释口径更正（21/14/17 新面）；
3. **knip 从警告升级为 CI 门禁**（workflows 权限 D-A 已还）。
