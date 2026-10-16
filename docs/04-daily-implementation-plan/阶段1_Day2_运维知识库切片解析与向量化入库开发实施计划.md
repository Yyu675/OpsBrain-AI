# 📅 阶段1_Day2_运维知识库切片解析与向量化入库开发实施计划

> **阶段所属**：阶段一：基础环境与数据准备  
> **当日核心目标**：实现对《阿里云运维排查指南》《K8s常见故障处理手册》等 Markdown 文档的自动解析、结构化父子切片（Parent-Child Chunking），并调用本地嵌入模型完成切片向量化入库至 `PgVector` 数据库。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：能够通过 Java 命令行/单测，输入一句关键词（如 `"Pod 为什么 crashloop"`），从 PostgreSQL 数据库中准确检索出余弦相似度最高的前 3 段核心中文运维手册文本。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:00：准备开源运维文档与 Markdown-Aware 切片解析逻辑
1. **准备原始数据源**：在工程根目录下新建文件夹 `src/main/resources/knowledge/`，放入 3-5 份整理好的 Markdown 文档（如 `k8s-pod-troubleshooting.md`, `aliyun-slb-guide.md`）。
2. **实现父子级切片解析器 (`ParentChildDocumentSplitter.java`)**：
   为了防范传统字数截断导致代码段破裂（见审查报告风险 1.1），我们将文档先按二级标题 `## ` 切分为**完整父段落 (~800 Token)**，再将父段落内部切为**精确小关键词段落 (~200 Token)**：
   ```java
   package com.devops.agent.service.rag;

   import dev.langchain4j.data.document.Document;
   import dev.langchain4j.data.segment.TextSegment;
   import org.springframework.stereotype.Component;
   import java.util.*;

   @Component
   public class ParentChildDocumentSplitter {

       public List<TextSegment> splitWithParentChild(Document document) {
           List<TextSegment> childSegments = new ArrayList<>();
           String fullText = document.text();
           String docTitle = document.metadata().getString("doc_title");

           // 1. 按二级或者三级 Markdown 标题分割出完整父章节
           String[] parentSections = fullText.split("(?=\\n##\\s)");
           
           for (int i = 0; i < parentSections.length; i++) {
               String parentText = parentSections[i].trim();
               if (parentText.isEmpty()) continue;
               
               String parentId = docTitle + "-P-" + i;
               
               // 2. 将整块父段落以 200 字窗口切分为子段落，每一个子段落的 metadata 强绑定 parent_id 与 parent_text
               String[] paragraphs = parentText.split("\\n\\n+");
               for (String para : paragraphs) {
                   if (para.length() < 15) continue; // 过滤无意义短句
                   TextSegment childSegment = TextSegment.from(para, 
                       document.metadata()
                           .copy()
                           .add("parent_id", parentId)
                           .add("parent_text", parentText) // 直接携带完整上下文，省去二次回查数据库！
                   );
                   childSegments.add(childSegment);
               }
           }
           return childSegments;
       }
   }
   ```

### ⏰ 11:00 - 13:00：嵌入模型 (EmbeddingModel) 与 PgVectorStore 初始化
1. **配置并声明 `PgVectorEmbeddingStore` Bean**：
   ```java
   package com.devops.agent.config;

   import dev.langchain4j.data.segment.TextSegment;
   import dev.langchain4j.model.embedding.EmbeddingModel;
   import dev.langchain4j.model.embedding.onnx.bge-large-zh.BgeLargeZhEmbeddingModel; // 本地开箱即用高质量嵌入
   import dev.langchain4j.store.embedding.EmbeddingStore;
   import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
   import org.springframework.context.annotation.Bean;
   import org.springframework.context.annotation.Configuration;

   @Configuration
   public class VectorStoreConfig {

       @Bean
       public EmbeddingStore<TextSegment> embeddingStore() {
           return PgVectorEmbeddingStore.builder()
                   .host("localhost")
                   .port(5432)
                   .user("devops")
                   .password("devops_password")
                   .database("devops_knowledge_db")
                   .table("sys_knowledge_chunk")
                   .dimension(1536) // 请确保此处维度与所用的 EmbeddingModel 输出维度绝对匹配
                   .createTable(true) // 首次运行自动建表
                   .build();
       }
   }
   ```

### ⏰ 14:30 - 17:30：一键切片批量向量化与入库脚本编写
1. 编写一键加载资源目录下的全部 Markdown 文件、执行切片、并调用底层 Vector API 写入 `PgVector` 的服务类 `KnowledgeIngestionService.java`：
   ```java
   @Service
   @RequiredArgsConstructor
   @Slf4j
   public class KnowledgeIngestionService {
       private final EmbeddingStore<TextSegment> embeddingStore;
       private final EmbeddingModel embeddingModel;
       private final ParentChildDocumentSplitter splitter;

       public void ingestAllLocalDocuments() {
           // 1. 加载 classpath 下 resources/knowledge/*.md
           // 2. 遍历执行 splitter.splitWithParentChild(doc)
           // 3. 批量生成 Embedding 向量并写入 embeddingStore.addAll(embeddings, segments)
           log.info("【知识库入库】成功完成 {} 段子切片的语义向量写入！", totalSegmentsCount);
       }
   }
   ```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：特殊符号清洗防分词崩溃**  
   根据《综合审查报告》针对 PostgreSQL 原生分词器的安全要求，入库切片在存入 DB 前，务必清理文本中的 `\0` 等非法数据库转义字符，避免 `PSQLException: invalid byte sequence for encoding`。
2. **💡 建议二：如果下载或运行本地 BGE 嵌入模型内存不够怎么办？**  
   如果你的笔记本电脑只有 8GB/16GB 内存，本地加载 ONNX 模型可能会占用过高 JVM 堆内存。可直接换用 **阿里云百炼文本嵌入 API (`qwen-text-embedding-v2`) 或 OpenAI/DeepSeek 远程嵌入接口**，速度快且本地 0 资源压力。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] `KnowledgeIngestionService.ingestAllLocalDocuments()` 执行成功，控制台打印入库行数
- [ ] 登录 Adminer 查看 `sys_knowledge_chunk` 表，至少看到 50+ 条记录，且 `embedding` 字段填充了真实的 `[-0.023, 0.041, ...]` 浮点数据
- [ ] 执行单元测试 `embeddingStore.search(EmbeddingSearchRequest.builder().queryEmbedding(embeddingModel.embed("Pod启动提示FailedMount").content()).maxResults(3).build()).matches()`，能够瞬间返回标题包含 K8s 生命周期的中文文档段落
