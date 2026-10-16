package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.devops.agent.domain.notify.NotifyMessage;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警通知卡片模板单源（A1 告警信息设计，2026-10-12；2026-10-14 合并第一批增量）。
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
 * 链接（runbook/大盘/平台）→ 关联工单 + 负责人。<b>先说影响，再说指标</b>：值班人
 * 第一眼要回答「要不要现在爬起来」，不是「CPU 多少」。</p>
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
 * <h3>2026-10-14：与并行落地的第一批合并（本类仍是单源）</h3>
 * <p>分支侧第一批（{@code AlertNotificationRenderer}）与本类是同题两版实现。合并取舍：
 * 以本类为单源，收下第一批独有的四项能力，删除重复的渲染器——两套模板并存本身
 * 就是「改一处漏一处」的复发条件。合并进来的四项：</p>
 * <ul>
 *   <li><b>恢复卡按事实分态收尾</b>：旧文案在「有工单」时同时写「请确认关单」与
 *       「无需处理，留痕归档」，自相矛盾。现在三态各异——工单已结束 / 仍有活跃告警
 *       （不得据此关整张工单）/ 无工单（才说无需处理，留痕归档）。</li>
 *   <li><b>负责人 + 工单状态 + 平台直达链接</b>：链接只由<b>受信任的 HTTPS 部署地址</b>
 *       与真实 ID 构造（带凭据、带 query、含空白/括号的一律拒绝），未配置时明说
 *       「请登录 OpsBrain 后按事件 ID 或工单号查询」，而不是给一个点不开的地址。</li>
 *   <li><b>外源文本转义与凭据脱敏</b>：annotation/labels 来自推送方，属于外部输入——
 *       转义 markdown 元字符、把 {@code @} 换成全角（防外部数据制造 @所有人）、
 *       控制字符单行化、{@code token=/password=} 一类键值就地脱敏、逐行限量。</li>
 *   <li><b>时间诚实性</b>：源未提供有效开始/结束时间时不报精确秒数，时长标注
 *       「（估算）」；诊断卡（证据不足/薄弱/齐备）恒为 normal，不再以独立紧急广播
 *       吵醒所有人。</li>
 * </ul>
 *
 * <h3>纯函数</h3>
 * <p>输入 Alert（已带 labelsJson/annotationsJson 的实体）+ 预解析好的 labels/
 * annotations Map，输出 {@link NotifyMessage}。不碰存储不碰网络——JSON 解析
 * 复用 AlertService 既有护身降级（解析失败给空 Map，卡片退化为五行的超集）。</p>
 */
public final class AlertNotifyTemplate {

    /** 卡片正文上限（字符）。钉钉 markdown 消息体上限远大于此，此值防的是「可读性截断」 */
    static final int MAX_MARKDOWN_CHARS = 1500;

    /** 单行值上限：一行塞 5000 字会把后面的处置信息全挤出可读区 */
    private static final int MAX_LINE_CHARS = 600;

