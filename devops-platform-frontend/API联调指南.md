# 前后端 API 接口联调指南

## ✅ 已完成工作

### 1. API 文档更新
- 修改 `docs/05-development-design/03-API接口设计.md`
- Base URL: `http://localhost:8080` → `http://localhost:8088/ai`
- 完整接口路径示例: `http://localhost:8088/ai/api/v1/chat/stream`

### 2. 前端依赖安装
- ✅ 已安装 `@microsoft/fetch-event-source` (SSE 客户端库)

### 3. API 服务层创建
- ✅ `src/config/api.ts` - API 配置
- ✅ `src/api/types.ts` - 类型定义（对应后端接口契约）
- ✅ `src/api/chat.ts` - SSE 流式问答接口
- ✅ `src/api/dashboard.ts` - 看板统计接口
- ✅ `src/api/knowledge.ts` - 知识库管理接口
- ✅ `src/api/tickets.ts` - 工单管理接口
- ✅ `src/api/health.ts` - 健康检查接口
- ✅ `src/api/index.ts` - 统一导出
- ✅ `src/api/examples.ts` - API 使用示例代码

### 4. 环境变量配置
- ✅ `.env.development` - 开发环境（`http://localhost:8088/ai`）
- ✅ `.env.production` - 生产环境（待配置实际域名）

---

## 🚀 联调步骤

### 第一步：启动后端服务

1. 确保 Docker 容器已启动（PostgreSQL + Redis）
   ```bash
   cd docker
   docker-compose -f docker-compose.dev.yml up -d
   ```

2. 启动 Spring Boot 后端
   ```bash
   cd devops-platform-backend
   mvn spring-boot:run
   # 或在 IDEA 中直接运行 DevopsPlatformBackendApplication
   ```

3. 验证后端启动成功
   ```bash
   # 检查健康状态
   curl http://localhost:8088/ai/api/v1/health
   ```

### 第二步：启动前端服务

```bash
cd devops-platform-frontend
npm run dev
```

前端将运行在 `http://localhost:5173`

### 第三步：测试接口连通性

#### 方式 1：使用浏览器控制台测试

打开前端页面 → F12 打开控制台 → 输入以下代码：

```javascript
// 测试健康检查接口
fetch('http://localhost:8088/ai/api/v1/health')
  .then(res => res.json())
  .then(data => console.log('健康检查:', data))

// 测试看板接口
fetch('http://localhost:8088/ai/api/v1/dashboard/overview')
  .then(res => res.json())
  .then(data => console.log('看板数据:', data))
```

#### 方式 2：使用 curl 命令测试

```bash
# 1. 健康检查
curl http://localhost:8088/ai/api/v1/health

# 2. 看板统计
curl http://localhost:8088/ai/api/v1/dashboard/overview

# 3. SSE 流式聊天
curl -N "http://localhost:8088/ai/api/v1/chat/stream?query=什么是K8s"

# 4. 知识库切片列表
curl "http://localhost:8088/ai/api/v1/knowledge/chunks?page=0&size=10"

# 5. 工单列表
curl "http://localhost:8088/ai/api/v1/tickets?page=0&size=10"
```

---

## 📋 API 接口清单

| # | 方法 | 路径 | 说明 | 前端对接 |
|---|------|------|------|---------|
| 1 | GET | `/api/v1/chat/stream` | SSE 流式问答 | `src/api/chat.ts` |
| 2 | GET | `/api/v1/dashboard/overview` | 看板统计 | `src/api/dashboard.ts` |
| 3 | POST | `/api/v1/knowledge/ingest` | 知识库摄取 | `src/api/knowledge.ts` |
| 4 | GET | `/api/v1/knowledge/chunks` | 知识库切片 | `src/api/knowledge.ts` |
| 5 | GET | `/api/v1/tickets` | 工单列表 | `src/api/tickets.ts` |
| 6 | GET | `/api/v1/health` | 健康检查 | `src/api/health.ts` |

---

## 🔧 常见问题排查

### 问题 1：前端请求后端 404 Not Found

**原因**：端口号或 context-path 不匹配

**检查清单**：
- ✅ 后端配置: `application.yml` 中 `server.port: 8088` 和 `context-path: /ai`
- ✅ 前端配置: `.env.development` 中 `VITE_API_BASE_URL=http://localhost:8088/ai`
- ✅ 完整路径: `http://localhost:8088/ai/api/v1/...`

### 问题 2：CORS 跨域错误

**后端需要配置跨域支持**：

在 `controller` 包中添加全局 CORS 配置类：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

或在每个 Controller 上添加 `@CrossOrigin(origins = "*")`

