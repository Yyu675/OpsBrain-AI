package com.devops.agent.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MockEmbeddingModel 维度契约（S0-2-J1 探针出的产物）。
 *
 * <h3>为什么存在</h3>
 * <p>
 * 该类曾硬编码 1536 维，无视 {@code devops.ai.vector.dimension}——
 * 注入「维度漂移 1536→512」的探针竟然全绿，红线在 MOCK 路径上是死的
 * （Mock 是 CI 唯一使用的模式，等于 CI 从不校验维度一致性）。
 * 修复后维度由构造器注入，本测试把这个行为钉死，防止未来再以
 * 「硬编码回来」的方式悄悄复辟。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-2）
 */
class MockEmbeddingModelTest {

    @Test
    @DisplayName("输出向量维度必须等于构造器注入的维度（铁律的 MOCK 侧知晓该配置）")
    void outputDimensionMustHonorInjectedDimension() {
        assertThat(new MockEmbeddingModel(1536).embed("test").content().vector()).hasSize(1536);
        assertThat(new MockEmbeddingModel(512).embed("test").content().vector()).hasSize(512);
    }

    @Test
    @DisplayName("维度小于 4 直接拒绝（前 4 维放余弦系数，越界会静默变成数组越界崩溃）")
    void dimensionBelowFourMustBeRejected() {
        assertThatThrownBy(() -> new MockEmbeddingModel(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("devops.ai.vector.dimension");
    }

    @Test
    @DisplayName("相同文本→相同向量（缓存命中的前提），不同文本→不同向量")
    void embeddingMustBeDeterministicAndDistinct() {
        var model = new MockEmbeddingModel(1536);
        float[] a1 = model.embed("Pod CrashLoopBackOff").content().vector();
        float[] a2 = model.embed("Pod CrashLoopBackOff").content().vector();
        float[] b = model.embed("完全不同的内容").content().vector();
        assertThat(a1).isEqualTo(a2);
        assertThat(a1).isNotEqualTo(b);
    }
}
