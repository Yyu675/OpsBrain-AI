# 📅 阶段3_Day6_后端SSE流式打字机接口与Redis语义缓存开发实施计划

> **阶段所属**：阶段三：前后端功能联调与缓存优化  
> **当日核心目标**：设计并打通 Controller 层基于 Spring Boot `SseEmitter` 的流式打字机数据推送规范，并在全业务的最前方成功插入 **Redis 语义缓存 (Semantic Cache) 拦截层**（命中耗时压缩到 `<50ms` 且 0 API 费）。  
> **预计耗时**：6 - 7 小时  
> **完成产出**：通过浏览器或 `curl -N http://localhost:8080/api/v1/chat/stream?query=你好` 访问接口，能看到字词按自定义的 `event: token` 格式一个个流畅冒出。连续多次问同样相似的问题，能被 Redis 瞬间全命中返回。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 12:00：Redis 语义缓存管理器 (`SemanticCacheService.java`) 开发
利用 LangChain4j 计算 Query 向量，在 Redis 缓存或内存表中对比近期高频问题余弦距离，只有当 **`Similarity > 0.95`** 时才判为命中并复用旧文本（见前置白皮书与审查报告数据）：
```java
package com.devops.agent.service.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    private final StringRedisTemplate redisTemplate;
    private final EmbeddingModel embeddingModel;
    
    // 内存级热点问题向量对缓存（也可以存入 Redis HashSet 或 PgVector）
    private final Map<String, float[]> recentQueryVectorCache = new ConcurrentHashMap<>();

    public String tryHitCache(String userQuery) {
        long start = System.currentTimeMillis();
        // 1. 将当前提问迅速向量化 (耗时 < 15ms)
        float[] currentVector = embeddingModel.embed(userQuery).content().vector();

        // 2. 遍历近期 24 小时内的热点提问计算余弦相似度
        for (Map.Entry<String, float[]> entry : recentQueryVectorCache.entrySet()) {
            String cachedQuery = entry.getKey();
            float[] cachedVector = entry.getValue();
            
            double similarity = CosineSimilarity.between(Embedding.from(currentVector), Embedding.from(cachedVector));
            if (similarity >= 0.95) { // 黄金判定阈值：只要余弦值超过 0.95，视同同一语义问题！
                String cachedAnswer = redisTemplate.opsForValue().get("devops:cache:ans:" + cachedQuery);
                if (cachedAnswer != null) {
                    log.info("【语义缓存大命中】当前提问 '[{}]' 与旧提问 '[{}]' 相似度高超 [{}] (耗时: {}ms)！直接 0 费复用历史答案！",
                            userQuery, cachedQuery, similarity, System.currentTimeMillis() - start);
                    return cachedAnswer;
                }
            }
        }
        return null;
    }

    public void putCache(String userQuery, String answer) {
        float[] vector = embeddingModel.embed(userQuery).content().vector();
        recentQueryVectorCache.put(userQuery, vector);
        redisTemplate.opsForValue().set("devops:cache:ans:" + userQuery, answer, 24, TimeUnit.HOURS);
    }
}
```

### ⏰ 13:30 - 17:00：规范化 SseEmitter 流式接口 (`DevOpsChatController.java`)
实现我们定义的前置白皮书契约 (`event: start/tool_status/token/complete`)，并在异步流中包装大模型 `TokenStream` 与语义缓存返回拦截：
```java
package com.devops.agent.controller;

import com.devops.agent.service.cache.SemanticCacheService;
import com.devops.agent.service.router.DevOpsIntentRouter;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*") // 解决前后端分离跨域
public class DevOpsChatController {

    private final SemanticCacheService cacheService;
    private final DevOpsIntentRouter router;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam("query") String query) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        SseEmitter emitter = new SseEmitter(60_000L); // 设成60秒超时防泄漏

        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                sendEvent(emitter, "start", String.format("{\"traceId\":\"%s\"}", traceId));

                // 1. 优先尝试拦截层：语义缓存命中测试
                String cachedAnswer = cacheService.tryHitCache(query);
                if (cachedAnswer != null) {
                    // 模拟极速 SSE 打字机流发回缓存文本
                    for (char c : cachedAnswer.toCharArray()) {
                        sendEvent(emitter, "token", String.format("{\"text\":\"%s\"}", escapeJson(String.valueOf(c))));
                        Thread.sleep(15);
                    }
                    sendEvent(emitter, "complete", String.format("{\"traceId\":\"%s\",\"isCached\":true,\"latencyMs\":%d,\"costRmb\":0.0}",
                            traceId, System.currentTimeMillis() - startTime));
                    emitter.complete();
                    return;
                }

                // 2. 未命中缓存，选择真实底引擎执行大模型流式调用
                StringBuilder fullResponseBuilder = new StringBuilder();
                TokenStream stream = router.routeEngine(query).streamChat(query); // 需支持流式 Engine 接口

                stream.onNext(token -> {
                    try {
                        fullResponseBuilder.append(token);
                        sendEvent(emitter, "token", String.format("{\"text\":\"%s\"}", escapeJson(token)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onComplete(tokenUsage -> {
                    try {
                        String fullAns = fullResponseBuilder.toString();
                        cacheService.putCache(query, fullAns); // 异步写入下一轮的语义缓存
                        sendEvent(emitter, "complete", String.format("{\"traceId\":\"%s\",\"isCached\":false,\"latencyMs\":%d,\"costRmb\":0.002}",
                                traceId, System.currentTimeMillis() - startTime));
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                .onError(emitter::completeWithError)
                .start();

            } catch (Exception ex) {
                log.error("[SSE流式通信异常] traceId={}", traceId, ex);
                emitter.completeWithError(ex);
            }
        });

        // 注册连接断开和超时的回调清理方法 (审查报告要求)
        emitter.onTimeout(() -> { log.warn("[SSE会话超时]: {}", traceId); emitter.complete(); });
        emitter.onError(t -> { log.warn("[SSE连接断开]: {}", traceId); emitter.complete(); });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, String jsonPayload) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(jsonPayload));
    }

    private String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：流式打字发包时注意手动实现 `escapeJson`**  
   因为在 SSE 的 `data: {"text": "..."}` 结构里，如果大模型恰好吐出了一个双引号 `"` 或者回车符 `\n`，如果你不提前转义，前端 JSON.parse 反序列化会当场报错导致打字中途卡死。一定要用 `escapeJson()` 做转义！
2. **💡 建议二：控制层使用单向长轮询 `CORS` 跨域配置**  
   Vue 前端由于本地是在 `http://localhost:5173` 跑，跟你的 Spring Boot `http://localhost:8080` 端口不同。在本地调试前期请务必在 Controller 加上 `@CrossOrigin(origins = "*")`，避免因为浏览器同源策略把你的流式连接拒在门外。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 打开终端输入命令：`curl -N http://localhost:8080/api/v1/chat/stream?query=什么是K8s的Pod`，能够看到终端中立刻逐句流淌出 `event: token` 的格式化数据块，没有发生任何异常闪退
- [ ] 连续发起第二次完全同义的问题：`curl -N http://localhost:8080/api/v1/chat/stream?query=可以告诉我什么叫K8s的Pod吗`，控制台和接口都能在 `50ms` 内极速完成输出，并附带了 `isCached: true` 标记
- [ ] 故意在流式回复吐了一半字的时候按键盘 `Ctrl+C` 中断 curl 连接，服务端后台控制台能平稳打印出 `[SSE连接断开]` 日志并正常释放底层线程库，毫无长链接内存泄露
