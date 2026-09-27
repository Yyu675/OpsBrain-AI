package com.devops.agent.application.runtime;

import com.devops.agent.common.audit.OperationAuditRecord;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.entity.TicketReply;
import com.devops.agent.domain.biz.service.TicketPostmortemService;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocService;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘自动沉淀知识草稿的单元测试。
 *
 * <h3>为什么四道守卫都要测</h3>
 * 该编排在保存复盘的请求尾部异步跑，产物是 DRAFT 文档（不发布）。
 * 它有极长的潜伏期：生成失败只在日志留一行 warn，用户界面无感知；
 * 而「多生成一篇草稿」或「该生成却没生成」都不会报错，只会在几天后
 * 被人发现知识库里多了/少了内容。守卫错任何一环，表现都是静默。
 */
@DisplayName("复盘自动沉淀知识草稿（PostmortemDraftOrchestrator）")
class PostmortemDraftOrchestratorTest {

    private TicketPostmortemService pmService;
    private TicketService ticketService;
    private KnowledgeDocService knowledgeDocService;
    private OperationAuditRepository auditRepository;

    private PostmortemDraftOrchestrator orchestrator;

    private static final String TICKET = "TKT-20260924-0001";
    private static final KnowledgeDocService.SaveResult SAVED =
            new KnowledgeDocService.SaveResult(
                    99L, 1, List.of(), KnowledgeDocService.IndexOutcome.skipped());

    @BeforeEach
    void setUp() {
        pmService = mock(TicketPostmortemService.class);
        ticketService = mock(TicketService.class);
        knowledgeDocService = mock(KnowledgeDocService.class);
        auditRepository = mock(OperationAuditRepository.class);
        orchestrator = new PostmortemDraftOrchestrator(
                pmService, ticketService, knowledgeDocService, auditRepository);
    }

    private void stubPostmortem() {
        TicketPostmortem pm = new TicketPostmortem();
        pm.setId(1L);
        pm.setTicketId(TICKET);
        pm.setTimeline("09:00 告警\n09:15 定位连接池耗尽");
        pm.setAuthor("张明");
        when(pmService.getPostmortem(TICKET)).thenReturn(pm);
    }

    private void stubTicket() {
        DevOpsTicket t = new DevOpsTicket();
        t.setId(TICKET);
        t.setTitle("order-service 连接池耗尽");
        t.setModule("order-service");
        t.setPriority("P1");
        t.setDescription("接口 p99 飙升，大量连接超时");
        t.setRootCause("DAO 层连接未归还");
        t.setRootCauseCategory("代码缺陷");
        when(ticketService.getTicketWithTags(TICKET)).thenReturn(t);
        when(ticketService.listReplies(TICKET)).thenReturn(List.of());
    }

    /** 让某工单被视为「已沉淀过」 */
    private void stubAlreadyLinked() {
        when(knowledgeDocService.findBySourceTicketId(TICKET))
                .thenReturn(List.of(new KnowledgeDoc()));
    }

    private String createContent() {
        ArgumentCaptor<KnowledgeDoc> cap = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(knowledgeDocService).create(cap.capture(), eq(List.of("故障复盘")), eq(false), eq("postmortem-auto"));
        return cap.getValue().getContent();
    }

    private KnowledgeDoc createDoc() {
        ArgumentCaptor<KnowledgeDoc> cap = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(knowledgeDocService).create(cap.capture(), eq(List.of("故障复盘")), eq(false), eq("postmortem-auto"));
        return cap.getValue();
    }

    // ==================== 守卫 ====================

    @Nested
    @DisplayName("四道守卫")
    class Guards {

        @Test
        @DisplayName("开关关闭：直接返回，连工单/复盘都不查")
        void disabledShortCircuits() {
            ReflectionTestUtils.setField(orchestrator, "enabled", false);

            assertThat(orchestrator.generateNow(TICKET)).isNull();
            verify(pmService, never()).getPostmortem(anyString());
            verify(ticketService, never()).getTicketWithTags(anyString());
            verify(knowledgeDocService, never()).findBySourceTicketId(anyString());
        }

