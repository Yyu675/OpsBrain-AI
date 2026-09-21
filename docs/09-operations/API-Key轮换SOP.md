# API Key 轮换标准操作流程（SOP）

> 版本：v1.0 | 最后更新：2026-09-20 | 适用环境：生产/预发/开发

---

## 一、为什么需要轮换

API Key（如百炼 `sk-ws-*`）一旦在以下场景中出现过，即视为已暴露，必须轮换：

- 日志输出中包含 Key（未打码的异常堆栈）
- 对话/工单/Issue 中粘贴过含 Key 的配置片段
- 屏幕截图或录屏中包含 Key
- 代码仓库中曾提交过含真实 Key 的 `.env` 文件
- 离职/转岗的前同事曾接触过 Key

**原则**：视为密码管理，泄露即轮换，不赌"没人看到"。

---

## 二、轮换前准备

### 2.1 确认影响范围

```bash
# 1. 确认当前哪些服务在用这个 Key
grep -r "AI_CHAT_API_KEY\|AI_EMBEDDING_API_KEY\|ALIBABA_API_KEY\|MODEL_KEY_CRYPT_SECRET" .env .env.example docker-compose*.yml

# 2. 确认当前运行的模型（轮换后需验证同一模型仍可用）
curl -s http://localhost:8088/ai/api/v1/health/ai-model
```

### 2.2 通知相关方

| 通知对象 | 内容 | 提前时间 |
|:---|:---|:---|
| 运维团队 | 计划轮换时间窗口 | 提前 1 天 |
| 开发团队 | 轮换后更新本地 `.env` | 轮换当天 |
| 若有 CI 环境 | 更新 CI Secret 变量 | 轮换同时 |

---

## 三、执行步骤

### Step 1：登录百炼控制台

```
https://bailian.console.aliyun.com
→ API 管理 → API Key 列表
```

### Step 2：创建新 Key

1. 点击「创建 API Key」
2. 命名建议格式：`opsbrain-{env}-{date}`（如 `opsbrain-prod-20260920`）
3. **创建后立即复制 Key**（关闭弹窗后 Key 不再完整显示）

### Step 3：删除旧 Key

1. 在 API Key 列表中找到旧 Key（形如 `sk-ws-H.xxxxx`）
2. 确认该 Key 的**最近调用时间**已超过 5 分钟（确认新 Key 已生效）
3. 点击「删除」→ 确认

### Step 4：更新配置文件

```bash
# 1. 更新 .env（生产环境）
# 修改以下变量为新 Key 值：
AI_CHAT_API_KEY=sk-ws-新Key
AI_EMBEDDING_API_KEY=sk-ws-新Key
ALIBABA_API_KEY=sk-ws-新Key

# 2. 如果 docker compose 部署
docker compose up -d app   # 重启应用加载新 Key

# 3. 如果是本地 IDEA 运行
# 重启 Spring Boot 应用
```

### Step 5：验证新 Key 生效

```bash
# 1. 健康检查（REAL 模式 + StartupGuard 会在启动时自检）
curl http://localhost:8088/ai/actuator/health

# 2. 发一条测试对话（验证 chat Key）
TOKEN=<登录获取的 satoken>
curl -X GET "http://localhost:8088/ai/api/v1/chat/stream?query=测试" \
  -H "satoken: $TOKEN" -H "Accept: text/event-stream"

# 3. 上传一篇测试文档（验证 embedding Key）
curl -X POST "http://localhost:8088/ai/api/v1/knowledge/docs/create" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"轮换测试","category":"测试","content":"API Key 轮换后的 embedding 验证"}'
```

### Step 6：通知相关方新 Key

- 通过安全渠道（1Password/Vault/加密邮件）分发新 Key
- **不要在钉钉/微信/邮件正文中明文发送 Key**
- 提醒开发团队更新本地 `.env`

---

## 四、故障回退

如果新 Key 不可用（403/401 错误）：

```bash
# 1. 立即切回旧 Key（若旧 Key 尚未删除）
# 2. 或使用备用 Key（建议始终保有一个未使用的备用 Key）
# 3. 回退后排查新 Key 问题：
#    - 百炼控制台确认 Key 状态为「已启用」
#    - 确认 Key 有足够额度
#    - 确认模型授权（部分模型需要单独开通）
```

---

## 五、轮换记录

| 日期 | 环境 | 旧 Key 尾号 | 新 Key 尾号 | 操作人 | 验证结果 |
|:---|:---|:---|:---|:---|:---|
| 2026-09-20 | dev | .....YIR | 待轮换 | Ocelo | 待执行 |
| | | | | | |

---

## 六、关联文档

- `.env.example`：完整环境变量模板
- `CLAUDE.md §5.3`：维度铁律约束
- `AGENTS.md §3.3`：三处联动铁律（VECTOR(n) / vector.dimension / EmbeddingModel）
- `RealModeStartupGuard.java`：启动期维度+Key 自检
- `docs/09-decisions/`：多知识库按库切片参数决策