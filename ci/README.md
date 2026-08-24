# CI 工作流（待启用）

## 为什么在这里而不是 `.github/workflows/`

这份工作流本应放在 `.github/workflows/ci.yml`。但当前会话通过 GitHub App 推送，
而该 App **没有 `workflows` 权限**，推送带 `.github/workflows/**` 的提交会被服务端拒绝：

```
refusing to allow a GitHub App to create or update workflow
`.github/workflows/ci.yml` without `workflows` permission
```

因此先把文件放在 `ci/` 下随其余改动一起提交，避免整批工作被这一个权限问题卡住。

## 如何启用（30 秒，需要你本地执行一次）

```bash
git pull
mkdir -p .github/workflows
git mv ci/github-actions-ci.yml .github/workflows/ci.yml
git commit -m "ci: 启用 GitHub Actions 工作流"
git push
```

用你自己的账号推送即可通过（个人账号有 workflow 权限）。推送后打开仓库
**Actions** 标签页即可看到首次运行。

## 这份工作流做什么

| Job | 步骤 |
| :-- | :-- |
| **后端** | JDK 21 → 校验 mvnw 可用 → 起 pgvector/Redis → 执行 `init.sql` 与 13 个迁移 → `mvnw verify`（`AI_MODE=MOCK`，不烧额度） |
| **前端** | `npm ci` → typecheck → lint → test → knip（暂不阻断）→ build |

### 几个刻意的设计

- **中间件端口与口令严格对齐 `application-dev.yml`**（25432 / 26379 /
  `devops_password` / `devops_redis_pwd`）。三个 `@SpringBootTest` 跑在 dev profile 上，
  对不上就会连不上数据库。
- **只起 PostgreSQL + Redis**，不用 `docker-compose.dev.yml` 整套 ——
  MinIO / Prometheus / Adminer 在 CI 里用不到，起了只拖慢。
- **knip 设了 `continue-on-error`**，并在文件内注明了退出条件。
  存量代码本就有 23 个未用导出 + 53 个未用类型（已用 `89e4f74` 基线核对，数量一致），
  直接阻断会让 CI 从第一天起就是红的 —— 长期红着的 CI 等于没有 CI。
  阶段 D 清完存量后删掉该标记即可正式纳入门禁。

## 首次运行时的预期

前端部分已在本地逐条实跑验证通过（typecheck、lint 0 error、563 tests、build）。

**后端部分尚未经过编译验证** —— 开发沙箱内没有 JDK 且 Maven Central 不可达。
本轮的后端改动是在静态校验（结构 / 导入 / 依赖签名对照上游源码）基础上完成的，
**首次 CI 运行就是它们的第一次真实编译**。若有编译错误，会在这一步暴露，属预期内。
