package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.notify.NotifyMessage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警通知卡片模板单源（A1 告警信息设计，2026-10-12）。
 *
 * <h3>为什么模板要抽成单源类</h3>
 * <p>此前卡片内联在 {@code AlertService.notifyAlert()} 里，只有
 * 级别/服务/模块/详情/工单 5 行——而 V11 起 instance/阈值/runbook 等排查上下文
 * 早已落进 annotations_json：<b>库里有粮，卡片要饭</b>。信息与消息错配是
 * 值班 MTTR 的隐形税（收到告警还得再点开平台查 runbook）。同时卡片格式散在
 * service 私有方法里改一处漏一处（升级卡/恢复卡各写各的），本类收口为单源。</p>
 *
 * <h3>信息顺序纪律（对齐值班侧告警信息最佳实践）</h3>
 * <p>影响 → 指标事实（当前值/阈值/持续）→ 现场描述 → 可能原因 → 首步动作 →
 * 链接（runbook/大盘）→ 关联工单。<b>先说影响，再说指标</b>：值班人第一眼要回答
 * 「要不要现在爬起来」，不是「CPU 多少」。</p>
 *
 * <h3>缺料整段省略，不渲染 "null"</h3>
 * <p>annotation 是推送方自愿填写的（见 {@link AlertAnnotationKeys}），缺了就把
 * 整段拿掉——渲染 "null" 或空占位会让读者分不清「没写」还是「系统坏了」。</p>
 *
 * <h3>长度上限</h3>
 * <p>钉钉 markdown 对超长文本有截断风险（截在哪不受我们控制），全文超过
 * {@link #MAX_MARKDOWN_CHARS} 时砍尾并留「详情见告警详情页」回链——
 * 平台内永远有全文，卡片只承担「够起步处置」的信息量。</p>
 *
 * <h3>纯函数</h3>
 * <p>输入 Alert（已带 labelsJson/annotationsJson 的实体）+ 预解析好的 labels/
 * annotations Map，输出 {@link NotifyMessage}。不碰存储不碰网络——JSON 解析
 * 复用 AlertService 既有护身降级（解析失败给空 Map，卡片退化为五行的超集）。</p>
 */
public final class AlertNotifyTemplate {

    /** 卡片正文上限（字符）。钉钉 markdown 消息体上限远大于此，此值防的是「可读性截断」 */
    static final int MAX_MARKDOWN_CHARS = 1500;

    private AlertNotifyTemplate() {}

    /**
     * 触发卡。urgent 判定沿用 {@link Alert#isHighRisk()}（P0/P1），
     * 与分级建单/风暴抑制同一级别语义，不在模板层另立分级。
     *
     * @param alert       告警实体（level/service/firstOccurredAt 等取本体）
     * @param labels      已解析的原始标签（instance 下钻维度）
     * @param annotations 已解析的注解（上下文段数据源，键约定见 AlertAnnotationKeys）
     * @param ticketId    关联工单号（可空——观察窗内未建单）
     */
    public static NotifyMessage firing(Alert alert, Map<String, String> labels,
                                       Map<String, String> annotations, String ticketId) {
        String level = nz(alert.getLevel(), "—");
        String service = nz(alert.getService(), null);
        String instance = AlertAnnotationKeys.valueOf(labels, "instance");

        // 标题机器可读可检索：[P1] service alertName | instance（钉钉内搜索/去重友好）
        StringBuilder title = new StringBuilder("[").append(level).append("] ");
        if (service != null) {
            title.append(service).append(' ');
        }
        title.append(nz(alert.getAlertName(), "告警"));
        if (instance != null) {
            title.append(" | ").append(instance);
        }

        StringBuilder md = new StringBuilder();
        md.append("### ").append(title).append("\n\n");

        // ① 影响先行：summary（人话摘要）与 impact（影响面）是「要不要现在爬起来」的答案
        line(md, "摘要", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.SUMMARY));
        line(md, "影响", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.IMPACT));

        // ② 指标事实：当前值/阈值成对；持续时长从首次发生算到当前
        String current = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.CURRENT_VALUE);
        String threshold = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.THRESHOLD);
        if (current != null && threshold != null) {
            line(md, "指标", current + "（阈值 " + threshold + "）");
        } else if (current != null) {
            line(md, "当前值", current);
        } else if (threshold != null) {
            line(md, "阈值", threshold);
        }
        line(md, "持续", humanDuration(alert.getFirstOccurredAt(), LocalDateTime.now()));
        if (alert.getOccurrenceCount() != null && alert.getOccurrenceCount() > 1) {
            line(md, "已发生", alert.getOccurrenceCount() + " 次");
        }

        // ③ 现场与线索：description 是汇聚后的核心描述（alert 本体字段，恒有）
        line(md, "详情", nz(alert.getDescription(), null));
        line(md, "可能原因", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.LIKELY_CAUSE));
        line(md, "首步动作", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.FIRST_ACTION));

        // ④ 链接：runbook 优先（照着做），大盘次之（自己看）
        line(md, "Runbook", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.RUNBOOK_URL));
        line(md, "监控大盘", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.DASHBOARD_URL));

        // ⑤ 闭环：关联工单 + 回平台处置的去处（卡片上没有 ack 按钮，指明门牌号）
        if (ticketId != null && !ticketId.isBlank()) {
            line(md, "关联工单", ticketId);
        }
        md.append("\n认领/恢复请至 OpsBrain 告警页（/alerts）");

        return alert.isHighRisk()
                ? NotifyMessage.urgent(title.toString(), clamp(md.toString()))
                : NotifyMessage.normal(title.toString(), clamp(md.toString()));
    }

    /**
     * 恢复卡：只给「曾经需要人处理」的告警发（调用方限 isHighRisk——低级别
     * 触发时本就静默入库，恢复再发一张卡就是链接设计原则里「恢复也要发，但别吵」的反面）。
     */
    public static NotifyMessage resolved(Alert alert, Map<String, String> labels,
                                         Map<String, String> annotations) {
        String level = nz(alert.getLevel(), "—");
        String service = nz(alert.getService(), null);

        StringBuilder title = new StringBuilder("[已恢复] ").append(level).append(' ');
        if (service != null) {
            title.append(service).append(' ');
        }
        title.append(nz(alert.getAlertName(), "告警"));

        StringBuilder md = new StringBuilder();
        md.append("### ").append(title).append("\n\n");
        line(md, "摘要", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.SUMMARY));
        String current = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.CURRENT_VALUE);
        String threshold = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.THRESHOLD);
        if (current != null && threshold != null) {
            line(md, "回落值", current + "（阈值 " + threshold + "）");
        }
        line(md, "持续", humanDuration(alert.getFirstOccurredAt(),
                alert.getResolvedAt() != null ? alert.getResolvedAt() : LocalDateTime.now()));
        if (alert.getTicketId() != null && !alert.getTicketId().isBlank()) {
            line(md, "关联工单", alert.getTicketId() + "（处置完成后请确认关单）");
        }
        md.append("\n无需处理，留痕归档");

        // 恢复卡恒为 normal：火已灭，不再 @所有人
        return NotifyMessage.normal(title.toString(), clamp(md.toString()));
    }

    // ==================== 渲染原语 ====================

    /** 一行「- **键**：值」；值为 null/空白整行省略（缺料不撒谎） */
    private static void line(StringBuilder md, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        md.append("- **").append(label).append("**：").append(value).append("\n");
    }

    /** 人类可读时长：59m 内报分，24h 内报时+分，更长按天（值班读「3天2小时」比「4517分钟」快） */
    static String humanDuration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        Duration d = Duration.between(from, to);
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "不足 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        if (minutes < 24 * 60) {
            return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
        }
        return d.toDays() + " 天 " + ((minutes % (24 * 60)) / 60) + " 小时";
    }

    /** 超长砍尾留回链——被砍的一定是排后面的链接段，核心处置信息优先保全 */
    private static String clamp(String md) {
        if (md.length() <= MAX_MARKDOWN_CHARS) {
            return md;
        }
        return md.substring(0, MAX_MARKDOWN_CHARS) + "\n…（内容过长已截断，全文见 OpsBrain 告警详情页）";
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
