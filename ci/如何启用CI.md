# 如何启用 CI（本地操作指引）

> 面向：仓库拥有者本人在**本地终端**执行
> 耗时：约 30 秒操作 + 5~8 分钟等待首次运行
> 为什么要你来做：见文末「为什么我（AI Agent）不能代劳」

---

## 一、为什么这件事卡住了

工作流文件现在躺在 `ci/github-actions-ci.yml`，**不在** GitHub 认的
`.github/workflows/` 目录下，所以 GitHub 根本不会执行它。

我尝试过直接推送到正确位置，被 GitHub 服务端拒绝：

```
! [remote rejected] refusing to allow a GitHub App to create or update
  workflow `.github/workflows/ci.yml` without `workflows` permission
```

这是 GitHub 的安全设计：**第三方 App 不能凭空给仓库添加会自动执行的代码**。
否则一个只申请了「读写代码」权限的 App，就能通过写 workflow 拿到
仓库 Secrets 和部署权限。这个限制是对的，不该绕过。

所以需要你用**你自己的 Git 身份**推一次。

---

## 二、操作步骤

在你本地的仓库目录下，逐条执行：

```bash
# 1. 切到本会话分支并拉取最新代码
git checkout arena/01a031f6-opsbrain-ai
git pull origin arena/01a031f6-opsbrain-ai

# 2. 把工作流移到 GitHub 认的位置
mkdir -p .github/workflows
git mv ci/github-actions-ci.yml .github/workflows/ci.yml

# 3. 提交并推送
git commit -m "ci: 启用 GitHub Actions 工作流

工作流此前暂存在 ci/ 目录（不会被执行）——GitHub 不允许第三方 App
写入 .github/workflows/，需仓库拥有者本人推送。

启用后后端 172 个 Java 文件将首次获得真实编译验证。"

git push origin arena/01a031f6-opsbrain-ai
```

**注意第 2 步用 `git mv` 而不是 `cp`**：用 `cp` 会让两份文件同时存在，
以后改 CI 时容易改错那一份没生效的。

---

## 三、去哪里看结果

推送后：

1. 打开 **https://github.com/Yyu675/OpsBrain-AI/actions**
2. 左侧会出现名为 **CI** 的工作流
3. 点最新一次运行，能看到两个并行任务：
   - **后端 · 编译与测试**（约 5-8 分钟）
   - **前端 · 类型/规范/测试/构建**（约 2-3 分钟）

### 如果 Actions 页面显示「Workflows aren't being run on this repository」

去 **Settings → Actions → General**，把
**Actions permissions** 设为 `Allow all actions and reusable workflows`，
保存后回到 Actions 页面点 **Re-run jobs**。

新建的仓库偶尔默认是关闭状态，这一步只需做一次。

---

## 四、对首次运行结果的预期

**前端大概率直接绿。** 它的每一步我都在沙箱里实跑过：
typecheck / lint / 847 tests / build 全通过，CI 只是重跑一遍。

**后端很可能是红的，这是正常且预期的。** 原因：

沙箱内没有 JDK，Maven Central 与全部 8 个国内镜像均不可达，
所以 **172 个后端 Java 文件从来没有被编译过一次**——
包括本会话新增的 `ClientIpResolver`、`AuditLogController`、
`AuditLogQueryRepository`、以及刚落地的 11 个 governance 相关文件。

我能做的静态兜底都做了（字符级括号平衡扫描、包名与路径一致性、
项目内 import 可解析性、被调用方法的签名逐个核对），
但这些替代不了真实编译——比如泛型推断、Lombok 生成的方法、
Spring 注解处理器的行为，静态检查看不出来。

**首次运行的价值就在于把这些问题一次性暴露出来。**
你不需要自己修，把失败日志给我，或者直接说「CI 红了」，
我会去 Actions 拉日志逐个修掉。

---

## 五、工作流里几个值得你知道的设计

这些是我写工作流时的取舍，你 review 时可以对照看：

| 设计 | 原因 |
|---|---|
| 后端起 pgvector + Redis 两个 service | 有 3 个 `@SpringBootTest` 需要真实 Spring 上下文，跑在 dev profile 上。端口/口令与 `application-dev.yml` 严格一致（PG 25432、Redis 26379），否则连不上 |
| 不用 `docker-compose.dev.yml` 整套 | MinIO / Prometheus / Adminer 在 CI 里用不到，起了只拖慢 |
| `AI_MODE: MOCK` | 不调真实模型，CI 不烧 API 额度也不依赖外网 |
| `SCHEMA_FAIL_FAST: 'true'` | 迁移漏执行会让测试直接失败，而不是等运行时用户点检索才发现。这两个环境变量在 `application.yml` 里都有对应占位符（`${AI_MODE:MOCK}`、`${SCHEMA_FAIL_FAST:false}`），已核对过能正确绑定 |
| 先跑 `init.sql` 再按 `sort -V` 跑全部迁移 | 我已验证**全部 21 个 SQL 文件的 INSERT 都可重复执行**（用 `ON CONFLICT DO NOTHING` 或 `WHERE NOT EXISTS` 守住），所以 init 与 migration 内容重叠不会让 `ON_ERROR_STOP=1` 中断 |
| 校验 `mvnw` 可执行位 | `mvnw` 曾长期未提交，导致 clone 下来无法开箱构建。这一步防止它再次丢失 |
| knip 设 `continue-on-error` | 存量有 24 个未用导出 + 57 个未用类型（已用基线 `89e4f74` 核对，与本次改动无关）。直接阻断会让 CI 从第一天起就是红的，而**长期红着的 CI 等于没有 CI**——大家会习惯性忽略，真正的回归也跟着被忽略。存量清零后删掉这行即可转为门禁 |
| `concurrency` + `cancel-in-progress` | 同分支的新提交取消尚在跑的旧任务，避免排队浪费 |

---

## 六、为什么我（AI Agent）不能代劳

不是能力问题，是**权限边界**，而且这个边界是合理的：

GitHub 对第三方 App 的 `workflows` 权限单独隔离，因为能写 workflow
就等于能在 CI 环境里执行任意代码，进而拿到仓库的 Secrets、
部署凭证、以及对其他分支的写入能力。

一个申请了「代码读写」的 App 若能顺手加 workflow，
就构成了权限提升。GitHub 拒绝我，是在保护你的仓库。

同理，**我也不会建议你给我开这个权限**——
让你手动推一次（30 秒）比长期放开一个高危权限划算得多。
