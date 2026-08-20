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
""")