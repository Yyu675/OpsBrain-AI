package com.devops.agent.domain.evidence;

import com.devops.agent.domain.rag.KnowledgeScope;
import com.devops.agent.domain.rag.KnowledgeScopeResolver;
import com.devops.agent.domain.rag.RetrievedChunk;
import com.devops.agent.domain.rag.Retriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识证据采集器单测（P0 2026-09-24）。
 *
 * <h3>测什么</h3>
 * 四态分流是这条链的语义核心：检索链路故障（null）必须落成 FAILED
 * （区别于「确实没有文档」的 NO_DATA）——否则「知识库没建成」会被误读为
 * 「这个故障没有先例可查」，引导人去补一份本来就有的文档，重复出现
 * RetrievedChunk 注释里那个真实缺陷的反面。
 */
@DisplayName("知识证据采集器（诊断第四方向）")
class KnowledgeEvidenceCollectorTest {

    private Retriever retriever;
    private KnowledgeScopeResolver scopeResolver;
    private KnowledgeEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        retriever = mock(Retriever.class);
        scopeResolver = mock(KnowledgeScopeResolver.class);
        when(scopeResolver.systemScope()).thenReturn(mock(KnowledgeScope.class));
        collector = new KnowledgeEvidenceCollector(retriever, scopeResolver);
    }

    @Test
    @DisplayName("service 为空：NO_DATA（无检索锚点），relevance 为空")
    void blankServiceIsNoData() {
        Evidence e = collector.collect("  ", "30m");
        assertThat(e.status()).isEqualTo(Evidence.EvidenceStatus.NO_DATA);
        assertThat(e.evidenceType()).isEqualTo(Evidence.Type.KNOWLEDGE);
        assertThat(e.relevanceScore()).isNull();
    }

    @Test
    @DisplayName("检索返回 null（链路故障）：FAILED——与「无文档」严格区分")
    void nullChunksIsFailedNotNoData() {
        when(retriever.retrieveWithSource(anyString(), anyInt(), any())).thenReturn(null);

        Evidence e = collector.collect("order-service", "30m");
        assertThat(e.status())
                .as("链路故障不能伪装成「没有文档」——后者会让人去补库里已有的文档")
                .isEqualTo(Evidence.EvidenceStatus.FAILED);
    }

    @Test
    @DisplayName("检索抛异常：兜为 FAILED，不外抛（诊断是附属增值）")
    void exceptionBecomesFailedNotThrown() {
        when(retriever.retrieveWithSource(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("vector db down"));

        Evidence e = collector.collect("order-service", "30m");
        assertThat(e.status()).isEqualTo(Evidence.EvidenceStatus.FAILED);
    }

    @Test
    @DisplayName("检索为空列表：NO_DATA（确实无相关文档，有效排除）")
    void emptyChunksIsNoData() {
        when(retriever.retrieveWithSource(anyString(), anyInt(), any())).thenReturn(List.of());

        Evidence e = collector.collect("order-service", "30m");
        assertThat(e.status()).isEqualTo(Evidence.EvidenceStatus.NO_DATA);
    }

    @Test
    @DisplayName("检索命中：SUCCESS，sourceRef 带 citation，内容含片段，relevance=最高分")
    void hitCarriesCitationsAndGrounding() {
        List<RetrievedChunk> hits = List.of(
                new RetrievedChunk("K8s故障排查手册.md", "## Pod CrashLoopBackOff", "查 events 看 OOMKilled…", 0.91, 42L),
                new RetrievedChunk("诊断SOP.md", null, "先确认资源水位…", 0.80));
        when(retriever.retrieveWithSource(anyString(), anyInt(), any())).thenReturn(hits);

        Evidence e = collector.collect("order-service", "30m");

        assertThat(e.status()).isEqualTo(Evidence.EvidenceStatus.SUCCESS);
        // sourceRef 必须带 citation——诊断反馈回流的反查入口，缺了它 boost 到不了 chunk
        assertThat(e.sourceRef()).contains("【来源：K8s故障排查手册.md");
        assertThat(e.relevanceScore()).isEqualTo(0.91);
        assertThat(e.content()).containsKey("hits");
        // chunkId 随证据落库：有 id 的切片带上，无 id 的不编造（诊断反馈回流的精确入口）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) e.content().get("hits");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("chunkId", 42L);
        assertThat(items.get(1)).doesNotContainKey("chunkId");
    }
}