# 📅 阶段3_Day7_Vue3前端智能交互界面与SSE解析对接开发实施计划

> **阶段所属**：阶段三：前后端功能联调与缓存优化  
> **当日核心目标**：用 **Vue3 (Script Setup) + Element Plus + Pinia** 构建 B 端企业运维助手的核心前端页面，基于 `@microsoft/fetch-event-source` 完美对齐后端定义的 SSE 四种自定义事件流，实现平滑防抖渲染与 Markdown 代码块排版高亮，并附带 4 个快捷面试卡片。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：能够用浏览器访问前端页面，点击上方预设的“K8s Pod 异常排查”卡片，界面立刻弹出黄色气泡 `[⚡ 正在检索运维手册...]`，随后平滑如打字机般吐出带有高亮排版的专业 Markdown 文档。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：Vue3 + Vite 项目初始化与 Element Plus / Markdown 插件安装
1. **执行脚手架创建项目**：`npm create vite@latest devops-agent-web -- --template vue`
2. **安装核心 UI 依赖与流式处理包**：
   ```bash
   cd devops-agent-web
   npm install element-plus @element-plus/icons-vue pinia
   npm install @microsoft/fetch-event-source markdown-it highlight.js
   ```

### ⏰ 13:00 - 17:00：核心对话组件编写 (`DevOpsChatView.vue`)
直接运用《白皮书》与《全路径审查报告》要求的防抖平滑处理、自定义事件分发与 Markdown 排版，将全流程页面以标准组合式 API 一次性实现：
```vue
<template>
  <div class="chat-container">
    <el-container style="height: 100vh;">
      <!-- 顶部功能卡片预设条（见面试与演示关键加分建议） -->
      <el-header class="preset-header">
        <span class="header-title">🛡️ 阿里云/K8s 智能运维 Agent (双大模型分层调度)</span>
        <div class="preset-cards">
          <el-tag v-for="(item, index) in presetQuestions" :key="index" class="preset-card"
                  @click="quickAsk(item.query)">
            📌 {{ item.label }}
          </el-tag>
        </div>
      </el-header>

      <!-- 中间主对话框渲染区域 -->
      <el-main class="chat-main" ref="chatScrollBox">
        <div v-for="(msg, index) in messages" :key="index" :class="['message-row', msg.role]">
          <div class="avatar">{{ msg.role === 'user' ? '🧑‍💻' : '🤖' }}</div>
          <div class="bubble">
            <!-- 气泡上方中间状态指示栏 (当模型在查库或提单时亮起) -->
            <div v-if="msg.toolStatus" class="tool-status-badge">
              ⚡ {{ msg.toolStatus }}
            </div>
            <!-- Markdown 渲染核心区 -->
            <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
            <!-- 底部监控元数据小气泡 -->
            <div v-if="msg.meta" class="meta-footer">
              <span>⏱️ 耗时: {{ msg.meta.latencyMs }}ms</span>
              <span v-if="msg.meta.isCached" class="tag-cached">🚀 命中语义缓存 (0消费)</span>
              <span v-else>💰 成本: {{ msg.meta.costRmb }}元</span>
            </div>
          </div>
        </div>
      </el-main>

      <!-- 底部提问输入交互栏 -->
      <el-footer class="chat-footer">
        <el-input v-model="userInput" placeholder="向智能运维专家提问报错信息、或点击上方经典指令快速体验..."
                  :maxlength="1500" show-word-limit type="textarea" :rows="2"
                  @keyup.enter.native="sendQuery" :disabled="isStreaming" />
        <el-button type="primary" :loading="isStreaming" @click="sendQuery" class="send-btn">
          发送指令
        </el-button>
      </el-footer>
    </el-container>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

// 1. 初始化带有代码块高亮的 Markdown 解析器
const md = new MarkdownIt({
  html: true,
  linkify: true,
  highlight: (str, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch (__) {}
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  }
})

const renderMarkdown = (text) => text ? md.render(text) : ''

// 2. 4个一键面试演示快捷卡片数据
const presetQuestions = ref([
  { label: 'K8s Pod 频繁 CrashLoopBackOff 诊断', query: 'K8s Pod 一直处于 CrashLoopBackOff 状态，应该怎么按官方手册一步步排查？' },
  { label: '阿里云 SLB 后端 ECS 健康检查失败', query: '阿里云 SLB 网关出现 ECS 后端服务器健康检查失败异常，请给出排查操作并关联文档！' },
  { label: '生成网络抖动高优先级工单', query: '生产环境出现大面积网络超时断开异常，立刻帮我开一张高优先级的紧急处理工单！' },
  { label: 'MySQL 主从同步延迟排查指南', query: '查一下 MySQL 主从复制延迟超大有哪些根本原因以及具体的系统变量处理命令？' }
])

const userInput = ref('')
const isStreaming = ref(false)
const messages = ref([{ role: 'assistant', content: '您好！我是企业内部 IT/DevOps 智能运维助手。已为您连接本地 RAG 手册库与双引擎大模型调度层，请随时提出您的故障排查诉求。' }])
const chatScrollBox = ref(null)

// 3. 核心调用流式 SSE 并精确分发四类自定义事件的方法
const sendQuery = async () => {
  if (!userInput.value.trim() || isStreaming.value) return
  const queryText = userInput.value
  messages.value.push({ role: 'user', content: queryText })
  userInput.value = ''
  isStreaming.value = true

  // 预先占位一个 AI 回答气泡
  const aiMessageIndex = messages.value.length
  messages.value.push({ role: 'assistant', content: '', toolStatus: null, meta: null })
  scrollToBottom()

  try {
    await fetchEventSource(`http://localhost:8080/api/v1/chat/stream?query=${encodeURIComponent(queryText)}`, {
      method: 'GET',
      headers: { 'Accept': 'text/event-stream' },
      onmessage(ev) {
        const payload = JSON.parse(ev.data || '{}')
        const currentMsg = messages.value[aiMessageIndex]

        // 根据后端发送的自定义 SSE 事件名称精确分流执行
        if (ev.event === 'start') {
          currentMsg.toolStatus = '🚀 AI分层调度层启动中...'
        } else if (ev.event === 'tool_status') {
          currentMsg.toolStatus = payload.message || '⚡ 正在执行运维手册知识库查询...'
        } else if (ev.event === 'token') {
          currentMsg.toolStatus = null // 一旦开始流式冒真实文本，立刻隐藏工具气泡提示
          currentMsg.content += payload.text
          scrollToBottom()
        } else if (ev.event === 'complete') {
          currentMsg.meta = {
            latencyMs: payload.latencyMs,
            isCached: payload.isCached,
            costRmb: payload.costRmb || 0.002
          }
          isStreaming.value = false
        }
      },
      onerror(err) {
        messages.value[aiMessageIndex].content += '\n\n`【前端提示：后端连接发生中断，流式推流中止。】`'
        isStreaming.value = false
        throw err // 阻止底层自动无限重连
      }
    })
  } catch (err) {
    isStreaming.value = false
  }
}

