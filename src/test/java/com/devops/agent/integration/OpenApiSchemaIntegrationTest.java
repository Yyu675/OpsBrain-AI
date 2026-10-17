package com.devops.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 契约的<b>可消费性</b>验证（P0-2 收尾）。
 *
 * <h3>为什么必须有这一步</h3>
 * <p>
 * P0-2 第二步已经把工单与知识库的列表端点从 {@code Map<String,Object>}
 * 换成了 record。但在此之前<b>从未验证过生成的 schema 到底长什么样</b>——
 * 只是假设「换了 record，OpenAPI 就能产出有效契约」。
 * </p>
 * <p>
 * 继续按模块改下去，万一某类 schema 形态（嵌套 record、
 * {@code List<ListItem>}、泛型包装 {@code ApiResponse<T>}）生成的结果
 * 前端工具消费不了，就是<b>多个模块一起返工</b>。
 * 这个测试把「假设」变成「验证」，投入小、能立刻兑现前两轮的成果。
 * </p>
 *
 * <h3>它同时也是 openapi-typescript 的前置检查</h3>
 * <p>
 * 前端要用 {@code openapi-typescript} 生成 TS 类型，前提是文档里
 * 这些类型确实有 {@code properties}。若只有
 * {@code additionalProperties: true}，生成出来的就是
 * {@code Record<string, unknown>}——与手写 {@code any} 无异，
 * P0-2 的投入等于白做。
 * </p>
 *
 * <h3>为什么用 @SpringBootTest 而不是切片</h3>
 * <p>
 * springdoc 的文档生成依赖完整的 {@code RequestMappingHandlerMapping}
 * 与全部 {@code @RestController} bean，{@code @WebMvcTest} 切片只加载
 * 指定控制器，扫不出全量端点。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OpenAPI 契约可消费性")
class OpenApiSchemaIntegrationTest {

    /** 文档拉取一次即可，多个用例共享，避免重复生成拖慢测试 */
    private static JsonNode apiDocs;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void resetCache() {
        apiDocs = null;
    }

    private JsonNode docs() throws Exception {
        if (apiDocs == null) {
            String json = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            apiDocs = objectMapper.readTree(json);
        }
        return apiDocs;
    }

    @Nested
    @DisplayName("文档本身可用")
    class DocumentAvailable {

        @Test
        @DisplayName("/v3/api-docs 返回合法 OpenAPI 3 文档")
        void apiDocsIsValidOpenApi() throws Exception {
            JsonNode root = docs();

            assertThat(root.path("openapi").asText())
                    .as("缺少 openapi 版本字段，说明生成的不是合法 OpenAPI 文档，"
                            + "openapi-typescript 会直接拒绝解析")
                    .startsWith("3.");
            assertThat(root.path("paths").isObject())
                    .as("缺少 paths 节点")
                    .isTrue();
        }

        @Test
        @DisplayName("扫到了业务端点 —— 配置的 packages-to-scan 生效")
        void businessEndpointsAreScanned() throws Exception {
            JsonNode paths = docs().path("paths");

            // 数量下限而非精确值：端点会随开发增减，写死会变成维护负担。
            // 但过少说明 packages-to-scan 配错了，那时文档等于空壳
            assertThat(paths.size())
                    .as("扫到的端点数过少（%d），检查 springdoc.packages-to-scan 配置。"
                            + "本项目有 130 个端点", paths.size())
                    .isGreaterThan(50);

            assertThat(paths.has("/api/v1/tickets"))
                    .as("工单列表端点未出现在文档里").isTrue();
            assertThat(paths.has("/api/v1/knowledge/docs"))
                    .as("知识库列表端点未出现在文档里").isTrue();
        }
    }

    @Nested
    @DisplayName("P0-2 换成 record 的端点，schema 必须有具体字段")
    class RecordSchemasAreConcrete {

        @Test
        @DisplayName("TicketPage 生成了完整字段，而不是 additionalProperties")
        void ticketPageHasProperties() throws Exception {
            JsonNode schema = componentSchema("TicketPage");

            assertThat(schema)
                    .as("components.schemas 里找不到 TicketPage。"
                            + "P0-2 第二步把 getTickets 换成了 record，"
                            + "若这里没有说明 springdoc 没识别到它")
                    .isNotNull();

            List<String> props = fieldNames(schema.path("properties"));
            assertThat(props)
                    .as("TicketPage 的 schema 必须含全部 5 个字段，"
                            + "前端 openapi-typescript 据此生成类型。实际：%s", props)
                    .contains("tickets", "total", "page", "size", "totalPages");
        }

