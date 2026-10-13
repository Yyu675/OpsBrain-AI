package com.devops.agent.domain.notify;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业告警固定字段模板：不调用模型、不猜测对象/数值/业务影响。
 * 输入 map 由调用方从持久化上下文解析，恢复取值只取本次恢复负载。
 * 群摘要有界、外部文本转义、链接仅由受信任部署地址和真实 ID 构造。
 */
public final class AlertNotificationRenderer {
    /** 由接入处理强制覆盖，不能由外部 annotations 伪造时间质量。 */
    public static final String START_TIME_ESTIMATED = "_opsbrain_starts_at_estimated";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Clock clock;

    public AlertNotificationRenderer() {
        this(Clock.systemDefaultZone());
    }

    public AlertNotificationRenderer(Clock clock) {
        this.clock = clock;
    }

    public NotifyMessage firing(Alert alert, Map<String, String> labels, Map<String, String> annotations,
                                String ticketHint, String baseUrl) {
        return firing(alert, labels, annotations, ticketHint, null, null, baseUrl);
    }

    public NotifyMessage firing(Alert alert, Map<String, String> labels, Map<String, String> annotations,
                                String ticketHint, String assignee, String ticketStatus, String baseUrl) {
        String title = "【告警｜" + plain(alert.getLevel(), 12) + "】" + subject(alert, labels);
        StringBuilder body = header(title);
        identity(body, alert, labels, annotations, "告警中（FIRING）");
        field(body, "监控取值", first(annotations, "value", "current_value", "observed_value"), 400);
        field(body, "告警说明", has(alert.getDescription()) ? alert.getDescription() : annotations.get("summary"), 500);
        String condition = first(annotations, "expression", "expr", "threshold");
        if (has(condition)) field(body, "触发条件 / 源阈值", condition, 300);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean estimated = !"false".equals(annotations.get(START_TIME_ESTIMATED));
        field(body, startTimeLabel(annotations), time(alert.getFirstOccurredAt()));
        field(body, estimated ? "接收后持续（估算）" : "已持续", duration(alert.getFirstOccurredAt(), now));
        field(body, "业务影响", "待确认");
        field(body, "处理状态", "ACKNOWLEDGED".equals(alert.getStatus()) ? "已确认" : "待认领");
        field(body, "负责人", has(assignee) ? assignee : "待分配，请值班人员认领");
        field(body, "关联工单", has(alert.getTicketId()) ? alert.getTicketId() : ticketHint);
        if (has(ticketStatus)) field(body, "工单状态", ticketStatus);
        body.append("\n请值班人员核对告警对象并认领，具体处置以监控规则和现场证据为准。\n");
        links(body, alert, baseUrl);
        return new NotifyMessage(title, body.toString(), alert.isHighRisk());
    }

