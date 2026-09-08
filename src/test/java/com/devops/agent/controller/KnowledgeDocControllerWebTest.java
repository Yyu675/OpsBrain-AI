package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocDiff;
import com.devops.agent.domain.rag.KnowledgeDocService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KnowledgeDocController} HTTP 契约测试。
 *
 * <h3>剩余未覆盖的 Controller 里它优先级最高</h3>
 * 342 行、15 个端点，且集中了三类最容易出错的东西：
 * <b>两个并行的状态机、乐观锁、不可逆操作</b>。
 *
 * <h3>覆盖重点一：status 与 indexStatus 是两个状态机，绝不能混用</h3>
 * 这是 6.21 明确记下的决策，也是本类最重要的一组断言：
 * <ul>
 *   <li>{@code status} —— 文档<b>生命周期</b>：DRAFT / PUBLISHED / DEPRECATED；</li>
 *   <li>{@code indexStatus} —— <b>向量化</b>状态：INDEXED / SKIPPED / UNCHANGED / FAILED。</li>
 * </ul>
 * 「已发布」不等于「可检索」。一篇 PUBLISHED 但向量化 FAILED 的文档，
 * 在知识库列表里看着好好的，<b>AI 检索时却永远命中不到它</b>——
 * 用户会以为「知识库里没这条」，实际是索引挂了。
 * 所以响应里 {@code retrievable} 必须独立于 {@code status} 给出，
 * 且 FAILED 时要带 {@code indexError} 说明原因。
 *
 * <h3>覆盖重点二：「删除」的默认语义是废弃，不是物理删</h3>
 * {@code /deprecate} 保留正文供历史查阅、只删向量使其退出检索；
 * {@code /purge} 才是物理删除，<b>不可逆</b>，因而限 ADMIN 且强制合规理由。
 * 两者若被前端调混，用户点一下「删除」就永久销毁了文档。
 *
 * <h3>覆盖重点三：四种 IndexOutcome 必须如实传达</h3>
 * 只回「成功/失败」两态的话，用户无法区分「还没建索引」（SKIPPED，草稿本就不该建）
 * 与「建索引失败」（FAILED，需要重试）。这两者的处置动作完全不同。
 *
 * <p>切片装配沿用 {@code TicketControllerWebTest} 的说明。
 * 注意 {@code /purge} 上的 {@code @SaCheckRole("ADMIN")} 由注册在
 * {@code WebConfig} 里的拦截器执行，本切片排除了它——
 * 因此<b>本类不构成对该端点权限的任何保证</b>，权限需由鉴权集成测试覆盖。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = KnowledgeDocController.class,
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
class KnowledgeDocControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    /**
     * F-5 知识库写权限守卫。
     *
     * <p>必须 mock：它是 @Component，@WebMvcTest 切片不会加载它，
     * 不 mock 会让整个上下文启动失败。mock 后默认放行（void 方法不抛异常），
     * 既有用例的语义不变——它们测的是业务行为，不是权限。
     * 权限本身由 KnowledgeWriteGuardTest 与 KnowledgeWritePermissionWebTest 覆盖。</p>
     */
    @MockitoBean
    private com.devops.agent.common.guard.KnowledgeWriteGuard writeGuard;

    @MockitoBean
    private KnowledgeDocService docService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static KnowledgeDoc doc(Long id, String status, String indexStatus) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setId(id);
        d.setTitle("K8s Pod 排障手册");
        d.setCategory("容器/K8s");
        d.setAuthor("张明");
        d.setContent("第一行\n第二行");
        d.setSummary("排查 CrashLoopBackOff");
        d.setVersion(3);
        d.setStatus(status);
        d.setIndexStatus(indexStatus);
        d.setCreateTime(LocalDateTime.of(2026, 8, 1, 10, 0));
        d.setUpdateTime(LocalDateTime.of(2026, 8, 20, 10, 0));
        return d;
    }

    private static KnowledgeDocService.SaveResult saveResult(
            KnowledgeDocService.IndexOutcome outcome,
            List<KnowledgeDocService.NearDuplicate> near) {
        return new KnowledgeDocService.SaveResult(7L, 4, near, outcome);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** HashMap 而非 Map.of：请求里多处需要 null 值（categoryId、version 等） */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("创建：两道去重关")
    class Create {

        @Test
        @DisplayName("创建草稿：status=DRAFT，且向量化被 SKIPPED（草稿本就不该建索引）")
        void draftSkipsIndexing() throws Exception {
            when(docService.create(any(), any(), eq(false), anyString()))
                    .thenReturn(saveResult(KnowledgeDocService.IndexOutcome.skipped(), List.of()));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "新手册", "content", "正文",
                                    "publish", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    // SKIPPED 不是失败——草稿不建索引是正确行为。
                    // 若前端把它当失败提示「向量化失败」，用户会去反复重试一个本就不该做的事
                    .andExpect(jsonPath("$.data.indexStatus").value("SKIPPED"))
                    .andExpect(jsonPath("$.data.retrievable").value(false))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("发布态创建：status=PUBLISHED 且 retrievable=true")
        void publishedCreateIsRetrievable() throws Exception {
            when(docService.create(any(), any(), eq(true), anyString()))
                    .thenReturn(saveResult(
                            KnowledgeDocService.IndexOutcome.indexed(12, 2), List.of()));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "新手册", "content", "正文",
                                    "publish", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.indexStatus").value("INDEXED"))
                    .andExpect(jsonPath("$.data.retrievable").value(true));
        }

        @Test
        @DisplayName("已发布但向量化失败：status=PUBLISHED 而 retrievable=false，且带 indexError")
        void publishedButIndexFailedIsNotRetrievable() throws Exception {
            when(docService.create(any(), any(), eq(true), anyString()))
                    .thenReturn(saveResult(
                            KnowledgeDocService.IndexOutcome.failed("embedding API 超时"), List.of()));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "新手册", "content", "正文",
                                    "publish", true))))
                    .andExpect(status().isOk())
                    // 这一组是本类最重要的断言：文档在列表里显示「已发布」，
                    // 但 AI 检索永远命中不到它。若不把 retrievable 独立给出，
                    // 用户会以为「知识库里没这条」，而实际是索引挂了
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.indexStatus").value("FAILED"))
                    .andExpect(jsonPath("$.data.retrievable").value(false))
                    // 失败原因必须给出，否则用户不知道是该重试还是该改内容
                    .andExpect(jsonPath("$.data.indexError").value("embedding API 超时"));
        }

        @Test
        @DisplayName("内容完全重复 → 40021，并附重复文档 ID 供跳转")
        void exactDuplicateRejectedWithDocId() throws Exception {
            when(docService.create(any(), any(), anyBoolean(), anyString()))
                    .thenThrow(new KnowledgeDocService.DuplicateContentException(
                            "内容与已有文档重复", 42L, "K8s Pod 排障手册"));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "抄的", "content", "正文"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40021))
                    // 只说「重复」而不给 ID，用户无从判断跟哪篇重复、也跳不过去
                    .andExpect(jsonPath("$.data.duplicateDocId").value(42))
                    .andExpect(jsonPath("$.data.duplicateTitle").value("K8s Pod 排障手册"));
        }

        @Test
        @DisplayName("近似重复只告警不拒绝：code=0 但带 nearDuplicates")
        void nearDuplicateWarnsButAllows() throws Exception {
            when(docService.create(any(), any(), anyBoolean(), anyString()))
                    .thenReturn(saveResult(
                            KnowledgeDocService.IndexOutcome.indexed(5, 0),
                            List.of(new KnowledgeDocService.NearDuplicate(9L, "相似手册", 3))));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "改写版", "content", "正文",
                                    "publish", true))))
                    .andExpect(status().isOk())
                    // 近似不等于抄袭——可能是同一主题的不同视角，
                    // 直接拒绝会挡住合理的新增，所以只告警
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.nearDuplicates[0].docId").value(9))
                    .andExpect(jsonPath("$.data.nearDuplicates[0].distance").value(3));
        }

        @Test
        @DisplayName("参数非法 → 40001")
        void invalidArgumentMapsTo40001() throws Exception {
            when(docService.create(any(), any(), anyBoolean(), anyString()))
                    .thenThrow(new IllegalArgumentException("标题不能为空"));

            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("content", "正文"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("标题不能为空"));
        }

        @Test
        @DisplayName("请求体畸形 → 400，而不是 500")
        void malformedBodyIsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/docs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ broken"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("更新：乐观锁")
    class Update {

        @Test
        @DisplayName("version 与 changeReason 一并传给 Service")
        void passesVersionAndReason() throws Exception {
            when(docService.update(anyLong(), any(), any(), anyInt(), anyString(), anyString()))
                    .thenReturn(saveResult(KnowledgeDocService.IndexOutcome.indexed(6, 1), List.of()));
            when(docService.findById(eq(7L), eq(false)))
                    .thenReturn(doc(7L, "PUBLISHED", "INDEXED"));

            mockMvc.perform(put("/api/v1/knowledge/docs/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "改标题", "content", "新正文",
                                    "version", 3, "changeReason", "补充回滚步骤"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value(4));

            verify(docService).update(eq(7L), any(), any(), eq(3), anyString(),
                    eq("补充回滚步骤"));
        }

        @Test
        @DisplayName("版本冲突 → 40009/409，绝不静默覆盖他人修改")
        void optimisticLockMapsTo40009() throws Exception {
            when(docService.update(anyLong(), any(), any(), any(), anyString(), any()))
                    .thenThrow(new OptimisticLockException("doc:7", 3, 5));

            mockMvc.perform(put("/api/v1/knowledge/docs/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "改标题", "version", 3))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40009))
                    // 提示要给出可执行的下一步，不能只说「冲突」
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("请刷新")));
        }

        @Test
        @DisplayName("内容未变时 indexStatus=UNCHANGED（零 API 调用），且仍可检索")
        void unchangedContentReusesIndex() throws Exception {
            when(docService.update(anyLong(), any(), any(), anyInt(), anyString(), any()))
                    .thenReturn(saveResult(KnowledgeDocService.IndexOutcome.unchanged(), List.of()));
            when(docService.findById(eq(7L), eq(false)))
                    .thenReturn(doc(7L, "PUBLISHED", "INDEXED"));

            mockMvc.perform(put("/api/v1/knowledge/docs/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "只改了标题", "version", 3))))
                    .andExpect(status().isOk())
                    // UNCHANGED 与 INDEXED 都算可检索。若把 UNCHANGED 当成
                    // 「没建索引」，用户每改一次标题都会看到「不可检索」的假警报
                    .andExpect(jsonPath("$.data.indexStatus").value("UNCHANGED"))
                    .andExpect(jsonPath("$.data.retrievable").value(true));
        }

        @Test
        @DisplayName("文档在更新后查不到时 status 给 null 而不是崩溃")
        void missingDocAfterUpdateYieldsNullStatus() throws Exception {
            when(docService.update(anyLong(), any(), any(), anyInt(), anyString(), any()))
                    .thenReturn(saveResult(KnowledgeDocService.IndexOutcome.indexed(1, 0), List.of()));
            when(docService.findById(anyLong(), anyBoolean())).thenReturn(null);

            mockMvc.perform(put("/api/v1/knowledge/docs/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("title", "x", "version", 3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").doesNotExist())
                    .andExpect(jsonPath("$.data.retrievable").value(false));
        }
    }

    @Nested
    @DisplayName("生命周期：废弃 ≠ 物理删")
    class Lifecycle {

        @Test
        @DisplayName("发布：返回向量化结果")
        void publishReturnsIndexOutcome() throws Exception {
            when(docService.publish(eq(7L), anyString()))
                    .thenReturn(KnowledgeDocService.IndexOutcome.indexed(9, 0));

            mockMvc.perform(post("/api/v1/knowledge/docs/7/publish"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.indexStatus").value("INDEXED"))
                    .andExpect(jsonPath("$.data.retrievable").value(true));
        }

        @Test
        @DisplayName("发布但索引失败：仍 code=0，但 retrievable=false 并给出原因")
        void publishWithFailedIndexIsHonest() throws Exception {
            when(docService.publish(anyLong(), anyString()))
                    .thenReturn(KnowledgeDocService.IndexOutcome.failed("向量库连接失败"));

            mockMvc.perform(post("/api/v1/knowledge/docs/7/publish"))
                    .andExpect(status().isOk())
                    // 发布这个动作本身成功了（状态已改），失败的是向量化。
                    // 报成整体失败会让用户以为文档没发布出去而重复操作
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.retrievable").value(false))
                    .andExpect(jsonPath("$.data.indexError").value("向量库连接失败"));
        }

        @Test
        @DisplayName("废弃：状态置 DEPRECATED，理由透传")
        void deprecatePassesReason() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/docs/7/deprecate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "流程已变更"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DEPRECATED"));

            verify(docService).deprecate(eq(7L), anyString(), eq("流程已变更"));
        }

        @Test
        @DisplayName("废弃无请求体也可以（理由选填）")
        void deprecateBodyIsOptional() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/docs/7/deprecate"))
                    .andExpect(status().isOk());

            verify(docService).deprecate(eq(7L), anyString(), isNull());
        }

        @Test
        @DisplayName("废弃不会调用物理删除 —— 这是「删除」默认语义的核心保证")
        void deprecateNeverPurges() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/docs/7/deprecate"))
                    .andExpect(status().isOk());

            // 若哪天有人把 deprecate 的实现改成 purge，正文就被永久销毁了。
            // 废弃保留正文供历史查阅，只删向量使其退出检索
            verify(docService, never()).purge(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("物理删除：合规理由透传给 Service（缺理由由 Service 拒绝）")
        void purgePassesComplianceReason() throws Exception {
            mockMvc.perform(delete("/api/v1/knowledge/docs/7/purge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("complianceReason", "含个人隐私，依 GDPR 删除"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(true));

            verify(docService).purge(eq(7L), anyString(),
                    eq("含个人隐私，依 GDPR 删除"));
        }

        @Test
        @DisplayName("物理删除缺合规理由 → 40001（不可逆操作必须留下书面依据）")
        void purgeWithoutReasonRejected() throws Exception {
            doThrow(new IllegalArgumentException("物理删除必须提供合规理由"))
                    .when(docService).purge(anyLong(), anyString(), isNull());

            mockMvc.perform(delete("/api/v1/knowledge/docs/7/purge"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("回滚到历史版本：返回新版本号")
        void restoreReturnsNewVersion() throws Exception {
            when(docService.restore(eq(7L), eq(2), anyString()))
                    .thenReturn(saveResult(KnowledgeDocService.IndexOutcome.indexed(8, 0), List.of()));

            mockMvc.perform(post("/api/v1/knowledge/docs/7/restore")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 2))))
                    .andExpect(status().isOk())
                    // 回滚产生的是新版本（4）而不是退回到 2——
                    // 历史必须只增不减，否则回滚本身无从追溯
                    .andExpect(jsonPath("$.data.version").value(4))
                    .andExpect(jsonPath("$.data.retrievable").value(true));

            verify(docService).restore(7L, 2, "SYSTEM");
        }
    }

    @Nested
    @DisplayName("查询与分页")
    class Query {

        @Test
        @DisplayName("默认第 1 页 10 条，分页元信息完整")
        void defaultPaging() throws Exception {
            when(docService.findPage(eq(1), eq(10), any(), any(), any(), any(), eq("UPDATED_DESC")))
                    .thenReturn(List.of(doc(1L, "PUBLISHED", "INDEXED")));
            when(docService.countByQuery(any(), any(), any(), any())).thenReturn(21L);

            mockMvc.perform(get("/api/v1/knowledge/docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentPage").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(21))
                    // 21 条 / 每页 10 → 3 页；用错 size 算会给出翻不到的页码
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andExpect(jsonPath("$.data.content[0].title").value("K8s Pod 排障手册"));
        }

        @Test
        @DisplayName("列表项不含正文 —— 列表页拉全文会让响应体膨胀几个数量级")
        void listItemsExcludeContent() throws Exception {
            when(docService.findPage(anyInt(), anyInt(), any(), any(), any(), any(), anyString()))
                    .thenReturn(List.of(doc(1L, "PUBLISHED", "INDEXED")));
            when(docService.countByQuery(any(), any(), any(), any())).thenReturn(1L);

            mockMvc.perform(get("/api/v1/knowledge/docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].content").doesNotExist())
                    // 但摘要要有，否则列表页无法预览
                    .andExpect(jsonPath("$.data.content[0].summary").isNotEmpty());
        }

        @Test
        @DisplayName("分页参数钳制：page≥1、size∈[1,200]")
        void clampsPaging() throws Exception {
            when(docService.findPage(anyInt(), anyInt(), any(), any(), any(), any(), anyString()))
                    .thenReturn(List.of());
            when(docService.countByQuery(any(), any(), any(), any())).thenReturn(0L);

            mockMvc.perform(get("/api/v1/knowledge/docs").param("page", "0").param("size", "9999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentPage").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(200));

            verify(docService).findPage(eq(1), eq(200), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("筛选条件透传，且列表与计数用同一套条件")
        void filtersAreConsistentBetweenListAndCount() throws Exception {
            when(docService.findPage(anyInt(), anyInt(), any(), any(), any(), any(), anyString()))
                    .thenReturn(List.of());
            when(docService.countByQuery(any(), any(), any(), any())).thenReturn(0L);

            mockMvc.perform(get("/api/v1/knowledge/docs")
                            .param("status", "PUBLISHED")
                            .param("category", "容器/K8s")
                            .param("keyword", "CrashLoop")
                            .param("tag", "k8s"))
                    .andExpect(status().isOk());

            // 条件不一致会出现「列表 0 条但总数 100」这种自相矛盾
            verify(docService).findPage(eq(1), eq(10), eq("PUBLISHED"), eq("容器/K8s"),
                    eq("CrashLoop"), eq("k8s"), anyString());
            verify(docService).countByQuery(eq("PUBLISHED"), eq("容器/K8s"),
                    eq("CrashLoop"), eq("k8s"));
        }

        @Test
        @DisplayName("详情含正文；不存在 → 40004")
        void detailIncludesContentAndHandlesMissing() throws Exception {
            when(docService.findById(eq(7L), eq(true)))
                    .thenReturn(doc(7L, "PUBLISHED", "INDEXED"));

            mockMvc.perform(get("/api/v1/knowledge/docs/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isNotEmpty())
                    .andExpect(jsonPath("$.data.version").value(3));

            when(docService.findById(eq(999L), eq(true))).thenReturn(null);
            mockMvc.perform(get("/api/v1/knowledge/docs/999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("热门标签 limit 夹到 [1,100]")
        void clampsHotTagLimit() throws Exception {
            when(docService.findHotTags(anyInt())).thenReturn(new LinkedHashMap<>(Map.of("k8s", 5)));

            mockMvc.perform(get("/api/v1/knowledge/docs/tags/hot").param("limit", "9999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].tag").value("k8s"))
                    .andExpect(jsonPath("$.data[0].count").value(5));

            verify(docService).findHotTags(100);
        }

        @Test
        @DisplayName("按源工单反查：无沉淀时返回空数组而非 null")
        void bySourceTicketReturnsEmptyArray() throws Exception {
            when(docService.findBySourceTicketId(123L)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/knowledge/docs/by-source-ticket/123"))
                    .andExpect(status().isOk())
                    // 工单详情页据此渲染「已沉淀为知识」徽标，null 会让前端判空逻辑各写各的
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Nested
    @DisplayName("版本历史与对比")
    class Versions {

        @Test
        @DisplayName("版本列表带 docId，便于前端关联")
        void listVersions() throws Exception {
            when(docService.listVersions(7L)).thenReturn(List.of(
                    Map.of("version", 3, "changeReason", "补充回滚步骤")));

            mockMvc.perform(get("/api/v1/knowledge/docs/7/versions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.docId").value(7))
                    .andExpect(jsonPath("$.data.versions[0].version").value(3));
        }

        @Test
        @DisplayName("取历史版本全文；不存在 → 40004")
        void getVersionContent() throws Exception {
            when(docService.findVersion(7L, 2)).thenReturn(doc(7L, "PUBLISHED", "INDEXED"));

            mockMvc.perform(get("/api/v1/knowledge/docs/7/versions/2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isNotEmpty());

            when(docService.findVersion(7L, 99)).thenReturn(null);
            mockMvc.perform(get("/api/v1/knowledge/docs/7/versions/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("对比返回三段式差异")
        void compareReturnsSegments() throws Exception {
            when(docService.compareVersions(eq(7L), eq(2), eq(3)))
                    .thenReturn(new KnowledgeDocService.VersionDiffData(
                            doc(7L, "PUBLISHED", "INDEXED"),
                            doc(7L, "PUBLISHED", "INDEXED"),
                            List.of(new KnowledgeDocDiff.DiffSegment(
                                            KnowledgeDocDiff.DiffSegment.Type.EQUAL, List.of("第一行")),
                                    new KnowledgeDocDiff.DiffSegment(
                                            KnowledgeDocDiff.DiffSegment.Type.INSERT, List.of("新增行")))));

            mockMvc.perform(get("/api/v1/knowledge/docs/7/compare")
                            .param("fromV", "2").param("toV", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fromVersion").value(2))
                    .andExpect(jsonPath("$.data.toVersion").value(3))
                    .andExpect(jsonPath("$.data.segments[0].type").value("EQUAL"))
                    .andExpect(jsonPath("$.data.segments[1].type").value("INSERT"));
        }

        @Test
        @DisplayName("fromV > toV 自动交换 —— 参数写反不该报错")
        void compareSwapsReversedVersions() throws Exception {
            when(docService.compareVersions(anyLong(), anyInt(), anyInt()))
                    .thenReturn(new KnowledgeDocService.VersionDiffData(
                            doc(7L, "PUBLISHED", "INDEXED"),
                            doc(7L, "PUBLISHED", "INDEXED"),
                            List.of()));

            mockMvc.perform(get("/api/v1/knowledge/docs/7/compare")
                            .param("fromV", "5").param("toV", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fromVersion").value(2))
                    .andExpect(jsonPath("$.data.toVersion").value(5));

            verify(docService).compareVersions(7L, 2, 5);
        }

        @Test
        @DisplayName("版本号非正 → 40001，且不发起查询")
        void compareRejectsNonPositiveVersion() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/docs/7/compare")
                            .param("fromV", "0").param("toV", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(docService, never()).compareVersions(anyLong(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("对比的版本不存在 → 40004")
        void compareMissingVersionMapsTo40004() throws Exception {
            when(docService.compareVersions(anyLong(), anyInt(), anyInt()))
                    .thenThrow(new IllegalArgumentException("版本 9 不存在"));

            mockMvc.perform(get("/api/v1/knowledge/docs/7/compare")
                            .param("fromV", "2").param("toV", "9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }
    }

    @Test
    @DisplayName("重建索引：返回成功条数 —— 没有这个补偿入口，一次网络抖动就让文档永久不可检索")
    void retryIndexingReturnsCount() throws Exception {
        when(docService.retryFailedIndexing(20)).thenReturn(3);

        mockMvc.perform(post("/api/v1/knowledge/docs/reindex/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retried").value(3));

        verify(docService).retryFailedIndexing(20);
    }
}
