package com.devops.agent.application.runtime;

import com.devops.agent.domain.memory.SessionSummary;
import com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository;
import com.devops.agent.infrastructure.persistence.repo.SessionSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冷归档 {@code contentScope} 的据实标注契约（B-2）。
 *
 * <h3>为什么单独测这个字段</h3>
 * {@code contentScope} 是归档文件的<b>自述</b>——使用者（合规审计、
 * 模型评测取数）靠它判断「这份归档能不能当完整记录用」。
 *
 * <p>补全原文之前它恒为 {@code SUMMARY_ONLY}，这是诚实的。
 * 本轮加了原文后，最容易犯的错是<b>把它写死成 {@code FULL_TRANSCRIPT}</b>——
 * 而<b>存量会话</b>（改动上线前产生的）根本没有原文记录，
 * 取出来是空列表。标成 FULL 会让使用者以为「这个会话只聊了 0 轮」，
 * 而实际是原文从未被记录过。<b>这比老老实实标 SUMMARY_ONLY 更误导</b>：
 * 前者会让人得出错误结论，后者只是信息少。</p>
 *
 * <h3>为什么这条测试是必要的</h3>
 * 注入验证时把它写死成 {@code FULL_TRANSCRIPT}，
 * 当时 CI <b>照常通过</b>——归档逻辑一行测试都没有。
 * 而这个字段一旦标错，错误的归档文件会持续产出，
 * 且没有任何机制会发现（JSON 结构完全合法）。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("冷归档 contentScope 据实标注")
class ColdMemoryArchiveScopeTest {

    private SessionSummaryRepository summaryRepo;
    private ConversationTurnRepository turnRepo;
    private MinioClient minio;
    private ColdMemoryArchiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        summaryRepo = mock(SessionSummaryRepository.class);
        turnRepo = mock(ConversationTurnRepository.class);
        minio = mock(MinioClient.class);
        scheduler = new ColdMemoryArchiveScheduler(
                summaryRepo, minio, new ObjectMapper(), turnRepo);
        // archiveBucket 是 @Value 注入的，直接 new 时为 null，
        // PutObjectArgs.builder().bucket(null) 会抛
        // 「bucket name must not be null」——那是夹具缺失，
        // 与被测的 contentScope 逻辑无关，却会让整组用例以无关原因失败。
        // 本地无法跑 JUnit，这类问题只能靠先读 @Value 字段清单来避免
        org.springframework.test.util.ReflectionTestUtils.setField(
                scheduler, "archiveBucket", "test-archive-bucket");
    }

    private SessionSummary summary(String sessionId) {
        SessionSummary s = new SessionSummary();
        s.setSessionId(sessionId);
        s.setTraceId("T1");
        s.setSummary("摘要");
        s.setTurnCount(3);
        s.setCreateTime(LocalDateTime.now().minusDays(100));
        return s;
    }

    /** 反射调 private archiveOne，并捕获真正上传的 JSON */
    private Map<String, Object> archiveAndCapture(SessionSummary s) throws Exception {
        Method m = ColdMemoryArchiveScheduler.class
                .getDeclaredMethod("archiveOne", SessionSummary.class);
        m.setAccessible(true);
        m.invoke(scheduler, s);

        ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(cap.capture());
        try (InputStream in = cap.getValue().stream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(json, LinkedHashMap.class);
            return parsed;
        }
    }

    @Test
    @DisplayName("有原文时标 FULL_TRANSCRIPT，并带上逐轮内容")
    void withTranscriptMarksFull() throws Exception {
        when(turnRepo.findBySession("S1")).thenReturn(List.of(
                Map.of("turn_seq", 1, "user_query", "Pod 起不来", "ai_answer", "看事件日志"),
                Map.of("turn_seq", 2, "user_query", "还是不行", "ai_answer", "查资源配额")));

        Map<String, Object> payload = archiveAndCapture(summary("S1"));

        assertThat(payload.get("contentScope")).isEqualTo("FULL_TRANSCRIPT");
        assertThat(payload.get("transcriptTurnCount")).isEqualTo(2);
        assertThat(payload.get("transcript").toString()).contains("Pod 起不来");
    }

    @Test
    @DisplayName("无原文时仍标 SUMMARY_ONLY——存量会话不得被误标为完整记录")
    void withoutTranscriptStaysSummaryOnly() throws Exception {
        // ── 本类最重要的一条 ──────────────────────────────
        // 改动上线前产生的会话没有原文。写死 FULL_TRANSCRIPT 会让
        // 使用者以为「这个会话只聊了 0 轮」，而实际是原文从未被记录——
        // 得出错误结论比信息缺失更糟
        when(turnRepo.findBySession("S-old")).thenReturn(List.of());

        Map<String, Object> payload = archiveAndCapture(summary("S-old"));

        assertThat(payload.get("contentScope"))
                .as("空原文必须如实标 SUMMARY_ONLY，不能一律写 FULL_TRANSCRIPT")
                .isEqualTo("SUMMARY_ONLY");
        assertThat(payload.get("transcriptTurnCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("说明文字随 scope 变化，不是固定一句话")
    void noteMatchesScope() throws Exception {
        // 说明文字是给人读的。scope 变了而说明没变，
        // 等于归档文件自己前后矛盾
        when(turnRepo.findBySession("S1")).thenReturn(List.of(
                Map.of("turn_seq", 1, "user_query", "q", "ai_answer", "a")));
        assertThat(archiveAndCapture(summary("S1")).get("contentScopeNote").toString())
                .contains("原文");

        setUp();   // 重置 mock，避免 verify 撞上一次调用
        when(turnRepo.findBySession("S2")).thenReturn(List.of());
        assertThat(archiveAndCapture(summary("S2")).get("contentScopeNote").toString())
                .contains("无原文记录");
    }

    @Test
    @DisplayName("摘要字段仍然齐备——补原文不能挤掉原有内容")
    void summaryFieldsStillPresent() throws Exception {
        when(turnRepo.findBySession(anyString())).thenReturn(List.of());

        Map<String, Object> payload = archiveAndCapture(summary("S1"));

        assertThat(payload).containsKeys("sessionId", "traceId", "summary",
                "turnCount", "finalState", "archivedAt");
    }

    @Test
    @DisplayName("原文查询失败时按无原文处理，归档不中断")
    void transcriptQueryFailureDegradesGracefully() throws Exception {
        // 仓储层查询失败会返回空列表并记 ERROR（已在那边处理）。
        // 这里验证归档侧不会因此崩掉——一份少了原文的归档，
        // 好过完全没有归档
        when(turnRepo.findBySession(anyString())).thenReturn(List.of());

        Map<String, Object> payload = archiveAndCapture(summary("S1"));

        assertThat(payload.get("contentScope")).isEqualTo("SUMMARY_ONLY");
        verify(minio).putObject(any(PutObjectArgs.class));
    }
}
