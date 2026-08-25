package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.rag.KnowledgeStatsService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KnowledgeManageController} HTTP 契约测试。
 *
 * <h3>这个类里最该守的是一个「已停用端点」</h3>
 * {@code POST /knowledge/ingest} 已废弃并返回 40010。它的历史值得记住：
 *
 * <p>该端点摄取的切片 {@code doc_id=NULL}（孤儿切片），
 * 无法被文档生命周期治理覆盖——{@code deprecate}/{@code purge} 按 doc_id 清理，
 * <b>永远删不到它们，而它们仍参与检索，持续污染结果</b>。
 * 用户以为自己删掉了一篇过时文档，AI 却还在拿它回答问题。</p>
 *
 * <p>此前的修法是加 {@code @Deprecated} 注解 + 打 WARN 日志，
 * 但<b>端点仍会真实执行摄取</b>——等于一边警告一边继续制造孤儿数据。
 * 后来改为直接返回 410 语义（业务码 40010）不再执行。</p>
 *
 * <p>所以本类有一条断言是「它<b>不能</b>做事」：
 * 调用后必须<b>没有任何摄取行为发生</b>。这类「确保某段代码不再被执行」的约束，
 * 除了测试没有别的办法固定——注释拦不住，编译器也不会报错。</p>
 *
 * <h3>另一处：分页兜底防的是异常与全表扫</h3>
 * {@code page=0} 会让 {@code PageRequest.of(-1, …)} 直接抛异常，
 * {@code size} 无上限则可被用来一次拉全表（切片表是全库最大的表之一）。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = KnowledgeManageController.class,
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
class KnowledgeManageControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private KnowledgeStatsService knowledgeStatsService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static Map<String, Object> chunkPage(int total, int page, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("page", page);
        m.put("size", size);
        m.put("list", List.of());
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("已停用的摄取端点")
    class DeprecatedIngest {

        @Test
        @DisplayName("返回 40010 并给出迁移路径 —— 只说「已废弃」用户不知道该改用什么")
        void ingestReturnsDeprecationWithMigrationPath() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"path\":\"/docs\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40010))
                    .andExpect(jsonPath("$.data.deprecated").value(true))
                    // migrateTo 是这个响应最有价值的部分：
                    // 调用方据此知道改调哪个端点，而不是自己去翻文档
                    .andExpect(jsonPath("$.data.migrateTo").value("POST /api/v1/knowledge/docs"))
                    // reason 说明为什么停用（孤儿切片无法被生命周期治理覆盖）
                    .andExpect(jsonPath("$.data.reason").isNotEmpty());
        }

        @Test
        @DisplayName("无请求体也返回同样的废弃响应，不因缺 body 报 400")
        void ingestWithoutBodyStillReturnsDeprecation() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/ingest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40010));
        }

        @Test
        @DisplayName("**绝不执行任何摄取** —— 这是该端点被停用的全部意义")
        void ingestPerformsNoWork() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"path\":\"/docs\",\"rebuild\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40010));

            // 曾经的修法是「加 @Deprecated + 打 WARN，但照常执行」——
            // 一边警告一边继续制造 doc_id=NULL 的孤儿切片。
            // 那些切片删不掉却仍参与检索，用户以为删了一篇过时文档，
            // AI 却还在拿它回答问题。
            // 这条断言确保它真的什么都不做，注释和编译器都拦不住这种回退
            org.mockito.Mockito.verifyNoInteractions(knowledgeStatsService);
        }
    }

    @Nested
    @DisplayName("统计与切片浏览")
    class StatsAndChunks {

        @Test
        @DisplayName("统计原样下发，带 traceId")
        void statsPassThrough() throws Exception {
            when(knowledgeStatsService.getStats()).thenReturn(Map.of(
                    "totalDocuments", 5, "totalChunks", 62));

            mockMvc.perform(get("/api/v1/knowledge/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.totalDocuments").value(5))
                    .andExpect(jsonPath("$.data.totalChunks").value(62))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("零数据时字段仍在（0 而非缺字段）")
        void zeroStatsKeepFields() throws Exception {
            when(knowledgeStatsService.getStats()).thenReturn(Map.of(
                    "totalDocuments", 0, "totalChunks", 0));

            mockMvc.perform(get("/api/v1/knowledge/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalDocuments").value(0));
        }

        @Test
        @DisplayName("切片列表默认第 1 页 10 条，关键词为 null")
        void chunksDefaultPaging() throws Exception {
            when(knowledgeStatsService.listChunks(eq(1), eq(10), isNull()))
                    .thenReturn(chunkPage(62, 1, 10));

            mockMvc.perform(get("/api/v1/knowledge/chunks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(62))
                    .andExpect(jsonPath("$.data.list").isArray());

            verify(knowledgeStatsService).listChunks(1, 10, null);
        }

        @Test
        @DisplayName("page<1 夹到 1 —— 否则 PageRequest.of(-1,…) 直接抛异常")
        void clampsPageLowerBound() throws Exception {
            when(knowledgeStatsService.listChunks(anyInt(), anyInt(), any()))
                    .thenReturn(chunkPage(0, 1, 10));

            mockMvc.perform(get("/api/v1/knowledge/chunks").param("page", "0"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/knowledge/chunks").param("page", "-3"))
                    .andExpect(status().isOk());

            verify(knowledgeStatsService, org.mockito.Mockito.times(2))
                    .listChunks(eq(1), eq(10), isNull());
        }

        @Test
        @DisplayName("size 夹到 [1,200] —— 上限防的是一次拉全表（切片表是全库最大的之一）")
        void clampsSize() throws Exception {
            when(knowledgeStatsService.listChunks(anyInt(), anyInt(), any()))
                    .thenReturn(chunkPage(0, 1, 200));

            mockMvc.perform(get("/api/v1/knowledge/chunks").param("size", "100000"))
                    .andExpect(status().isOk());
            verify(knowledgeStatsService).listChunks(1, 200, null);

            mockMvc.perform(get("/api/v1/knowledge/chunks").param("size", "0"))
                    .andExpect(status().isOk());
            verify(knowledgeStatsService).listChunks(1, 1, null);
        }

        @Test
        @DisplayName("关键词原样透传（含特殊字符，转义由持久层负责）")
        void keywordPassesThrough() throws Exception {
            when(knowledgeStatsService.listChunks(anyInt(), anyInt(), any()))
                    .thenReturn(chunkPage(0, 1, 10));

            mockMvc.perform(get("/api/v1/knowledge/chunks").param("keyword", "100%_K8s"))
                    .andExpect(status().isOk());

            // Web 层不做转义——在这里转会和持久层的 escapeLike 叠加成双重转义，
            // 用户搜「100%」反而搜不到
            verify(knowledgeStatsService).listChunks(1, 10, "100%_K8s");
        }

        @Test
        @DisplayName("page 传非数字 → 400，而不是 500")
        void nonNumericPageIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/chunks").param("page", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }
}