### 问题 3：SSE 流式响应中断或乱码

**后端需要设置正确的响应头**：

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chatStream(@RequestParam String query) {
    SseEmitter emitter = new SseEmitter();
    // 设置响应头
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    return emitter;
}
```

### 问题 4：数据格式不匹配

**检查后端响应格式是否符合接口契约**：

```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "traceId": "a1b2c3d4",
  "timestamp": 1720944000000
}
```

---

## 📦 如何在前端页面中使用 API

### 示例 1：在 Dashboard 中加载看板数据

修改 `src/views/Dashboard.vue`：

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getDashboardOverview } from '@/api/dashboard'
import type { DashboardOverview } from '@/api/types'

const dashboardData = ref<DashboardOverview | null>(null)
const loading = ref(false)

async function loadData() {
  loading.value = true
  try {
    dashboardData.value = await getDashboardOverview()
  } catch (err) {
    console.error('加载失败:', err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div v-if="loading">加载中...</div>
  <div v-else-if="dashboardData">
    <div>总查询次数: {{ dashboardData.totalQueries }}</div>
    <div>缓存命中率: {{ dashboardData.cacheHitRate }}%</div>
    <!-- 使用真实数据渲染 -->
  </div>
</template>
```

### 示例 2：创建智能问答聊天组件

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useCancelableChat } from '@/api/chat'

const query = ref('')
const answer = ref('')
const isStreaming = ref(false)
const { startChat, cancel } = useCancelableChat()

async function ask() {
  if (!query.value.trim()) return

  answer.value = ''
  isStreaming.value = true

  await startChat(query.value, {
    onStart: (data) => {
      console.log('使用模型:', data.routerModel)
    },
    onToken: (data) => {
      answer.value += data.text
    },
    onComplete: (data) => {
      console.log('成本:', data.costRmb, '元')
      isStreaming.value = false
    },
    onError: (data) => {
      console.error('错误:', data.message)
      isStreaming.value = false
    }
  })
}

function stop() {
  cancel()
  isStreaming.value = false
}
</script>

<template>
  <div>
    <input v-model="query" placeholder="输入问题" />
    <button @click="ask" :disabled="isStreaming">提问</button>
    <button @click="stop" v-if="isStreaming">停止</button>
    <div>{{ answer }}</div>
  </div>
</template>
```

---

## ✅ 联调验收清单

- [ ] 后端服务成功启动在 `8088` 端口
- [ ] 前端服务成功启动在 `5173` 端口
- [ ] 健康检查接口返回正常
- [ ] 看板统计接口返回数据
- [ ] SSE 流式聊天能正常打字输出
- [ ] 知识库接口能正常查询
- [ ] 工单接口能正常查询
- [ ] 浏览器控制台无 CORS 错误
- [ ] 浏览器控制台无 404 错误

---

## 📝 后续工作

### 前端页面改造（可选）

目前前端页面使用的是 Mock 数据，如需对接真实后端，可以修改以下文件：

1. **Dashboard.vue** - 看板统计页
   - 将硬编码的 `kpis` 数据替换为 `getDashboardOverview()` 的返回值
   - 将 ECharts 的 Mock 数据替换为后端返回的趋势数据

2. **TicketList.vue** - 工单列表页
   - 将 Pinia store 中的 Mock 数据替换为 `getTickets()` 的返回值

3. **KnowledgeBase.vue** - 知识库页
   - 将 Mock 文章数据替换为 `getKnowledgeChunks()` 的返回值

4. **创建新的聊天页面**
   - 使用 `src/api/chat.ts` 创建 SSE 流式问答界面
   - 参考 `src/api/examples.ts` 中的示例代码

### 后端接口开发（必做）

目前后端接口还未实现，需要按照以下优先级开发：

**P0 核心接口（Day1-Day6）**：
1. ✅ `/api/v1/health` - 健康检查（简单，先做）
2. `/api/v1/chat/stream` - SSE 流式问答（核心，M1 模块）
3. `/api/v1/dashboard/overview` - 看板统计（M8 模块）

**P1 管理接口（Day7-Day8）**：
4. `/api/v1/knowledge/chunks` - 知识库切片查询（M5 模块）
5. `/api/v1/tickets` - 工单列表查询（M7 模块）
6. `/api/v1/knowledge/ingest` - 知识库摄取（M5 模块）

---

## 📚 参考文档

- API 接口契约: `docs/05-development-design/03-API接口设计.md`
- 功能模块设计: `docs/05-development-design/02-功能模块设计.md`
- 技术架构设计: `docs/05-development-design/01-技术架构设计.md`
- API 使用示例: `src/api/examples.ts`