        @Test
        @DisplayName("复盘不存在（还没归档）：跳过——没有沉淀的原料")
        void missingPostmortemSkips() {
            when(pmService.getPostmortem(TICKET)).thenReturn(null);

            assertThat(orchestrator.generateNow(TICKET)).isNull();
            verify(knowledgeDocService, never()).create(any(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("该工单已有回链文档：跳过——抽屉发过或草稿已建，每工单至多一篇")
        void alreadyLinkedSkips() {
            stubPostmortem();
            stubAlreadyLinked();

            assertThat(orchestrator.generateNow(TICKET)).isNull();
            verify(ticketService, never()).getTicketWithTags(anyString());
            verify(knowledgeDocService, never()).create(any(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("工单查不到（复盘存在但工单被删）：跳过 + warn，不抛")
        void missingTicketSkips() {
            stubPostmortem();
            when(knowledgeDocService.findBySourceTicketId(TICKET)).thenReturn(List.of());
            when(ticketService.getTicketWithTags(TICKET)).thenReturn(null);

            assertThat(orchestrator.generateNow(TICKET)).isNull();
            verify(knowledgeDocService, never()).create(any(), any(), anyBoolean(), anyString());
        }
    }

    // ==================== 正文生成 ====================

    @Nested
    @DisplayName("正文：模板 / LLM 两轨")
    class Content {

        @BeforeEach
        void stubBase() {
            stubPostmortem();
            stubTicket();
            when(knowledgeDocService.create(any(), any(), eq(false), any())).thenReturn(SAVED);
        }

        @Test
        @DisplayName("无 ChatModel：结构化模板，六章节齐全 + 真实根因 + 缺失页待补充")
        void templateHasSixSectionsAndRealFacts() {
            Long docId = orchestrator.generateNow(TICKET);

            assertThat(docId).isEqualTo(99L);
            String content = createContent();
            assertThat(content)
                    .contains("## 故障现象", "## 影响范围", "## 根因分析",
                            "## 处理步骤", "## 预防措施", "## 改进项");
            // 根因是结构化字段里的真实事实，模板必须带上（不是「待补充」）
            assertThat(content).contains("DAO 层连接未归还");
            // 影响范围没归档 → 诚实写「待补充」，绝不编造
            assertThat(content).contains("待补充");
        }

        @Test
        @DisplayName("LLM 产出合格 RCA：用模型文本，prompt 含工单标题")
        void llmQualifiedOutputUsed() {
            ChatModel chat = mock(ChatModel.class);
            when(chat.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from("## 故障现象\n真实故障\n\n## 根因分析\n连接未归还\n\n其余章节略"))
                    .build());
            ReflectionTestUtils.setField(orchestrator, "turboModel", chat);

            orchestrator.generateNow(TICKET);

            String content = createContent();
            assertThat(content).contains("## 故障现象").contains("真实故障");
            ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
            verify(chat).chat(req.capture());
            assertThat(((UserMessage) req.getValue().messages().get(1)).singleText())
                    .contains("order-service 连接池耗尽");
        }

        @Test
        @DisplayName("LLM 输出缺必需章节（含 MOCK 固定假数据）：回落模板，不用垃圾正文")
        void llmJunkFallsBackToTemplate() {
            ChatModel chat = mock(ChatModel.class);
            when(chat.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from("mock-canned-response without any rca headers")).build());
            ReflectionTestUtils.setField(orchestrator, "turboModel", chat);

            orchestrator.generateNow(TICKET);

            assertThat(createContent()).contains("## 故障现象", "## 根因分析");
        }

        @Test
        @DisplayName("LLM 抛异常：回落模板，不外抛")
        void llmThrowFallsBackToTemplate() {
            ChatModel chat = mock(ChatModel.class);
            when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("quota"));
            ReflectionTestUtils.setField(orchestrator, "turboModel", chat);

            orchestrator.generateNow(TICKET);

            assertThat(createContent()).contains("## 故障现象");
        }
    }

    // ==================== 落库契约 ====================

    @Nested
    @DisplayName("DRAFT 落库契约")
    class Persistence {

        @BeforeEach
        void stubBase() {
            stubPostmortem();
            stubTicket();
            when(knowledgeDocService.create(any(), any(), eq(false), any())).thenReturn(SAVED);
        }

        @Test
        @DisplayName("草稿字段齐备：DRAFT（不发布）、回链字符串工单号、来源类型、作者=归档人")
        void draftCarriesBacklink() {
            orchestrator.generateNow(TICKET);

            KnowledgeDoc doc = createDoc();
            assertThat(doc.getSourceTicketId()).isEqualTo(TICKET);
            assertThat(doc.getSourceType()).isEqualTo("TICKET");
            assertThat(doc.getKnowledgeSource()).isEqualTo("postmortem-auto");
            assertThat(doc.getAuthor()).isEqualTo("张明");
            assertThat(doc.getTitle()).startsWith("【故障复盘】");
            assertThat(doc.getCategory()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("审计旁写：action=knowledge.doc.create，actor=postmortem-auto")
        void auditsCreate() {
            orchestrator.generateNow(TICKET);

            ArgumentCaptor<OperationAuditRecord> cap = ArgumentCaptor.forClass(OperationAuditRecord.class);
            verify(auditRepository).save(cap.capture());
            assertThat(cap.getValue().action()).isEqualTo("knowledge.doc.create");
            assertThat(cap.getValue().actorName()).isEqualTo("postmortem-auto");
        }

        @Test
        @DisplayName("审计旁写抛异常：不影响草稿结果（已生成仍返回 docId）")
        void auditFailureIsolated() {
            // save 是 void 方法——用 doThrow 而非 when().thenThrow()
            org.mockito.Mockito.doThrow(new RuntimeException("audit-db down"))
                    .when(auditRepository).save(any());

            assertThat(orchestrator.generateNow(TICKET)).isEqualTo(99L);
        }

        @Test
        @DisplayName("创建被并发重复拦截（DuplicateContent）：视为已沉淀，返回 null 不抛")
        void duplicateContentTreatedAsAlreadySunk() {
            when(knowledgeDocService.create(any(), any(), eq(false), any()))
                    .thenThrow(new KnowledgeDocService.DuplicateContentException("重复", 7L, "已有"));

            assertThat(orchestrator.generateNow(TICKET)).isNull();
        }

        @Test
        @DisplayName("非重复类异常（如参数校验失败）不吞：冒给异步收口层 warn")
        void unexpectedErrorPropagates() {
            when(knowledgeDocService.create(any(), any(), eq(false), any()))
                    .thenThrow(new IllegalArgumentException("文档标题不能为空"));

            // generateNow 不吞校验异常——由 generateSafe 收口：
            // 吞掉会让「草稿静默丢失」与「正常跳过」无法区分
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.generateNow(TICKET))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== 异步在途去重 ====================

    @Nested
    @DisplayName("submitDraftAsync 的异步语义")
    class Async {
        @Test
        @DisplayName("生成在途时重复保存不入队：两次触发只执行一次守卫链")
        void inFlightDedup() throws Exception {
            stubPostmortem();
            when(knowledgeDocService.findBySourceTicketId(TICKET)).thenReturn(List.of());
            when(ticketService.getTicketWithTags(TICKET)).thenReturn(ticket());
            when(knowledgeDocService.create(any(), any(), eq(false), any())).thenReturn(SAVED);

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            CountDownLatch created = new CountDownLatch(1);
            when(pmService.getPostmortem(any())).thenAnswer(inv -> {
                entered.countDown();
                boolean go = proceed.await(2, TimeUnit.SECONDS);
                if (!go) throw new RuntimeException("proceed latch timeout");
                return pm();
            });
            when(ticketService.getTicketWithTags(any())).thenAnswer(inv -> ticket());
            when(knowledgeDocService.create(any(), any(), eq(false), any())).thenAnswer(inv -> {
                created.countDown();
                return SAVED;
            });

            orchestrator.submitDraftAsync(TICKET);
            // 等任务真正进入执行（在途标记已加、正在跑守卫）
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 在途期间再来一次保存：应被去重挡掉，而不是排队
            orchestrator.submitDraftAsync(TICKET);

            proceed.countDown();
            assertThat(created.await(2, TimeUnit.SECONDS)).isTrue();

            // 全程只有一次 create——第二次触发没入队
            verify(knowledgeDocService, timeout(2000).times(1))
                    .create(any(), any(), eq(false), any());
        }

        private DevOpsTicket ticket() {
            DevOpsTicket t = new DevOpsTicket();
            t.setId(TICKET);
            t.setTitle("title");
            t.setDescription("desc");
            return t;
        }

        private TicketPostmortem pm() {
            TicketPostmortem pm = new TicketPostmortem();
            pm.setId(1L);
            pm.setTicketId(TICKET);
            return pm;
        }
    }
}