# 批次 31（D-D）：评测基线自动更新 workflow_dispatch 接线——增强件落地

日期：2026-09-08 ｜ 状态：完成（激活待并入 main，脚本链已实证） ｜ 承接：报告 118(S4-3 基线门禁）、eval_compare.js 头注挂账（「CI 内 workflow_dispatch 接线挂账——同 T12 先例」)

---

## 一、何为 D-D（决策纪要补录）

4-3.2 首跑即基线的 CI 侧回写：主线门禁（S4-3 回归对比）只**读** `tools/audit/eval_baseline.json`;
基线从哪来——此前只有「本地真窗首跑 + 人工 --update 提交」一条路。D-D 决策：**在 CI 装一条
workflow_dispatch 手动派单的自动更新通道**，定位增强件，**不动主线门禁一根指头**。

## 二、文件

- `.github/workflows/eval-baseline-update.yml`（新增）：派单 → 镜像主线 backend 环境重跑
  `mvnw verify`（AI_MODE=MOCK 契约层常驻落盘）→ `eval_compare` 先**亮账**（劣化 rc=1 整步失败，
  后续 update/commit 不执行——回归物理上无法混进基线）→ 无劣化才 `--update` 成物
  → diff 比对，有变化才以 bot 身份提交回派单分支。
- `tools/audit/eval_compare.js` 头注挂账转已办，指向新工作流。

## 三、四道守护设计（防假绿/防自激/防漂基线）

1. **亮账先于回写**:compare 不过 = 工作流红 = 零提交。派单人必须先在 run 日志里看清账本。
2. **环境同口径**:services/schema init/redis 密码/verify 命令逐字段镜像主线 backend job——
   「轻量环境造基线、全量环境查基线」会系统性误红，禁止。
3. **`[skip ci]` 防自激**:bot 提交的基线是纯数据文件，同 SHA 代码再跑主线无新增信号，跳过。
4. **审计链**：提交信息强制携带派单理由（inputs.reason 必填）;target/eval-metrics.json 与
   eval-compare.txt 上传 artifact 留档 30 天。
5. 权限隔离：主线 `ci.yml` contents:read 不变；本工作流独立声明 contents:write，两门不相通。

## 四、边界声明（别指望它干不在职的事）

- **EVAL_RAG/EVAL_LLM 真窗基线不在其能力域**:runner 无真模型凭证，MOCK 窗只产契约层指标。
  真窗首跑基线仍走 4-3.2 原定路径（本地真窗 + 人工 --update)——S4-2 ECE 红线欠账不变。
- 无历史基线时 compare 按首跑语义 rc=0 放行（引导语义与主线一致），本工作流即可承担
  「 MOCK 契约层基线首跑」的搬运工角色。

## 五、验证账（本批实测）

- **激活边界（实测发现）**:`workflow_dispatch` 工作流在落到默认分支 `main` 前不在 Actions 注册（派单 API 404)。本会话限定 arena 分支、不碰 main,故**真派单端到端并入 main 后首次可用**——非阻塞设计：文件随分支入主干即激活。
- **脚本链全场景本地转台实证**(eval_compare 真身，四场):
  A 首跑无基线→亮账 rc=0 放行 + --update 成物 ✓
  B 基线一致→diff 跳过分支不提交 ✓
  D 改善/持平→rc=0,单侧缺席指标跳项可见 ✓
  C 真劣化(securityBlockRate -10pt / leakedTotal +1)→**rc=1 中止** ✓
- 转台教训（两连，自记一账）：转台 JSON 必须保持 `layers` 嵌套结构与 current/baseline 角色不翻转——第一次模拟因少包 layers 而全空比出假绿，第二次把恶化写反角色又假绿，**工具本身倒是每次都是对的**。


## 六、事件丢失案（批 31 报账插曲，结案）

推送 `0c4e920` 后 25+ 分钟零 workflow run（push 与 PR 双事件皆无）,YAML 解析合法、
远端 HEAD 正确——判为 **GitHub 侧 push 事件丢失**（罕见，与本日早段 artifact 下载 EOF
同属平台抖动气质）。处置：**空探针法**——在同枝顶推空提交 `bfde51a` 复触发事件，
探针 run 立即入队且树与 0c4e920 完全相同，其双腿 success 即批 31 内容的有效 CI 声明。
教训入报账纪律：推后先核「run 是否存在」再核 conclusion；零 run 超时即探针复触，
不许把「没跑」静默报成「没红」。
