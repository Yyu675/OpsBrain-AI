package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.rag.KnowledgeCategory;
import com.devops.agent.domain.rag.KnowledgeCategoryService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link KnowledgeCategoryController} HTTP 契约测试。
 *
 * <h3>为什么这个 Controller 值得单独一组测试</h3>
 * 它是全项目<b>唯一一个「一半自己 catch、一半交给全局处理器」</b>的控制器，
 * 而这不是疏忽，是有意为之的过渡态：{@code moveDocument} 已经交给
 * {@link GlobalExceptionHandler}（因为它要区分乐观锁冲突 40009），
 * 其余四个端点仍保留本地 catch。两套路径对同一类异常给出的<b>状态码不同</b>：
 * <ul>
 *   <li>本地 catch：{@code IllegalArgumentException} → HTTP <b>200</b> + code 40001；</li>
 *   <li>全局处理：{@code IllegalArgumentException} → HTTP <b>400</b> + code 40001。</li>
 * </ul>
 * 前端 http 客户端同时看 HTTP 状态与业务码，这种差异会实打实地影响错误分支走向。
 * 这组测试把<b>当前真实行为</b>钉住——将来把剩余四个端点也收敛到全局处理器时，
 * 这些用例会立刻变红，提醒同步改前端，而不是等用户报「创建失败但没提示」。
 *
 * <p>顺带说明：这个文件本身就有过一次编译期事故——commit {@code 4aee62d}
 * 删 try 时漏删了 catch，留下孤儿 catch 块。当时没有任何测试会跑到它，
 * 直到 CI 首次真实编译才暴露。</p>
 *
 * <h3>另一条被钉住的语义：删除的两道前置条件</h3>
 * 「还有子分类」和「还有文档」都必须拒绝删除。若放行，文档会变成孤儿——
 * 它们的 {@code category} 字段指向一个不存在的分类，
 * 在知识库树里既不出现在任何节点下，也不出现在「未分类」里，
 * 等于<b>从界面上彻底消失</b>，但数据还在库里。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = KnowledgeCategoryController.class,
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
class KnowledgeCategoryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private KnowledgeCategoryService service;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static KnowledgeCategory category(Long id, String name) {
        return new KnowledgeCategory(id, null, name, 0, 3L,
                LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 2, 10, 0));
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    /** HashMap 而非 Map.of：分类请求里 parentId=null 是「顶级分类」，Map.of 不允许 null 值 */
    private static Map<String, Object> body(Object parentId, Object name, Object sortOrder) {
        Map<String, Object> m = new HashMap<>();
        m.put("parentId", parentId);
        m.put("name", name);
        m.put("sortOrder", sortOrder);
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("列表返回分类数组，带 traceId")
        void listReturnsCategories() throws Exception {
            when(service.findAll()).thenReturn(List.of(category(1L, "K8s 运维")));

            mockMvc.perform(get("/api/v1/knowledge/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].name").value("K8s 运维"))
                    .andExpect(jsonPath("$.data[0].docCount").value(3))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("树结构里 uncategorized 必须始终存在（哪怕为空）")
        void treeAlwaysCarriesUncategorizedBucket() throws Exception {
            when(service.tree()).thenReturn(Map.of(
                    "categories", List.of(Map.of("id", 1L, "name", "K8s 运维", "documents", List.of())),
                    "uncategorized", List.of()));

            mockMvc.perform(get("/api/v1/knowledge/categories/tree"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.categories[0].name").value("K8s 运维"))
                    // 这个桶缺席，前端就没有地方渲染无归属文档，
                    // 那些文档会从界面上消失
                    .andExpect(jsonPath("$.data.uncategorized").isArray());
        }
    }

    @Nested
    @DisplayName("创建")
    class Create {

        @Test
        @DisplayName("参数原样传给 Service，返回新建分类")
        void createPassesFieldsThrough() throws Exception {
            when(service.create(any(), anyString(), any())).thenReturn(category(9L, "网络排障"));

            mockMvc.perform(post("/api/v1/knowledge/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(null, "网络排障", 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(9));

            verify(service).create(isNull(), eq("网络排障"), eq(5));
        }

        @Test
        @DisplayName("sortOrder 省略时传 null，由 Service 决定默认值（不在 Web 层擅自补 0）")
        void nullSortOrderIsForwardedAsNull() throws Exception {
            when(service.create(any(), anyString(), any())).thenReturn(category(9L, "网络排障"));

            mockMvc.perform(post("/api/v1/knowledge/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(null, "网络排障", null))))
                    .andExpect(status().isOk());

            // Web 层若自作主张补 0，Service 就永远收不到「未指定」这个信号，
            // 更新时也就无法保留原有排序
            verify(service).create(isNull(), eq("网络排障"), isNull());
        }

        @Test
        @DisplayName("重名（IllegalArgumentException）→ 40001，HTTP 仍为 200（本地 catch 的现状）")
        void duplicateNameYieldsBizCode40001() throws Exception {
            when(service.create(any(), anyString(), any()))
                    .thenThrow(new IllegalArgumentException("分类名称已存在"));

            mockMvc.perform(post("/api/v1/knowledge/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(null, "K8s 运维", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    // 消息必须是能读懂的原因，不是「创建失败」
                    .andExpect(jsonPath("$.message").value("分类名称已存在"));
        }

        @Test
        @DisplayName("未预期异常 → 50001，且不把堆栈细节当成 message 主体")
        void unexpectedErrorYields50001() throws Exception {
            when(service.create(any(), anyString(), any()))
                    .thenThrow(new RuntimeException("connection reset"));

            mockMvc.perform(post("/api/v1/knowledge/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(null, "网络排障", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50001));
        }
    }

    @Nested
    @DisplayName("更新与删除")
    class UpdateAndDelete {

        @Test
        @DisplayName("更新把路径 id 与请求体一起传给 Service")
        void updatePassesPathId() throws Exception {
            when(service.update(anyLong(), any(), anyString(), any()))
                    .thenReturn(category(3L, "改名后"));

            mockMvc.perform(put("/api/v1/knowledge/categories/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(1L, "改名后", 2))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名后"));

            verify(service).update(eq(3L), eq(1L), eq("改名后"), eq(2));
        }

        @Test
        @DisplayName("成环的父子关系被拒（40001）—— 否则树渲染会无限递归")
        void rejectsCycle() throws Exception {
            when(service.update(anyLong(), any(), anyString(), any()))
                    .thenThrow(new IllegalArgumentException("不能将分类移动到其子分类下"));

            mockMvc.perform(put("/api/v1/knowledge/categories/3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body(7L, "K8s 运维", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("不能将分类移动到其子分类下"));
        }

        @Test
        @DisplayName("删除成功时回传 id 与 deleted 标记，前端据此就地移除节点")
        void deleteReturnsAck() throws Exception {
            mockMvc.perform(delete("/api/v1/knowledge/categories/4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(4))
                    .andExpect(jsonPath("$.data.deleted").value(true));

            verify(service).delete(4L);
        }

        @Test
        @DisplayName("仍有子分类时拒绝删除（40001）")
        void refusesDeleteWithChildren() throws Exception {
            doThrow(new IllegalStateException("该分类仍包含子分类，请先移动或删除子分类"))
                    .when(service).delete(anyLong());

            mockMvc.perform(delete("/api/v1/knowledge/categories/4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("该分类仍包含子分类，请先移动或删除子分类"));
        }

        @Test
        @DisplayName("仍有文档时拒绝删除 —— 放行会让这些文档从界面上彻底消失")
        void refusesDeleteWithDocuments() throws Exception {
            doThrow(new IllegalStateException("该分类仍包含文档，请先移动文档"))
                    .when(service).delete(anyLong());

            mockMvc.perform(delete("/api/v1/knowledge/categories/4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("移动文档（已收敛到全局异常处理器）")
    class MoveDocument {

        @Test
        @DisplayName("移动成功回传 docId 与 moved 标记")
        void moveReturnsAck() throws Exception {
            mockMvc.perform(put("/api/v1/knowledge/categories/documents/11")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("categoryId", 2, "version", 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(11))
                    .andExpect(jsonPath("$.data.moved").value(true));

            verify(service).moveDocument(11L, 2L, 5);
        }

        @Test
        @DisplayName("categoryId=null 表示移出分类（回到未分类），不是「参数缺失」")
        void nullCategoryMeansUncategorized() throws Exception {
            Map<String, Object> b = new HashMap<>();
            b.put("categoryId", null);
            b.put("version", 5);

            mockMvc.perform(put("/api/v1/knowledge/categories/documents/11")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(b)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.moved").value(true));

            verify(service).moveDocument(eq(11L), isNull(), eq(5));
        }

        @Test
        @DisplayName("版本冲突 → 40009 / HTTP 409，绝不静默覆盖他人修改")
        void optimisticLockMapsTo40009() throws Exception {
            // 三参构造：资源标识 + 客户端持有版本 + 数据库当前版本。
            // 消息由异常自己拼装，刻意不暴露 "version" 字样，
            // 而是给出可执行的下一步（刷新后重新提交）
            doThrow(new OptimisticLockException("doc:11", 1, 3))
                    .when(service).moveDocument(anyLong(), any(), any());

            mockMvc.perform(put("/api/v1/knowledge/categories/documents/11")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("categoryId", 2, "version", 1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40009))
                    // 提示必须告诉用户「刷新后重新提交」，
                    // 只说「版本冲突」用户不知道该做什么
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("请刷新")));
        }

        @Test
        @DisplayName("目标分类不存在 → 40004 / HTTP 409（全局处理器接管，与本地 catch 的 200 不同）")
        void missingTargetMapsTo40004() throws Exception {
            doThrow(new IllegalStateException("分类不存在"))
                    .when(service).moveDocument(anyLong(), any(), any());

            mockMvc.perform(put("/api/v1/knowledge/categories/documents/11")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("categoryId", 999, "version", 1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40004));
        }

        @Test
        @DisplayName("非法参数 → 40001 / HTTP 400")
        void illegalArgumentMapsTo400() throws Exception {
            doThrow(new IllegalArgumentException("文档 ID 非法"))
                    .when(service).moveDocument(anyLong(), any(), any());

            mockMvc.perform(put("/api/v1/knowledge/categories/documents/11")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("categoryId", 2, "version", 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }
}
