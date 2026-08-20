# 📅 阶段2_Day4_四层幻觉防护体系代码落地与重试闭环开发实施计划

> **阶段所属**：阶段二：Agent核心逻辑与安全防护开发  
> **当日核心目标**：实战写死大模型“四层事实捏造治理防线”（Prompt 约束 -> 工具白名单 -> JSON Schema 自愈重试 -> `Score < 0.73` 低相似度熔断兜底），彻底堵死 AI 脑补与瞎编命令的操作隐患。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：完成当面对知识库未收录或蓄意超纲提问（如 `"怎么用 K9s 发射核武器？"`）时，系统精确触发相似度熔断并优雅平稳地推荐人工接管，无任何事实捏造。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：第三层 —— 工具 JSON Schema 参数反序列化与自愈重试
当 Agent 调用 `createDevOpsTicket` 时，如果大模型输出的 JSON 参数残缺，我们在业务层或 Tool 切面里进行捕获并向模型发起重试提示（见全路径审查报告风险 3）：
1. **编写工具参数强校验包装逻辑**：
   ```java
   package com.devops.agent.service.guard;

   import org.springframework.stereotype.Component;

   @Component
   public class ToolParameterValidator {

       public void validateTicketCreation(String title, String priority, String description) {
           if (title == null || title.trim().length() < 3) {
               throw new IllegalArgumentException("工单标题参数过短，请针对具体发生的报错概括至至少3个字以上再提交！");
           }
           if (description == null || description.trim().length() < 10) {
               throw new IllegalArgumentException("故障描述说明信息不足10字，必须补充具体的堆栈摘要或发生步骤后才准提交工单！");
           }
       }
   }
   ```
2. **在 `DevOpsTools.createDevOpsTicket` 内部第一行调用校验器**：  
   由于 LangChain4j 的 ReAct 循环捕获到 Java `RuntimeException` / `IllegalArgumentException` 后会自动将异常消息带回给大模型作为“反思上下文”，模型将在一秒内自动进行下一轮参数补全重试！

### ⏰ 13:00 - 16:00：第四层 —— 相似度分数阈值 (`Score < 0.73`) 自动熔断与强制溯源
1. **修改检索工具实现分数拦截兜底 (`searchDevOpsKnowledge`)**：
   ```java
   @Tool("从知识库中检索实操文档")
   public String searchDevOpsKnowledge(String keyword) {
       // 1. 设置阈值：要求最小余弦相似度必须 >= 0.73
       EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
               .queryEmbedding(embeddingModel.embed(keyword).content())
               .maxResults(3)
               .minScore(0.73) // 绝不把低分垃圾文本塞给大模型！
               .build();
       EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
       List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
       
       // 2. 0.73 阈值熔断拦截
       if (matches.isEmpty()) {
           log.warn("【低相似度熔断】关键词 [{}] 没有任何相似度 >= 0.73 的匹配记录，执行兜底逻辑...", keyword);
           return "【内部知识库无匹配记录】知识库中目前完全没有针对关键词 '" + keyword + "' 的操作规范和处理方案。" +
                  "为了防止发生误导性生产操作事故，请立刻直接回答用户：『当前运维参考手册未明确说明此异常处理步骤，" +
                  "建议您尝试重新概括报错信息，或点击下方【转接二级工单】由人工运维工程师介入诊断。』，严禁自行推测或捏造任何终端指令！";
       }

       // 3. 强制把【出处溯源页码】显式嵌入返回给模型的字符串前端，逼迫其引用
       StringBuilder sb = new StringBuilder();
       for (EmbeddingMatch<TextSegment> match : matches) {
           String docTitle = match.embedded().metadata().getString("doc_title");
           String parentText = match.embedded().metadata().getString("parent_text");
           sb.append(String.format("【文献来源引用标记：%s (匹配度: %.2f)】\n%s\n\n---\n\n", docTitle, match.score(), parentText));
       }
       return sb.toString();
   }
   ```

### ⏰ 16:30 - 18:00：第一层 —— 针对结果出处归因的 Prompt 事实约束升级
为了强迫模型在回答问题时必须以 `[参考出处：阿里云SLB手册-P3]` 结尾，升级并在系统指令中写死强制出处约束：
```java
@SystemMessage({
    "你是一个专业的 K8s/IT 运维诊断 AI。",
    "每次调用知识库工具拿到返回文本后，请执行以下事实归因自查：",
    "1. 如果系统给出的文本标记了【文献来源引用标记】，你回答的任何具体操作和参数必须 100% 来源于此段文本；",
    "2. 【强制性排版铁律】：无论你回答得有多精彩，在回答文本的最后一行单独一行，必须输出如下出处追溯格式：",
    "   『📚 本段建议参考来源：<你在检索内容中看到的具体文献来源名称>』",
    "3. 如果触发了低相似度熔断未给出具体建议，则最后一行输出：『📚 本段建议参考来源：暂无确切匹配文段（已转接工单建议）』"
})
```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：0.73 这个阈值怎么定的？如果找不到东西太敏感怎么办？**  
   余弦相似度阈值并不是恒定不变的！对于本地的 `bge-large-zh`，标准问答大概落在 `0.78 ~ 0.88` 之间；如果使用 `qwen-text-embedding-v2`，通常在 `0.68 ~ 0.82`。建议在今天多拿几条业务真实语句去单测里计算分数区间，推荐将起步阈值设在 `0.72`~`0.74`。
2. **💡 建议二：如果大模型死活就是不愿写『📚 本段建议参考来源』怎么办？**  
   对于一些小参数量模型（如 7B/14B），写久了偶尔会忘记格式化要求。你可以通过 Spring AOP 切面或拦截器，在拿到 Agent 返回的结果 `result` 后做轻量正向正则检查，如果不包含 `📚` 标识，直接由 Java 自动在末尾拼接一个默认出处来源标签，确保对外输出高度一致和规范。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 输入超纲测试语 `"怎么才能在阿里云 K8s 控制台开启全服自爆倒计时？"`，系统完全触发 Score 过滤并平稳回答知识库无匹配，并引导转人工，毫无乱编痕迹
- [ ] 连续 3 次正常业务问答结果末尾均带有标准的 `📚 本段建议参考来源：XXXX手册` 标记
- [ ] 故意提交一个没有输入充分详情的故障工单触发参数校验错误，日志显示模型接收到报错建议并在 1.5 秒后自行发起了带有完整修正参数的第二次成功建单请求
