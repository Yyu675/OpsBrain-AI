# 📅 阶段2_Day5_智能意图路由与大小模型分层调度开发实施计划

> **阶段所属**：阶段二：Agent核心逻辑与安全防护开发  
> **当日核心目标**：设计开发能够极速判断用户咨询复杂度的 **两级智能路由分类器 (`DevOpsIntentRouter`)**，实现主日常路（`Qwen-Turbo/DeepSeek-V3`）与长堆栈推理路（`DeepSeek-R1/Qwen-Max`）的动态大小模型分层调度，将单次问答综合调用费降至 `0.005元` 以内。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：完成一个意图分流与双底层引擎管理工厂。能看到当用户发一句“怎么查看Pod日志命令”时只耗时 800ms 走小模型；当发送 200 字长异常日志堆栈时，自动切换至 R1 进行逻辑推导。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：双大模型连接池与工厂定义
1. **在 `application.yml` 引入不同能级模型的连接隔离**：
   ```yaml
   ai:
     # 1. 主路常规查询小模型 (极低成本、极速响应)
     turbo-model:
       base-url: https://api.deepseek.com/v1
       api-key: ${DEEPSEEK_API_KEY}
       model-name: deepseek-chat
     
     # 2. 复杂多链推导/报错分析高阶模型
     reasoning-model:
       base-url: https://api.deepseek.com/v1
       api-key: ${DEEPSEEK_API_KEY}
       model-name: deepseek-reasoner # 或者使用 qwen-max
   ```
2. **在 `AiModelConfig.java` 声明两套模型 Bean 并构造对应的 `DevOpsAgentEngine`**：
   ```java
   @Bean("turboChatModel")
   public ChatModel turboChatModel(@Value("${ai.turbo-model.model-name}") String modelName) { ... }

   @Bean("reasoningChatModel")
   public ChatModel reasoningChatModel(@Value("${ai.reasoning-model.model-name}") String modelName) { ... }

   // 构造对应两套 Agent 引擎实例
   @Bean("turboAgentEngine")
   public DevOpsAgentEngine turboAgentEngine(@Qualifier("turboChatModel") ChatModel model, DevOpsTools tools) {
       return AiServices.builder(DevOpsAgentEngine.class).chatModel(model).tools(tools).maxIterations(3).build();
   }

   @Bean("reasoningAgentEngine")
   public DevOpsAgentEngine reasoningAgentEngine(@Qualifier("reasoningChatModel") ChatModel model, DevOpsTools tools) {
       return AiServices.builder(DevOpsAgentEngine.class).chatModel(model).tools(tools).maxIterations(3).build();
   }
   ```

### ⏰ 13:00 - 16:30：意图路由分流调度器 (`DevOpsIntentRouter.java`) 编码
综合使用 **正则拦截快查 + 轻量语义分类** 实现极速且准确的意图分流（见全路径审查报告成本优化章）：
```java
package com.devops.agent.service.router;

import com.devops.agent.service.DevOpsAgentEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DevOpsIntentRouter {

    @Qualifier("turboAgentEngine")
    private final DevOpsAgentEngine turboEngine;

    @Qualifier("reasoningAgentEngine")
    private final DevOpsAgentEngine reasoningEngine;

    // 长异常堆栈匹配特征库
    private static final Pattern COMPLEX_TRACE_PATTERN = Pattern.compile(
        "(?i)(exception|stacktrace|at com\\.|error:.*code|panic:|broken pipe|caused by|k8s.*timeout.*trace)"
    );

    public DevOpsAgentEngine routeEngine(String userQuery) {
        long startTime = System.currentTimeMillis();
        
        // 1. 如果用户提问字符数 < 60 字，且都是基础名词或者简单提问，极速路由到 Turbo 小模型
        if (userQuery.length() < 60 && !COMPLEX_TRACE_PATTERN.matcher(userQuery).find()) {
            log.info("【智能分层路由】直接判定为基础问答/极简操作 (耗时: {}ms) -> 分配至 Turbo 轻量级引擎", System.currentTimeMillis() - startTime);
            return turboEngine;
        }

        // 2. 如果包含明确的长报错代码段、深度故障词汇，或者文本长度极大，则直接分配到 Reasoning 高智商引擎
        if (userQuery.length() >= 150 || COMPLEX_TRACE_PATTERN.matcher(userQuery).find()) {
            log.info("【智能分层路由】检测到长篇异常堆栈/跨域故障特征 (耗时: {}ms) -> 分配至 DeepSeek-R1 推理专家引擎", System.currentTimeMillis() - startTime);
            return reasoningEngine;
        }

        // 3. 灰度地带默认兜底策略：选择稳健的主路 Turbo 引擎处理，保障系统并发性能
        return turboEngine;
    }
}
```

### ⏰ 17:00 - 18:00：统一调用网关并接入统计计算逻辑
1. 将外部调用请求收口给一个唯一的门面服务类 `AgentDispatchFacadeService.java`，让它负责测算不同调用的 Token 消耗并落入到我们定义的 `sys_agent_call_log` 统计表中。

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：不要全部去用小模型做第二遍 LLM 意图判断**  
   某些教程会教你“让 GPT 每次先花 300 毫秒跑一个提示词来决定用哪个大模型”。在实际工程里，这多消耗 300 毫秒响应延迟且一样要付一次 Token 费。其实运用我们在这一天编写的 **正则特征判断 + 文本长度截断法**，已经能够极速分流 90% 以上的常见运维场景，性能更高且 0 延时！
2. **💡 建议二：大模型降级与兜底策略切记写上 (Resilience4j)**  
   如果某一天晚上高智商推理模型 (`DeepSeek-R1`) 服务器繁忙返回 `HTTP 503/429` 异常，必须使用 `try-catch` 或 Spring `@Retryable` 捕获异常，**迅速把请求平滑降级抛回给主路 `turboEngine`** 接管答复，千万不能给用户看报错弹窗。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 发送提问 `"如何使用 kubectl 查看默认命名空间下所有的 Pod"`，系统后台打印：`直接判定为基础问答 -> 分配至 Turbo 轻量级引擎`，在 `1秒` 内做出回复
- [ ] 发送提问带有 Java/Go 报错堆栈代码超过 `200` 个字符的段落，后台监控立刻打印：`检测到长篇异常堆栈 -> 分配至 DeepSeek-R1 推理专家引擎`，进行深度逻辑梳理并返回修复方案
- [ ] 能够通过注入方式模拟 R1 引擎出现连接超时，并在 `3秒` 内成功被 Turbo 备用引擎降级接管
