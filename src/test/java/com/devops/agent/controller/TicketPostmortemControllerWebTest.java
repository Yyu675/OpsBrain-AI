package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.biz.entity.TicketActionItem;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.service.TicketPostmortemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TicketPostmortemController} HTTP 契约测试（B4 闭环阶段 7）。
 *
 * <h3>复盘这块的数据有个特点：写进去就是给几个月后的人看的</h3>
 * 时间线、影响范围、经验教训、改进项——它们不参与任何实时流程，
 * 唯一的价值是<b>故障复发时有人翻出来看</b>。
 * 这意味着这里的缺陷有极长的潜伏期：写坏了当时没人发现，
 * 等到真需要它的那天才知道数据是错的或者根本没存下来。
 *
 * <h3>本类覆盖的重点</h3>
 * <ol>
 *   <li><b>路径 id 必须覆盖请求体</b>——{@code ticketId} 取自 URL 而非 body。
 *       若信任 body，前端拼错就会把 A 工单的复盘写进 B 工单，
 *       且两边都不会报错；</li>
 *   <li><b>「还没写复盘」与「工单不存在」不能混淆</b>——
 *       {@code getPostmortem} 返回 null 是正常态（复盘是可选的），
 *       不该被当成错误；</li>
 *   <li><b>改进项的日期解析</b>——{@code dueDate} 是在业务代码里手工
 *       {@code LocalDate.parse} 的，没走 Spring 类型绑定。
 *       格式写错时必须是 400 而不是 500（见下）；</li>
 *   <li><b>改进项查询的三个筛选条件</b>——尤其 {@code overdue=true}
 *       这个「只看逾期未完成」的视图，是改进项看板存在的主要理由。</li>
 * </ol>
 *
 * <h3>写这组测试时查出的缺陷</h3>
 * {@code LocalDate.parse(req.dueDate())} 抛的
 * {@link java.time.format.DateTimeParseException} 继承自 {@code RuntimeException}，
 * 全局处理器此前没有接管，会落到兜底分支返回 <b>HTTP 500「服务内部异常」</b>。
 *
 * <p>用户填了 {@code 2026/08/25}（斜杠）或 {@code 2026-8-5}（月份没补零），
 * 本该被告知「格式不对」，却看到「请联系管理员」——他会去找管理员，
 * 而管理员在日志里看到的是一条 5xx，同样会往服务端故障方向查。
 * 已在 {@code GlobalExceptionHandler} 补上映射，本类有对应用例守着。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = TicketPostmortemController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.devops.agent.controller.config.WebConfig.class,
                        com.devops.agent.common.audit.OperationAuditInterceptor.class
                }),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, com.devops.agent.common.web.TraceIdFilter.class})
class TicketPostmortemControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private TicketPostmortemService pmService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static TicketPostmortem postmortem(String ticketId) {
        TicketPostmortem pm = new TicketPostmortem();
        pm.setId(1L);
        pm.setTicketId(ticketId);
        pm.setTimeline("09:00 告警触发\n09:15 定位到连接池耗尽");
        pm.setImpactScope("订单服务全部实例");
        pm.setImpactDuration(45);
        pm.setLessons("连接未归还，需在 DAO 层统一 try-with-resources");
        pm.setAuthor("张明");
        pm.setCreateTime(LocalDateTime.of(2026, 8, 25, 10, 0));
        return pm;
    }

    private static TicketActionItem actionItem(Long id, String status, LocalDate due) {
        TicketActionItem item = new TicketActionItem();
        item.setId(id);
        item.setTicketId("TK-2026-0001");
        item.setPostmortemId(1L);
        item.setContent("给 DAO 层加连接归还检查");
        item.setOwner("李四");
        item.setDueDate(due);
        item.setStatus(status);
        return item;
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** HashMap：多处需要显式 null（docId、dueDate、owner） */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("复盘详情与保存")
    class Postmortem {

        @Test
        @DisplayName("详情字段齐备 —— 这些字段是几个月后复发时唯一的线索")
        void detailCarriesAllFields() throws Exception {
            when(pmService.getPostmortem("TK-2026-0001")).thenReturn(postmortem("TK-2026-0001"));

            mockMvc.perform(get("/api/v1/tickets/TK-2026-0001/postmortem"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.ticketId").value("TK-2026-0001"))
                    .andExpect(jsonPath("$.data.timeline").isNotEmpty())
                    .andExpect(jsonPath("$.data.impactScope").value("订单服务全部实例"))
                    .andExpect(jsonPath("$.data.impactDuration").value(45))
                    .andExpect(jsonPath("$.data.lessons").isNotEmpty())
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("还没写复盘时 data 为 null 且 code=0 —— 复盘是可选的，不是错误")
        void missingPostmortemIsNotAnError() throws Exception {
            when(pmService.getPostmortem(anyString())).thenReturn(null);

            mockMvc.perform(get("/api/v1/tickets/TK-2026-0002/postmortem"))
                    .andExpect(status().isOk())
                    // 若这里返回 40004，前端详情页会弹「数据不存在」的错误提示，
                    // 而实际情况只是「这张工单还没人写复盘」——
                    // 正确的表现是展示一个空白的复盘表单让人填
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("保存时 ticketId 取自路径，请求体里的同名字段被忽略")
        void pathIdOverridesBody() throws Exception {
            when(pmService.savePostmortem(any(), anyString())).thenReturn(postmortem("TK-2026-0001"));

            mockMvc.perform(put("/api/v1/tickets/TK-2026-0001/postmortem")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(
                                    "timeline", "09:00 告警",
                                    "impactScope", "订单服务",
                                    "impactDuration", 45,
                                    "lessons", "要加检查",
                                    "author", "张明",
                                    // 恶意/手滑传入的另一个工单号，必须被忽略
                                    "ticketId", "TK-9999-9999"))))
                    .andExpect(status().isOk());

            ArgumentCaptor<TicketPostmortem> cap = ArgumentCaptor.forClass(TicketPostmortem.class);
            verify(pmService).savePostmortem(cap.capture(), eq("张明"));
            // 若信任 body，前端拼错就会把 A 工单的复盘写进 B 工单，且两边都不报错
            assertThat(cap.getValue().getTicketId()).isEqualTo("TK-2026-0001");
        }

        @Test
        @DisplayName("保存时各字段原样透传，docId 为 null 表示尚未沉淀为知识")
        void saveForwardsAllFields() throws Exception {
            when(pmService.savePostmortem(any(), any())).thenReturn(postmortem("TK-2026-0001"));

            mockMvc.perform(put("/api/v1/tickets/TK-2026-0001/postmortem")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(
                                    "timeline", "时间线",
                                    "impactScope", "影响范围",
                                    "impactDuration", 30,
                                    "lessons", "教训",
                                    "docId", null,
                                    "author", "王五"))))
                    .andExpect(status().isOk());

            ArgumentCaptor<TicketPostmortem> cap = ArgumentCaptor.forClass(TicketPostmortem.class);
            verify(pmService).savePostmortem(cap.capture(), eq("王五"));
            TicketPostmortem saved = cap.getValue();
            assertThat(saved.getTimeline()).isEqualTo("时间线");
            assertThat(saved.getImpactDuration()).isEqualTo(30);
            assertThat(saved.getLessons()).isEqualTo("教训");
            // null 表示「还没沉淀成知识库文档」，不能补成 0——
            // 0 会被当成一个真实存在的文档 ID
            assertThat(saved.getDocId()).isNull();
        }

        @Test
        @DisplayName("impactDuration 为 null 时保持 null，不伪装成 0 分钟")
        void nullDurationIsNotZero() throws Exception {
            when(pmService.savePostmortem(any(), any())).thenReturn(postmortem("TK-2026-0001"));

            mockMvc.perform(put("/api/v1/tickets/TK-2026-0001/postmortem")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("timeline", "x", "author", "张明"))))
                    .andExpect(status().isOk());

            ArgumentCaptor<TicketPostmortem> cap = ArgumentCaptor.forClass(TicketPostmortem.class);
            verify(pmService).savePostmortem(cap.capture(), any());
            // 「没填影响时长」和「影响时长 0 分钟」是两回事：
            // 后者意味着故障瞬间自愈，会让 MTTR 统计凭空变好看
            assertThat(cap.getValue().getImpactDuration()).isNull();
        }

        @Test
        @DisplayName("生成时间线草稿：返回可编辑的文本，不直接落库")
        void generateDraftReturnsEditableText() throws Exception {
            when(pmService.generateTimelineDraft("TK-2026-0001"))
                    .thenReturn("09:00 告警触发\n09:15 人工确认");

            mockMvc.perform(post("/api/v1/tickets/TK-2026-0001/postmortem/draft"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.timeline").value("09:00 告警触发\n09:15 人工确认"));

            // 草稿只是拼接建议，必须让用户编辑后再保存——
            // 直接落库会让自动拼的内容冒充人写的复盘
            verify(pmService, never()).savePostmortem(any(), any());
        }

        @Test
        @DisplayName("请求体畸形 → 400 而不是 500")
        void malformedBodyIsBadRequest() throws Exception {
            mockMvc.perform(put("/api/v1/tickets/TK-2026-0001/postmortem")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ broken"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("改进项：查询")
    class ActionItemQuery {

        @Test
        @DisplayName("无筛选时三个条件都传默认值")
        void noFilterPassesDefaults() throws Exception {
            when(pmService.findActionItems(any(), any(), anyBoolean()))
                    .thenReturn(List.of(actionItem(1L, "OPEN", LocalDate.of(2026, 9, 1))));

            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].content").isNotEmpty())
                    .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                    .andExpect(jsonPath("$.data[0].owner").value("李四"));

            verify(pmService).findActionItems(isNull(), isNull(), eq(false));
        }

        @Test
        @DisplayName("overdue=true 透传 —— 「只看逾期未完成」是改进项看板存在的主要理由")
        void overdueFilterIsForwarded() throws Exception {
            when(pmService.findActionItems(any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items")
                            .param("overdue", "true"))
                    .andExpect(status().isOk());

            // 这个 true 被当成 null 丢掉的话，看板会退化成「显示全部改进项」，
            // 真正该被催的那几条被淹没在几十条已完成的里面
            verify(pmService).findActionItems(isNull(), isNull(), eq(true));
        }

        @Test
        @DisplayName("status 与 owner 组合筛选一并透传")
        void statusAndOwnerForwarded() throws Exception {
            when(pmService.findActionItems(any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items")
                            .param("status", "DOING")
                            .param("owner", "李四"))
                    .andExpect(status().isOk());

            verify(pmService).findActionItems(eq("DOING"), eq("李四"), eq(false));
        }

        @Test
        @DisplayName("空清单返回空数组而非 null")
        void emptyListIsArray() throws Exception {
            when(pmService.findActionItems(any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Spring 接受 yes/on/1 作为 true —— 这不是 bug，别误当非法值")
        void springAcceptsBooleanSynonyms() throws Exception {
            // 本来想断言 overdue=yes 返回 400，CI 告诉我是 200。
            // 查证后确认是**我的测试假设错了**：Spring 的 StringToBooleanConverter
            // 接受 true/false、on/off、yes/no、1/0 共四组同义词。
            // 「yes」是合法的 true，不是非法输入。
            //
            // 记在这里是为了防止后来者看到 yes 能通过就以为绑定校验失效，
            // 反过来去「修」一个并不存在的问题。
            when(pmService.findActionItems(any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items")
                            .param("overdue", "yes"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items")
                            .param("overdue", "1"))
                    .andExpect(status().isOk());

            verify(pmService, org.mockito.Mockito.times(2))
                    .findActionItems(isNull(), isNull(), eq(true));
        }

        @Test
        @DisplayName("真正非法的布尔值 → 400，不静默当成 false")
        void invalidOverdueIsRejected() throws Exception {
            // 「maybe」不在 Spring 的同义词表里，属于真正的非法输入。
            // 静默当成 false 会让「只看逾期」的筛选悄悄失效，
            // 用户以为自己在看逾期项，实际看到的是全部
            mockMvc.perform(get("/api/v1/tickets/postmortem/action-items")
                            .param("overdue", "maybe"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("改进项：新建与状态流转")
    class ActionItemWrite {

        @Test
        @DisplayName("新建：ticketId 取自路径，dueDate 正确解析为 LocalDate")
        void addParsesDueDate() throws Exception {
            when(pmService.addActionItem(any()))
                    .thenReturn(actionItem(5L, "OPEN", LocalDate.of(2026, 9, 30)));

            mockMvc.perform(post("/api/v1/tickets/TK-2026-0001/postmortem/action-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("postmortemId", 1, "content", "加连接归还检查",
                                    "owner", "李四", "dueDate", "2026-09-30"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(5));

            ArgumentCaptor<TicketActionItem> cap = ArgumentCaptor.forClass(TicketActionItem.class);
            verify(pmService).addActionItem(cap.capture());
            assertThat(cap.getValue().getTicketId()).isEqualTo("TK-2026-0001");
            assertThat(cap.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        }

        @Test
        @DisplayName("dueDate 省略时为 null —— 「没定期限」是合法状态")
        void nullDueDateIsAllowed() throws Exception {
            when(pmService.addActionItem(any())).thenReturn(actionItem(5L, "OPEN", null));

            mockMvc.perform(post("/api/v1/tickets/TK-2026-0001/postmortem/action-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("postmortemId", 1, "content", "长期优化项",
                                    "dueDate", null))))
                    .andExpect(status().isOk());

            ArgumentCaptor<TicketActionItem> cap = ArgumentCaptor.forClass(TicketActionItem.class);
            verify(pmService).addActionItem(cap.capture());
            // 补一个默认日期会让「长期优化项」凭空变成逾期项
            assertThat(cap.getValue().getDueDate()).isNull();
        }

        @Test
        @DisplayName("dueDate 格式错误 → 400/40001，而不是 500「服务内部异常」")
        void badDueDateIsBadRequestNotServerError() throws Exception {
            // 这条是写本类时查出的缺陷。dueDate 是在业务代码里手工 LocalDate.parse 的，
            // 没走 Spring 类型绑定，抛出的 DateTimeParseException 继承 RuntimeException，
            // 修复前会落到兜底分支返回 500「服务内部异常，请联系管理员」。
            //
            // 用户填了斜杠日期或月份没补零，本该被告知「格式不对」，
            // 却被引导去找管理员；而管理员在日志里看到 5xx，
            // 同样会往服务端故障方向排查
            mockMvc.perform(post("/api/v1/tickets/TK-2026-0001/postmortem/action-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("postmortemId", 1, "content", "x",
                                    "dueDate", "2026/09/30"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    // 提示要给出期望格式，才是可执行的下一步
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("yyyy-MM-dd")));

            verify(pmService, never()).addActionItem(any());
        }

        @Test
        @DisplayName("月份未补零的日期同样被拒（2026-9-5 不是合法 ISO 日期）")
        void nonPaddedDateRejected() throws Exception {
            mockMvc.perform(post("/api/v1/tickets/TK-2026-0001/postmortem/action-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("postmortemId", 1, "content", "x",
                                    "dueDate", "2026-9-5"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("更新状态：itemId 与 status 一并透传")
        void updateStatusForwarded() throws Exception {
            when(pmService.updateActionItemStatus(anyLong(), anyString()))
                    .thenReturn(actionItem(5L, "DONE", LocalDate.of(2026, 9, 30)));

            mockMvc.perform(patch("/api/v1/tickets/postmortem/action-items/5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "DONE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DONE"));

            verify(pmService).updateActionItemStatus(5L, "DONE");
        }

        @Test
        @DisplayName("非法状态由 Service 拒绝 → 40001")
        void invalidStatusRejected() throws Exception {
            when(pmService.updateActionItemStatus(anyLong(), anyString()))
                    .thenThrow(new IllegalArgumentException("非法状态：FINISHED"));

            mockMvc.perform(patch("/api/v1/tickets/postmortem/action-items/5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "FINISHED"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("itemId 非数字 → 400，而不是 500")
        void nonNumericItemIdIsBadRequest() throws Exception {
            mockMvc.perform(patch("/api/v1/tickets/postmortem/action-items/undefined")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "DONE"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("改进项状态更新只接受 PATCH，PUT 返回 405")
        void wrongMethodIsRejected() throws Exception {
            mockMvc.perform(put("/api/v1/tickets/postmortem/action-items/5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "DONE"))))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
