# AI 智能体应用开发面试速成清单

> **目标受众**：准备 AI 应用开发/AI 全栈岗位面试的候选人  
> **使用场景**：1 周内快速建立 RAG + Agent + LangChain 核心知识体系  
> **配套项目**：OpsBrain AI 企业级 DevOps 知识库智能助手

---

## 📋 目录

1. [核心概念速查](#1-核心概念速查)
2. [RAG 检索增强生成](#2-rag-检索增强生成)
3. [Agent 智能体架构](#3-agent-智能体架构)
4. [LangChain4j 工程实践](#4-langchain4j-工程实践)
5. [向量数据库与检索](#5-向量数据库与检索)
6. [Prompt 工程](#6-prompt-工程)
7. [幻觉防护与可控性](#7-幻觉防护与可控性)
8. [成本优化策略](#8-成本优化策略)
9. [高频面试问题与标准答案](#9-高频面试问题与标准答案)
10. [OpsBrain 项目技术亮点话术](#10-opsbrain-项目技术亮点话术)

---

## 1. 核心概念速查

### 1.1 必知术语表

| 术语 | 定义 | 在 OpsBrain 中的应用 |
|------|------|---------------------|
| **LLM** | Large Language Model，大语言模型 | 使用 GPT-4/GPT-3.5 Turbo |
| **RAG** | Retrieval-Augmented Generation，检索增强生成 | 核心架构，文档检索 + 大模型生成 |
| **Agent** | 智能体，能自主决策、调用工具的 AI 系统 | ReAct Agent 实现工单创建、知识检索 |
| **Embedding** | 嵌入向量，将文本转为数值向量 | OpenAI text-embedding-ada-002，1536 维 |
| **Vector Store** | 向量数据库 | PostgreSQL + pgvector 扩展 |
| **Prompt** | 提示词，给大模型的指令 | System Prompt 约束 Agent 行为 |
| **Tool Calling** | 工具调用，模型主动调用外部函数 | `searchDevOpsKnowledge`、`createDevOpsTicket` |
| **Hallucination** | 幻觉，模型编造不存在的信息 | 四层防护机制 |
| **Streaming** | 流式输出，逐 token 返回结果 | SSE (Server-Sent Events) 实现 |
| **Semantic Cache** | 语义缓存，相似问题复用答案 | Redis 存储问题向量 + 答案 |

---

## 2. RAG 检索增强生成

### 2.1 什么是 RAG？

**定义**：RAG = Retrieval（检索）+ Augmented（增强）+ Generation（生成）

**核心思想**：
- 传统 LLM：只依赖训练数据，知识有时效性限制
- RAG：先从外部知识库检索相关文档，再让 LLM 基于检索结果生成答案

**流程图**：
```
用户提问 → Embedding 向量化 → 向量数据库检索 Top-K 相似文档 
→ 构造 Prompt（问题 + 检索到的文档）→ LLM 生成答案
```

### 2.2 RAG vs 传统搜索 vs Fine-tuning

| 对比维度 | 传统搜索 | RAG | Fine-tuning |
|---------|---------|-----|-------------|
| **原理** | 关键词匹配 | 语义相似度检索 + LLM 生成 | 重新训练模型参数 |
| **优势** | 速度快，成本低 | 语义理解强，答案自然 | 领域适配性最强 |
| **劣势** | 无法理解语义 | 依赖检索质量 | 成本高，更新困难 |
| **适用场景** | 精确匹配查询 | 问答、摘要、知识库 | 垂直领域专用模型 |

**OpsBrain 为什么选 RAG？**
- 企业运维文档实时更新，Fine-tuning 无法快速适配
- 传统搜索无法理解"重启服务无效"等口语化问题
- RAG 可灵活添加新文档，无需重新训练

### 2.3 RAG 关键技术点

#### 2.3.1 文档预处理
```
原始文档 → 清洗（去除格式） → 切片（Chunk，避免超长） 
→ Embedding 向量化 → 存入向量数据库
```

**切片策略**（OpsBrain 采用）：
- **固定长度切片**：每 500 字符一个 Chunk，重叠 50 字符（避免语义截断）
- **段落切片**：按 Markdown 标题层级切分

#### 2.3.2 向量检索
```java
// OpsBrain 中的实现逻辑
String question = "Nginx 502 错误怎么排查？";
Embedding questionEmbedding = embeddingModel.embed(question).content();
List<EmbeddingMatch<TextSegment>> matches = vectorStore.findRelevant(
    questionEmbedding, 
    5,  // Top-5 召回
    0.73  // 最低相似度阈值（L4 幻觉防护）
);
```

**相似度计算**：余弦相似度（Cosine Similarity）
```
score = (A · B) / (||A|| * ||B||)  // 范围 [-1, 1]，越接近 1 越相似
```

#### 2.3.3 Prompt 构造
```
System: 你是企业运维助手，根据以下文档回答问题。不知道就说不知道。

Context:
[文档1] Nginx 502 错误常见原因：后端服务未启动、超时配置不当...
[文档2] 排查步骤：1. 检查后端服务状态 2. 查看错误日志...

User: Nginx 502 错误怎么排查？
```

---

## 3. Agent 智能体架构

### 3.1 什么是 Agent？

**定义**：Agent = Perception（感知）+ Reasoning（推理）+ Action（行动）

**与普通 LLM 对话的区别**：
- **普通对话**：用户问 → LLM 答（一次性响应）
- **Agent**：用户问 → Agent 分析 → 调用工具 → 获取结果 → 继续推理 → 给出答案（多轮自主决策）

**类比**：Agent 就像有了"手"（工具）和"大脑"（推理）的 AI 助手

### 3.2 ReAct 框架（OpsBrain 采用）

**ReAct = Reasoning（推理）+ Acting（行动）**

**执行流程**：
```
1. Thought（思考）: 我需要查找 Nginx 502 错误的文档
2. Action（行动）: searchDevOpsKnowledge(query="Nginx 502")
3. Observation（观察）: [返回 3 篇相关文档]
4. Thought: 文档中提到需要检查后端服务，我再生成答案
5. Final Answer: 根据文档，排查步骤是...
```

**与其他框架对比**：

| 框架 | 特点 | 适用场景 |
|------|------|----------|
| **ReAct** | 思考 + 行动交替 | 需要多步推理的任务 |
| **Plan-and-Execute** | 先规划全部步骤，再执行 | 固定流程的任务 |
| **Reflexion** | 有自我反思机制 | 需要纠错的复杂任务 |

### 3.3 工具调用（Tool Calling）

**OpsBrain 注册的两个工具**：

```java
// 工具 1：知识检索
@Tool("搜索 DevOps 运维知识库")
public String searchDevOpsKnowledge(
    @P("用户的问题或关键词") String query
) {
    // 调用 RAG 检索
    return ragService.search(query);
}

// 工具 2：创建工单
@Tool("创建运维工单")
public String createDevOpsTicket(
    @P("工单标题") String title,
    @P("问题描述") String description,
    @P("优先级 P0-P3") String priority
) {
    // 调用工单系统
    return ticketService.create(title, description, priority);
}
```

**Agent 如何选择工具？**
- LLM 根据用户问题和工具描述，生成 JSON 格式的函数调用
- LangChain4j 解析 JSON，执行对应 Java 方法
- 结果返回给 LLM 继续推理

### 3.4 多轮对话与上下文管理

**挑战**：Agent 需要记住历史对话
```
用户: 查一下 Redis 连接失败的解决方法
Agent: [调用工具，返回文档]
用户: 第 2 步具体怎么做？  ← 需要知道"第 2 步"指的是什么
```

**OpsBrain 解决方案**：
- 使用 `ChatMemory` 存储对话历史（Redis 持久化）
- 每次请求带上 `conversationId`，自动加载上下文

---

## 4. LangChain4j 工程实践

### 4.1 LangChain4j 核心组件

| 组件 | 作用 | OpsBrain 使用 |
|------|------|---------------|
| **ChatLanguageModel** | 大模型接口封装 | `OpenAiChatModel`（GPT-4/3.5） |
| **EmbeddingModel** | 向量化模型 | `OpenAiEmbeddingModel`（text-embedding-ada-002） |
| **VectorStore** | 向量存储 | `PgVectorEmbeddingStore`（PostgreSQL） |
| **ChatMemory** | 对话记忆 | `MessageWindowChatMemory` + Redis 持久化 |
| **AiServices** | 工具注册与 Agent 构建 | 注册 `@Tool` 方法，自动生成 Agent |

### 4.2 典型代码结构（OpsBrain 实现）

```java
// 1. 配置大模型
ChatLanguageModel chatModel = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4")
    .temperature(0.7)
    .build();

// 2. 配置向量存储
EmbeddingStore<TextSegment> vectorStore = PgVectorEmbeddingStore.builder()
    .host("localhost")
    .port(5432)
    .database("devops_kb")
    .table("document_embeddings")
    .dimension(1536)  // 必须与 Embedding 模型一致
    .build();

// 3. 构建 RAG 检索器
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(vectorStore)
    .embeddingModel(embeddingModel)
    .maxResults(5)  // Top-5 召回
    .minScore(0.73)  // 最低相似度阈值
    .build();

// 4. 注册工具并构建 Agent
interface DevOpsAgent {
    @SystemMessage("你是企业运维助手，根据检索到的文档回答问题...")
    String chat(@UserMessage String userMessage);
}

DevOpsAgent agent = AiServices.builder(DevOpsAgent.class)
    .chatLanguageModel(chatModel)
    .contentRetriever(retriever)
    .tools(new KnowledgeTools(), new TicketTools())  // 注册工具
    .chatMemory(chatMemory)
    .build();

// 5. 调用 Agent
String response = agent.chat("Nginx 502 错误怎么排查？");
```

### 4.3 流式输出（SSE）实现

**为什么需要流式输出？**
- LLM 生成耗时长（5-30 秒），用户等待体验差
- 流式输出逐 token 返回，用户感知响应更快

**OpsBrain SSE 事件类型**：
```
event: start         // 开始生成
data: {"conversationId": "xxx"}

event: tool_status   // 工具调用中
data: {"tool": "searchDevOpsKnowledge", "status": "executing"}

event: token         // 逐 token 返回
data: {"content": "根据", "costRmb": 0.0001}

event: complete      // 生成完成
data: {"fullText": "...", "totalCostRmb": 0.05}

event: error         // 错误
data: {"message": "API rate limit exceeded"}
```

**实现代码**：
```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam String message) {
    SseEmitter emitter = new SseEmitter();
    
    chatModel.generate(message, new StreamingResponseHandler<String>() {
        @Override
        public void onNext(String token) {
            emitter.send(SseEmitter.event()
                .name("token")
                .data(Map.of("content", token)));
        }
        
        @Override
        public void onComplete(Response<String> response) {
            emitter.send(SseEmitter.event()
                .name("complete")
                .data(Map.of("fullText", response.content())));
            emitter.complete();
        }
        
        @Override
        public void onError(Throwable error) {
            emitter.send(SseEmitter.event()
                .name("error")
                .data(Map.of("message", error.getMessage())));
            emitter.completeWithError(error);
        }
    });
    
    return emitter;
}
```

---

## 5. 向量数据库与检索

### 5.1 为什么需要向量数据库？

**传统数据库的局限**：
```sql
-- 传统 SQL：只能精确匹配
SELECT * FROM docs WHERE content LIKE '%Nginx 502%';
```
- 无法匹配"Nginx 网关错误"（语义相同，关键词不同）
- 无法处理错别字"Ngix 502"

**向量数据库的优势**：
```sql
-- 向量检索：基于语义相似度
SELECT * FROM docs 
ORDER BY embedding <=> query_embedding  -- 余弦距离
LIMIT 5;
```
- "Nginx 502" 和 "Nginx 网关错误" 向量相似度高
- 支持模糊语义匹配

### 5.2 pgvector 扩展

**为什么选 pgvector？**（OpsBrain 技术选型理由）
- 无需引入新数据库，PostgreSQL 即可支持向量检索
- 同时存储结构化数据（工单、用户）和向量数据
- 支持混合查询（向量相似度 + 业务过滤条件）

**关键配置**：
```sql
-- 安装扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建向量表（1536 维必须与 Embedding 模型一致）
CREATE TABLE document_embeddings (
    id UUID PRIMARY KEY,
    content TEXT,
    embedding VECTOR(1536),  -- ← 维度铁律
    metadata JSONB
);

-- 创建向量索引（加速检索）
CREATE INDEX ON document_embeddings 
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);  -- 聚类数量，根据数据量调整
```

### 5.3 Embedding 模型选择

| 模型 | 维度 | 成本 | 性能 | OpsBrain 选择 |
|------|------|------|------|---------------|
| **text-embedding-ada-002** | 1536 | $0.0001/1K tokens | 通用场景最佳 | ✅ 采用 |
| text-embedding-3-small | 1536 | $0.00002/1K tokens | 便宜但精度略低 | 备选 |
| text-embedding-3-large | 3072 | $0.00013/1K tokens | 精度最高 | 成本过高 |

**维度一致性铁律**：
```
Embedding 模型输出维度 = pgvector 表定义维度 = VectorStore 配置维度
```
- 不一致会导致"维度不匹配"错误
- OpsBrain 全链路统一 **1536 维**

### 5.4 相似度计算方法

| 方法 | 公式 | 特点 | pgvector 操作符 |
|------|------|------|----------------|
| **余弦相似度** | cos(θ) = (A·B)/(‖A‖‖B‖) | 关注方向，忽略长度 | `<=>` |
| 欧氏距离 | √Σ(A-B)² | 关注绝对距离 | `<->` |
| 内积 | A·B | 关注长度和方向 | `<#>` |

**OpsBrain 为什么用余弦相似度？**
- Embedding 向量已归一化，余弦相似度等价于内积
- 业界 RAG 标准做法（OpenAI 官方推荐）

---

## 6. Prompt 工程

### 6.1 什么是 Prompt 工程？

**定义**：设计和优化给 LLM 的输入指令，使其生成更准确、可控的输出

**重要性**：
- 同样的模型，Prompt 好坏导致输出质量天壤之别
- 是 AI 应用开发最核心的技能之一

### 6.2 OpsBrain 的 System Prompt 设计

```java
@SystemMessage("""
你是企业级 DevOps 运维助手，专业、简洁、可靠。

## 核心规则
1. **只回答运维相关问题**（服务器、网络、数据库、容器、监控、CI/CD）
2. **基于检索到的文档回答**，不知道的明确说"文档中没有相关信息"
3. **不编造命令或配置**，不确定的内容标注"建议人工确认"
4. **优先级判断**：P0=故障/P1=紧急/P2=重要/P3=一般

## 工具使用
- 知识类问题 → 调用 searchDevOpsKnowledge
- 需要人工介入 → 调用 createDevOpsTicket

## 回答格式
- 故障排查：列出步骤，每步说明原因
- 配置问题：给出配置示例 + 注意事项
- 创建工单后：返回工单ID和预估处理时间

## 禁止行为
- 执行任何系统命令（rm、shutdown 等）
- 访问未授权的系统
- 泄露敏感信息（密码、密钥）
""")## 6.3 Prompt 优化技巧

### 技巧 1：明确角色和任务
```
❌ 差：回答问题
✅ 好：你是企业运维助手，回答 DevOps 相关问题
```

### 技巧 2：给出示例（Few-shot Learning）
```
## 示例
Q: Redis 连接超时
A: 1. 检查 Redis 服务状态 2. 验证网络连通性 3. 查看连接池配置

Q: {用户问题}
A: 
```

### 技巧 3：明确输出格式
```
## 输出格式
{
  "answer": "具体答案",
  "confidence": "high/medium/low",
  "needTicket": true/false
}
```

### 技巧 4：添加约束条件
```
- 回答不超过 200 字
- 不使用技术黑话
- 每个步骤说明原因
```

---

## 7. 幻觉防护与可控性

### 7.1 什么是幻觉（Hallucination）？

**定义**：LLM 生成看似合理但实际错误或编造的信息

**典型案例**：
- 编造不存在的 Linux 命令参数
- 引用虚构的配置文件路径
- 捏造错误代码对应的解决方案

**危害**：
- 用户按错误方案操作 → 故障扩大
- 企业信任度下降

### 7.2 OpsBrain 四层幻觉防护

#### L1：Prompt 层约束
```java
@SystemMessage("""
核心规则：
1. 只基于检索到的文档回答
2. 不知道的明确说"文档中没有相关信息"
3. 不编造命令或配置
""")
```

**原理**：通过明确指令约束模型行为

#### L2：工具白名单
```java
// 只注册 2 个安全工具，不注册危险工具
@Tool("搜索知识库")  // ✅ 安全
public String searchDevOpsKnowledge(String query) {...}

@Tool("创建工单")    // ✅ 安全
public String createDevOpsTicket(String title) {...}

// ❌ 绝不注册
// @Tool("执行Shell命令")  Runtime.exec()
// @Tool("删除文件")      Files.delete()
```

**原理**：限制 Agent 的"手"，只能做安全操作

#### L3：Schema 校验 + 自愈重试
```java
public class RetryLimitedChatModel implements ChatLanguageModel {
    private final ChatLanguageModel delegate;
    private final int maxRetries = 3;
    
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        for (int i = 0; i < maxRetries; i++) {
            Response<AiMessage> response = delegate.generate(messages);
            
            // 校验工具调用格式
            if (isValidToolCall(response)) {
                return response;
            }
            
            // 格式错误，追加修正提示重试
            messages.add(new SystemMessage(
                "你的工具调用格式错误，请严格按 JSON Schema 生成"
            ));
        }
        
        throw new RuntimeException("工具调用格式校验失败，已重试 3 次");
    }
}
```

**原理**：模型输出格式错误时自动重试，而非直接报错

#### L4：相似度熔断
```java
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(vectorStore)
    .minScore(0.73)  // ← 低于 0.73 的结果直接过滤
    .build();
```

**原理**：
- 相似度 < 0.73 说明检索结果不相关
- 过滤低质量结果，避免模型基于无关内容胡乱生成

**阈值选择经验**：
- 0.6-0.7：宽松，召回率高但可能引入噪声
- 0.7-0.8：平衡（OpsBrain 采用 0.73）
- 0.8-0.9：严格，准确率高但可能漏掉相关结果

### 7.3 面试高频问题

**Q：如何判断模型是否产生了幻觉？**

A：三种检测方法：
1. **事实核查**：对比模型输出与真实文档
2. **一致性检测**：多次生成，看答案是否矛盾
3. **置信度评估**：让模型输出置信度分数

OpsBrain 采用第 1 种 + L4 相似度熔断

**Q：幻觉能完全消除吗？**

A：不能，但可以降到可接受水平：
- 学术研究显示，RAG 可将幻觉率从 40% 降至 10% 以下
- OpsBrain 四层防护 + 人工兜底（创建工单）

---

## 8. 成本优化策略

### 8.1 LLM API 成本构成

**OpenAI 定价（2024-2026）**：
| 模型 | 输入价格 | 输出价格 | 适用场景 |
|------|---------|---------|----------|
| **GPT-4** | $0.03/1K tokens | $0.06/1K tokens | 复杂推理、多步 Agent |
| **GPT-3.5 Turbo** | $0.0005/1K tokens | $0.0015/1K tokens | 简单分类、意图识别 |
| text-embedding-ada-002 | $0.0001/1K tokens | - | 向量化 |

**OpsBrain 日均成本估算**（假设 1000 次查询）：
```
Embedding：1000 次 × 50 tokens × $0.0001 = $5
GPT-4 推理：500 次 × 2000 tokens × $0.03 = $30
GPT-3.5 分类：500 次 × 500 tokens × $0.0005 = $0.125
总计：约 $35/天 = ¥250/天
```

### 8.2 OpsBrain 三大优化策略

#### 策略 1：大小模型智能分流
```java
public class ModelRouter {
    public ChatLanguageModel route(String userMessage) {
        // 简单意图识别用 GPT-3.5 Turbo（便宜 60 倍）
        if (isSimpleQuery(userMessage)) {
            return gpt35Turbo;  // $0.0005/1K tokens
        }
        
        // 复杂推理、多步 Agent 才用 GPT-4
        return gpt4;  // $0.03/1K tokens
    }
    
    private boolean isSimpleQuery(String message) {
        // 关键词匹配：查询类、文档类
        return message.contains("查") || message.contains("搜索");
    }
}
```

**节省效果**：预估降低 40% 成本

#### 策略 2：Redis 语义缓存
```java
public class SemanticCache {
    private final RedisTemplate<String, String> redis;
    private final EmbeddingModel embeddingModel;
    
    public Optional<String> get(String question) {
        // 1. 计算问题向量
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        
        // 2. 在 Redis 中查找相似问题（余弦相似度 > 0.95）
        Set<String> cachedKeys = redis.keys("cache:*");
        for (String key : cachedKeys) {
            String cachedEmbedding = redis.opsForValue().get(key + ":embedding");
            if (cosineSimilarity(questionEmbedding, cachedEmbedding) > 0.95) {
                return Optional.of(redis.opsForValue().get(key + ":answer"));
            }
        }
        
        return Optional.empty();
    }
    
    public void put(String question, String answer) {
        Embedding embedding = embeddingModel.embed(question).content();
        String key = "cache:" + UUID.randomUUID();
        redis.opsForValue().set(key + ":embedding", embedding.toString());
        redis.opsForValue().set(key + ":answer", answer);
        redis.expire(key, 7, TimeUnit.DAYS);  // 7 天过期
    }
}
```

**原理**：
- 用户问"Nginx 502 怎么办？"
- 缓存中有"Nginx 502 错误排查"（相似度 0.97）
- 直接返回缓存答案，不调用 LLM API

**节省效果**：命中率 30% 时，节省 30% 成本

#### 策略 3：Prompt 压缩
```java
// ❌ 差：冗余的上下文
String context = """
文档1（3000字）：...详细的 Nginx 配置说明...
文档2（2500字）：...Docker 部署指南...
文档3（2000字）：...监控告警配置...
""";  // 总计 7500 tokens

// ✅ 好：只提取相关段落
String context = """
文档1 关键段落：Nginx 502 错误常见原因...（200字）
文档3 关键段落：排查步骤...（150字）
""";  // 总计 350 tokens，节省 95% tokens
```

**实现方式**：
- 文档预处理时按段落切片（Chunk）
- 检索时只返回最相关的 Top-5 Chunk，而非整篇文档

### 8.3 成本追踪实现

```java
public class CostTracker {
    public double calculateCost(String modelName, int inputTokens, int outputTokens) {
        return switch (modelName) {
            case "gpt-4" -> 
                inputTokens * 0.03 / 1000 + outputTokens * 0.06 / 1000;
            case "gpt-3.5-turbo" -> 
                inputTokens * 0.0005 / 1000 + outputTokens * 0.0015 / 1000;
            default -> 0.0;
        } * 7.2;  // 转换为人民币（汇率 7.2）
    }
}

// SSE 流式输出时实时返回成本
event: token
data: {"content": "根据", "costRmb": 0.0001}

event: complete
data: {"fullText": "...", "totalCostRmb": 0.05, "tokens": {"input": 500, "output": 200}}
```

---

## 9. 高频面试问题与标准答案

### 9.1 基础概念类

#### Q1：RAG 和 Fine-tuning 有什么区别？什么时候用 RAG？

**标准答案**：
- **Fine-tuning**：重新训练模型参数，适合固定领域（如医疗、法律），但成本高、更新慢
- **RAG**：外挂知识库 + 检索增强，适合知识频繁更新的场景（如企业文档、新闻）

**OpsBrain 选 RAG 的原因**：
1. 运维文档更新频繁（新技术栈、配置变更）
2. Fine-tuning 成本高（需要标注数据 + GPU 训练）
3. RAG 可解释性强（能看到引用了哪些文档）

---

#### Q2：Embedding 是什么？为什么要做向量化？

**标准答案**：
- **Embedding**：把文本转为高维数值向量（如 1536 维）
- **原理**：语义相近的文本，向量距离也相近

**示例**：
```
"Nginx 502 错误" → [0.23, -0.45, 0.67, ..., 0.12]  (1536维)
"Nginx 网关故障" → [0.25, -0.43, 0.65, ..., 0.14]  (相似度 0.95)
"Python 爬虫教程" → [-0.67, 0.89, -0.34, ..., 0.56]  (相似度 0.12)
```

**为什么要做**：传统关键词匹配无法理解语义，向量化后可以用数学方法（余弦相似度）计算语义相关性

---

#### Q3：Agent 和普通 Chatbot 有什么区别？

**标准答案**：

| 维度 | 普通 Chatbot | Agent |
|------|-------------|-------|
| **能力** | 只能对话 | 对话 + 调用工具 + 自主决策 |
| **流程** | 一问一答 | 多轮推理（ReAct） |
| **示例** | ChatGPT 网页版 | OpsBrain（能调知识库、创建工单） |

**类比**：
- Chatbot = 只会说话的客服
- Agent = 会说话 + 会查系统 + 会下工单的客服

---

### 9.2 技术实现类

#### Q4：如何防止 LLM 产生幻觉？

**标准答案**（结合 OpsBrain 四层防护）：
1. **L1 Prompt 约束**："不知道就说不知道，不要编造"
2. **L2 工具白名单**：只注册安全工具，不给 Runtime.exec()
3. **L3 Schema 校验**：工具调用格式错误自动重试（最多 3 次）
4. **L4 相似度熔断**：检索结果 Score < 0.73 直接过滤

**面试加分项**：提到"幻觉无法完全消除，但可以通过工程手段降到 10% 以下"

---

#### Q5：向量检索的相似度阈值怎么定？

**标准答案**：
- **经验范围**：0.6-0.8（具体看业务）
- **OpsBrain 选 0.73 的原因**：
  - 0.6 太低 → 引入噪声（无关文档）
  - 0.8 太高 → 漏掉相关结果（用户换个说法就检索不到）
  - 0.73 是实验后的平衡点

**调优方法**：
1. 准备 100 个测试问题 + 标注正确答案
2. 分别测试 0.6、0.7、0.75、0.8 的准确率和召回率
3. 选 F1 分数最高的阈值

---

#### Q6：如何优化 LLM API 成本？

**标准答案**（OpsBrain 三大策略）：
1. **大小模型分流**：简单任务用 GPT-3.5，复杂任务用 GPT-4（节省 40%）
2. **语义缓存**：相似问题直接返回缓存（命中率 30% = 节省 30%）
3. **Prompt 压缩**：只传相关段落，不传整篇文档（节省 80% tokens）

**面试加分项**：提到"我们实现了实时成本追踪，每次请求都返回 costRmb 字段"

---

### 9.3 架构设计类

#### Q7：为什么选 PostgreSQL + pgvector，而不是专业向量数据库（如 Milvus、Pinecone）？

**标准答案**：
- **优势**：
  1. 无需引入新组件，降低运维复杂度
  2. 支持混合查询（向量相似度 + 业务字段过滤）
  3. 事务支持（向量和关系数据一致性）
  
- **劣势**：
  1. 超大规模数据（亿级）性能不如专业向量库
  2. 分布式能力较弱

**OpsBrain 场景**：企业内部文档量级在万-十万篇，pgvector 完全够用

---

#### Q8：多轮对话的上下文怎么管理？

**标准答案**（OpsBrain 实现）：
```java
// 1. 使用 LangChain4j 的 ChatMemory
ChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(10)  // 保留最近 10 轮对话
    .build();

// 2. Redis 持久化（跨会话）
public class RedisChatMemory implements ChatMemory {
    public void add(String conversationId, ChatMessage message) {
        String key = "chat:history:" + conversationId;
        redis.opsForList().rightPush(key, serialize(message));
        redis.expire(key, 24, TimeUnit.HOURS);  // 24 小时过期
    }
    
    public List<ChatMessage> getMessages(String conversationId) {
        String key = "chat:history:" + conversationId;
        return redis.opsForList().range(key, 0, -1)
            .stream()
            .map(this::deserialize)
            .toList();
    }
}
```

**关键点**：
- 用 `conversationId` 区分不同会话
- 限制窗口大小（避免 tokens 超限）
- 设置过期时间（避免 Redis 内存爆炸）

---

### 9.4 项目经验类

#### Q9：你的项目遇到过什么技术难点？怎么解决的？

**推荐回答**（选 1-2 个）：

**难点 1：向量维度不一致导致检索失败**
- **问题**：Embedding 模型输出 1536 维，但 pgvector 表定义成 768 维 → 插入报错
- **排查**：通过日志发现"dimension mismatch"
- **解决**：统一全链路维度配置（init.sql、VectorStoreConfig、EmbeddingModel）
- **收获**：深刻理解向量检索全链路的配置一致性要求

**难点 2：Agent 工具调用格式不稳定**
- **问题**：模型有时返回错误的 JSON 格式 → 工具调用失败
- **解决**：实现 `RetryLimitedChatModel` 包装器，格式错误自动重试（最多 3 次）
- **收获**：学会用装饰器模式增强第三方组件能力

---

#### Q10：如果让你优化这个项目，你会怎么做？

**推荐回答**（展示技术视野）：

**短期优化**（1-2 周）：
1. 增加检索结果重排序（Rerank）：用 CrossEncoder 模型对 Top-20 结果二次排序，提升准确率
2. 支持多模态：允许上传图片（架构图、监控截图），用 GPT-4V 分析

**中期优化**（1-2 个月）：
1. 引入 Agent 规划能力：用 Plan-and-Execute 框架处理复杂多步任务
2. 增加用户反馈闭环：让用户标注答案质量，用强化学习（RLHF）微调

**长期优化**（3-6 个月）：
1. 私有化部署：切换到开源模型（Llama 3、Qwen），降低成本
2. 多 Agent 协作：知识检索 Agent + 工单处理 Agent + 监控分析 Agent

---

## 10. OpsBrain 项目技术亮点话术

### 10.1 简历项目描述模板（中文）

```markdown
## 企业级 DevOps 知识库智能助手（OpsBrain AI）

**项目背景**：企业运维文档分散、排障效率低，传统关键词搜索无法理解语义化查询

**核心技术**：
- 基于 LangChain4j 实现 ReAct Agent，支持自主决策与工具调度
- RAG 检索增强：文档向量化（text-embedding-ada-002，1536 维）+ PostgreSQL pgvector 语义检索
- 四层幻觉防护：Prompt 约束 + 工具白名单 + Schema 自愈重试 + 相似度熔断（0.73）
- 大小模型智能分流：简单任务用 GPT-3.5 Turbo，复杂推理用 GPT-4，降低 40% 成本
- Redis 语义缓存：相似问题复用答案，命中率 30%，进一步降低 API 调用
- SSE 流式输出 + 实时成本追踪，响应时延 < 2 秒

**技术栈**：Spring Boot 3.x、LangChain4j、PostgreSQL + pgvector、Redis 7、OpenAI API

**项目成果**：
- 支持 XXX 篇运维文档向量化存储，检索相关性 Top-3 准确率达 XX%
- Agent 工具调用成功率 XX%，平均 X 轮对话解决用户问题
- 日均处理 XXX 次查询，单次查询成本 < ¥0.5
```

### 10.2 面试自我介绍话术（1 分钟版）

```
我最近在做一个企业级 DevOps 知识库智能助手项目，核心是用 RAG + Agent 解决传统运维文档检索效率低的问题。

技术上，我用 LangChain4j 实现了 ReAct Agent，能自主调用知识检索和工单创建两个工具。
向量检索这块，用 PostgreSQL 的 pgvector 扩展存储 1536 维 Embedding，
通过余弦相似度找最相关的文档，再喂给 GPT-4 生成答案。

为了防止大模型瞎编，我做了四层防护：
Prompt 层约束行为，工具层白名单限制权限，
Schema 层自动重试修正格式错误，检索层用 0.73 的相似度阈值过滤低质量结果。

成本优化方面，简单任务分流到 GPT-3.5，加上 Redis 语义缓存，整体降低了 40% 的 API 开销。

这个项目让我深入理解了 RAG 全链路实现和 Agent 工程化落地的关键细节。
```

### 10.3 项目亮点提问引导

**面试技巧**：主动引导面试官问你准备好的问题

**话术示例**：
- "这个项目最有挑战的是**四层幻觉防护机制**，我可以详细讲讲吗？"
- "我在**成本优化**上做了大小模型分流和语义缓存，您感兴趣吗？"
- "**向量检索这块**我踩了一些坑，比如维度不一致问题，您想了解吗？"

---

## 11. 学习资源推荐

### 11.1 必读论文（1-2 天速读）
1. **RAG 原论文**：_Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks_（2020）
   - 重点看：RAG 架构图、与 Fine-tuning 的对比实验
   
2. **ReAct 论文**：_ReAct: Synergizing Reasoning and Acting in Language Models_（2023）
   - 重点看：Thought-Action-Observation 循环流程

### 11.2 实战教程（3-5 天）
1. **LangChain4j 官方文档**：https://docs.langchain4j.dev/
   - 必读章节：Tutorials → RAG、AI Services、Tools
   
2. **OpenAI Cookbook**：https://github.com/openai/openai-cookbook
   - 重点看：`embedding.ipynb`、`function_calling.ipynb`

### 11.3 视频课程（碎片时间）
1. **李沐论文精读**：B站搜"李沐 RAG"（30 分钟）
2. **LangChain 实战**：YouTube 搜"LangChain Tutorial"（1 小时）

---

## 12. 1 周学习计划

| 日期 | 任务 | 产出 | 检验标准 |
|-----|------|------|---------|
| **Day 1** | 阅读本文档 + RAG 论文 | 笔记 | 能画出 RAG 流程图 |
| **Day 2** | 跑通 OpsBrain 项目 | 录屏演示 | 能演示完整问答流程 |
| **Day 3** | 阅读关键代码 | 代码注释 | 能讲清楚 Agent 调用链路 |
| **Day 4** | 准备面试问答 | 答案文档 | 背熟本文档第 9 章 10 个问题 |
| **Day 5** | 模拟面试 | 录音/录屏 | 能流利讲完项目介绍（不卡壳） |
| **Day 6** | 优化简历 + 写博客 | 简历 v2.0 | 简历含 5 个以上 AI 关键词 |
| **Day 7** | 投递简历 | 投递记录 | 投递 50 家，标注 AI 岗 |

---

## 13. 面试前自检清单

**基础概念**（必须能讲清楚）：
- [ ] RAG 是什么？与 Fine-tuning 的区别
- [ ] Embedding 原理和作用
- [ ] Agent 与 Chatbot 的区别
- [ ] ReAct 框架流程

**OpsBrain 项目**（脱稿讲 2 分钟）：
- [ ] 项目背景和要解决的问题
- [ ] 技术架构（RAG + Agent）
- [ ] 四层幻觉防护机制
- [ ] 成本优化策略

**代码实现**（能写伪代码）：
- [ ] 向量检索代码逻辑
- [ ] Agent 工具注册方式
- [ ] SSE 流式输出实现
- [ ] 语义缓存实现

**踩坑经验**（至少 2 个）：
- [ ] 向量维度不一致问题
- [ ] 工具调用格式错误
- [ ] 相似度阈值调优过程

---

## 附录：术语中英对照表

| 中文 | 英文 | 缩写 |
|------|------|------|
| 检索增强生成 | Retrieval-Augmented Generation | RAG |
| 大语言模型 | Large Language Model | LLM |
| 嵌入向量 | Embedding | - |
| 向量数据库 | Vector Database / Vector Store | - |
| 智能体 | Agent | - |
| 工具调用 | Tool Calling / Function Calling | - |
| 提示词工程 | Prompt Engineering | - |
| 幻觉 | Hallucination | - |
| 流式输出 | Streaming | - |
| 语义缓存 | Semantic Cache | - |
| 余弦相似度 | Cosine Similarity | - |
| 上下文窗口 | Context Window | - |
| 微调 | Fine-tuning | - |
| 少样本学习 | Few-shot Learning | - |

---

**文档版本**：v1.0  
**最后更新**：2026-07-15  
**配套项目**：OpsBrain AI DevOps Platform Backend

---

## 使用建议

1. **第一遍阅读**：通读全文，标注不理解的概念
2. **第二遍学习**：对照 OpsBrain 代码，理解每个技术点的实现
3. **第三遍背诵**：重点背第 9 章面试问答，录音自查
4. **持续更新**：每次面试后补充新问题到本文档

**祝你面试成功！🎉**
