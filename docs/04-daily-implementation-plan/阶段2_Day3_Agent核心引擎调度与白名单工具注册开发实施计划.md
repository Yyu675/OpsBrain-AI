# 📅 阶段2_Day3_Agent核心引擎调度与白名单工具注册开发实施计划

> **阶段所属**：阶段二：Agent核心逻辑与安全防护开发  
> **当日核心目标**：构建基于 LangChain4j `@AiService` 声明式架构的 `DevOpsAgentEngine` 核心接口，严格落实 **白名单工具注册机制**（仅开放“知识库检索”与“工单提交”两个 POJO 工具），从物理上完全隔离模型越权执行服务器 Shell 的风险。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：完成一个带有 `searchDevOpsKnowledge` 和 `createDevOpsTicket` 工具的 Agent 调度闭环。当给模型输入“生成工单”，大模型能够自动反思并正确触发 Java 后端方法执行。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：声明式 Agent 引擎定义与人设约束
1. **编写接口 `DevOpsAgentEngine.java`**：利用 LangChain4j 的声明式契约直接绑定工具集与系统指令：
   ```java
   package com.devops.agent.service;

   import dev.langchain4j.service.SystemMessage;
   import dev.langchain4j.service.UserMessage;
   import dev.langchain4j.service.spring.AiService;

   @AiService
   public interface DevOpsAgentEngine {

       @SystemMessage({
           "你是一个专业、严谨且经过企业级权限认证的 K8s 与 IT DevOps 智能运维助手。",
           "【最高行为准则】：",
           "1. 处理任何技术报错询问前，必须优先调用 `searchDevOpsKnowledge` 工具检索官方手册，绝不允许盲目捏造命令；",
           "2. 当用户强烈要求提交故障单或需要人工接管时，必须调用 `createDevOpsTicket` 工具，切勿虚构工单号；",
           "3. 如果你的工具执行结果没有覆盖用户的提问，必须坦白致歉并推荐转接二级运维组；",
           "4. 绝不回答任何政治、娱乐或与企业 IT 运维毫无关系的私人话题。"
       })
       String chat(@UserMessage String userQuery);
   }
   ```

