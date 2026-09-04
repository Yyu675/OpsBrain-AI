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
