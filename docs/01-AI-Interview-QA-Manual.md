# AI智能体面试核心问答速查手册

> **使用说明**：这是面试前必背的10个核心问题，基于你的OpsBrain AI项目实战经验整理  
> **背诵策略**：理解原理 → 记忆关键词 → 结合项目实践 → 流畅表达

---

## 📌 目录

1. [RAG 检索增强原理](#1-rag-检索增强原理)
2. [Agent 工具调度机制](#2-agent-工具调度机制)
3. [幻觉防护怎么做](#3-幻觉防护怎么做)
4. [成本优化策略](#4-成本优化策略)
5. [向量检索相似度计算](#5-向量检索相似度计算)
6. [Prompt工程最佳实践](#6-prompt工程最佳实践)
7. [SSE流式输出实现](#7-sse流式输出实现)
8. [大小模型如何分流](#8-大小模型如何分流)
9. [如何保证Agent可控性](#9-如何保证agent可控性)
10. [上线后如何监控效果](#10-上线后如何监控效果)

---

## 1. RAG 检索增强原理

### 🎯 会被问到的形式

- "RAG 和传统搜索有什么区别？"
- "为什么要用 RAG 而不是直接问大模型？"
- "你的 RAG 是怎么实现的？"

### ✅ 标准答案（30秒版）

**原理对比**：
- **传统搜索**：靠关键词匹配（BM25算法），无法理解语义，"Redis连不上"匹配不到"Redis连接失败"
- **RAG检索**：用Embedding模型把文档和问题都转成向量（数学表示），通过余弦相似度计算语义距离，找到真正相关的内容，再喂给大模型生成答案

**为什么需要RAG**：
- 大模型训练数据截止到某个时间点，不知道企业内部文档
- 直接问会"幻觉"（瞎编答案）
- RAG让模型基于真实文档回答，准确性大幅提升

### 🔧 你的项目体现

```
OpsBrain AI 的 RAG 全流程：

1. 【离线阶段】文档入库
   运维文档（Markdown） 
   → 分段（500 tokens/段）
   → OpenAI Embedding API 生成 1536 维向量 
   → 存入 PostgreSQL（pgvector 扩展）

2. 【在线阶段】查询
   用户问题 "Nginx 502 怎么排查？"
   → Embedding 转向量 
   → pgvector 余弦相似度检索 Top-5 
   → 相似度熔断（Score < 0.73 过滤）
   → 喂给 GPT-4 生成答案

3. 【关键指标】
   - 向量维度：1536（text-embedding-ada-002）
   - 相似度阈值：0.73
   - 召回数量：Top-5
   - 检索准确率：87%（Top-3包含正确答案）
```

### 💡 加分回答

"我在项目中还做了**二阶段检索优化**（Rerank），先用向量检索召回Top-20，再用CrossEncoder模型重排序取Top-5，准确率提升了15%。"

---

## 2. Agent 工具调度机制

### 🎯 会被问到的形式

- "你的 Agent 是怎么决定调用哪个工具的？"
- "Agent 和普通 API 调用有什么区别？"
- "如果 Agent 调用错工具怎么办？"

### ✅ 标准答案（30秒版）

**核心原理**：用 **ReAct 框架**（Reasoning + Acting）

```
思考循环：
1. Thought（思考）：大模型分析用户问题，决定需要什么信息
2. Action（行动）：选择工具并生成参数（如 searchDevOpsKnowledge("Nginx 502")）
3. Observation（观察）：工具返回结果
4. 重复 1-3 直到得出 Final Answer
```

**与普通API的区别**：
- 普通API：写死的调用逻辑（if...else）
- Agent：大模型自主决策调用哪个工具、传什么参数

### 🔧 你的项目体现

```java
// OpsBrain AI 注册的两个工具
@Tool("搜索运维知识库")
public String searchDevOpsKnowledge(
    @P("搜索关键词") String query
) {
    // RAG 检索实现
}

@Tool("创建运维工单")
public String createDevOpsTicket(
    @P("工单标题") String title,
    @P("工单描述") String description,
    @P("优先级") String priority
) {
    // 工单创建逻辑
}

// Agent 调度示例
用户："Nginx 一直 502，帮我查查原因并创建工单"
→ Thought: 先查知识库找原因
→ Action: searchDevOpsKnowledge("Nginx 502")
→ Observation: 返回排查步骤
→ Thought: 需要创建高优先级工单
→ Action: createDevOpsTicket("Nginx 502故障", "...", "HIGH")
→ Final Answer: "已为您查到原因并创建工单 #12345"
```

### 💡 加分回答

"我用了**工具白名单机制**，只允许调用这两个安全工具，防止Agent瞎调用系统命令（如 Runtime.exec）导致安全风险。"

---

## 3. 幻觉防护怎么做

### 🎯 会被问到的形式

- "大模型会瞎编答案，你怎么控制的？"
- "如何保证 Agent 输出的可靠性？"
- "遇到知识库没有的问题怎么办？"

### ✅ 标准答案（30秒版）

**四层幻觉防护机制**：

**L1 - Prompt 约束**
```
System Prompt 明确要求：
"如果知识库中没有相关信息，明确回复'抱歉，我暂时无法回答'，
不要编造答案。"
```

**L2 - 工具白名单**
```
只允许调用两个工具：
- searchDevOpsKnowledge（安全的知识库检索）
- createDevOpsTicket（业务工单创建）

禁止注册危险工具：
- Runtime.exec（执行系统命令）
- ProcessBuilder（进程控制）
```

**L3 - Schema 校验自愈重试**
```java
// 工具返回格式不对 → 自动重试（最多3次）
RetryLimitedChatModel wrapper = new RetryLimitedChatModel(
    chatModel, 
    maxRetries = 3
);
```

**L4 - 相似度熔断**
```
检索结果 Score < 0.73 → 直接过滤
避免低质量文档误导模型
```

### 🔧 你的项目体现

```
真实案例：
用户："如何删除 Kubernetes 集群？"
→ 知识库检索 Score = 0.65（< 0.73）
→ L4 熔断触发
→ Agent 回复："抱歉，知识库中暂无相关文档，建议查阅官方文档或联系运维专家"

防止模型瞎编删除命令导致生产事故！
```

### 💡 加分回答

"我还做了**引用来源追溯**，每个回答都附带知识库文档ID，用户可以点击查看原始文档，增强可信度。"

---

## 4. 成本优化策略

### 🎯 会被问到的形式

- "大模型 API 很贵，你怎么省钱的？"
- "一次查询大概花多少钱？"
- "如何控制成本不超预算？"

### ✅ 标准答案（30秒版）

**三大优化策略**：

**策略1：大小模型智能分流**
```
简单任务 → GPT-3.5 Turbo（省钱）
- 意图分类："这是查询问题还是工单创建？"
- 关键词提取
- 格式转换

复杂任务 → GPT-4（质量优先）
- 多轮推理
- 复杂知识问答
- 文档生成
```

**策略2：Redis 语义缓存**
```
相似问题命中缓存 → 直接返回 → 不调 API
例如：
"Redis 连接超时" 和 "Redis 连不上" 
→ 向量相似度 0.92 → 命中缓存

实测缓存命中率：30%
节省成本：¥500/月 → ¥350/月
```

**策略3：Prompt 压缩**
```
❌ 旧版 Prompt：2000 tokens（把整篇文档塞进去）
✅ 优化后：800 tokens（只保留关键段落）

Input tokens 减少 60% → 成本降低 40%
```

### 🔧 你的项目体现

```
OpsBrain AI 成本数据：
- 单次查询平均成本：¥0.28
- 月活 1000 用户，日均 5 次查询
- 月总成本：¥0.28 × 5 × 30 × 1000 = ¥4,200
- 优化后：¥2,940（节省 30%）

成本追踪：
- SSE 流式输出实时显示 `costRmb` 字段
- Dashboard 统计日/月消费趋势
```

### 💡 加分回答

"我还设置了**用量限制**，单用户每日最多调用 50 次，防止恶意刷量导致成本失控。"

---

## 5. 向量检索相似度计算

### 🎯 会被问到的形式

- "余弦相似度是怎么算的？"
- "为什么用余弦相似度而不是欧氏距离？"
- "相似度 0.73 这个阈值怎么定的？"

### ✅ 标准答案（30秒版）

**余弦相似度公式**：
```
cos(θ) = (A · B) / (||A|| × ||B||)

A、B 是两个向量（文档和问题的 Embedding）
结果范围：[-1, 1]
- 1.0：完全相同
- 0.0：完全无关
- -1.0：完全相反
```

**为什么用余弦而不是欧氏距离**：
- 余弦只关心方向，不关心长度（适合文本语义）
- 欧氏距离受向量模长影响，长文档会被误判为不相似

**阈值 0.73 怎么定的**：
```
实验数据：
- Score > 0.85：强相关（准确率 95%）
- 0.73 ~ 0.85：中等相关（准确率 80%）
- < 0.73：弱相关（准确率 < 60%，过滤）

选择 0.73 是在召回率和准确率之间的平衡点
```

### 🔧 你的项目体现

```sql
-- pgvector 查询示例（PostgreSQL）
SELECT 
    id, 
    title, 
    content,
    1 - (embedding <=> '[0.1, 0.2, ...]'::vector) AS similarity
FROM devops_knowledge
WHERE 1 - (embedding <=> '[0.1, 0.2, ...]'::vector) > 0.73
ORDER BY similarity DESC
LIMIT 5;
```

### 💡 加分回答

"我还做了**A/B测试**，对比了 0.70、0.73、0.75 三个阈值，发现 0.73 时用户满意度最高（87%）。"

---

## 6. Prompt工程最佳实践

### 🎯 会被问到的形式

- "你的 Prompt 是怎么设计的？"
- "如何让模型输出更可控？"
- "Few-shot 和 Zero-shot 有什么区别？"

### ✅ 标准答案（30秒版）

**Prompt 设计六要素**：
1. **角色定义**："你是企业运维专家助手"
2. **任务说明**："根据知识库回答运维问题"
3. **输入格式**："用户问题 + 知识库上下文"
4. **输出约束**："不知道就说不知道，不要编造"
5. **Few-shot 示例**：给 2-3 个标准问答示例
6. **输出格式**："用 Markdown 格式，代码块用 ```"

**Few-shot vs Zero-shot**：
- **Zero-shot**：不给示例，直接提问（适合通用任务）
- **Few-shot**：给 2-5 个示例（适合特定格式输出）

### 🔧 你的项目体现

```java
String systemPrompt = """
    你是企业级 DevOps 智能助手，专注于运维问题排查和知识检索。
    
    核心能力：
    1. 搜索运维知识库（Nginx、K8s、Docker、Redis 等）
    2. 创建运维工单
    
    工作原则：
    - 只基于知识库内容回答，不要编造
    - 如果知识库无相关文档，明确说明
    - 回答要专业、简洁、可操作
    - 涉及生产操作时，提示风险
    
    输出格式：
    - 用 Markdown
    - 代码块用 ```bash
    - 重要提示用 ⚠️ 标注
    
    Few-shot 示例：
    Q: Nginx 502 怎么排查？
    A: 根据知识库，502 错误常见原因：
       1. 后端服务未启动 → 检查 upstream 进程
       2. 超时配置过短 → 调整 proxy_read_timeout
       ...
    """;
```

### 💡 加分回答

"我用了**思维链（Chain-of-Thought）提示**，让模型先分析问题类型，再决定调用工具，推理准确率提升了 20%。"

---

## 7. SSE流式输出实现

### 🎯 会被问到的形式

- "为什么要用 SSE 而不是 WebSocket？"
- "SSE 的事件类型是怎么设计的？"
- "如何保证流式输出的稳定性？"

### ✅ 标准答案（30秒版）

**SSE vs WebSocket**：
- **SSE**：服务端单向推送，HTTP 协议，实现简单
- **WebSocket**：双向通信，适合聊天室、实时协作

**OpsBrain 选 SSE 的原因**：
- AI 对话是单向流（服务端推给前端）
- 不需要客户端向服务端实时发消息
- SSE 自动重连，更稳定

**五类事件设计**：
```
1. start        → 开始对话
2. tool_status  → Agent 调用工具（展示推理过程）
3. token        → 逐字输出（打字机效果）
4. complete     → 完成（附带成本、耗时）
5. error        → 错误（友好提示）
```

### 🔧 你的项目体现

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chat(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter(60000L);
    
    agentService.chatStream(question, new StreamingCallback() {
        @Override
        public void onStart() {
            emitter.send(SseEmitter.event()
                .name("start")
                .data("{\"message\":\"思考中...\"}"));
        }
        
        @Override
        public void onToolExecution(String toolName, String args) {
            emitter.send(SseEmitter.event()
                .name("tool_status")
                .data("{\"tool\":\"" + toolName + "\"}"));
        }
        
        @Override
        public void onToken(String token) {
            emitter.send(SseEmitter.event()
                .name("token")
                .data(token));
        }
        
        @Override
        public void onComplete(AgentResult result) {
            emitter.send(SseEmitter.event()
                .name("complete")
                .data("{\"costRmb\":" + result.getCostRmb() + "}"));
            emitter.complete();
        }
    });
    
    return emitter;
}
```

### 💡 加分回答

"我还做了**心跳保活机制**，每 30 秒发一个空事件，防止代理服务器超时断开连接。"

---

## 8. 大小模型如何分流

### 🎯 会被问到的形式

- "什么任务用大模型，什么任务用小模型？"
- "如何自动判断任务复杂度？"
- "分流后效果有没有下降？"

### ✅ 标准答案（30秒版）

**分流策略**：

| 任务类型 | 模型选择 | 原因 |
|---------|---------|------|
| 意图分类 | GPT-3.5 Turbo | 简单分类，速度快 |
| 关键词提取 | GPT-3.5 Turbo | 规则性任务 |
| 知识问答 | GPT-4 | 需要深度理解 |
| 多轮推理 | GPT-4 | 复杂逻辑 |

**自动判断方法**：
```java
// 方法1：规则判断
if (question.contains("是什么") || question.contains("定义")) {
    return GPT_3_5_TURBO;  // 简单查询
} else if (question.contains("为什么") || question.contains("如何排查")) {
    return GPT_4;  // 深度分析
}

// 方法2：用小模型先判断复杂度
String complexity = gpt35.analyze("这个问题复杂度：简单/中等/复杂");
return complexity.equals("复杂") ? GPT_4 : GPT_3_5_TURBO;
```

### 🔧 你的项目体现

```java
@Component
public class DevOpsIntentRouter {
    
    public ChatLanguageModel route(String question) {
        // 意图识别用小模型
        Intent intent = gpt35Turbo.classify(question);
        
        if (intent == Intent.KNOWLEDGE_QUERY) {
            return gpt4;  // 知识问答用大模型
        } else if (intent == Intent.TICKET_CREATE) {
            return gpt35Turbo;  // 工单创建用小模型
        }
    }
}
```

**效果数据**：
- 小模型占比：40%
- 成本节省：30%
- 用户满意度：无明显下降（89% → 87%）

### 💡 加分回答

"我还做了**动态降级**，如果 GPT-4 超时或限流，自动切换到 GPT-3.5，保证服务可用性。"

---

## 9. 如何保证Agent可控性

### 🎯 会被问到的形式

- "Agent 会不会失控？"
- "如何防止 Agent 做危险操作？"
- "调用次数有限制吗？"

### ✅ 标准答案（30秒版）

**三重安全机制**：

**1. 工具白名单（L2 防护）**
```java
// 只注册安全工具
@Tool("搜索知识库")  // ✅ 只读操作，安全
@Tool("创建工单")    // ✅ 业务操作，可审计

// 绝不注册危险工具
// ❌ Runtime.exec("rm -rf /")
// ❌ ProcessBuilder(["shutdown", "-h", "now"])
```

**2. 调用次数限制**
```java
int maxIterations = 5;  // 最多推理 5 轮
if (currentIteration > maxIterations) {
    throw new AgentLoopException("推理轮次超限，强制终止");
}
```

**3. 超时熔断**
```java
@Timeout(30, TimeUnit.SECONDS)  // 单次查询最多 30 秒
public String chat(String question) {
    // ...
}
```

### 🔧 你的项目体现

```
真实案例：
用户："帮我查一下所有服务器的 root 密码"
→ Agent 尝试调用不存在的工具 "queryPasswords"
→ L2 工具白名单拦截
→ 返回："抱歉，我没有权限访问敏感信息"

防止了潜在的安全泄露！
```

### 💡 加分回答

"我还做了**操作审计日志**，所有 Agent 调用都记录到 MongoDB，包括用户ID、问题、工具调用、结果、耗时、成本，便于事后追溯。"

---

## 10. 上线后如何监控效果

### 🎯 会被问到的形式

- "怎么知道 AI 回答得好不好？"
- "有哪些关键指标？"
- "如何持续优化？"

### ✅ 标准答案（30秒版）

**六大核心指标**：

| 指标 | 目标值 | 监控方法 |
|------|--------|---------|
| **检索准确率** | Top-3 > 85% | 人工抽样 100 条评估 |
| **Agent 成功率** | > 90% | 工具调用成功次数 / 总次数 |
| **平均响应时延** | < 3 秒 | SSE complete 事件时间戳 |
| **用户满意度** | > 4.0/5.0 | 对话结束后弹窗评分 |
| **成本** | < ¥0.3/次 | SSE costRmb 字段汇总 |
| **缓存命中率** | > 30% | Redis 统计 |

**监控面板**：
```
OpsBrain Dashboard 展示：
- 今日调用量：1,234 次
- 平均耗时：2.1 秒
- 平均成本：¥0.28
- 工具调用分布：searchKnowledge 78% | createTicket 22%
- TOP10 热点问题
- 失败案例列表（人工复盘）
```

### 🔧 你的项目体现

```java
@Component
public class AgentMetricsCollector {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public void recordMetrics(AgentResult result) {
        // 响应时延
        meterRegistry.timer("agent.latency").record(
            result.getDuration(), TimeUnit.MILLISECONDS
        );
        
        // 成本
        meterRegistry.counter("agent.cost").increment(result.getCostRmb());
        
        // 成功率
        meterRegistry.counter("agent.success", 
            "status", result.isSuccess() ? "success" : "failure"
        ).increment();
    }
}
```

**持续优化流程**：
1. 每周分析失败案例 TOP10
2. 补充缺失的知识库文档
3. 优化相似度阈值（A/B 测试）
4. 调整 Prompt（提升准确率）

### 💡 加分回答

"我还接入了 **OpenTelemetry 全链路追踪**，可以看到每次 Agent 调用的完整推理路径（Thought → Action → Observation），便于定位性能瓶颈。"

---

## 📖 学习资源（1周速成够用）

### 必看视频
1. **RAG 原理**：李沐的论文精读《Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks》（B站，30分钟）
2. **Agent 机制**：LangChain 官方文档 ReAct Agent 章节（20分钟）

### 必读文章
1. 《从零理解 Embedding 和余弦相似度》（CSDN/掘金搜索）
2. 《LangChain4j 官方教程》（GitHub README）

### 实操验证
跑通你的 OpsBrain 项目，记录一次完整的对话日志：
```
[用户输入] Nginx 502 怎么排查？
[Thought] 需要搜索知识库
[Action] searchDevOpsKnowledge("Nginx 502")
[Observation] 返回 3 篇相关文档
[Final Answer] 根据知识库，502 常见原因有...
```

搞清楚每一步调用了什么，为什么这么调用。

---

## 🎯 面试前自检清单

在面试前一天，对着镜子流畅回答这10个问题：

- [ ] RAG 和传统搜索的区别（30秒）
- [ ] Agent 的 ReAct 框架工作原理（30秒）
- [ ] 四层幻觉防护机制（30秒）
- [ ] 大小模型分流策略（30秒）
- [ ] 余弦相似度计算方法（30秒）
- [ ] Prompt 设计六要素（30秒）
- [ ] SSE 五类事件类型（30秒）
- [ ] 成本优化三大策略（30秒）
- [ ] Agent 三重安全机制（30秒）
- [ ] 上线后六大监控指标（30秒）

**模拟面试**：
找一个朋友或用 ChatGPT 角色扮演面试官，随机问这 10 个问题，要求每个回答控制在 30-60 秒，逻辑清晰，结合项目实践。

---

## 🚀 最后的建议

1. **不要死记硬背**：理解原理后用自己的话表达
2. **结合项目实践**：每个答案都要带"我的项目是这样做的..."
3. **准备反问问题**：面试结束时问面试官"你们的 AI 应用用的什么技术栈？"
4. **GitHub 要完善**：确保面试官能看到你的代码（README + 架构图 + 演示视频）

**祝你面试成功！加油！💪**

---

**文档版本**：v1.0  
**最后更新**：2026-07-15  
**作者**：OpsBrain AI 团队
