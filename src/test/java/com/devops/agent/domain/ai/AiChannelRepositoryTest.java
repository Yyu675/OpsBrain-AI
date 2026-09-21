package com.devops.agent.domain.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelRepository} 纯单元测试（阶段A：渠道配置落库/查询）。
 *
 * <h3>为什么锁语句而非连真库</h3>
 * 本机 Testcontainers 被 Docker Desktop npipe 缺陷挡死（批 72），
 * {@code @SpringBootTest} 连真 PG 不可靠。而 {@code AiChannelRepository}
 * 的全部风险集中在「SQL 语句与参数对不对」——用 Mockito mock
 * {@code JdbcTemplate} 做<b>语句级契约断言</b>，与本项目
 * {@code OrderByInjectionContractTest} 同一策略，零容器、零环境依赖。
 *
 * <h3>覆盖重点</h3>
 * <ul>
 *   <li><b>P0 只写「启动镜像」幂等 upsert</b>，9 列参数顺序与语句一一对应；</li>
 *   <li><b>status 缺省落 ACTIVE</b>（镜像构造可不显式给状态）；</li>
 *   <li><b>embedding 的 model/dimension 不串到 chat 专用列</b>；</li>
 *   <li>RowMapper 把 ResultSet 行映射回实体（含 apiKeyEnc 承载、dimension null 语义）。</li>
 * </ul>
 *
 * <p>安全边界：SQL 注入是 JDBC 预编译占位符的责任，非本层；本测试只保证
 * 「调用方传参顺序与语句列顺序一致」与「行映射不错位」两层契约。</p>
 *
 * @author OpsBrain AI
 */
class AiChannelRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AiChannelRepository repo = new AiChannelRepository(jdbc);

    @Nested
    @DisplayName("upsert（启动镜像幂等写）")
    class Upsert {

        /** 捕获一次 {@code update(String, Object...)} 的 SQL 与参数数组。 */
        private org.mockito.ArgumentCaptor<Object[]> argsCaptor() {
            return org.mockito.ArgumentCaptor.forClass(Object[].class);
        }

        @Test
        @DisplayName("chat：13 列参数顺序与语句占位符一一对应（含 fallback 四列）")
        void upsertChatBindsAllNineColumnsInOrder() {
            AiChannel chat = new AiChannel("chat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "enc:v1:AbC", "sk-ws-****7890", "qwen-turbo", "deepseek-v4-flash-0731",
                    null, null, "ACTIVE", null);

            repo.upsert(chat);

            org.mockito.ArgumentCaptor<String> sqlCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<Object[]> argsCaptor = argsCaptor();
            verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());

            // SQL 必须带幂等 ON CONFLICT——P0 镜像启动反复跑不产生重复/击穿
            assertThat(sqlCaptor.getValue())
                    .contains("INSERT INTO sys_ai_channel")
                    .contains("ON CONFLICT (channel_key) DO UPDATE");
            // 13 列参数顺序：channel_key, base_url, api_key_enc, key_masked,
            //   turbo_model, reasoner_model, model, dimension, status,
            //   fallback_base_url, fallback_model, fallback_api_key_enc, fallback_key_masked
            assertThat(argsCaptor.getValue()).containsExactly(
                    "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "enc:v1:AbC", "sk-ws-****7890", "qwen-turbo", "deepseek-v4-flash-0731",
                    null, null, "ACTIVE", null, null, null, null);
        }

        @Test
        @DisplayName("status 为 null 时回落 ACTIVE")
        void statusDefaultsToActive() {
            AiChannel c = new AiChannel("embedding", "https://x", "enc:v1:X",
                    "sk-ws-****1", null, null, "qwen3.7-text-embedding", 1536, null, null);

            repo.upsert(c);

            org.mockito.ArgumentCaptor<Object[]> argsCaptor = argsCaptor();
            verify(jdbc).update(org.mockito.ArgumentMatchers.anyString(), argsCaptor.capture());

            assertThat(argsCaptor.getValue()).containsExactly(
                    "embedding", "https://x", "enc:v1:X", "sk-ws-****1",
                    null, null, "qwen3.7-text-embedding", 1536, "ACTIVE", null, null, null, null);
        }

        @Test
        @DisplayName("embedding：model 落 model 列、dimension 落 dimension 列（不串到 chat 专用列）")
        void embeddingModelAndDimensionDoNotBleedIntoChatColumns() {
            AiChannel em = new AiChannel("embedding", "https://x", null, null,
                    null, null, "qwen3.7-text-embedding", 1536, "ACTIVE", null);

            repo.upsert(em);

            org.mockito.ArgumentCaptor<Object[]> argsCaptor = argsCaptor();
            verify(jdbc).update(org.mockito.ArgumentMatchers.anyString(), argsCaptor.capture());

            // turbo/reasoner 列为 null（chat 专用），model/dimension 各自落位
            assertThat(argsCaptor.getValue()).containsExactly(
                    "embedding", "https://x", null, null,
                    null, null, "qwen3.7-text-embedding", 1536, "ACTIVE", null, null, null, null);
        }

        @Test
        @DisplayName("备用模型四列随 upsert 落库（方案 A）")
        void upsertPersistsFallbackColumns() {
            AiChannel chat = new AiChannel("chat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "enc:v1:AbC", "sk-ws-****7890", "qwen-turbo", "deepseek-v4-flash-0731",
                    null, null, "ACTIVE", null,
                    "https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:Fb", "sk-fb-****5678");

            repo.upsert(chat);

            org.mockito.ArgumentCaptor<String> sqlCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<Object[]> argsCaptor = argsCaptor();
            verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());

            assertThat(sqlCaptor.getValue()).contains("fallback_base_url").contains("fallback_api_key_enc");
            // 末 4 位是 fallback 列，顺序与 SQL 一致
            assertThat(argsCaptor.getValue()).endsWith(
                    "https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:Fb", "sk-fb-****5678");
        }
    }

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("findByKey 按渠道键查询，命中多条只取首条（stream.findFirst）")
        void findByKeyQueriesByChannelKey() {
            String sql = "SELECT channel_key, base_url, api_key_enc, key_masked, turbo_model, reasoner_model, model, dimension, status, updated_at, fallback_base_url, fallback_model, fallback_api_key_enc, fallback_key_masked FROM sys_ai_channel WHERE channel_key = ?";
            AiChannel row = mock(AiChannel.class);
            when(jdbc.query(eq(sql),
                    org.mockito.ArgumentMatchers.<RowMapper<AiChannel>>any(),
                    eq("chat")))
                    .thenReturn(List.of(row));
            when(jdbc.query(eq(sql),
                    org.mockito.ArgumentMatchers.<RowMapper<AiChannel>>any(),
                    eq("missing")))
                    .thenReturn(List.of());

            assertThat(repo.findByKey("chat")).isEqualTo(Optional.of(row));
            assertThat(repo.findByKey("missing")).isEmpty();
        }

        @Test
        @DisplayName("updateStatus 只更新状态与时间戳，不触碰其它列")
        void updateStatusOnlyTouchesStatus() {
            repo.updateStatus("chat", "DISABLED");

            verify(jdbc).update(
                    "UPDATE sys_ai_channel SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE channel_key = ?",
                    "DISABLED", "chat");
        }

        @Test
        @DisplayName("updateMerged：整行更新（P1 权威源），13 值 + WHERE 键一一对应")
        void updateMergedWritesAllColumnsWithWhereKey() {
            AiChannel merged = new AiChannel("chat", "https://new.example.com/v1",
                    "enc:v1:NewKey", "sk-ws-****9999",
                    "qwen-turbo-v2", "deepseek-v4-flash-0731", null, null,
                    "ACTIVE", null,
                    "https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:Fb", "sk-fb-****5678");

            repo.updateMerged(merged);

            org.mockito.ArgumentCaptor<String> sqlCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<Object[]> argsCaptor =
                    org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());

            assertThat(sqlCaptor.getValue())
                    .as("P1 编辑走 UPDATE 而非 INSERT——只改既有行，渠道不存在由影响行数=0 判 404")
                    .startsWith("UPDATE sys_ai_channel")
                    .contains("WHERE channel_key = ?");
            // 参数顺序：9 主列 + 4 fallback 列 + WHERE 键（updated_at 由 CURRENT_TIMESTAMP 占位）
            assertThat(argsCaptor.getValue()).containsExactly(
                    "https://new.example.com/v1", "enc:v1:NewKey", "sk-ws-****9999",
                    "qwen-turbo-v2", "deepseek-v4-flash-0731", null, null,
                    "ACTIVE",
                    "https://api.deepseek.com/v1", "deepseek-chat", "enc:v1:Fb", "sk-fb-****5678",
                    "chat");
        }
    }

    @Nested
    @DisplayName("RowMapper 行映射")
    class RowMapping {

        /**
         * 反射取私有静态 {@code MAPPER}，用 mock ResultSet 驱动真实映射。
         * 验证「DB 列 → 实体字段」无错位——这是仓储层最常见的隐性 bug。
         */
        private RowMapper<AiChannel> mapper() throws Exception {
            Field f = AiChannelRepository.class.getDeclaredField("MAPPER");
            f.setAccessible(true);
            return (RowMapper<AiChannel>) f.get(null);
        }

        @Test
        @DisplayName("全列映射回实体（apiKeyEnc 承载密文、maskedKey 脱敏、维度/时间各就位）")
        void mapsAllColumns() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("channel_key")).thenReturn("chat");
            when(rs.getString("base_url")).thenReturn("https://x");
            when(rs.getString("api_key_enc")).thenReturn("enc:v1:Z");
            when(rs.getString("key_masked")).thenReturn("sk-ws-****99");
            when(rs.getString("turbo_model")).thenReturn("qwen-turbo");
            when(rs.getString("reasoner_model")).thenReturn("r-model");
            when(rs.getString("model")).thenReturn("m");
            when(rs.getObject("dimension")).thenReturn(1536);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getTimestamp("updated_at"))
                    .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 20, 10, 0)));

            AiChannel row = mapper().mapRow(rs, 0);

            assertThat(row.channelKey()).isEqualTo("chat");
            assertThat(row.apiKeyEnc()).isEqualTo("enc:v1:Z"); // 仓储承载密文是实情，API 展示另行脱敏
            assertThat(row.maskedKey()).isEqualTo("sk-ws-****99");
            assertThat(row.dimension()).isEqualTo(1536);
            assertThat(row.status()).isEqualTo("ACTIVE");
            assertThat(row.updatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
        }

        @Test
        @DisplayName("dimension 为 NULL 时读为 null 而非 0（reranker 无维度）")
        void dimensionNullReadsAsNull() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("channel_key")).thenReturn("reranker");
            when(rs.getString("base_url")).thenReturn("https://x");
            when(rs.getString("api_key_enc")).thenReturn((String) null);
            when(rs.getString("key_masked")).thenReturn((String) null);
            when(rs.getString("turbo_model")).thenReturn((String) null);
            when(rs.getString("reasoner_model")).thenReturn((String) null);
            when(rs.getString("model")).thenReturn("qwen3-reranker-8b");
            when(rs.getObject("dimension")).thenReturn(null);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getTimestamp("updated_at")).thenReturn(null);

            AiChannel row = mapper().mapRow(rs, 0);
            assertThat(row.channelKey()).isEqualTo("reranker");
            assertThat(row.dimension()).isNull();
            assertThat(row.maskedKey()).isNull();
            assertThat(row.updatedAt()).isNull();
        }
    }
}
