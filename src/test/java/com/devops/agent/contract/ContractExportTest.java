package com.devops.agent.contract;

import com.devops.agent.common.error.BizError;
import com.devops.agent.controller.TicketController;
import com.devops.agent.domain.biz.entity.TicketEnums;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前后端契约导出。
 *
 * <h3>要解决什么</h3>
 * 项目里有三处约束是「前后端各写一份、靠人工同步」的，且都已经漂移过：
 * <ul>
 *   <li><b>工单状态机</b> —— 前端 {@code ALLOWED_TRANSITIONS} vs 后端
 *       {@code TicketEnums.Status}。曾漂移出 8 处不一致，其中
 *       「已解决/已关闭 → 重新打开」被前端误禁用，导致故障复发只能新建工单。</li>
 *   <li><b>字段长度</b> —— 前端 {@code maxlength} vs 后端 {@code @Size}。
 *       前端曾严一个数量级（描述 1000 vs 20000），粘贴的堆栈被静默截断。</li>
 *   <li><b>业务码</b> —— 前端 {@code bizCode.ts} vs 后端 {@code BizError}。</li>
 * </ul>
 *
 * <h3>为什么用「导出 JSON」而不是别的方案</h3>
 * <p>此前前端是把后端的值<b>手工镜像</b>一份再断言。这能防住「改了一侧忘另一侧」，
 * 但防不住「镜像本身抄错」——镜像仍是手写的。</p>
 *
 * <p>引入 OpenAPI（springdoc）能彻底解决，但要动 3-5 天且改动面覆盖所有 Controller。
 * 本方案投入约 1 天，覆盖当前三处已知契约：<b>后端用反射把真实值导出成 JSON，
 * 前端测试直接读它做断言</b>。单一真相源是后端代码本身，
 * 改了后端而没改前端，前端测试立刻失败。</p>
 *
 * <h3>产物</h3>
 * {@code devops-platform-frontend/src/contracts/backend-contract.json}
 * <p>刻意写进前端源码目录并纳入版本控制——这样即便本地没跑过后端测试，
 * 前端 CI 也能独立运行。文件变更会出现在 diff 里，评审时一眼能看到契约改动。</p>
 */
@DisplayName("前后端契约导出")
class ContractExportTest {

    /**
     * 导出路径。相对仓库根，由 Maven 在项目根目录执行保证。
     */
    private static final Path OUTPUT = Path.of(
            "devops-platform-frontend", "src", "contracts", "backend-contract.json");

    @Test
    @DisplayName("导出状态机 / 字段长度 / 业务码，供前端契约测试消费")
    void exportContract() throws IOException {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("_generatedBy", "ContractExportTest —— 请勿手工编辑，改后端后重跑 mvn test");
        contract.put("ticketStatus", exportTicketStatus());
        contract.put("fieldLimits", exportFieldLimits());
        contract.put("bizCodes", exportBizCodes());

        String json = toJson(contract, 0);

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, json + System.lineSeparator(), StandardCharsets.UTF_8);

        // 自检：导出内容不能是空壳，否则前端会拿着空契约「断言通过」
        assertFalse(json.isBlank(), "契约导出为空");
        assertTrue(json.contains("PENDING"), "状态机未被导出");
        assertTrue(json.contains("title"), "字段长度未被导出");
    }

    // ==================== 状态机 ====================

    /**
     * 导出状态流转表。
     *
     * <p>用 {@code nextStates} 而非直接读私有字段：前者是对外契约，
     * 后者是实现细节。若将来流转规则改为动态计算，这里仍然正确。</p>
     */
    private Map<String, Object> exportTicketStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<String> all = List.of(
                TicketEnums.Status.PENDING, TicketEnums.Status.PROCESSING,
                TicketEnums.Status.RESOLVED, TicketEnums.Status.CLOSED,
                TicketEnums.Status.VOID);
        result.put("all", all);

        Map<String, Object> transitions = new LinkedHashMap<>();
        for (String from : all) {
            Set<String> next = TicketEnums.Status.nextStates(from);
            // 排序保证导出稳定——Set 的迭代顺序不保证，不排序会让 JSON 每次都变，
            // 制造无意义的 diff 噪音
            transitions.put(from, next.stream().sorted().collect(Collectors.toList()));
        }
        result.put("transitions", transitions);

        result.put("terminal", all.stream()
                .filter(TicketEnums.Status::isTerminal)
                .collect(Collectors.toList()));

        return result;
    }

    // ==================== 字段长度 ====================

    /**
     * 从 {@code CreateTicketRequest} 的 {@code @Size} 注解反射出真实上限。
     *
     * <p>反射而非手抄：手抄的镜像会和注解漂移，而漂移正是本文件要消灭的问题。</p>
     */
    private Map<String, Object> exportFieldLimits() {
        Map<String, Object> limits = new TreeMap<>();

        for (RecordComponent rc : TicketController.CreateTicketRequest.class.getRecordComponents()) {
            Size size = rc.getAnnotation(Size.class);
            if (size != null && size.max() != Integer.MAX_VALUE) {
                limits.put(rc.getName(), size.max());
            }
        }
        return Map.of("createTicket", limits);
    }

    // ==================== 业务码 ====================

    private List<Map<String, Object>> exportBizCodes() {
        List<Map<String, Object>> codes = new ArrayList<>();
        for (BizError e : BizError.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", e.name());
            // BizError 用 record 风格访问器（code()/httpStatus()/retry()），
            // 不是 JavaBean 的 getXxx()。写错这里不会有任何运行期表现——
            // 它压根编译不过，只是此前无人编译过后端而已。
            item.put("code", e.code());
            item.put("httpStatus", e.httpStatus().value());
            item.put("retry", e.retry().name());
            codes.add(item);
        }
        codes.sort((a, b) -> Integer.compare((Integer) a.get("code"), (Integer) b.get("code")));
        return codes;
    }

    // ==================== 极简 JSON 序列化 ====================

    /**
     * 手写序列化而非引 Jackson：本测试要在<b>不启动 Spring 上下文</b>的前提下运行
     * （启动一次上下文要十几秒，而这只是个导出任务）。
     * 契约结构固定且简单，几十行足够，避免为一个导出任务引入依赖与启动开销。
     */
    @SuppressWarnings("unchecked")
    private String toJson(Object value, int indent) {
        String pad = "  ".repeat(indent);
        String padInner = "  ".repeat(indent + 1);

        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            return "{\n" + map.entrySet().stream()
                    .map(e -> padInner + quote(String.valueOf(e.getKey())) + ": "
                            + toJson(e.getValue(), indent + 1))
                    .collect(Collectors.joining(",\n"))
                    + "\n" + pad + "}";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            boolean scalar = list.stream().noneMatch(x -> x instanceof Map || x instanceof List);
            if (scalar) {
                return "[" + list.stream().map(x -> toJson(x, 0))
                        .collect(Collectors.joining(", ")) + "]";
            }
            return "[\n" + list.stream()
                    .map(x -> padInner + toJson(x, indent + 1))
                    .collect(Collectors.joining(",\n"))
                    + "\n" + pad + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value == null) return "null";
        return quote(String.valueOf(value));
    }

    private String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