        @Test
        @DisplayName("DocPage 生成了完整字段")
        void docPageHasProperties() throws Exception {
            JsonNode schema = componentSchema("DocPage");
            assertThat(schema)
                    .as("components.schemas 里找不到 DocPage").isNotNull();

            List<String> props = fieldNames(schema.path("properties"));
            assertThat(props)
                    .as("DocPage 的 schema 必须含全部 5 个字段。实际：%s", props)
                    .contains("content", "totalElements", "totalPages",
                            "currentPage", "pageSize");
        }

        @Test
        @DisplayName("嵌套的 List<ListItem> 能被正确解析为数组 + 引用")
        void nestedListItemIsResolved() throws Exception {
            // 这是本测试最有价值的一条：验证「record 里套 record 的集合」
            // 这种形态生成的 schema 是否可用。
            // 若 content 只生成 type: array 而没有 items.$ref，
            // 前端拿到的是 unknown[]，等于没有类型
            JsonNode content = componentSchema("DocPage").path("properties").path("content");

            assertThat(content.path("type").asText())
                    .as("content 应为数组类型").isEqualTo("array");
            assertThat(content.path("items").has("$ref"))
                    .as("content.items 必须是对 ListItem 的 $ref 引用。"
                            + "只有 type:array 而无 items.$ref 时，"
                            + "前端生成的类型是 unknown[]，等于没有类型")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("P0-2b 告警/治理的 record，schema 必须有具体字段")
    class StepThreeSchemasAreConcrete {

        @Test
        @DisplayName("AlertPage 生成了完整字段，而不是 additionalProperties")
        void alertPageHasProperties() throws Exception {
            JsonNode schema = componentSchema("AlertPage");
            assertThat(schema)
                    .as("components.schemas 里找不到 AlertPage。"
                            + "P0-2b 第三步把告警 listAlerts 换成了 record，"
                            + "若这里没有说明 springdoc 没识别到它")
                    .isNotNull();

            List<String> props = fieldNames(schema.path("properties"));
            assertThat(props)
                    .as("AlertPage 的 schema 必须含全部 5 个字段，"
                            + "前端 alerts.ts 据 openapi-typescript 生成类型。实际：%s", props)
                    .contains("alerts", "total", "page", "size", "totalPages");
        }

        @Test
        @DisplayName("治理 ActionPage 字段完整，且 items 是 $ref 引用的数组而非 unknown[]")
        void actionPageHasPropertiesAndRefItems() throws Exception {
            JsonNode schema = componentSchema("ActionPage");
            assertThat(schema)
                    .as("components.schemas 里找不到 ActionPage")
                    .isNotNull();

            List<String> props = fieldNames(schema.path("properties"));
            assertThat(props)
                    .as("ActionPage 的 schema 必须含全部 5 个字段。实际：%s", props)
                    .contains("items", "total", "page", "size", "totalPages");

            JsonNode items = schema.path("properties").path("items");
            assertThat(items.path("type").asText())
                    .as("items 应为数组类型").isEqualTo("array");
            assertThat(items.path("items").has("$ref"))
                    .as("items.items 必须是对 ActionAllowlistEntry 的 $ref 引用。"
                            + "只有 type:array 而无 items.$ref 时前端拿到 unknown[]，"
                            + "治理页等于没有类型保护")
                    .isTrue();
        }

        @Test
        @DisplayName("EvaluateResult 字段完整——含放行时的四个约束字段")
        void evaluateResultHasProperties() throws Exception {
            JsonNode schema = componentSchema("EvaluateResult");
            assertThat(schema)
                    .as("components.schemas 里找不到 EvaluateResult")
                    .isNotNull();

            List<String> props = fieldNames(schema.path("properties"));
            assertThat(props)
                    .as("EvaluateResult 的 schema 必须含全部 8 个字段。"
                            + "NOTE：@JsonInclude(NON_NULL) 只影响序列化时的省略，"
                            + "不影响 schema 的属性罗列。实际：%s", props)
                    .contains("actionKey", "environment", "allowed", "reason",
                            "requiresApproval", "approvalMode",
                            "blastRadiusCount", "cooldownSeconds");
        }

        @Test
        @DisplayName("批129 P0-2b: 核心弱契约端点重构为具体 record 结构")
        void batch129EndpointsHaveProperties() throws Exception {
            JsonNode pending = componentSchema("PendingApprovalCountResponse");
            assertThat(pending).as("PendingApprovalCountResponse 缺失").isNotNull();
            assertThat(fieldNames(pending.path("properties"))).contains("pending");

            JsonNode pipeline = componentSchema("PipelineHeartbeat");
            assertThat(pipeline).as("PipelineHeartbeat 缺失").isNotNull();
            assertThat(fieldNames(pipeline.path("properties"))).contains("lastSeenAt", "silent", "silenceMinutes");

            JsonNode logs = componentSchema("LogsHeartbeat");
            assertThat(logs).as("LogsHeartbeat 缺失").isNotNull();
            assertThat(fieldNames(logs.path("properties"))).contains("freshestLogAt", "silent", "silenceMinutes", "probed", "lokiEnabled");

            JsonNode notify = componentSchema("NotifyHealthResponse");
            assertThat(notify).as("NotifyHealthResponse 缺失").isNotNull();
            assertThat(fieldNames(notify.path("properties"))).contains("attempts", "successes", "failed", "degraded", "delivered", "deliverFailed", "configured", "enabled", "silent");

            JsonNode serviceOwnerList = componentSchema("ServiceOwnerListResponse");
            assertThat(serviceOwnerList).as("ServiceOwnerListResponse 缺失").isNotNull();
            assertThat(fieldNames(serviceOwnerList.path("properties"))).contains("total", "items");

            JsonNode serviceOwnerUpsert = componentSchema("ServiceOwnerUpsertResponse");
            assertThat(serviceOwnerUpsert).as("ServiceOwnerUpsertResponse 缺失").isNotNull();
            assertThat(fieldNames(serviceOwnerUpsert.path("properties"))).contains("id", "service", "owner");

            JsonNode serviceOwnerDisable = componentSchema("ServiceOwnerDisableResponse");
            assertThat(serviceOwnerDisable).as("ServiceOwnerDisableResponse 缺失").isNotNull();
            assertThat(fieldNames(serviceOwnerDisable.path("properties"))).contains("service", "disabled");

            JsonNode approvalPage = componentSchema("ApprovalPageResponse");
            assertThat(approvalPage).as("ApprovalPageResponse 缺失").isNotNull();
            assertThat(fieldNames(approvalPage.path("properties"))).contains("items", "total", "page", "size", "totalPages");

            JsonNode ticketHotTags = componentSchema("TicketHotTagsResponse");
            assertThat(ticketHotTags).as("TicketHotTagsResponse 缺失").isNotNull();
            assertThat(fieldNames(ticketHotTags.path("properties"))).contains("tags", "counts");

            JsonNode downloadUrl = componentSchema("AttachmentDownloadUrlResponse");
            assertThat(downloadUrl).as("AttachmentDownloadUrlResponse 缺失").isNotNull();
            assertThat(fieldNames(downloadUrl.path("properties"))).contains("url", "expiresInSeconds");

            JsonNode knowledgeHotTag = componentSchema("KnowledgeHotTagResponse");
            assertThat(knowledgeHotTag).as("KnowledgeHotTagResponse 缺失").isNotNull();
            assertThat(fieldNames(knowledgeHotTag.path("properties"))).contains("tag", "count");

            JsonNode userView = componentSchema("UserView");
            assertThat(userView).as("UserView 缺失").isNotNull();
            assertThat(fieldNames(userView.path("properties"))).contains("id", "username", "displayName", "role");

            JsonNode loginResponse = componentSchema("LoginResponse");
            assertThat(loginResponse).as("LoginResponse 缺失").isNotNull();
            assertThat(fieldNames(loginResponse.path("properties"))).contains("token", "tokenName", "user");

            JsonNode agentTraceStats = componentSchema("AgentTraceStatsResponse");
            assertThat(agentTraceStats).as("AgentTraceStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(agentTraceStats.path("properties"))).contains("activeSessions", "idleTimeoutMinutes");

            JsonNode agentTraceDetail = componentSchema("AgentTraceDetailResponse");
            assertThat(agentTraceDetail).as("AgentTraceDetailResponse 缺失").isNotNull();
            assertThat(fieldNames(agentTraceDetail.path("properties"))).contains(
                    "traceId", "found", "currentState", "currentStateText", "settled", "terminal",
                    "transitions", "transitionCount", "totalDurationMs");

            JsonNode dashboardTrends = componentSchema("DashboardTrendsResponse");
            assertThat(dashboardTrends).as("DashboardTrendsResponse 缺失").isNotNull();
            assertThat(fieldNames(dashboardTrends.path("properties"))).contains(
                    "days", "created", "resolved", "cost", "cacheHitRate", "windowDays", "module", "callTrendScope");

            JsonNode healingDetail = componentSchema("HealingExecutionDetailResponse");
            assertThat(healingDetail).as("HealingExecutionDetailResponse 缺失").isNotNull();
            assertThat(fieldNames(healingDetail.path("properties"))).contains("execution", "steps");
            JsonNode healingStep = componentSchema("HealingExecutionStep");
            assertThat(healingStep).as("HealingExecutionStep 缺失").isNotNull();
            assertThat(fieldNames(healingStep.path("properties"))).contains("name", "status", "detail", "at");

            JsonNode healingStats = componentSchema("HealingStatsResponse");
            assertThat(healingStats).as("HealingStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(healingStats.path("properties"))).contains(
                    "total", "succeeded", "failed", "rejected", "pendingApproval", "undone", "undoFailed",
                    "autoTotal", "autoClosedLoop", "verifyPass", "verifyFail", "verifyUnknown", "verifySkipped",
                    "verifyPending", "autoClosedLoopRate", "verifyFailRate", "undoSuccessRate");

            JsonNode diagnosisBoard = componentSchema("DiagnosisBoardResponse");
            assertThat(diagnosisBoard).as("DiagnosisBoardResponse 缺失").isNotNull();
            assertThat(fieldNames(diagnosisBoard.path("properties"))).contains(
                    "windowDays", "sessions", "sessionTrend", "evidenceDirections", "attentionTypes", "calibration");

            JsonNode auditOps = componentSchema("AuditOperationsResponse");
            assertThat(auditOps).as("AuditOperationsResponse 缺失").isNotNull();
            assertThat(fieldNames(auditOps.path("properties"))).contains("items", "total", "page", "size", "totalPages");

            JsonNode auditAiCalls = componentSchema("AuditAiCallsResponse");
            assertThat(auditAiCalls).as("AuditAiCallsResponse 缺失").isNotNull();
            assertThat(fieldNames(auditAiCalls.path("properties"))).contains("items", "total", "page", "size", "totalPages", "stats");

            JsonNode auditTraceDetail = componentSchema("AuditTraceDetailResponse");
            assertThat(auditTraceDetail).as("AuditTraceDetailResponse 缺失").isNotNull();
            assertThat(fieldNames(auditTraceDetail.path("properties"))).contains("traceId", "aiCall", "operations");

            JsonNode auditFilterOptions = componentSchema("AuditFilterOptionsResponse");
            assertThat(auditFilterOptions).as("AuditFilterOptionsResponse 缺失").isNotNull();
            assertThat(fieldNames(auditFilterOptions.path("properties"))).contains("models", "operationTypes", "actions", "targetTypes");

            JsonNode metricCatalog = componentSchema("MetricCatalogResponse");
            assertThat(metricCatalog).as("MetricCatalogResponse 缺失").isNotNull();
            assertThat(fieldNames(metricCatalog.path("properties"))).contains("metrics", "enabled");

            JsonNode metricInstant = componentSchema("MetricInstantResponse");
            assertThat(metricInstant).as("MetricInstantResponse 缺失").isNotNull();
            assertThat(fieldNames(metricInstant.path("properties"))).contains("metric", "samples");

            JsonNode metricRange = componentSchema("MetricRangeResponse");
            assertThat(metricRange).as("MetricRangeResponse 缺失").isNotNull();
            assertThat(fieldNames(metricRange.path("properties"))).contains("metric", "from", "to", "step", "hours", "series");

            JsonNode metricOverview = componentSchema("MetricOverviewResponse");
            assertThat(metricOverview).as("MetricOverviewResponse 缺失").isNotNull();
            assertThat(fieldNames(metricOverview.path("properties"))).contains("cards", "timestamp");

            JsonNode metricDatasource = componentSchema("MetricDatasourceResponse");
            assertThat(metricDatasource).as("MetricDatasourceResponse 缺失").isNotNull();
            assertThat(fieldNames(metricDatasource.path("properties"))).contains("datasources", "total");

            JsonNode knowledgeStats = componentSchema("KnowledgeStatsResponse");
            assertThat(knowledgeStats).as("KnowledgeStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(knowledgeStats.path("properties"))).contains(
                    "totalDocuments", "totalChunks", "activeChunks", "filteredOut", "bySource", "lastUpdateTime", "hotQueryCount");

            JsonNode knowledgeTree = componentSchema("KnowledgeCategoryTreeResponse");
            assertThat(knowledgeTree).as("KnowledgeCategoryTreeResponse 缺失").isNotNull();
            assertThat(fieldNames(knowledgeTree.path("properties"))).contains("categories", "uncategorized");

            JsonNode sessionList = componentSchema("SessionListResponse");
            assertThat(sessionList).as("SessionListResponse 缺失").isNotNull();
            assertThat(fieldNames(sessionList.path("properties"))).contains("sessions", "total", "page", "size", "totalPages");

            JsonNode sessionContext = componentSchema("SessionContextResponse");
            assertThat(sessionContext).as("SessionContextResponse 缺失").isNotNull();
            assertThat(fieldNames(sessionContext.path("properties"))).contains("sessionId", "recentHistory", "keyFactsText", "hasHistory");

            JsonNode changeReport = componentSchema("ChangeEventReportResponse");
            assertThat(changeReport).as("ChangeEventReportResponse 缺失").isNotNull();
            assertThat(fieldNames(changeReport.path("properties"))).contains("success", "deduplicated", "message");

            JsonNode sagaAttention = componentSchema("SagaAttentionResponse");
            assertThat(sagaAttention).as("SagaAttentionResponse 缺失").isNotNull();
            assertThat(fieldNames(sagaAttention.path("properties"))).contains("records", "count");

            JsonNode sagaSteps = componentSchema("SagaStepsResponse");
            assertThat(sagaSteps).as("SagaStepsResponse 缺失").isNotNull();
            assertThat(fieldNames(sagaSteps.path("properties"))).contains("sagaId", "steps", "stepCount");

            JsonNode sagaCompensate = componentSchema("SagaCompensateResponse");
            assertThat(sagaCompensate).as("SagaCompensateResponse 缺失").isNotNull();
            assertThat(fieldNames(sagaCompensate.path("properties"))).contains(
                    "sagaId", "compensatedCount", "failedCount", "compensated", "failed", "fullySucceeded");

            JsonNode slaAtRisk = componentSchema("TicketSlaAtRiskResponse");
            assertThat(slaAtRisk).as("TicketSlaAtRiskResponse 缺失").isNotNull();
            assertThat(fieldNames(slaAtRisk.path("properties"))).contains("total", "withinMinutes", "tickets");

            JsonNode firstResponseStats = componentSchema("TicketFirstResponseStatsResponse");
            assertThat(firstResponseStats).as("TicketFirstResponseStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(firstResponseStats.path("properties"))).contains(
                    "responded", "notResponded", "breached", "avgFirstResponseMinutes");

            JsonNode ticketAction = componentSchema("TicketActionResponse");
            assertThat(ticketAction).as("TicketActionResponse 缺失").isNotNull();
            assertThat(fieldNames(ticketAction.path("properties"))).contains("id", "action");

            JsonNode missingPostmortem = componentSchema("MissingPostmortemItemDto");
            assertThat(missingPostmortem).as("MissingPostmortemItemDto 缺失").isNotNull();
            assertThat(fieldNames(missingPostmortem.path("properties"))).contains("id", "title", "status", "createTime");

            JsonNode ticketVoid = componentSchema("TicketVoidResponse");
            assertThat(ticketVoid).as("TicketVoidResponse 缺失").isNotNull();
            assertThat(fieldNames(ticketVoid.path("properties"))).contains("ticketId", "message");

            JsonNode ticketStats = componentSchema("TicketStatsResponse");
            assertThat(ticketStats).as("TicketStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(ticketStats.path("properties"))).contains(
                    "total", "todayNew", "byStatus", "pending", "processing", "resolved", "byPriority", "urgentPending", "unassignedOpen");

            JsonNode aiFeedback = componentSchema("TicketAiFeedbackResponse");
            assertThat(aiFeedback).as("TicketAiFeedbackResponse 缺失").isNotNull();
            assertThat(fieldNames(aiFeedback.path("properties"))).contains("analysisId", "helpful", "knowledgeBoosted");

            JsonNode aiAccuracy = componentSchema("TicketAiAccuracyStatsResponse");
            assertThat(aiAccuracy).as("TicketAiAccuracyStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(aiAccuracy.path("properties"))).contains("total", "rated", "helpful", "unhelpful", "helpfulRate");

            JsonNode closureMetrics = componentSchema("TicketClosureMetricsResponse");
            assertThat(closureMetrics).as("TicketClosureMetricsResponse 缺失").isNotNull();
            assertThat(fieldNames(closureMetrics.path("properties"))).contains(
                    "total", "firstResponded", "mitigated", "rootCauseConfirmed", "verified", "verifySkipped",
                    "mttaMinutes", "mttmMinutes", "mttrMinutes", "skipRate");

            JsonNode categoryCount = componentSchema("KnowledgeCategoryCountResponse");
            assertThat(categoryCount).as("KnowledgeCategoryCountResponse 缺失").isNotNull();
            assertThat(fieldNames(categoryCount.path("properties"))).contains("name", "count");

            JsonNode docHealth = componentSchema("KnowledgeDocHealthResponse");
            assertThat(docHealth).as("KnowledgeDocHealthResponse 缺失").isNotNull();
            assertThat(fieldNames(docHealth.path("properties"))).contains("docId", "title", "helpful", "wrong", "net");

            JsonNode citationsFeedback = componentSchema("KnowledgeCitationsFeedbackResponse");
            assertThat(citationsFeedback).as("KnowledgeCitationsFeedbackResponse 缺失").isNotNull();
            assertThat(fieldNames(citationsFeedback.path("properties"))).contains("boosted");

            JsonNode endpointDeprecated = componentSchema("EndpointDeprecatedResponse");
            assertThat(endpointDeprecated).as("EndpointDeprecatedResponse 缺失").isNotNull();
            assertThat(fieldNames(endpointDeprecated.path("properties"))).contains("deprecated", "reason", "migrateTo");

            JsonNode chunkPage = componentSchema("KnowledgeChunkPageResponse");
            assertThat(chunkPage).as("KnowledgeChunkPageResponse 缺失").isNotNull();
            assertThat(fieldNames(chunkPage.path("properties"))).contains("list", "total", "totalPages", "page", "size");

            JsonNode teamMembers = componentSchema("TeamMemberListResponse");
            assertThat(teamMembers).as("TeamMemberListResponse 缺失").isNotNull();
            assertThat(fieldNames(teamMembers.path("properties"))).contains("total", "users");

            JsonNode effTrend = componentSchema("EffectivenessTrendItem");
            assertThat(effTrend).as("EffectivenessTrendItem 缺失").isNotNull();
            assertThat(fieldNames(effTrend.path("properties"))).contains("snapshot_date", "total_tickets", "finished_tickets");

            JsonNode searchItem = componentSchema("SearchResultItem");
            assertThat(searchItem).as("SearchResultItem 缺失").isNotNull();
            assertThat(fieldNames(searchItem.path("properties"))).contains("id", "title", "kind", "sub");

            JsonNode rootCauseStats = componentSchema("TicketRootCauseStatsResponse");
            assertThat(rootCauseStats).as("TicketRootCauseStatsResponse 缺失").isNotNull();
            assertThat(fieldNames(rootCauseStats.path("properties"))).contains("stats");

            JsonNode pmDraft = componentSchema("PostmortemDraftResponse");
            assertThat(pmDraft).as("PostmortemDraftResponse 缺失").isNotNull();
            assertThat(fieldNames(pmDraft.path("properties"))).contains("timeline");

            JsonNode diagReplay = componentSchema("DiagnosisReplayResponse");
            assertThat(diagReplay).as("DiagnosisReplayResponse 缺失").isNotNull();
            assertThat(fieldNames(diagReplay.path("properties"))).contains("traceId", "session", "evidences", "hypotheses", "aiMode");
            JsonNode diagnosisSession = componentSchema("DiagnosisReplaySession");
            assertThat(fieldNames(diagnosisSession.path("properties"))).contains(
                    "id", "trace_id", "alert_id", "ticket_id", "service", "status", "sufficiency",
                    "summary", "created_at", "updated_at");
            JsonNode diagnosisSessionView = componentSchema("DiagnosisReplaySessionView");
            assertThat(diagnosisSessionView.path("oneOf")).hasSize(2);
            assertThat(fieldNames(componentSchema("DiagnosisEvidenceItem").path("properties")))
                    .contains("id", "trace_id", "agent_name", "evidence_type", "status", "title",
                            "content", "source_ref", "relevance_score", "collected_at");
            assertThat(fieldNames(componentSchema("DiagnosisHypothesisItem").path("properties")))
                    .contains("id", "session_trace_id", "rank", "statement", "reasoning", "confidence",
                            "evidence_ids", "contradict_ids", "suggested_action", "created_at");

            JsonNode hypoFeedback = componentSchema("HypothesisFeedbackResponse");
            assertThat(hypoFeedback).as("HypothesisFeedbackResponse 缺失").isNotNull();
            assertThat(fieldNames(hypoFeedback.path("properties"))).contains("hypothesisId", "feedback", "knowledgeBoosted");

            JsonNode entityDelete = componentSchema("EntityDeleteResponse");
            assertThat(entityDelete).as("EntityDeleteResponse 缺失").isNotNull();
            assertThat(fieldNames(entityDelete.path("properties"))).contains("id", "deleted");

            JsonNode docMove = componentSchema("DocumentMoveResponse");
            assertThat(docMove).as("DocumentMoveResponse 缺失").isNotNull();
            assertThat(fieldNames(docMove.path("properties"))).contains("id", "moved");

            JsonNode kbReindex = componentSchema("KnowledgeBaseReindexResponse");
            assertThat(kbReindex).as("KnowledgeBaseReindexResponse 缺失").isNotNull();
            assertThat(fieldNames(kbReindex.path("properties"))).contains("kbId", "kbName", "total", "success", "failed", "failures");
            JsonNode kbReindexFailure = componentSchema("KnowledgeBaseReindexFailure");
            assertThat(kbReindexFailure).as("KnowledgeBaseReindexFailure 缺失").isNotNull();
            assertThat(fieldNames(kbReindexFailure.path("properties")))
                    .contains("docId", "title", "indexStatus", "error");

            JsonNode serviceOwnerItem = componentSchema("ServiceOwnerItem");
            assertThat(serviceOwnerItem).as("ServiceOwnerItem 缺失").isNotNull();
            assertThat(fieldNames(serviceOwnerItem.path("properties")))
                    .contains("id", "service", "owner", "enabled", "createTime", "updateTime");
        }
    }

    @Nested
    @DisplayName("导出静态契约供前端消费")
    class ExportForFrontend {

        @Test
        @DisplayName("导出 openapi.json 到 target/，供 openapi-typescript 使用")
        void exportsSpec() throws Exception {
            // 导出到 target/ 而非源码目录：它是构建产物，不该进版本库。
            // CI 已配置上传 target 下的测试报告，需要时可一并取用。
            //
            // 前端生成类型的命令（待 P0-2 收尾后接入）：
            //   npx openapi-typescript target/openapi.json -o src/api/generated.d.ts
            Path out = Path.of("target", "openapi.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out,
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(docs()),
                    StandardCharsets.UTF_8);

            assertThat(Files.size(out))
                    .as("导出的 openapi.json 过小，可能是空文档")
                    .isGreaterThan(10_000L);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 取 {@code components.schemas.<name>}。
     *
     * <p>springdoc 对嵌套 record 生成的名字是<b>简单类名</b>
     * （{@code TicketPage} 而非 {@code TicketDto$TicketPage}），
     * 但不同版本可能带上外层类名前缀，故做一次后缀匹配兜底——
     * 找不到时返回 {@code null} 让断言给出明确信息，
     * 而不是抛 NPE 让人以为是测试写错了。</p>
     */
    private JsonNode componentSchema(String simpleName) throws Exception {
        JsonNode schemas = docs().path("components").path("schemas");
        if (schemas.has(simpleName)) {
            return schemas.get(simpleName);
        }
        var it = schemas.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (key.endsWith(simpleName)) {
                return schemas.get(key);
            }
        }
        return null;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