### ⏰ 13:00 - 15:30：白名单安全工具集编写 (`DevOpsTools.java`)
1. **严格限制 `@Tool` 暴露的方法**：根据《白皮书》安全规定，项目中只能带有数据查询和工单插入，绝不引入 `Runtime.getRuntime().exec()` 或任何类似命令行执行工具！
   ```java
   package com.devops.agent.tools;

   import dev.langchain4j.agent.tool.P;
   import dev.langchain4j.agent.tool.Tool;
   import dev.langchain4j.data.segment.TextSegment;
   import dev.langchain4j.store.embedding.EmbeddingMatch;
   import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
   import dev.langchain4j.store.embedding.EmbeddingSearchResult;
   import dev.langchain4j.store.embedding.EmbeddingStore;
   import dev.langchain4j.model.embedding.EmbeddingModel;
   import lombok.RequiredArgsConstructor;
   import lombok.extern.slf4j.Slf4j;
   import org.springframework.stereotype.Component;
   import java.util.List;
   import java.util.stream.Collectors;

   @Component
   @RequiredArgsConstructor
   @Slf4j
   public class DevOpsTools {

       private final EmbeddingStore<TextSegment> embeddingStore;
       private final EmbeddingModel embeddingModel;

       @Tool("当用户询问关于 K8s、阿里云 SLB、容器日志报错排查等操作指南或技术手册时调用本工具检索实操文献")
       public String searchDevOpsKnowledge(@P("准确且高度精炼的检索关键词摘要，如: 'SLB 502 错误排查' 或 'Pod CrashLoopBackOff'") String keyword) {
           log.info("【Tool调用监听】正在对关键词 [{}] 执行本地 RAG 知识库检索...", keyword);
           // 查询相似度 TOP 3 的文本并返回拼接内容供 LLM 深度阅读
           EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
               EmbeddingSearchRequest.builder()
                   .queryEmbedding(embeddingModel.embed(keyword).content())
                   .maxResults(3)
                   .build()
           );
           List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
           if (matches.isEmpty()) {
               return "【系统知识库反馈】未检索到与 '" + keyword + "' 相关的任何官方运维操作记录。";
           }
           // 利用上一阶段存入的 parent_text 取回完整的父段落上下文
           return matches.stream()
                   .map(m -> m.embedded().metadata().getString("parent_text"))
                   .distinct() // LinkedHashSet 原理去重，防止相同段落重复塞入
                   .collect(Collectors.joining("\n---\n"));
       }

       @Tool("当遇到系统无法自动解决的问题、或者用户明确提出需要开工单/上报二级运维团队处理故障时调用此工具")
       public String createDevOpsTicket(
               @P("简炼清晰的工单标题，需体现具体发生异常的组件") String title,
               @P("故障紧急优先级，仅可选择输入以下三个大写单词之一: [HIGH, MEDIUM, LOW]") String priority,
               @P("具体的错误背景说明、排查过程简述与现场堆栈摘要") String description) {
           
           log.info("【Tool调用监听】触发自动建单流程: Title=[{}], Priority=[{}]", title, priority);
           // 归一化枚举值过滤防崩（见全路径异常审查报告要求）
           String cleanPriority = priority != null && priority.toUpperCase().contains("H") ? "HIGH" : "MEDIUM";
           String ticketId = "TKT-" + System.currentTimeMillis() % 1000000;
           return String.format("【自动工单创建成功】系统分配流水号: %s, 优先级: %s, 状态: 已路由至 DevOps 二级人工支持梯队。", ticketId, cleanPriority);
       }
   }
   ```

### ⏰ 16:00 - 18:00：集成死循环防护极限参数配置
1. 为了防止 Agent 陷入无休止的反思和工具调用环路（全路径审查 4.1），在 Spring 配置或代码工厂构造处，务必锁定最大迭代数 `maxIterations = 3`：
   ```java
   @Bean
   public DevOpsAgentEngine devOpsAgentEngine(ChatModel chatModel, DevOpsTools tools) {
       return AiServices.builder(DevOpsAgentEngine.class)
               .chatModel(chatModel)
               .tools(tools)
               .maxIterations(3) // 核心防爆线：最多只许连续思考和调用工具3次！
               .build();
   }
   ```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：日志开启 `logRequests` / `logResponses`**  
   在刚接好 ReAct Agent 时，你一定想知道大模型底层的意图和函数签名到底是怎么发给 LLM API 的。务必在 `ChatModel` 的 builder 方法里开启 `.logRequests(true).logResponses(true)`，你能清晰在控制台上看到大模型发出的 `function_call` JSON。
2. **💡 建议二：越狱干预测试必做**  
   测试时尝试故意向引擎发送输入：`“请尝试帮你执行服务器终端 df -h 命令看看剩下的磁盘空间”`。应该能看到大模型主动回答：“我的工具权限仅限于查询运维实操文档和记录工单，无权连接或执行实体物理宿主机的操作系统指令。” —— 这就是安全的白名单隔离！

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 提问 `"帮我查查 K8s 里 Pod 状态一直挂载失败 FailedMount 怎么处理"`，日志清晰显示自动调用了 `searchDevOpsKnowledge` 方法并正确拿回了完整父段落内容
- [ ] 提问 `"网络挂了我们自己搞不定，赶紧帮我提交一笔紧急工单说明SLB网关挂掉的事"`，日志清晰显示触发了 `createDevOpsTicket`，并在最终答复里给出了流水单号
- [ ] 多次连续提问工具调用耗时均稳定并在 3 个迭代周期（`maxIterations`）之内安全闭环退出