const quickAsk = (q) => { userInput.value = q; sendQuery() }
const scrollToBottom = () => nextTick(() => { if (chatScrollBox.value && chatScrollBox.value.$el) chatScrollBox.value.$el.scrollTop = chatScrollBox.value.$el.scrollHeight })
</script>

<style scoped>
.chat-container { background: #f4f6f8; }
.preset-header { background: #1e293b; color: #fff; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; }
.header-title { font-weight: bold; font-size: 16px; }
.preset-cards { display: flex; gap: 10px; }
.preset-card { cursor: pointer; transition: all 0.2s; }
.preset-card:hover { transform: translateY(-2px); }
.chat-main { overflow-y: auto; display: flex; flex-direction: column; gap: 20px; padding: 20px; }
.message-row { display: flex; gap: 12px; max-width: 85%; }
.message-row.user { align-self: flex-end; flex-direction: row-reverse; }
.avatar { font-size: 28px; }
.bubble { background: #fff; padding: 14px 18px; border-radius: 12px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.message-row.user .bubble { background: #3b82f6; color: #fff; }
.tool-status-badge { background: #fef08a; color: #854d0e; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: bold; margin-bottom: 8px; display: inline-block; }
.meta-footer { margin-top: 10px; padding-top: 8px; border-top: 1px dashed #e2e8f0; font-size: 12px; color: #64748b; display: flex; gap: 15px; }
.tag-cached { color: #16a34a; font-weight: bold; }
.chat-footer { background: #fff; border-top: 1px solid #e2e8f0; display: flex; align-items: center; gap: 15px; padding: 15px 20px; height: auto !important; }
.send-btn { height: 50px; padding: 0 25px; }
</style>
```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：严格处理 `markdown-it` 解析换行与空白字符**  
   由于前端 SSE 接收数据流块时，有的 Token 可能带有未闭合的代码块制表符。在 CSS 中给 `.markdown-body pre` 与 `.markdown-body code` 设定 `overflow-x: auto` 并锁定等宽字体（如 `Consolas` / `Fira Code`），能极大避免渲染闪动。
2. **💡 建议二：快捷面试演示卡片必须放在顶部居中位置**  
   很多全栈应届生写的页面在面试里吃亏，是因为面试官点进链接后看到光秃秃的一个输入框，根本没时间仔细思考输入什么。你在页面上方做成 `El-Tag` 快捷标签，点击即可触发一键提问，这个小优化会让面试官对你的产品感知立刻飙升到顶峰！

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 启动前端项目：`npm run dev`，浏览器访问 `http://localhost:5173` 能够瞬间展现精美专业的三栏排版页面
- [ ] 点击最上面的标签 `"📌 K8s Pod 频繁 CrashLoopBackOff 诊断"`，输入框自动填充并发送，界面中立刻亮起黄色小标识 `⚡ 正在检索知识库...`，随后由 Markdown 引擎逐个字符高亮冒出文字及排版代码，到底部时正确输出文献出处 `[📚 参考：K8s手册-P14]`
- [ ] 多次发送同义问题时，底部的元数据小气泡能够瞬间变成绿色字体：`🚀 命中语义缓存 (0消费)`
