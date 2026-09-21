package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.controller.dto.KnowledgeBaseDto;
import com.devops.agent.domain.rag.ChunkProfile;
import com.devops.agent.domain.rag.KnowledgeBase;
import com.devops.agent.domain.rag.KnowledgeBaseService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KnowledgeBaseController} HTTP 契约测试。
 *
 * <h3>覆盖重点</h3>
 * <ul>
 *   <li><b>切片参数的「显式值 vs 跟随默认」双轨呈现</b>：列表项同时带
 *       原始值（可 null）与 effective* 合并值，前端靠它区分「用户配过」
 *       与「跟着全局默认走」——丢了这个区分，用户改掉默认值后会以为
 *       某些库还是显式配置。</li>
 *   <li><b>clearChunkParams 与切片参数互斥</b>：同时传是自相矛盾的请求，
 *       必须在控制器层拒绝（400），不能放进服务层猜用户意图。</li>
 *   <li><b>错误语义分流</b>：IllegalArgumentException → 40001（用户能改），
 *       IllegalStateException → 40400（资源没了），两者混用会让前端
 *       把「库不存在」提示成「参数错误」。</li>
 *   <li><b>reindex-all 的成本如实呈现</b>：返回 total/success/failed 明细，
 *       不能把部分失败吞成「成功」。</li>
 * </ul>
 *
 * <p>权限（requireEdit / requireDestructive）由 {@code KnowledgeWriteGuardTest}
 * 与 {@code KnowledgeWritePermissionWebTest} 覆盖，本切片 mock 放行，
 * 不构成对权限的保证。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = KnowledgeBaseController.class,
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
class KnowledgeBaseControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private KnowledgeBaseService kbService;

    @MockitoBean
    private KnowledgeDocService docService;

    @MockitoBean
    private com.devops.agent.common.guard.KnowledgeWriteGuard writeGuard;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static KnowledgeBase kb(Long id, String code, boolean withCustomParams) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("故障 FAQ 库");
        kb.setCode(code);
        kb.setDescription("短平快的故障处置问答");
        if (withCustomParams) {
            kb.setParentChunkSize(1200);
            kb.setChildChunkSize(300);
            kb.setChunkOverlap(50);
        }
        kb.setStatus(KnowledgeBase.STATUS_ACTIVE);
        kb.setCreateTime(LocalDateTime.of(2026, 9, 1, 10, 0));
        kb.setUpdateTime(LocalDateTime.of(2026, 9, 18, 10, 0));
        return kb;
    }

    /** HashMap 而非 Map.of：请求体需要 null 值（显式字段缺省） */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // ==================================================================

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("列表：原始参数与 effective 合并值同时给出（默认库 effective=全局默认）")
        void listShowsRawAndEffectiveParams() throws Exception {
            when(kbService.findAll()).thenReturn(List.of(
                    kb(1L, "default", false),
                    kb(2L, "faq", true)));
            when(kbService.docStatsByBase()).thenReturn(Map.of(
                    1L, new com.devops.agent.infrastructure.persistence.repo
                            .KnowledgeBaseRepository.KbDocStats(7, 6, 1),
                    2L, new com.devops.agent.infrastructure.persistence.repo
                            .KnowledgeBaseRepository.KbDocStats(3, 3, 0)));

            mockMvc.perform(get("/api/v1/knowledge/bases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    // 默认库：原始值为 null（Jackson 默认序列化 null 字段），
                    // effective 回落全局默认 2400/600/100
                    .andExpect(jsonPath("$.data[0].parentChunkSize")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data[0].effectiveParentChunkSize")
                            .value(ChunkProfile.DEFAULT_PARENT_CHUNK_SIZE))
                    .andExpect(jsonPath("$.data[0].effectiveChildChunkSize")
                            .value(ChunkProfile.DEFAULT_CHILD_CHUNK_SIZE))
                    .andExpect(jsonPath("$.data[0].docCount").value(7))
                    // 索引健康度：失败计数必须透出——它是发现「文档在库里但检索不到」的入口
                    .andExpect(jsonPath("$.data[0].indexedCount").value(6))
                    .andExpect(jsonPath("$.data[0].failedCount").value(1))
                    // 自定义库：原始值与生效值一致
                    .andExpect(jsonPath("$.data[1].parentChunkSize").value(1200))
                    .andExpect(jsonPath("$.data[1].effectiveParentChunkSize").value(1200))
                    .andExpect(jsonPath("$.data[1].docCount").value(3));
        }

        @Test
        @DisplayName("库无文档时健康度归零而不是 null（前端不做空值防御）")
        void listZeroStatsForEmptyBase() throws Exception {
            when(kbService.findAll()).thenReturn(List.of(kb(1L, "default", false)));
            when(kbService.docStatsByBase()).thenReturn(Map.of());

            mockMvc.perform(get("/api/v1/knowledge/bases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].docCount").value(0))
                    .andExpect(jsonPath("$.data[0].indexedCount").value(0))
                    .andExpect(jsonPath("$.data[0].failedCount").value(0));
        }
    }

    @Nested
    @DisplayName("创建")
    class Create {

        @Test
        @DisplayName("创建成功：切片参数可缺省（跟随全局默认）")
        void createSuccess() throws Exception {
            when(kbService.create(any())).thenAnswer(i -> {
                KnowledgeBase k = i.getArgument(0);
                k.setId(3L);
                k.setStatus(KnowledgeBase.STATUS_ACTIVE);
                k.setCreateTime(LocalDateTime.of(2026, 9, 18, 12, 0));
                k.setUpdateTime(LocalDateTime.of(2026, 9, 18, 12, 0));
                return k;
            });

            mockMvc.perform(post("/api/v1/knowledge/bases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "SOP 手册库", "code", "sop",
                                    "description", "长文运维手册"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(3))
                    .andExpect(jsonPath("$.data.docCount").value(0));
        }

        @Test
        @DisplayName("编码重复 / 参数非法 → 40001 且消息原样透传（用户能改的错误）")
        void createDuplicateCode() throws Exception {
            when(kbService.create(any()))
                    .thenThrow(new IllegalArgumentException("知识库编码已存在: sop"));

            mockMvc.perform(post("/api/v1/knowledge/bases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "SOP", "code", "sop"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("知识库编码已存在: sop"));
        }
    }

    @Nested
    @DisplayName("更新")
    class Update {

        @Test
        @DisplayName("clearChunkParams 与切片参数互斥：同传在控制器层直接 400")
        void clearParamsMutuallyExclusiveWithExplicitValues() throws Exception {
            mockMvc.perform(put("/api/v1/knowledge/bases/2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(
                                    "clearChunkParams", true,
                                    "childChunkSize", 300))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("互斥")));

            // 自相矛盾的请求不应到达服务层——服务层无法判断用户到底想干嘛
            verify(kbService, never()).update(anyLong(), any(), anyBoolean());
        }

        @Test
        @DisplayName("库不存在 → 40400（IllegalStateException 语义分流）")
        void updateNotFound() throws Exception {
            when(kbService.update(eq(99L), any(), eq(false)))
                    .thenThrow(new IllegalStateException("知识库不存在: 99"));

            mockMvc.perform(put("/api/v1/knowledge/bases/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "新名字"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("默认库保护：停用默认库 → 40001（服务层规则如实透传）")
        void disableDefaultRejected() throws Exception {
            when(kbService.update(eq(1L), any(), eq(false)))
                    .thenThrow(new IllegalArgumentException("默认知识库不允许停用——未指定库的新建文档将无处落"));

            mockMvc.perform(put("/api/v1/knowledge/bases/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("status", "DISABLED"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("默认知识库不允许停用")));
        }
    }

    @Nested
    @DisplayName("重建库索引")
    class ReindexAll {

        @Test
        @DisplayName("成功：返回 total/success/failed 明细与库名，部分失败不吞")
        void reindexSuccessWithFailures() throws Exception {
            when(kbService.findById(2L)).thenReturn(kb(2L, "faq", true));
            Map<String, Object> serviceResult = new LinkedHashMap<>();
            serviceResult.put("total", 5);
            serviceResult.put("success", 4);
            serviceResult.put("failed", 1);
            serviceResult.put("failures", List.of(Map.of(
                    "docId", 42L, "title", "坏文档", "indexStatus", "FAILED", "error", "embedding 超时")));
            when(docService.reindexByKnowledgeBase(2L)).thenReturn(serviceResult);

            mockMvc.perform(post("/api/v1/knowledge/bases/2/reindex-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.kbId").value(2))
                    .andExpect(jsonPath("$.data.kbName").value("故障 FAQ 库"))
                    .andExpect(jsonPath("$.data.total").value(5))
                    .andExpect(jsonPath("$.data.success").value(4))
                    .andExpect(jsonPath("$.data.failed").value(1))
                    .andExpect(jsonPath("$.data.failures[0].docId").value(42));
        }

        @Test
        @DisplayName("库不存在 → 40400，且不触发任何重建")
        void reindexKbNotFound() throws Exception {
            when(kbService.findById(99L)).thenReturn(null);

            mockMvc.perform(post("/api/v1/knowledge/bases/99/reindex-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));

            verify(docService, never()).reindexByKnowledgeBase(anyLong());
        }
    }
}
