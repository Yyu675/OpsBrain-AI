package com.devops.agent.domain.tools;

/**
 * 工单草稿（Single Writer 模式的载体）
 * <p>
 * 参考 Agent Methodology §10.2 单写原则：
 * <blockquote>
 * 主编排器负责写主状态；子 Agent / 工具只返回结构化建议或证据。
 * 多 Worker 可以并行，主状态只能单点落锤。
 * </blockquote>
 * </p>
 * <p>
 * 工具不再直接写库，而是产出本草稿；由编排层
 * ({@code DevOpsAgentServiceImpl}) 作为唯一写入者落库。
 * 这样做解决三个问题：
 * <ol>
 *   <li><b>审批可前置</b>：高风险工单可在写库<i>之前</i>拦截，
 *       而非事后作废</li>
 *   <li><b>traceId 原生可用</b>：编排层持有 traceId，
 *       无需事后回填（工具在模型 HTTP 回调线程，ThreadLocal 取不到）</li>
 *   <li><b>Saga 时序正确</b>：先登记步骤再写库，
 *       避免"写库成功但登记失败"产生无补偿记录的孤儿数据</li>
 * </ol>
 *
 * @param title       工单标题
 * @param priority    优先级 HIGH/MEDIUM/LOW
 * @param module      故障模块 K8S/ALIYUN_SLB/MYSQL/NETWORK/OTHER
 * @param description 故障描述
 * @param needsApproval 是否需人工审批（HIGH 优先级等高风险场景）
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public record TicketDraft(
        String title,
        String priority,
        String module,
        String description,
        boolean needsApproval
) {

    /**
     * 草稿标记前缀
     * <p>
     * 工具返回文本中嵌入本标记包裹的 JSON，编排层据此识别并提取草稿。
     * 使用 HTML 注释风格，模型通常会忽略而不复述给用户。
     * </p>
     */
    public static final String MARKER_START = "<!--TICKET_DRAFT:";
    public static final String MARKER_END = "-->";

    /**
     * 序列化为可嵌入工具返回文本的标记块
     * <p>手写 JSON 避免引入 ObjectMapper 依赖到 domain 层。</p>
     */
    public String toMarkerBlock() {
        return MARKER_START
                + "{\"title\":\"" + escape(title) + "\","
                + "\"priority\":\"" + escape(priority) + "\","
                + "\"module\":\"" + escape(module) + "\","
                + "\"description\":\"" + escape(description) + "\","
                + "\"needsApproval\":" + needsApproval + "}"
                + MARKER_END;
    }

    /**
     * JSON 字符串转义
     * <p>运维文本常含引号、换行、路径反斜杠，必须转义否则破坏 JSON 结构。</p>
     */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 其余控制字符统一转 \\uXXXX，防止非法 JSON
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}