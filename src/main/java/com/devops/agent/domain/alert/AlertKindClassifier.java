package com.devops.agent.domain.alert;

import java.util.List;
import java.util.Map;

/**
 * 告警分类器（业务/技术/安全/因果四维分诊）。
 *
 * <h3>分类依据（三级兜底，最坏场景也能分对）</h3>
 * <ol>
 *   <li><b>显式 label</b>：告警规则里打了 {@code alert_kind}/{@code kind} 标签
 *       就直接用（最可靠，运维显式标记）；</li>
 *   <li><b>关键词推断</b>：告警名/描述含特征词（backlog/sla → 业务；
 *       attack/injection → 安全；disk-full/cert-expiry → 因果）；</li>
 *   <li><b>兜底</b>：{@link AlertKind#TECHNICAL}——保持现状行为，
 *       不认识的告警按技术性处理（向后兼容，不破坏现有链路）。</li>
 * </ol>
 *
 * <h3>为什么用关键词映射表而不是一堆 if-else</h3>
 * 新增类型的特征词只需往 {@link #KEYWORDS} 加一行——分类规则是数据不是代码，
 * 改规则不用动判定逻辑。这也与 {@code AutomationPolicy} 的「配置即数据」风格一致。
 */
public final class AlertKindClassifier {

    /**
     * 各类型的特征关键词（命中任一即归该类）。小写匹配。
     * 新增类型/特征词 = 在这里加一行，判定逻辑不动。
     */
    private static final Map<AlertKind, List<String>> KEYWORDS = Map.of(
            AlertKind.BUSINESS, List.of(
                    "backlog", "sla", "urgent", "pending", "conversion", "revenue",
                    "积压", "违约", "转化", "订单量", "积压工单"),
            AlertKind.SECURITY, List.of(
                    "attack", "intrusion", "injection", "malicious", "brute", "bruteforce",
                    "攻击", "入侵", "注入", "恶意", "爆破"),
            AlertKind.CAUSAL, List.of(
                    "diskspace", "disk-full", "diskfull", "certificate", "cert-expiry",
                    "quota", "磁盘", "证书", "配额", "容量将尽")
    );

    /** 显式标记的 label 键（告警规则里打这个标签即显式指定类型）。 */
    private static final List<String> EXPLICIT_LABEL_KEYS = List.of("alert_kind", "kind", "alert_type");

    private AlertKindClassifier() {}

    /**
     * 分类告警。
     *
     * @param alertName 告警规则名（如 OpsBrainUrgentPendingHigh）
     * @param labels    告警 labels（含可能的显式 alert_kind 标记）
     * @param description 告警描述（关键词推断的文本来源之一）
     */
    public static AlertKind classify(String alertName, Map<String, String> labels, String description) {
        // 第一级：显式 label 优先（运维显式标记最可靠）
        if (labels != null) {
            for (String key : EXPLICIT_LABEL_KEYS) {
                String v = labels.get(key);
                if (v != null && !v.isBlank()) {
                    AlertKind explicit = parse(v);
                    if (explicit != null) return explicit;
                }
            }
        }
        // 第二级：关键词推断（告警名 + 描述拼接，小写匹配）
        String haystack = ((alertName == null ? "" : alertName) + " "
                + (description == null ? "" : description)).toLowerCase();
        for (Map.Entry<AlertKind, List<String>> e : KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (haystack.contains(kw)) return e.getKey();
            }
        }
        // 第三级：兜底技术性（现状行为，向后兼容）
        return AlertKind.TECHNICAL;
    }

    /** 解析显式标记的 label 值为 AlertKind；不识别返回 null（继续走关键词推断）。 */
    private static AlertKind parse(String value) {
        try {
            return AlertKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
