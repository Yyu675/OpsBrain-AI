package com.devops.agent.infrastructure.health;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两个健康指示器的单元测试（P2 自身可观测性）。
 *
 * <h3>为什么这两个类必须有测试</h3>
 * 它们是「系统自己有没有坏」的判据。判错的两个方向后果都很重：
 *
 * <ul>
 *   <li><b>该 DOWN 却报 UP</b>——模型或向量库已经挂了，探针却说健康。
 *       K8s 不会重启 Pod、告警不会触发，运维看着绿灯而 AI 全废；</li>
 *   <li><b>该 UP 却报 DOWN</b>——更糟。{@code /actuator/health} 是 K8s 探针端点，
 *       误报 DOWN 会让存活探针把**本来正常的 Pod 反复重启**，
 *       把一次局部抖动放大成滚动故障。</li>
 * </ul>
 *
 * <h3>为什么用纯 Mockito 而不是 @SpringBootTest</h3>
 * 这两个类没有任何 Spring 语义可测（不涉及事务、代理、配置绑定），
 * 只有「依赖返回什么 → 我判成什么」这一层纯逻辑。
 * 用切片测试要拉起完整上下文、还得连真库，既慢又把「向量库不可达」
 * 这类关键分支变得无法构造——而那恰恰是最该覆盖的路径。
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
@DisplayName("健康指示器（P2 自身可观测性）")
class HealthIndicatorsTest {

    // ==================================================================
    // LLM 可达性
    // ==================================================================

    @Nested
    @DisplayName("LlmHealthIndicator")
    class Llm {

        @Test
        @DisplayName("模型正常应答 → UP，并标出模型名供排查")
        void upWhenModelReplies() {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyString())).thenReturn("pong");

            Health h = new LlmHealthIndicator(model).health();

            assertEquals(Status.UP, h.getStatus());
            assertEquals("deepseek", h.getDetails().get("model"));
        }

        @Test
        @DisplayName("模型返回空串 → DOWN：连得上不等于能用")
        void downWhenReplyBlank() {
            // 鉴权失败/配额耗尽时，部分网关会返回 200 + 空 body。
            // 只判「没抛异常」会把这种情况误报成健康
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyString())).thenReturn("   ");

            Health h = new LlmHealthIndicator(model).health();

            assertEquals(Status.DOWN, h.getStatus());
            assertTrue(String.valueOf(h.getDetails().get("reason")).contains("空响应"));
        }

        @Test
        @DisplayName("模型返回 null → DOWN，不得抛 NPE 把探针打崩")
        void downWhenReplyNull() {
            // 探针自身抛异常会让 /actuator/health 返回 500，
            // 那时 K8s 看到的不是「DOWN」而是「端点坏了」，两者处置方式不同
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyString())).thenReturn(null);

            Health h = new LlmHealthIndicator(model).health();

            assertEquals(Status.DOWN, h.getStatus());
        }

        @Test
        @DisplayName("调用抛异常 → DOWN 并带异常类型，不向外传播")
        void downWhenModelThrows() {
            // 超时/网络不通是最常见的真实故障。异常必须被吞掉转成 DOWN，
            // 否则整个 /actuator/health 端点会 500
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyString())).thenThrow(new RuntimeException("connect timed out"));

            Health h = new LlmHealthIndicator(model).health();

            assertEquals(Status.DOWN, h.getStatus());
            // 带上异常类型：运维据此区分「网络不通」与「鉴权失败」
            assertTrue(String.valueOf(h.getDetails().get("reason")).contains("RuntimeException"));
            assertEquals("connect timed out", h.getDetails().get("message"));
        }

        @Test
        @DisplayName("异常 message 为 null 时详情为空串，不显示字面量 null")
        void nullMessageBecomesEmptyString() {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyString())).thenThrow(new RuntimeException());

            Health h = new LlmHealthIndicator(model).health();

            assertEquals("", h.getDetails().get("message"));
        }
    }

    // ==================================================================
    // 向量库可达性
    // ==================================================================

    @Nested
    @DisplayName("VectorStoreHealthIndicator")
    class VectorStore {

        private JdbcTemplate jdbcOk(int selectOne, int extensionCount) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(selectOne);
            when(jdbc.queryForObject(
                    eq("SELECT count(*) FROM pg_extension WHERE extname = 'vector'"),
                    eq(Integer.class))).thenReturn(extensionCount);
            return jdbc;
        }

        @Test
        @DisplayName("连接正常且 pgvector 已装 → UP")
        void upWhenReachableAndExtensionInstalled() {
            Health h = new VectorStoreHealthIndicator(jdbcOk(1, 1)).health();

            assertEquals(Status.UP, h.getStatus());
            assertEquals("available", h.getDetails().get("pgvector"));
        }

        @Test
        @DisplayName("库连得上但 pgvector 未安装 → DOWN")
        void downWhenExtensionMissing() {
            // 这是最容易漏判的一种：SELECT 1 成功、连接池健康，
            // 但 RAG 检索一定失败。只探连通性会把它报成 UP，
            // 于是「AI 全部回答『知识库无相关文档』」而监控一片绿
            Health h = new VectorStoreHealthIndicator(jdbcOk(1, 0)).health();

            assertEquals(Status.DOWN, h.getStatus());
            assertTrue(String.valueOf(h.getDetails().get("reason")).contains("pgvector"));
        }

        @Test
        @DisplayName("SELECT 1 返回意外值 → DOWN")
        void downWhenProbeQueryUnexpected() {
            Health h = new VectorStoreHealthIndicator(jdbcOk(0, 1)).health();
            assertEquals(Status.DOWN, h.getStatus());
        }

        @Test
        @DisplayName("SELECT 1 返回 null → DOWN，不得抛 NPE")
        void downWhenProbeQueryNull() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(null);
            when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);

            Health h = new VectorStoreHealthIndicator(jdbc).health();

            assertEquals(Status.DOWN, h.getStatus());
        }

        @Test
        @DisplayName("数据库不可达 → DOWN 并带异常类型，不向外传播")
        void downWhenDatabaseUnreachable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            Health h = new VectorStoreHealthIndicator(jdbc).health();

            assertEquals(Status.DOWN, h.getStatus());
            assertNotNull(h.getDetails().get("reason"));
            assertTrue(String.valueOf(h.getDetails().get("reason"))
                    .contains("DataAccessResourceFailureException"));
        }
    }
}