    /**
     * 「首次发生时间是否只是接收时间」的标记键。
     * <p>源没给有效的 startsAt（零值/缺失）时，平台只能用首次接收时刻兜底——此时
     * 「持续」不是真实持续时间，只是「我们已知它存在了多久」。不标注就会把
     * 「接收后 49 天」当成真实持续 49 天读（这正是旧模板被投诉的点之一）。</p>
     */
    public static final String START_TIME_ESTIMATED = "_opsbrain_starts_at_estimated";

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
        return firing(alert, labels, annotations, ticketId, null, null, null, null);
    }

    /**
     * 触发卡（带工单上下文与平台地址）。
     *
     * @param ticketId     关联工单号（可空——空则渲染建单状态行）
     * @param assignee     工单负责人（可空——空即「待分配」，明说比留白诚实）
     * @param ticketStatus 工单状态（可空）
     * @param baseUrl      平台部署地址（仅接受受信任 HTTPS；非法/未配置则给查询指引）
     * @param ticketHint   未建单时的原因提示（可空；来自触发路径，不进「关联工单」槽位——
     *                     一句中文提示冒充工单号会让读者去找一个不存在的单）
     */
    public static NotifyMessage firing(Alert alert, Map<String, String> labels,
                                       Map<String, String> annotations, String ticketId,
                                       String assignee, String ticketStatus, String baseUrl,
                                       String ticketHint) {
        String level = nz(alert.getLevel(), "—");
        String service = nz(alert.getService(), null);
        String instance = AlertAnnotationKeys.valueOf(labels, "instance");

        // 标题机器可读可检索：[P1] service alertName | instance（钉钉内搜索/去重友好）
        // 标题：结构性方括号（[级别]/|）由本类产生，外源片段逐个过 titlePart——
        // 整串 esc 会把 [P1] 渲染成 \[P1\]（值班第一眼看到的是转义符，不是级别）
        StringBuilder title = new StringBuilder("[").append(titlePart(level)).append("] ");
        if (service != null) {
            title.append(titlePart(service)).append(' ');
        }
        title.append(titlePart(nz(alert.getAlertName(), "告警")));
        if (instance != null) {
            title.append(" | ").append(titlePart(instance));
        }

        StringBuilder md = header(title.toString());

        // ① 状态与影响先行：明确告知当前告警状态与等级（降低认知门槛），紧接人话摘要与影响面
        line(md, "当前状态", "告警中（PROBLEM）");
        line(md, "告警等级", formatLevel(alert.getLevel()));
        line(md, "摘要", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.SUMMARY));
        line(md, "影响", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.IMPACT));

        // ② 定位硬核事实（吸收企业真实Zabbix等源实践）：主机、地址与监控项
        String hostName = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.HOST_NAME);
        if (hostName == null) {
            hostName = first(labels, "host", "hostname", "node");
        }
        String targetAddress = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.TARGET_ADDRESS);
        if (targetAddress == null) {
            targetAddress = first(labels, "ip", "address");
        }
        String monitorItem = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.MONITOR_ITEM);
        if (monitorItem == null) {
            monitorItem = first(annotations, "item_name");
        }
        line(md, "告警主机", hostName);
        line(md, "告警地址", targetAddress);
        line(md, "监控项目", monitorItem);

        // ③ 指标事实：当前值/阈值成对；持续时长从首次发生算到当前
        String current = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.CURRENT_VALUE);
        String threshold = AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.THRESHOLD);
        if (current != null && threshold != null) {
            line(md, "指标", current + "（阈值 " + threshold + "）");
        } else if (current != null) {
            line(md, "当前值", current);
        } else if (threshold != null) {
            line(md, "阈值", threshold);
        }
        line(md, estimatedStart(annotations) ? "接收后持续（估算）" : "持续",
                humanDuration(alert.getFirstOccurredAt(), LocalDateTime.now()));
        if (alert.getOccurrenceCount() != null && alert.getOccurrenceCount() > 1) {
            line(md, "已发生", alert.getOccurrenceCount() + " 次");
        }

        // ④ 现场与线索：description 是汇聚后的核心描述（alert 本体字段，恒有）
        line(md, "详情", nz(alert.getDescription(), null));
        line(md, "可能原因", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.LIKELY_CAUSE));
        line(md, "首步动作", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.FIRST_ACTION));

        // ④ 链接：runbook 优先（照着做），大盘次之（自己看）
        line(md, "Runbook", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.RUNBOOK_URL));
        line(md, "监控大盘", AlertAnnotationKeys.valueOf(annotations, AlertAnnotationKeys.DASHBOARD_URL));

        // ⑤ 闭环：关联工单 + 负责人（谁在管）+ 工单状态 —— 值班人不用回平台才知道找谁
        if (ticketId != null && !ticketId.isBlank()) {
            line(md, "关联工单", ticketId);
            line(md, "负责人", nz(assignee, "待分配，请值班人员认领"));
            line(md, "工单状态", formatTicketStatus(ticketStatus));
        } else {
            line(md, "建单状态", nz(ticketHint, "尚未建单（观察窗内或未达建单级别）"));
        }
        platformLinks(md, alert, baseUrl, ticketId);

        return alert.isHighRisk()
                ? NotifyMessage.urgent(title.toString(), clamp(md.toString()))
                : NotifyMessage.normal(title.toString(), clamp(md.toString()));
    }

    /**
     * 恢复卡（兼容签名：不带工单上下文）。未提供恢复取值与工单状态时按「无工单」态收尾。
     */
    public static NotifyMessage resolved(Alert alert, Map<String, String> labels,
                                         Map<String, String> annotations) {
        return resolved(alert, labels, annotations, Map.of(), false, null, null, null);
    }

    /**
     * 恢复卡：只给「曾经需要人处理」的告警发（调用方限 isHighRisk——低级别
     * 触发时本就静默入库，恢复再发一张卡就是链接设计原则里「恢复也要发，但别吵」的反面）。
     *
     * <p>收尾话术按事实三态：有工单且已知结束 / 有工单且仍有活跃告警 / 无工单。
     * <b>不再同时出现「请确认关单」与「无需处理，留痕归档」</b>——那两条是互斥结论，
     * 并列出现会让值班人无法判断到底要不要动手。</p>
     *
     * @param recoveryAnnotations 恢复推送自带的注解（取值以恢复时刻为准，不用触发时刻的旧值）
     * @param estimatedEnd        结束时间是「接收时刻」兜底（源未提供有效 endsAt）
     * @param ticketStatus        关联工单状态（可空）
     * @param otherActive         同一工单下仍活跃的告警条数（可空）
     * @param baseUrl             平台部署地址（仅接受受信任 HTTPS）
     */
    public static NotifyMessage resolved(Alert alert, Map<String, String> labels,
                                         Map<String, String> originalAnnotations,
                                         Map<String, String> recoveryAnnotations, boolean estimatedEnd,
                                         String ticketStatus, Long otherActive, String baseUrl) {
        String level = nz(alert.getLevel(), "—");
        String service = nz(alert.getService(), null);

        StringBuilder title = new StringBuilder("[已恢复] ").append(titlePart(level)).append(' ');
        if (service != null) {
            title.append(titlePart(service)).append(' ');
        }
        title.append(titlePart(nz(alert.getAlertName(), "告警")));

        StringBuilder md = header(title.toString());
        line(md, "当前状态", "监控恢复（OK）");
        line(md, "告警等级", formatLevel(alert.getLevel()));
        line(md, "摘要", AlertAnnotationKeys.valueOf(originalAnnotations, AlertAnnotationKeys.SUMMARY));

        // 对称定位硬核事实（恢复主机、恢复地址与监控项目）
        String resHost = AlertAnnotationKeys.valueOf(originalAnnotations, AlertAnnotationKeys.HOST_NAME);
        if (resHost == null) {
            resHost = first(labels, "host", "hostname", "node");
        }
        String resAddress = AlertAnnotationKeys.valueOf(originalAnnotations, AlertAnnotationKeys.TARGET_ADDRESS);
        if (resAddress == null) {
            resAddress = first(labels, "ip", "address");
        }
        String resItem = AlertAnnotationKeys.valueOf(originalAnnotations, AlertAnnotationKeys.MONITOR_ITEM);
        if (resItem == null) {
            resItem = first(originalAnnotations, "item_name");
        }
        line(md, "恢复主机", resHost);
        line(md, "恢复地址", resAddress);
        line(md, "监控项目", resItem);
        line(md, "原告警说明", nz(alert.getDescription(), null));

        // 恢复取值：优先取恢复推送里的取值（回落值），没有则回落到触发时的当前值并标注来源差异
        String recoveryValue = first(recoveryAnnotations, "value", "current_value", AlertAnnotationKeys.CURRENT_VALUE);
        String originalValue = AlertAnnotationKeys.valueOf(originalAnnotations, AlertAnnotationKeys.CURRENT_VALUE);
        if (recoveryValue != null) {
            line(md, "恢复取值", recoveryValue);
        } else if (originalValue != null) {
            line(md, "恢复取值", "未提供（触发时取值 " + originalValue + "）");
        }
        boolean estimated = estimatedEnd || estimatedStart(originalAnnotations);
        line(md, estimated ? "本次持续（估算）" : "本次持续",
                humanDuration(alert.getFirstOccurredAt(),
                        alert.getResolvedAt() != null ? alert.getResolvedAt() : LocalDateTime.now()));

        boolean hasTicket = alert.getTicketId() != null && !alert.getTicketId().isBlank();
        if (hasTicket) {
            line(md, "关联工单", alert.getTicketId());
            line(md, "工单状态", formatTicketStatus(ticketStatus));
            if (otherActive != null && otherActive > 0) {
                md.append("\n监控侧该告警已解除，但同一工单下仍有 ").append(otherActive)
                        .append(" 条活跃告警——**不要据此关闭整张工单**，请逐条核对。\n");
            } else if ("CLOSED".equals(ticketStatus) || "VOID".equals(ticketStatus)) {
                md.append("\n工单已结束。本通知无需处理，留痕归档；业务验证结果以工单记录为准。\n");
            } else {
                md.append("\n监控告警已解除；**请到工单核查其他关联告警、业务验证与处置记录**，确认后再关单。\n");
            }
        } else {
            md.append("\n本告警未建单（观察窗内自愈或未达建单级别）：无需处理，留痕归档。\n");
        }
        platformLinks(md, alert, baseUrl, alert.getTicketId());

        // 恢复卡恒为 normal：火已灭，不再 @所有人
        return NotifyMessage.normal(title.toString(), clamp(md.toString()));
    }

    /**
     * 升级卡：触发卡的全部上下文 + 首行「级别 prev → new」。
     * <p>升级是「必须触达的新事实」（建单通知对老单早已发过），信息设计同触发卡——
     * 值班人同样需要影响/指标/首步动作，而不是只有「⬆️ 升级了」四个字。
     * urgent 判定仍走 {@link Alert#isHighRisk()}（调用方只在升级进 P0/P1 时发）。</p>
     */
    public static NotifyMessage escalated(Alert alert, Map<String, String> labels,
                                          Map<String, String> annotations, String prevLevel) {
        NotifyMessage base = firing(alert, labels, annotations, alert.getTicketId());
        String title = "⬆️ " + base.title();
        // 级别变化行插在标题之后、摘要之前——它是这条卡与触发卡唯一的差异事实
        int firstBlank = base.markdown().indexOf("\n\n");
        String change = "- **级别**：" + nz(formatLevel(prevLevel), "?") + " → " + nz(formatLevel(alert.getLevel()), "?") + "\n";
        String md = firstBlank < 0
                ? base.markdown() + change
                : base.markdown().substring(0, firstBlank + 2) + change + base.markdown().substring(firstBlank + 2);
        return alert.isHighRisk() ? NotifyMessage.urgent(title, md) : NotifyMessage.normal(title, md);
    }

    /**
     * 诊断补充卡：取证结果的可读性补链，<b>恒为 normal</b>——证据不足是「需要人看」，
     * 不是「需要把所有人群呼起来」；触发卡已经承担了强提醒职责。
     *
     * <p>只给结论状态与逐方向取证概况，不转发日志样本/假设原文/取证明细：
     * 群摘要与审计明细分离，明细留在权限受控的详情页。</p>
     */
    public static NotifyMessage diagnosis(Alert alert, Map<String, String> labels,
                                          EvidenceAggregator.AggregateResult result,
                                          String ticketId, String baseUrl) {
        String target = first(labels, "node", "host", "hostname", "pod", "instance");
        String subject = nz(target, nz(alert.getService(), null));
        String title = "[诊断补充] " + titlePart(subject == null ? nz(alert.getAlertName(), "告警") : subject)
                + " · " + titlePart(nz(alert.getAlertName(), ""));
        StringBuilder md = header(title.trim());
        line(md, "平台事件 ID", alert.getId() == null ? null : alert.getId().toString());
        line(md, "监控规则", alert.getAlertName());
        line(md, "关联工单", firstValue(ticketId, alert.getTicketId()));
        if (result != null) {
            line(md, "诊断状态", switch (result.sufficiency()) {
                case INSUFFICIENT -> "证据不足，暂无法形成可靠根因结论";
                case WEAK -> "证据薄弱，诊断结果需人工复核";
                case SUFFICIENT -> "取证完成；诊断结论及其依据请查看详情，不等于根因已确认";
            });
            String statuses = result.evidences() == null ? null : result.evidences().stream()
                    .filter(e -> e != null)
                    .limit(8)
                    .map(e -> direction(e.evidenceType()) + "：" + statusText(e.status()))
                    .collect(Collectors.joining("；"));
            line(md, "取证概况", statuses);
            if (result.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT) {
                line(md, "排查建议", "诊断数据源缺失或采集异常，请先核验监控探针、日志上报链路与权限，优先转人工研判");
            } else if (result.sufficiency() == EvidenceAggregator.Sufficiency.WEAK) {
                line(md, "排查建议", "证据链存在推断薄弱项，请人工核验关键异常时序与调用链后再执行根因处置");
            }
        }
        md.append("\n请查看取证记录；证据不足时先核对对象、时间窗与采集覆盖，**不要据此认定业务正常**。\n");
        platformLinks(md, alert, baseUrl, firstValue(ticketId, alert.getTicketId()));
        return NotifyMessage.normal(title.trim(), clamp(md.toString()));
    }

    // ==================== 渲染原语 ====================

    /**
     * 标题里的单个外源片段：脱敏 + 单行化 + 限量 + 转义（不含本类自加的结构字符）。
     * <p>标题是卡片第一行，也是最容易被外部数据注入的位置（alertName/labels 来自推送方）；
     * 但它同时承载级别/[已恢复] 这类可读标记，所以只能逐片段消毒、不能整串转义。</p>
     */
    private static String titlePart(String value) {
        return value == null ? null : esc(plain(value, 80));
    }

    /** 标题行（"### " + 标题 + 空行）；标题各外源片段由 {@link #titlePart} 消毒 */
    private static StringBuilder header(String title) {
        return new StringBuilder("### ").append(title).append("\n\n");
    }

    /**
     * 一行「- **键**：值」；值为 null/空白整行省略（缺料不撒谎）。
     * 外部来源文本在这里统一过 {@link #safe}：脱敏 → 单行化 → 限量 → 转义。
     */
    private static void line(StringBuilder md, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        md.append("- **").append(label).append("**：").append(safe(value)).append("\n");
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

    /**
     * 平台直达链接：只由<b>受信任的部署地址</b> + 真实 ID 构造。
     *
     * <p>未配置/非法地址时给一句可执行的指引，而不是渲染一个点不开或指向外站的链接——
     * 卡片里的链接是「值班人会点」的东西，不能让外部数据决定它去哪。</p>
     */
    private static void platformLinks(StringBuilder md, Alert alert, String configuredBase, String ticketId) {
        String base = validBase(configuredBase);
        if (base == null) {
            md.append("\n请登录 OpsBrain 后按平台事件 ID 或工单号查询（未配置可信的 HTTPS 平台地址）。\n");
            return;
        }
        StringBuilder links = new StringBuilder();
        if (alert != null && alert.getId() != null) {
            links.append("[查看告警并处理](").append(base).append("/alerts/").append(alert.getId()).append(")");
        }
        if (ticketId != null && !ticketId.isBlank()) {
            if (links.length() > 0) {
                links.append("  ");
            }
            links.append("[查看工单](").append(base).append("/tickets/")
                    .append(URLEncoder.encode(ticketId, StandardCharsets.UTF_8).replace("+", "%20")).append(")");
        }
        if (links.length() > 0) {
            md.append('\n').append(links).append('\n');
        }
    }

    /** 受信任的部署地址：仅 https、无凭据、无 query/fragment、无空白与括号等可注入字符 */
    private static String validBase(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(configured.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || configured.matches(".*[\\s\\\\()<>].*")) {
                return null;
            }
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 外源文本落卡前的统一处理：脱敏 → 单行化 → 限量 → markdown 转义 */
    private static String safe(String value) {
        return esc(plain(value, MAX_LINE_CHARS));
    }

    /**
     * 基础凭据脱敏 + 控制字符单行化 + 限量。
     * <p>原始证据仍在权限受控的详情页，卡片只承载「够起步处置」的部分。</p>
     */
    private static String plain(String value, int max) {
        if (value == null) {
            return null;
        }
        String safe = value
                .replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[已脱敏]")
                .replaceAll("(?i)((?:access_token|api_key|token|password|secret)\\s*[=:]\\s*)[^\\s&,;]+", "$1[已脱敏]")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return safe.length() <= max ? safe : safe.substring(0, max) + "…（完整内容见详情）";
    }

    /**
     * markdown 转义：防外部数据制造链接/加粗/代码段，{@code @} 换全角防被渠道当提醒目标
     * （实际 @ 由渠道配置控制，不由告警内容决定）。
     */
    private static String esc(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("`", "\\`")
                .replace("@", "＠");
    }

    private static String first(Map<String, String> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            String v = values.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String firstValue(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** 首次发生时间是否只是「接收时间」兜底（源未给有效 startsAt） */
    private static boolean estimatedStart(Map<String, String> annotations) {
        return annotations != null && !"false".equals(annotations.get(START_TIME_ESTIMATED));
    }

    private static String statusText(Evidence.EvidenceStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case SUCCESS -> "查询成功";
            case NO_DATA -> "无匹配数据（不能据此排除）";
            case FAILED -> "查询失败";
            case UNAVAILABLE -> "数据源未启用/不可用";
        };
    }

    private static String direction(String type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case Evidence.Type.METRICS -> "指标";
            case Evidence.Type.CHANGES -> "变更";
            case Evidence.Type.LOGS -> "日志";
            case Evidence.Type.KNOWLEDGE -> "知识库";
            default -> plain(type, 30);
        };
    }


    /**
     * 工单状态中文本地化（降低一线值班人员认知门槛）。
     * <p>主显中文，保留原英文括号对照，方便同时满足初级值班易读与老工程师原值对账。</p>
     */

    /**
     * 告警级别中文标签化（降低认知门槛：P0~P4 映射为人话级别说明）。
     */
    static String formatLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        return switch (level.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "P0", "DISASTER" -> "P0 灾难级（Disaster）";
            case "P1", "HIGH" -> "P1 严重级（High）";
            case "P2", "AVERAGE" -> "P2 一般严重（Average）";
            case "P3", "WARNING" -> "P3 警告级（Warning）";
            case "P4", "INFO", "INFORMATION" -> "P4 提示级（Info）";
            default -> level.trim();
        };
    }

    static String formatTicketStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PENDING" -> "待分配（未认领 PENDING）";
            case "PROCESSING" -> "处置中（PROCESSING）";
            case "RESOLVED" -> "已恢复待验证（RESOLVED）";
            case "CLOSED" -> "已闭环（CLOSED）";
            case "VOID" -> "已作废（VOID）";
            default -> status.trim();
        };
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
