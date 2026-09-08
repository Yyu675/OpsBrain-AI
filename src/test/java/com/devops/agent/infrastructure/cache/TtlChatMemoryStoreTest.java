package com.devops.agent.infrastructure.cache;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TtlChatMemoryStore} 行为测试。
 *
 * <p>保护的契约：<b>会话窗口必须能被回收</b>。这是 A1 修复的核心——
 * 修复前的裸 {@code InMemoryChatMemoryStore} 只增不减，会持续泄漏直至 OOM。
 * 这些用例都是确定性的（直接驱动清扫逻辑，不依赖 sleep 或后台调度时序）。</p>
 */
class TtlChatMemoryStoreTest {

    private static List<ChatMessage> msg(String text) {
        return List.of(UserMessage.from(text));
    }

    @Test
    @DisplayName("写入后可读回，且计入活跃会话数")
    void storesAndCountsSessions() {
        TtlChatMemoryStore store = new TtlChatMemoryStore(120, 1000, 5);

        store.updateMessages("s1", msg("hello"));

        assertEquals(1, store.size(), "写入一个会话后活跃数应为 1");
        assertEquals(1, store.getMessages("s1").size());
    }

    @Test
    @DisplayName("显式删除会同时清掉消息与访问记录，不残留计数")
    void deleteRemovesBothMessagesAndAccessRecord() {
        TtlChatMemoryStore store = new TtlChatMemoryStore(120, 1000, 5);
        store.updateMessages("s1", msg("hello"));

        store.deleteMessages("s1");

        assertEquals(0, store.size(), "删除后不应残留访问记录（否则容量统计会漂移）");
        assertTrue(store.getMessages("s1").isEmpty(), "删除后消息应为空");
    }

    @Test
    @DisplayName("空闲超过 TTL 的会话被回收，未超时的保留")
    void evictsOnlyIdleSessionsPastTtl() throws Exception {
        // TTL 设为 0 分钟：任何已存在的条目在下次清扫时都算过期
        TtlChatMemoryStore store = new TtlChatMemoryStore(0, 1000, 5);
        store.updateMessages("idle", msg("old"));

        // 直接触发清扫，不等后台调度——避免 sleep 造成的不确定性
        invokeSweep(store);

        assertEquals(0, store.size(), "TTL=0 时已存在条目应在清扫中被回收");
        assertTrue(store.evictedCount() >= 1, "驱逐计数应递增，供指标观测");
    }

    @Test
    @DisplayName("TTL 未到期的会话不会被清扫误删")
    void keepsFreshSessions() throws Exception {
        TtlChatMemoryStore store = new TtlChatMemoryStore(120, 1000, 5);
        store.updateMessages("fresh", msg("recent"));

        invokeSweep(store);

        assertEquals(1, store.size(), "TTL 内的活跃会话不得被回收（否则用户对话中途失忆）");
        assertEquals(0, store.evictedCount());
    }

    @Test
    @DisplayName("读操作也会续期，长时间只读的活跃会话不被回收")
    void readAlsoRefreshesTtl() {
        TtlChatMemoryStore store = new TtlChatMemoryStore(120, 1000, 5);
        store.updateMessages("s1", msg("hello"));

        store.getMessages("s1");

        assertEquals(1, store.size(), "读操作应视为访问并续期");
    }

    @Test
    @DisplayName("条目数超过上限时按最旧访问时间淘汰，规模收敛到上限")
    void enforcesMaxEntries() throws Exception {
        int max = 10;
        TtlChatMemoryStore store = new TtlChatMemoryStore(120, max, 5);

        // 写入超过上限的会话，模拟「大量匿名单轮对话」这一真实泄漏来源
        for (int i = 0; i < max + 25; i++) {
            store.updateMessages("session-" + i, msg("m" + i));
        }
        assertEquals(max + 25, store.size(), "清扫前应如实反映写入量");

        invokeSweep(store);

        assertEquals(max, store.size(), "清扫后规模必须收敛到上限，这是防 OOM 的兜底");
        assertEquals(25, store.evictedCount(), "应恰好淘汰超出的部分");
    }

    /** 直接调用私有 sweep()，让测试不依赖后台调度时序 */
    private void invokeSweep(TtlChatMemoryStore store) throws Exception {
        var m = TtlChatMemoryStore.class.getDeclaredMethod("sweep");
        m.setAccessible(true);
        m.invoke(store);
    }
}
