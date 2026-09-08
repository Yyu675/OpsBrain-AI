package com.devops.agent.integration;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L2 告警链路<b>端到端集成测试</b>。
 *
 * <h3>为什么需要它：这是项目此前完全空白的一层</h3>
 * 核查时确认过，测试金字塔缺了最上面一层：
 * <pre>
 *   E2E / 联调        0        ← 本类要填的
 *   契约测试        129 例      ← Controller 层，Service 全 mock
 *   单元测试        469 + 1074
 * </pre>
 * 单元测试用 mock 隔离了依赖，契约测试只验证 HTTP 层。
 * <b>「Alertmanager 推一条告警 → 后端去重 → 落库 → 自动建单 → 列表查得到」
 * 这条完整链路，从未被任何测试跑通过。</b>
 *
 * <p>而这恰恰是最容易出问题的地方——它跨了 Controller、Guard、Service、
 * 两个 Repository、真实的 PostgreSQL 唯一索引，
 * 每一层单测都绿，拼起来仍可能不通（如唯一索引与应用层去重逻辑打架）。</p>
 *
 * <h3>为什么用 @SpringBootTest 而不是 Testcontainers</h3>
 * CI 已经起了 PostgreSQL + Redis 两个 service 容器（见 .github/workflows/ci.yml），
 * 端口与 dev profile 对齐，且建表脚本已在 CI 步骤中执行。
 * 再引 Testcontainers 等于在容器里再套一层容器：
 * 多一份依赖、多一次镜像拉取、CI 慢一倍，换来的隔离性在这里并无额外收益。
 *
 * <p><b>代价是本类依赖真实数据库</b>，因此每个用例都自带清理（见 {@link #cleanUp()}），
 * 且用随机 alertname 保证并行/重跑时互不干扰。</p>
 *
 * <h3>数据清理策略：只删自己造的</h3>
 * 不用 {@code @Transactional} 回滚——本链路内部有独立事务边界
 * （AlertService 建单走 TicketService），回滚会让测试看不到真实的提交结果。
 * 改为按本次运行的唯一标识精确删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("L2 告警链路端到端（Webhook → 去重 → 建单 → 可查）")
class AlertWebhookChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private DevOpsTicketRepository ticketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次运行的唯一标识，混进 alertname 与 service，避免与既有数据或并行用例冲突 */
    private final String runId = "IT" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // 先删工单（告警表存的是 ticket_id 字符串，无外键，但顺序保持语义清晰）
        jdbcTemplate.update(
                "DELETE FROM sys_devops_ticket WHERE title LIKE ?", "%" + runId + "%");
        jdbcTemplate.update(
                "DELETE FROM sys_alert WHERE alert_name LIKE ?", "%" + runId + "%");
    }

    // ==================== 请求构造 ====================

    private Map<String, Object> alertPayload(String status, String alertName,
                                             String service, String severity,
                                             Map<String, String> extraLabels) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", alertName);
        labels.put("service", service);
        labels.put("severity", severity);
        labels.put("module", "POD");
        if (extraLabels != null) labels.putAll(extraLabels);

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("status", status);
        alert.put("labels", labels);
        alert.put("annotations", Map.of("description", "集成测试告警 " + runId));
        alert.put("startsAt", "2026-08-25T09:00:00Z");
        alert.put("fingerprint", "fp-" + alertName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", "opsbrain-webhook");
        body.put("status", status);
        body.put("alerts", List.of(alert));
        return body;
    }

    private void postWebhook(Map<String, Object> body) throws Exception {
        mockMvc.perform(post("/api/v1/alerts/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                // 契约：无论内部处理结果如何都必须 200——
                // Alertmanager 对非 200 会重试，返回错误会造成告警反复推送
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private Optional<Alert> findByName(String alertName) {
        List<Alert> all = alertRepository.findPage(null, null, 1, 200);
        return all.stream().filter(a -> alertName.equals(a.getAlertName())).findFirst();
    }

    // ==================================================================

    @Test
    @DisplayName("完整链路：推送 → 告警落库 → 自动建单 → 工单可查且字段正确")
    void firingAlertPersistsAndCreatesTicket() throws Exception {
        String alertName = "PodCrashLoop-" + runId;

        postWebhook(alertPayload("firing", alertName, "order-svc", "critical", null));

        // 1) 告警确实落库了
        Optional<Alert> saved = findByName(alertName);
        assertThat(saved).as("告警必须落库——这是告警可见性铁律").isPresent();

        Alert alert = saved.get();
        assertThat(alert.getStatus()).isEqualTo("FIRING");
        assertThat(alert.getSource()).isEqualTo("prometheus");
        assertThat(alert.getLevel()).isEqualTo("P0");   // critical → P0
        assertThat(alert.getModule()).isEqualTo("POD");
        assertThat(alert.getOccurrenceCount()).isEqualTo(1);
        assertThat(alert.getDedupKey()).as("去重键必须生成，否则重复推送会反复建单").isNotBlank();

        // 2) 自动建单，且工单号回填到了告警上
        //    不回填的话，列表页与详情页的「关联工单」永远显示「—」，
        //    运维看到告警却找不到对应工单，自动建单等于白做
        assertThat(alert.getTicketId()).as("工单号必须回填，否则告警与工单彻底失联").isNotBlank();

        // 3) 工单真的存在，且优先级随告警级别分级（B0 改造：P0 不再塌缩成 HIGH）
        DevOpsTicket ticket = ticketRepository.findById(alert.getTicketId());
        assertThat(ticket).as("回填的工单号必须能查到真实工单").isNotNull();
        assertThat(ticket.getPriority()).isEqualTo("P0");
        assertThat(ticket.getTitle()).contains(alertName);
        assertThat(ticket.getCreator()).isEqualTo("alert-bot");
    }

    @Test
    @DisplayName("重复推送同一告警：次数递增，绝不重复建单")
    void duplicateAlertIncrementsInsteadOfCreatingSecondTicket() throws Exception {
        String alertName = "HighMemory-" + runId;
        Map<String, Object> payload = alertPayload(
                "firing", alertName, "pay-svc", "warning", Map.of("instance", "node-7"));

        postWebhook(payload);
        String firstTicket = findByName(alertName).orElseThrow().getTicketId();

        // 同一告警再推两次（Alertmanager 的 repeat_interval 会真实产生这种重复）
        postWebhook(payload);
        postWebhook(payload);

        Alert alert = findByName(alertName).orElseThrow();
        assertThat(alert.getOccurrenceCount())
                .as("重复推送应递增次数").isEqualTo(3);

        // 关键：工单号没变——一次持续故障只该有一张工单，
        // 否则 Alertmanager 每隔 repeat_interval 就给值班人塞一张新单
        assertThat(alert.getTicketId()).isEqualTo(firstTicket);

        long ticketCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_devops_ticket WHERE title LIKE ?",
                Long.class, "%" + alertName + "%");
        assertThat(ticketCount).as("持续故障只应产生一张工单").isEqualTo(1L);
    }

    @Test
    @DisplayName("数据库唯一索引与应用层去重一致：活跃告警同 dedup_key 只有一条")
    void uniqueIndexAgreesWithApplicationDedup() throws Exception {
        // sql/init.sql 里有：
        //   CREATE UNIQUE INDEX uk_alert_active_dedup ON sys_alert (dedup_key)
        //     WHERE status IN ('FIRING','ACKNOWLEDGED')
        // 应用层去重若与它不一致，第二次插入会直接抛约束冲突。
        // 这正是单元测试（mock 掉 Repository）永远发现不了的那类问题
        String alertName = "DiskFull-" + runId;
        Map<String, Object> payload = alertPayload("firing", alertName, "db-svc", "P2", null);

        postWebhook(payload);
        postWebhook(payload);

        String dedupKey = findByName(alertName).orElseThrow().getDedupKey();
        Long activeSameKey = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE dedup_key = ? "
                        + "AND status IN ('FIRING','ACKNOWLEDGED')",
                Long.class, dedupKey);

        assertThat(activeSameKey)
                .as("唯一索引要求同 dedup_key 的活跃告警至多一条").isEqualTo(1L);
    }

    @Test
    @DisplayName("恢复推送把告警标记 RESOLVED，并释放去重键供下次故障使用")
    void resolvedAlertFreesDedupKey() throws Exception {
        String alertName = "NodeDown-" + runId;
        Map<String, Object> firing = alertPayload("firing", alertName, "infra", "critical", null);

        postWebhook(firing);
        Alert first = findByName(alertName).orElseThrow();
        String dedupKey = first.getDedupKey();

        // Alertmanager 在告警恢复时推 status=resolved
        postWebhook(alertPayload("resolved", alertName, "infra", "critical", null));

        Long stillActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE dedup_key = ? "
                        + "AND status IN ('FIRING','ACKNOWLEDGED')",
                Long.class, dedupKey);
        assertThat(stillActive).as("恢复后不应再有活跃告警").isEqualTo(0L);

        // 同一故障再次发生：因为去重键已释放，应当作为新告警重新入库并建单，
        // 而不是被当成「重复」丢掉——否则故障复发时没有人会收到通知
        postWebhook(firing);

        Long activeAgain = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE dedup_key = ? "
                        + "AND status IN ('FIRING','ACKNOWLEDGED')",
                Long.class, dedupKey);
        assertThat(activeAgain).as("故障复发必须能重新告警").isEqualTo(1L);
    }

    @Test
    @DisplayName("同一批次里一条脏数据不影响其余告警入库")
    void badAlertInBatchDoesNotBlockOthers() throws Exception {
        String goodName = "GoodAlert-" + runId;

        Map<String, String> badLabels = new LinkedHashMap<>();
        badLabels.put("service", "x");   // 故意缺 alertname

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("status", "firing");
        bad.put("labels", badLabels);
        bad.put("startsAt", "2026-08-25T09:00:00Z");

        Map<String, Object> good = new LinkedHashMap<>();
        good.put("status", "firing");
        good.put("labels", Map.of("alertname", goodName, "service", "api",
                "severity", "warning", "module", "POD"));
        good.put("annotations", Map.of("description", "集成测试 " + runId));
        good.put("startsAt", "2026-08-25T09:00:00Z");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", "opsbrain-webhook");
        body.put("status", "firing");
        body.put("alerts", List.of(bad, good));

        postWebhook(body);

        assertThat(findByName(goodName))
                .as("脏数据不能让同批次的合法告警一起丢失").isPresent();
    }

    @Test
    @DisplayName("空负载返回 200 —— 返回非 200 会让 Alertmanager 无限重推")
    void emptyPayloadStillReturns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", "opsbrain-webhook");
        body.put("status", "firing");
        body.put("alerts", List.of());

        postWebhook(body);
    }
}
