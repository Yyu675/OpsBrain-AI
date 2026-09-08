# 122 knip 死代码清零·第一波：四类分治与 @public 豁免标准件

> 报告性质：前端模块技术债（阶段 D），批次 19。
> 动因是 ci.yml 里挂了号的借条：knip 死代码检测长期
> `continue-on-error: true`，退出条件白纸黑字——**存量清零后转正门禁**。
> 清零不依赖任何外部权限，与批次 18「权限未到弹药先行」同构先行；
> 删 `continue-on-error` 那一行与 ci 注释口径更正，并入权限落地批次。

---

## 一、账面三口径（对账先行，动刀在后）

| 口径 | 未用导出 | 未用类型 | 重复导出 | Unlisted 依赖 |
|---|---|---|---|---|
| ci.yml 注释基线（89e4f74 时代） | 23 | 53 | 1 | — |
| 本批开工实测（knip 5.88.1） | 21 | 92 | 1 | 17 |
| 本批收刀后 | **17** | **91** | **0** | 17 |

口径漂移（23/53→21/92）不是清理成果也不是劣化，而是 knip 版本与代码
生长的合账——**对账先于动刀**，否则「减少」与「暴露」会被混读。
ci 注释的基线数字已过期，更正挂权限落地 checklist（同一文件，同批走）。

## 二、本批动刀 4 条 + 一类毕业

| 条目 | 初判 | 证据 | 终判 |
|---|---|---|---|
| `DiagnosisSufficiencyStat`(dashboard.ts) | 未用类型+导出 | 仅同文件自引用（批次 16 自产自账） | 去 `export`，双榜离账 |
| `permission` 重复出口（directives) | duplicate | `main.ts` 走 named import | 删 `export default`，**重复导出类清零** |
| `THEME_INIT_SCRIPT`(useTheme) | 疑死链 | `index.html:13` 注释自证「内容以本常量为 SSoT」 | **保留 + `@public`**（HTML 层引用，knip 盲区） |
| `mountWithQuery`(test-utils) | 疑死链 | 文件头注释明写「保留待用，knip 属预期误报」 | **保留 + `@public`** |
| `getApproval`(api/approval) | 疑死链 | 后端 `GET /api/v1/approvals/{id}` **真实在服务** | **保留 + `@public`**（详情页未建的半成面） |

三条「疑死链」全翻案为保留——证据优于直觉：
SSoT 双份（HTML 先于 JS 加载防白闪，历史决策）、公共库待用、
前后端成对接口。删它们省的是一行账，丢的是下一位开发者的接线模板。

## 三、`@public` 定为豁免标准件

本批确立并即时生效的仓库约定：**knip 盲区里的公开 API，统一以
JSDoc `@public` 标记豁免，标记必须随动因注释**（为什么外人看得见、
为什么它该活）。「假装没引用的活代码」与「真有引用的活代码」
自此在仓库里有可检索的区分面（`grep @public`）。

## 四、假阳对账（knip 5.88.1 观察，7 条已实测证伪不动刀）

`BASE_URL` / `API_PREFIX` / `navigationItems` / `TICKET_STATUS_LABELS` /
`TICKET_PRIORITY_LABELS` / `getStatusClass` / `getPriorityClass` ——
`grep` 实测均有真引用（2~7 处），却被 knip 5.88.1 判为未用导出。
处置原则：**grep 实测有真引用者一律不碰**，记入对账待 knip 版本收敛
或 entry/`.vue` 解析面复核。假阳不清零，门禁转正就有伤人风险——
这 7 条是转正前的最后一道必修题，已挂账。

## 五、剩余欠账（分批推进）

| 类 | 存量 | 下一刀 |
|---|---|---|
| 未用导出 17 | 含 7 条假阳 + 10 待逐条证伪 | T36 起，每批 ≤8 条控 diff 面 |
| 未用类型 91 | 接口/类型大批，不动面大 | 按 api/ 域分簇，逐簇证伪 |
| Unlisted deps 17 | codemirror vendor 动态加载形态待查 | 依赖声明 vs 解析面的对账单开 |
| 门禁转正 | 删 ci.yml `continue-on-error` + 注清基线口径 | **等清零 + workflows 权限**，与 eval patch 同批落地 |

验证：knip 账面三读（22→17→0 duplicate），vue-tsc 0 / lint 0 err /
vitest 93 文件 **1806 全绿** / 生产构建成。