    public NotifyMessage resolved(Alert alert, Map<String, String> labels, Map<String, String> originalAnnotations,
                                  Map<String, String> recoveryAnnotations, boolean estimatedEnd,
                                  String ticketStatus, Long otherActive, String baseUrl) {
        String title = "【恢复｜原" + plain(alert.getLevel(), 12) + "】" + subject(alert, labels);
        StringBuilder body = header(title);
        identity(body, alert, labels, originalAnnotations, "监控告警已解除（RESOLVED）");
        field(body, "原告警说明", alert.getDescription(), 500);
        String recoveryValue = first(recoveryAnnotations, "value", "current_value", "observed_value");
        field(body, "恢复取值", has(recoveryValue) ? recoveryValue : "未提供恢复取值", 400);
        boolean estimatedStart = !"false".equals(originalAnnotations.get(START_TIME_ESTIMATED));
        field(body, startTimeLabel(originalAnnotations), time(alert.getFirstOccurredAt()));
        field(body, estimatedEnd ? "恢复接收时间（源未提供有效结束时间）" : "恢复时间", time(alert.getResolvedAt()));
        field(body, estimatedStart || estimatedEnd ? "本次持续（估算）" : "本次持续",
                duration(alert.getFirstOccurredAt(), alert.getResolvedAt()));
        field(body, "恢复依据", "收到监控源 resolved 事件；不代表业务已验证");
        field(body, "业务验证", "待确认");
        if (has(alert.getTicketId())) {
            field(body, "关联工单", alert.getTicketId());
            field(body, "工单状态", ticketStatus);
            if (otherActive != null && otherActive > 0) {
                field(body, "关联告警", "仍有 " + otherActive + " 条活跃告警，不应据此关闭整张工单");
            } else if ("CLOSED".equals(ticketStatus) || "VOID".equals(ticketStatus)) {
                body.append("\n工单已结束；本通知留痕归档，业务验证结果请以实际记录为准。\n");
            } else {
                body.append("\n该监控告警已解除；请到工单核查其他关联告警、业务验证及处置记录。\n");
            }
        } else {
            body.append("\n该监控告警已解除；业务可用性仍需按实际情况确认。\n");
        }
        links(body, alert, baseUrl);
        return NotifyMessage.normal(title, body.toString());
    }

    /** 群摘要与审计 summary 分离，不直接转发日志样本/假设原文/取证明细。 */
    public NotifyMessage diagnosis(Alert alert, Map<String, String> labels,
                                   EvidenceAggregator.AggregateResult result, String ticketId, String baseUrl) {
        String title = "【诊断补充】" + subject(alert, labels);
        StringBuilder body = header(title);
        field(body, "平台事件 ID", alert.getId() == null ? null : alert.getId().toString());
        field(body, "监控规则", alert.getAlertName());
        field(body, "关联工单", has(ticketId) ? ticketId : alert.getTicketId());
        field(body, "诊断状态", switch (result.sufficiency()) {
            case INSUFFICIENT -> "证据不足，暂无法形成可靠根因结论";
            case WEAK -> "证据薄弱，诊断结果需人工复核";
            case SUFFICIENT -> "取证完成；诊断结论及其依据请查看详情，不等于根因已确认";
        });
        String statuses = result.evidences().stream().filter(e -> e != null)
                .limit(8).map(e -> direction(e.evidenceType()) + "：" + switch (e.status()) {
                    case SUCCESS -> "查询成功";
                    case NO_DATA -> "无匹配数据（不能据此排除）";
                    case FAILED -> "查询失败";
                    case UNAVAILABLE -> "数据源未启用/不可用";
                }).collect(Collectors.joining("；"));
        field(body, "取证概况", statuses, 500);
        body.append("\n请查看取证记录；证据不足时先核对对象、时间窗和采集覆盖，勿据此认定业务正常。\n");
        links(body, alert, baseUrl, has(ticketId) ? ticketId : alert.getTicketId());
        return NotifyMessage.normal(title, body.toString());
    }

    private static String startTimeLabel(Map<String, String> annotations) {
        return switch (annotations.getOrDefault(START_TIME_ESTIMATED, "unknown")) {
            case "false" -> "发生时间";
            case "true" -> "首次接收时间（源未提供有效开始时间）";
            default -> "发生时间（历史时间来源未验证）";
        };
    }

    private void identity(StringBuilder body, Alert alert, Map<String, String> labels,
                          Map<String, String> annotations, String status) {
        field(body, "当前状态", status);
        field(body, "环境", first(labels, "env", "environment"));
        field(body, "系统 / 服务", plain(alert.getSystem(), 80) + " / " + plain(alert.getService(), 80));
        field(body, "告警对象", first(labels, "node", "host", "hostname", "pod", "instance"));
        // instance 可能是 host:port 或云资源，不能无条件称为 IP。
        field(body, "地址 / 实例", first(labels, "ip", "instance"));
        if (has(labels.get("namespace"))) field(body, "命名空间", labels.get("namespace"));
        field(body, "监控项目 / 规则", has(annotations.get("monitor_item"))
                ? annotations.get("monitor_item") : alert.getAlertName());
        field(body, "平台等级", alert.getLevel());
        if (has(labels.get("severity"))) field(body, "源等级", labels.get("severity"));
        field(body, "监控来源", alert.getSource());
        field(body, "平台事件 ID", alert.getId() == null ? null : alert.getId().toString());
        if (has(labels.get("event_id"))) field(body, "源事件 ID", labels.get("event_id"));
    }

