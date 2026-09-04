package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.rag.KnowledgeTag;
import com.devops.agent.domain.rag.KnowledgeTagService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
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
 * {@link KnowledgeTagController} HTTP 契约测试。
 *
 * <h3>标签操作里有两个「会悄悄改动别人数据」的动作</h3>
 * 列表和改名都很直白，真正需要测试守着的是这两个：
 * <ul>
 *   <li><b>合并</b>——把源标签下的所有文档改挂到目标标签，然后删掉源标签。
 *       这是一次批量写，影响面可能是几百篇文档，<b>且不可撤销</b>；</li>
 *   <li><b>删除时的替换标签</b>——{@code replacementId} 为 null 表示
 *       「直接删，文档就此失去这个标签」，非 null 表示「先改挂再删」。
 *       这两种语义的结果完全不同，而它们只差一个可选字段。</li>
 * </ul>
 *
 * <p>因此本类重点断言 <b>{@code replacementId} 的 null 与非 null 都要如实传给
 * Service</b>——Web 层若擅自把 null 补成某个默认值，或反过来把有值的情况
 * 当成 null，用户点「删除并改挂到 X」时文档会直接失去标签，
 * 而界面上不会有任何异常提示。</p>
 *
 * <h3>另一处值得注意：create 没有 try-catch</h3>
 * 同一个类里 {@code rename}/{@code merge}/{@code delete} 都自己 catch 了
 * {@code IllegalArgumentException} 并返回 200 + 40001，
 * 而 {@code create} 没有，会落到 {@link GlobalExceptionHandler} 返回 <b>400</b> + 40001。
 * 同一组端点里两种状态码，前端 http 客户端同时看 HTTP 状态与业务码，
 * 这个差异会实打实影响错误分支。这里把<b>当前真实行为</b>钉住——
 * 将来统一时测试会变红，提醒同步改前端。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = KnowledgeTagController.class,
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
class KnowledgeTagControllerWebTest {

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
    private KnowledgeTagService tagService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static KnowledgeTag tag(Long id, String name, long usage) {
        return new KnowledgeTag(id, name, "容器相关", "#1677ff", usage);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** HashMap：多处需要显式 null（description/color/replacementId） */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("查询与创建")
    class Basic {

        @Test
        @DisplayName("列表带 usageCount —— 用户据此判断哪个标签值得合并")
        void listCarriesUsageCount() throws Exception {
            when(tagService.findAll()).thenReturn(List.of(tag(1L, "k8s", 42)));

            mockMvc.perform(get("/api/v1/knowledge/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].name").value("k8s"))
                    // 没有使用次数，用户无从判断「这个标签还有人用吗」
                    .andExpect(jsonPath("$.data[0].usageCount").value(42))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("空标签库返回空数组而非 null")
        void emptyListIsArray() throws Exception {
            when(tagService.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/knowledge/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("创建：三个字段原样透传")
        void createPassesAllFields() throws Exception {
            when(tagService.create(anyString(), any(), any())).thenReturn(tag(9L, "mysql", 0));

            mockMvc.perform(post("/api/v1/knowledge/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "mysql", "description", "数据库",
                                    "color", "#f5222d"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(9));

            verify(tagService).create("mysql", "数据库", "#f5222d");
        }

        @Test
        @DisplayName("description/color 省略时传 null，由 Service 决定默认值")
        void nullOptionalFieldsForwardedAsNull() throws Exception {
            when(tagService.create(anyString(), any(), any())).thenReturn(tag(9L, "mysql", 0));

            mockMvc.perform(post("/api/v1/knowledge/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "mysql"))))
                    .andExpect(status().isOk());

            // Web 层自作主张补默认色，会让 Service 永远收不到「未指定」这个信号
            verify(tagService).create(eq("mysql"), isNull(), isNull());
        }

        @Test
        @DisplayName("创建重名 → 400 + 40001（无本地 catch，走全局处理器）")
        void createDuplicateGoesThroughGlobalHandler() throws Exception {
            when(tagService.create(anyString(), any(), any()))
                    .thenThrow(new IllegalArgumentException("标签名称已存在"));

            mockMvc.perform(post("/api/v1/knowledge/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "k8s"))))
                    // 注意是 400——本类里唯一走全局处理器的端点，
                    // 其余端点自己 catch 后返回 200。这个差异被刻意钉住
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("标签名称已存在"));
        }
    }

    @Nested
    @DisplayName("改名")
    class Rename {

        @Test
        @DisplayName("路径 id 与请求体一并传给 Service")
        void renamePassesPathId() throws Exception {
            when(tagService.rename(anyLong(), anyString(), any(), any()))
                    .thenReturn(tag(3L, "kubernetes", 42));

            mockMvc.perform(put("/api/v1/knowledge/tags/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "kubernetes", "color", "#52c41a"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("kubernetes"));

            verify(tagService).rename(3L, "kubernetes", null, "#52c41a");
        }

        @Test
        @DisplayName("改名冲突 → 200 + 40001（本地 catch 的现状）")
        void renameConflictYields40001() throws Exception {
            when(tagService.rename(anyLong(), anyString(), any(), any()))
                    .thenThrow(new IllegalStateException("已存在同名标签"));

            mockMvc.perform(put("/api/v1/knowledge/tags/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "mysql"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("已存在同名标签"));
        }

        @Test
        @DisplayName("未预期异常 → 50001")
        void renameUnexpectedErrorYields50001() throws Exception {
            when(tagService.rename(anyLong(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("connection reset"));

            mockMvc.perform(put("/api/v1/knowledge/tags/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "x"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50001));
        }

        @Test
        @DisplayName("路径 id 非数字 → 400，而不是 500")
        void nonNumericIdIsBadRequest() throws Exception {
            mockMvc.perform(put("/api/v1/knowledge/tags/undefined")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body("name", "x"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("合并与删除（批量改动他人数据的两个动作）")
    class MergeAndDelete {

        @Test
        @DisplayName("合并：源 id 取自路径，目标 id 取自请求体，顺序不能颠倒")
        void mergePassesSourceAndTargetInOrder() throws Exception {
            when(tagService.merge(anyLong(), anyLong())).thenReturn(tag(5L, "kubernetes", 60));

            mockMvc.perform(post("/api/v1/knowledge/tags/3/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("targetId", 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.usageCount").value(60));

            // 参数顺序颠倒会把「把 3 合并进 5」变成「把 5 合并进 3」——
            // 结果是保留了错误的那个标签，且这是一次不可撤销的批量写
            verify(tagService).merge(3L, 5L);
        }

        @Test
        @DisplayName("合并到不存在的目标 → 40001")
        void mergeInvalidTargetYields40001() throws Exception {
            when(tagService.merge(anyLong(), anyLong()))
                    .thenThrow(new IllegalArgumentException("目标标签不存在"));

            mockMvc.perform(post("/api/v1/knowledge/tags/3/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("targetId", 999))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("合并到自身 → 40001（否则会把标签合并进自己再删掉，文档全部失去该标签）")
        void mergeIntoSelfRejected() throws Exception {
            when(tagService.merge(anyLong(), anyLong()))
                    .thenThrow(new IllegalStateException("不能合并到自身"));

            mockMvc.perform(post("/api/v1/knowledge/tags/3/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("targetId", 3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("删除带替换标签：replacementId 必须原样传下去")
        void deleteWithReplacementPassesId() throws Exception {
            mockMvc.perform(delete("/api/v1/knowledge/tags/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("replacementId", 8))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(3))
                    .andExpect(jsonPath("$.data.deleted").value(true));

            // 丢掉 replacementId 会让「删除并改挂到 8」变成「直接删」，
            // 几百篇文档就此失去标签，而界面上不会有任何异常提示
            verify(tagService).delete(3L, 8L);
        }

        @Test
        @DisplayName("删除不带请求体：replacementId 为 null，语义是「直接删」")
        void deleteWithoutBodyPassesNull() throws Exception {
            mockMvc.perform(delete("/api/v1/knowledge/tags/3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(true));

            verify(tagService).delete(eq(3L), isNull());
        }

        @Test
        @DisplayName("删除时请求体里 replacementId 显式为 null，同样按「直接删」处理")
        void deleteWithExplicitNullReplacement() throws Exception {
            Map<String, Object> b = new HashMap<>();
            b.put("replacementId", null);

            mockMvc.perform(delete("/api/v1/knowledge/tags/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(b)))
                    .andExpect(status().isOk());

            verify(tagService).delete(eq(3L), isNull());
        }

        @Test
        @DisplayName("删除被拒时不返回 deleted=true —— 否则前端会把还在的标签从列表移除")
        void refusedDeleteDoesNotClaimSuccess() throws Exception {
            doThrow(new IllegalStateException("该标签仍被 42 篇文档使用，请先指定替换标签"))
                    .when(tagService).delete(anyLong(), any());

            mockMvc.perform(delete("/api/v1/knowledge/tags/3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("42 篇")));
        }

        @Test
        @DisplayName("合并端点只接受 POST，GET 返回 405 而不是 500")
        void mergeRejectsWrongMethod() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/tags/3/merge"))
                    .andExpect(status().isMethodNotAllowed());

            verify(tagService, never()).merge(anyLong(), anyLong());
        }
    }
}
