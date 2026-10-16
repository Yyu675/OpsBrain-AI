package com.devops.agent.domain.alert;

import java.util.List;
import java.util.Map;

/**
 * 告警 annotation 键字典（A1 告警信息设计，2026-10-12）。
 *
 * <p>告警卡片与工单描述能承载多少排查上下文，取决于<b>推送方填了多少
 * annotation</b>——Alertmanager 的 annotations 是自由键值对，没有约定就是抽奖：
 * 有人写 {@code summary}、有人写 {@code desc}，模板永远渲染不全。本类把约定
 * 钉成代码，{@link AlertNotifyTemplate} 按此取数，接入文档按此要求规则作者。</p>
 *
 * <h3>键的分层语义（与 labels/annotations 分工一致）</h3>
 * <ul>
 *   <li><b>labels</b> 给机器：路由/去重/分组（alertname/service/severity…），
 *       已在 {@code AlertmanagerSourceAdapter} 消费；</li>
 *   <li><b>annotations</b> 给人看：本字典只收「给人看」的键。往 annotations
 *       里塞路由用途的键是误用（annotations 不参与去重）。</li>
 * </ul>
 *
 * <h3>新增键的纪律</h3>
 * <p>新键必须先在此登记 + 在 {@code AlertNotifyTemplate} 有渲染位 + 更新
 * {@code docs/09-operations/告警接入命名规范}，三处联动——只加字典不加渲染位
 * 等于没加，只加渲染位不登记则接入方无从知晓。</p>
 *
 * @author OpsBrain AI
 * @since 2026-10-12
 */
public final class AlertAnnotationKeys {

    private AlertAnnotationKeys() {}

    // ==================== 一句话与现场描述 ====================

    /** 一句话摘要（人话版标题，优先于 alertname 展示；Alertmanager 社区惯例键） */
    public static final String SUMMARY = "summary";

    /** 现场描述（指标说了什么，AlertService 的 description 汇聚链已消费） */
    public static final String DESCRIPTION = "description";

    // ==================== 影响与原因 ====================

    /** 业务影响（对用户/链路的影响面——「先说影响，再说指标」的信息设计第一原则） */
    public static final String IMPACT = "impact";

    /** 可能原因（提示性，不是结论；与诊断产物区分——诊断是查出来的，这里是规则作者预判的） */
    public static final String LIKELY_CAUSE = "likely_cause";

    // ==================== 处置引导 ====================

    /** 首步动作（"1) 看大盘 2) 查最近发布"式编号串，值班人照做即可起步） */
    public static final String FIRST_ACTION = "first_action";

    /** 处置手册链接 */
    public static final String RUNBOOK_URL = "runbook_url";

    /** 监控大盘链接 */
    public static final String DASHBOARD_URL = "dashboard_url";

    // ==================== 指标事实 ====================

    /** 当前值（字符串承载——比例/字节/次数单位不一，格式化交给填写方） */
    public static final String CURRENT_VALUE = "current_value";

    /** 阈值（与 CURRENT_VALUE 成对渲染为「当前值 X / 阈值 Y」） */
    public static final String THRESHOLD = "threshold";

    // ==================== 多源监控（Zabbix等）硬核定位 ====================

    /** 监控项目（对应 Zabbix item_name，如 "HPE Primera: Get errors"） */
    public static final String MONITOR_ITEM = "monitor_item";

    /** 目标地址/IP（对应 Zabbix host_ip，如 "10.210.44.11"） */
    public static final String TARGET_ADDRESS = "target_address";

    /** 发生/恢复主机（对应 Zabbix host_name，如 "primera01"） */
    public static final String HOST_NAME = "host_name";

    /** 全部人看键（渲染顺序即重要度顺序：影响 →指标事实→ 原因 → 动作 → 链接） */
    public static final List<String> ALL = List.of(
            SUMMARY, IMPACT, MONITOR_ITEM, TARGET_ADDRESS, HOST_NAME, CURRENT_VALUE, THRESHOLD, DESCRIPTION,
            LIKELY_CAUSE, FIRST_ACTION, RUNBOOK_URL, DASHBOARD_URL);

    /**
     * 从 annotations 取值：null-safe，缺键/空白返回 null（模板据此整段省略，
     * 缺料渲染 "null" 是信息设计上的谎言——读者会以为告警真的没写影响）。
     */
    public static String valueOf(Map<String, String> annotations, String key) {
        if (annotations == null) {
            return null;
        }
        String v = annotations.get(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