    private static String subject(Alert alert, Map<String, String> labels) {
        String target = first(labels, "node", "host", "hostname", "pod", "instance");
        if (!has(target)) target = alert.getService();
        return plain(target, 80) + " · " + plain(alert.getAlertName(), 100);
    }

    private String time(LocalDateTime time) {
        return time == null ? null : TIME.format(time) + "（" + clock.getZone().getId() + "，服务器时间）";
    }

    private static String duration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return "无法计算（时间缺失）";
        long seconds = Duration.between(start, end).getSeconds();
        if (seconds < 0) return "无法计算（结束早于开始，请核查源时间）";
        long days = seconds / 86400;
        return (days > 0 ? days + " 天 " : "") + seconds % 86400 / 3600 + " 小时 "
                + seconds % 3600 / 60 + " 分 " + seconds % 60 + " 秒";
    }

    private static String direction(String type) {
        return switch (type) {
            case Evidence.Type.METRICS -> "指标";
            case Evidence.Type.CHANGES -> "变更";
            case Evidence.Type.LOGS -> "日志";
            case Evidence.Type.KNOWLEDGE -> "知识库";
            default -> plain(type, 30);
        };
    }

    private static StringBuilder header(String title) {
        return new StringBuilder("### ").append(escape(title)).append("\n\n");
    }

    private static void field(StringBuilder body, String name, String value) {
        field(body, name, value, 180);
    }

    private static void field(StringBuilder body, String name, String value, int limit) {
        body.append("- **").append(name).append("**：").append(escape(plain(value, limit))).append("\n");
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) if (has(values.get(key))) return values.get(key);
        return null;
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String plain(String value, int max) {
        if (!has(value)) return "未提供";
        // 基础凭据脱敏，随后移除控制字符、单行化、限制长度；原始证据仍在权限控制的详情。
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[已脱敏]")
                .replaceAll("(?i)((?:access_token|api_key|token|password|secret)\\s*[=:]\\s*)[^\\s&,;]+", "$1[已脱敏]")
                .replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return safe.length() <= max ? safe : safe.substring(0, max) + "…（完整内容见详情）";
    }

    private static String escape(String text) {
        // 防外部数据制造链接/标题/格式和正文 @，实际提醒由渠道 at 配置控制。
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("`", "\\`")
                .replace("@", "＠");
    }

    private static void links(StringBuilder body, Alert alert, String configuredBase) {
        links(body, alert, configuredBase, alert.getTicketId());
    }

    private static void links(StringBuilder body, Alert alert, String configuredBase, String ticketId) {
        String base = validBase(configuredBase);
        if (base == null) {
            body.append("\n请登录 OpsBrain 后按平台事件 ID 或工单号查询（未配置有效 HTTPS 访问地址）。\n");
            return;
        }
        if (alert.getId() != null) body.append("\n[查看告警并处理](").append(base)
                .append("/alerts/").append(alert.getId()).append(")");
        if (has(ticketId)) body.append("  [查看工单](").append(base).append("/tickets/")
                .append(URLEncoder.encode(ticketId, StandardCharsets.UTF_8).replace("+", "%20"))
                .append(")");
        body.append("\n");
    }

    private static String validBase(String configured) {
        if (!has(configured)) return null;
        try {
            URI uri = URI.create(configured.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || configured.matches(".*[\\s\\\\()<>].*")) return null;
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
