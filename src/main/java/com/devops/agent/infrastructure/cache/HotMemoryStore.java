package com.devops.agent.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 热记忆存储（Redis）
 * <p>
 * 参考 Agent Methodology §6.1：热记忆存最近 N 轮对话、当前会话状态、
 * 最近工具结果。特点是低延迟、有 TTL、面向当前生成。
 * </p>
 * <p>
 * Redis 键设计：
 * <pre>
 *   devops:session:{sessionId}:messages   List   最近 N 轮消息（滑动窗口）
 *   devops:session:{sessionId}:meta       Hash   轮次/累计 Token/累计成本
 * </pre>
 * TTL 采用<b>滑动续期</b>：每次读写都刷新过期时间，活跃会话不失效，
 * 静默超时后自动回收（由归档任务负责持久化到温记忆）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class HotMemoryStore {

    private static final String KEY_PREFIX = "devops:session:";
    private static final String SUFFIX_MESSAGES = ":messages";
    private static final String SUFFIX_META = ":meta";

    private static final String FIELD_TURN_COUNT = "turnCount";
    private static final String FIELD_TOTAL_TOKENS = "totalTokens";
    private static final String FIELD_TOTAL_COST = "totalCostRmb";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 滑动窗口保留轮数（一轮 = 用户提问 + AI 回答，即 2 条消息） */
    @Value("${devops.ai.memory.hot-window-turns:10}")
    private int hotWindowTurns;

    /** 热记忆 TTL（分钟），静默超过此时长自动回收 */
    @Value("${devops.ai.memory.hot-ttl-minutes:120}")
    private long hotTtlMinutes;

    public HotMemoryStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 消息读写 ====================

    /**
     * 追加一条消息到热记忆，并裁剪至滑动窗口大小
     *
     * @param sessionId 会话 ID
     * @param role      角色：user / assistant
     * @param content   消息内容
     */
    public void appendMessage(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String key = messagesKey(sessionId);
            String payload = objectMapper.writeValueAsString(new Message(role, content));

            redisTemplate.opsForList().rightPush(key, payload);

            // 裁剪：保留最近 N 轮（N*2 条消息）
            long maxSize = (long) hotWindowTurns * 2;
            redisTemplate.opsForList().trim(key, -maxSize, -1);

            touchTtl(sessionId);
        } catch (Exception e) {
            // 热记忆失败不阻塞主流程（降级为无历史）
            log.warn("⚠️ [HotMemory] 追加消息失败 | sessionId={} | {}", sessionId, e.getMessage());
        }
    }

    /**
     * 读取最近对话历史（时间正序：旧 → 新）
     *
     * @param sessionId 会话 ID
     * @return 消息列表，异常或无数据时返回空列表
     */
    public List<Message> loadMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Collections.emptyList();
        try {
            List<String> raw = redisTemplate.opsForList().range(messagesKey(sessionId), 0, -1);
            if (raw == null || raw.isEmpty()) return Collections.emptyList();

            List<Message> messages = new ArrayList<>(raw.size());
            for (String s : raw) {
                try {
                    messages.add(objectMapper.readValue(s, Message.class));
                } catch (Exception ignore) {
                    // 单条解析失败跳过，不影响其余历史
                }
            }
            touchTtl(sessionId);
            return messages;
        } catch (Exception e) {
            log.warn("⚠️ [HotMemory] 读取消息失败（降级为无历史）| sessionId={} | {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 读取历史并渲染为紧凑文本，供 Prompt 注入
     */
    public List<String> loadHistoryAsText(String sessionId) {
        List<Message> messages = loadMessages(sessionId);
        List<String> lines = new ArrayList<>(messages.size());
        for (Message m : messages) {
            String prefix = "user".equals(m.role()) ? "用户" : "助手";
            lines.add(prefix + "：" + m.content());
        }
        return lines;
    }

    // ==================== 统计累积 ====================

    /**
     * 累积会话统计（轮次 +1，Token 与成本累加）
     */
    public void accumulateStats(String sessionId, int tokens, double costRmb) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String key = metaKey(sessionId);
            redisTemplate.opsForHash().increment(key, FIELD_TURN_COUNT, 1);
            redisTemplate.opsForHash().increment(key, FIELD_TOTAL_TOKENS, tokens);
            // Hash 不支持浮点自增，用整数分存储
            redisTemplate.opsForHash().increment(key, FIELD_TOTAL_COST, Math.round(costRmb * 10000));
            touchTtl(sessionId);
        } catch (Exception e) {
            log.warn("⚠️ [HotMemory] 累积统计失败 | sessionId={} | {}", sessionId, e.getMessage());
        }
    }

    /**
     * 读取会话统计
     */
    public SessionStats loadStats(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return SessionStats.empty();
        try {
            var ops = redisTemplate.opsForHash();
            String key = metaKey(sessionId);
            int turns = parseInt(ops.get(key, FIELD_TURN_COUNT));
            int tokens = parseInt(ops.get(key, FIELD_TOTAL_TOKENS));
            // 从万分之一元还原
            double cost = parseInt(ops.get(key, FIELD_TOTAL_COST)) / 10000.0;
            return new SessionStats(turns, tokens, cost);
        } catch (Exception e) {
            log.warn("⚠️ [HotMemory] 读取统计失败 | sessionId={} | {}", sessionId, e.getMessage());
            return SessionStats.empty();
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 刷新 TTL（滑动续期）
     */
    private void touchTtl(String sessionId) {
        Duration ttl = Duration.ofMinutes(hotTtlMinutes);
        redisTemplate.expire(messagesKey(sessionId), ttl);
        redisTemplate.expire(metaKey(sessionId), ttl);
    }

    /**
     * 清除会话热记忆（归档到温记忆后调用）
     */
    public void evict(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            redisTemplate.delete(messagesKey(sessionId));
            redisTemplate.delete(metaKey(sessionId));
            log.debug("🗑️ [HotMemory] 已清除热记忆 | sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("⚠️ [HotMemory] 清除失败 | sessionId={} | {}", sessionId, e.getMessage());
        }
    }

    /**
     * 会话是否存在热记忆
     */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(messagesKey(sessionId)));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 内部工具 ====================

    private String messagesKey(String sessionId) {
        return KEY_PREFIX + sessionId + SUFFIX_MESSAGES;
    }

    private String metaKey(String sessionId) {
        return KEY_PREFIX + sessionId + SUFFIX_META;
    }

    private int parseInt(Object v) {
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 数据类型 ====================

    /**
     * 热记忆中的一条消息
     */
    public record Message(String role, String content) {
    }

    /**
     * 会话统计
     */
    public record SessionStats(int turnCount, int totalTokens, double totalCostRmb) {
        public static SessionStats empty() {
            return new SessionStats(0, 0, 0.0);
        }
    }
}