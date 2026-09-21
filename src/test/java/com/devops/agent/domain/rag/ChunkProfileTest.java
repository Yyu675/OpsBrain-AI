package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkProfile} 参数校验与回落测试。
 *
 * <p>为什么值得测：切片参数的非法组合（子片≥父片、重叠≥切片）会让
 * 切片器窗口无法前进或父子层级倒挂——这些配置来自知识库管理界面，
 * 必须在入口就被拒绝，而不是等索引时产出垃圾切片。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
class ChunkProfileTest {

    @Test
    @DisplayName("默认配置即硬编码历史值（2400/600/100）——存量行为不变")
    void defaultMatchesHistoricalConstants() {
        assertEquals(2400, ChunkProfile.DEFAULT.parentChunkSize());
        assertEquals(600, ChunkProfile.DEFAULT.childChunkSize());
        assertEquals(100, ChunkProfile.DEFAULT.overlap());
    }

    @Test
    @DisplayName("全 null 回落默认；部分覆盖时其余字段跟随默认")
    void ofNullableFallsBackPerField() {
        assertSame(ChunkProfile.DEFAULT, ChunkProfile.ofNullable(null, null, null));

        ChunkProfile p = ChunkProfile.ofNullable(null, 300, null);
        assertEquals(2400, p.parentChunkSize());
        assertEquals(300, p.childChunkSize());
        assertEquals(100, p.overlap());
    }

    @Test
    @DisplayName("子段落 ≥ 父段落 → 拒绝（父子层级倒挂）")
    void rejectsChildLargerThanParent() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkProfile.ofNullable(500, 600, null));
    }

    @Test
    @DisplayName("重叠 ≥ 子段落 → 拒绝（窗口无法前进）")
    void rejectsOverlapExceedingChild() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkProfile.ofNullable(null, 300, 300));
    }

    @Test
    @DisplayName("非正数 → 拒绝")
    void rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkProfile.ofNullable(0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkProfile.ofNullable(null, -1, null));
    }

    @Test
    @DisplayName("合法自定义参数正常构造")
    void acceptsValidCustomProfile() {
        ChunkProfile p = ChunkProfile.ofNullable(4000, 800, 200);
        assertEquals(4000, p.parentChunkSize());
        assertEquals(800, p.childChunkSize());
        assertEquals(200, p.overlap());
    }
}
