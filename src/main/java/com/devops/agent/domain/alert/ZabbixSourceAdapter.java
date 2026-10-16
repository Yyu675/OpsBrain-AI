package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.DTO.ZabbixWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zabbix 源适配器（Zabbix Webhook 媒介类型负载 → {@link AlertSignal}，L2 多信号源第二源）。
 *
 * <p>
 * 这是 {@link AlertSourceAdapter} 接缝的<b>第二个真实实现</b>——文档 78 号
 * 「告警来源接口评估」给的判据是「出现第二个真实接入源时再抽中性模型」，
 * 本轮正是那一刻：Alertmanager（自由标签模型）与 Zabbix（固定字段模型）
 * 的差异全部收敛在本类，核心链（去重/建单/诊断/通知）一行未改。
 * </p>
 *
 * <h3>字段映射（Zabbix → 平台）</h3>
 * <table border="1">
 *   <tr><th>Zabbix</th><th>平台</th><th>说明</th></tr>
 *   <tr><td>trigger_name / event_name</td><td>alertName</td>
 *       <td>trigger_name 优先（跨 PROBLEM/OK 稳定）；两者都缺 → 拒绝该负载</td></tr>
 *   <tr><td>tag service ／ host_name</td><td>service</td>
 *       <td>Zabbix 事件路径没有「业务服务」概念，host 是最近似的影响面，
 *           也是去重键里真正起分辨作用的维度（同一触发器在不同主机必须分开）</td></tr>
 *   <tr><td>event_severity</td><td>severity → P0~P4</td>
 *       <td>6 级压缩为 5 级，见 {@link #mapSeverity}</td></tr>
 *   <tr><td>host_name / host_ip</td><td>labels: host / instance</td>
 *       <td>去重键的标签维度；instance 缺 IP 时回退主机名</td></tr>
 *   <tr><td>event_tags / host_tags</td><td>labels + annotations</td>
 *       <td>标签进 labels（参与去重，与保留字冲突时让位保留字）；
 *           {@code module} 标签决定 module，缺省 OTHER（与 Alertmanager 同规则）</td></tr>
 *   <tr><td>event_name / trigger_description / item_value</td><td>annotations</td>
 *       <td>按 {@link AlertAnnotationKeys} 字典落位：summary / description / current_value</td></tr>
 *   <tr><td>event_date + event_time</td><td>startsAt（PROBLEM）／ endsAt（OK）</td>
 *       <td>无时区墙钟按 {@code devops.alert.zabbix.timezone} 解释，见类注释末</td></tr>
 *   <tr><td>event_status=OK ／ event_value=0</td><td>resolved</td>
 *       <td>恢复事件在 Zabbix 里是<b>另一条事件</b>（event_id 不同），
 *           故去重不依赖 event_id，见 {@link #fingerprintOf}</td></tr>
 * </table>
 *
 * <h3>标签是 Zabbix 侧唯一的「自由文本位」，字典键就从标签补</h3>
 * <p>
 * Alertmanager 有 annotations 可以放 {@code impact / first_action / runbook_url} 这些
 * 给人看的键，Zabbix 没有对应物——规则作者能填自由文本的地方只有事件标签。
 * 所以本适配器把<b>与 {@link AlertAnnotationKeys} 同名的标签</b>补进 annotations
 * （显式宏优先、标签只填空缺），并且这类键<b>不进 labels</b>：labels 参与去重键，
 * 把会变动的文案放进去会让同一条问题每次推送都算出新键，表里堆出同源多条的告警。
 * </p>
 *
 * <h3>为什么不把 severity 直接透传给核心链</h3>
 * <p>
 * 核心链的 {@code normalizeLevel} 只认 CRITICAL/WARNING/INFO/P0~P4，其余一律落到
 * 默认 P3——Zabbix 的 "Average"/"High"/"Disaster" 若原样透传，<b>服务器宕机与
 * 磁盘快满会得到同一个级别</b>，且不报错。所以在这里翻译成平台词汇表：
 * 与社区词表重合的（Warning/Information）沿用核心链既有映射，不制造第二套语义；
 * Zabbix 独有的按 6→5 压缩。
 * </p>
 *
 * <h3>时间：无时区是 Zabbix 的常态，别猜（关键取舍）</h3>
 * <p>
 * {@code event_date}/{@code event_time} 是 Zabbix <b>服务器本地时间</b>的墙钟，
 * 负载里没有偏移量。平台所有 TIMESTAMP 列都是无时区本地时间，而部署时区是钉死的
 * （Dockerfile / compose 设 TZ，见 {@code DeploymentTimeZoneContractTest}；
 * JVM 里就是 {@code ZoneId.systemDefault()}），所以这里<b>默认用部署时区</b>解释墙钟——
 * 不写死某个时区名：平台迁到别的时区部署时，写死的默认值会把事件时刻整体平移且无人察觉。
 * 需要特殊解释时可用 {@code devops.alert.zabbix.timezone} 显式指定。
 * 时区配错时<b>持续时长仍然正确</b>
 * （PROBLEM 与 OK 用同一把尺子），只有绝对起始时刻会整体平移，
 * 这正是「宁可标注估算也不假装精确」的边界（解析不出来就交 null，
 * 由核心链标 {@code START_TIME_ESTIMATED} 并在卡片上写明「估算」）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-10-14
 */
@Slf4j
@Component
public class ZabbixSourceAdapter implements AlertSourceAdapter {

    /** 落库来源标识 → {@code sys_alert.source}（与 Alertmanager 的 prometheus 并列） */
    public static final String SOURCE = "zabbix";

    private static final String DEFAULT_MODULE = "OTHER";

    /** 能证明「这是 Zabbix 事件」的键——负载形状可自定义，故用「有没有它的身份证」判定 */
    private static final List<String> IDENTITY_KEYS =
            List.of("event_id", "trigger_id", "trigger_name", "event_name", "host_name");

    /** 保留标签名：这些键在 labels 里另有语义，事件标签不得覆盖（撞名时让位，不报错） */
    private static final List<String> RESERVED_LABELS =
            List.of("alertname", "service", "severity", "module", "system", "host", "instance", "source");

    /**
     * Zabbix 六级 → 平台 P0~P4。
     * <p>词表含英文（官方）与中文（本地化界面里 {@code {EVENT.SEVERITY}} 就是中文）
     * 两种写法，以及 0~5 的数字形态——三种形态在实施里都真实出现过。</p>
     */
    private static final Map<String, String> SEVERITY_MAP = Map.ofEntries(
            Map.entry("5", "P0"), Map.entry("DISASTER", "P0"), Map.entry("灾难", "P0"),
            Map.entry("4", "P1"), Map.entry("HIGH", "P1"), Map.entry("严重", "P1"),
            Map.entry("3", "P2"), Map.entry("AVERAGE", "P2"), Map.entry("一般严重", "P2"),
            Map.entry("2", "P2"), Map.entry("WARNING", "P2"), Map.entry("警告", "P2"),
            Map.entry("1", "P4"), Map.entry("INFORMATION", "P4"), Map.entry("INFO", "P4"),
            Map.entry("信息", "P4"),
            Map.entry("0", "P4"), Map.entry("NOT CLASSIFIED", "P4"), Map.entry("NOTCLASSIFIED", "P4"),
            Map.entry("未分类", "P4"));

    /**
     * 日期形态表。
     * <p>
     * <b>{@code {EVENT.DATE}} 的默认格式是 {@code yyyy.MM.dd}（点分隔）</b>——
     * 只认 ISO 短横线会让所有真实事件的时间都解析不出来（表现为持续时长归零、
     * 卡片上写着「估算」，且不报错）。ISO 形态一并接受，谁在脚本里换过格式都收。
     * </p>
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy.MM.dd"));

    /** {@code {EVENT.TIME}} 默认 {@code H:mm:ss}；不补零/不补秒的写法一并收 */
    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm"));

    /** {@code {EVENT.TAGSJSON}} 形态：只取 tag/value 两键，不做通用 JSON 解析（见 {@link #parseTags}） */
    private static final Pattern TAG_JSON_PAIR = Pattern.compile(
            "\"tag\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\""
                    + "|\"value\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"tag\"\\s*:\\s*\"([^\"]*)\"");

    /** 事件墙钟的解释时区（默认跟随部署时区；见类注释「时间」一节） */
    private final ZoneId zone;

    /** Spring 装配入口：时区可配，默认跟随部署时区（JVM 的 TZ，容器内已钉成约定值） */
    @Autowired
    public ZabbixSourceAdapter(
            @Value("${devops.alert.zabbix.timezone:}") String zoneId) {
        // 默认跟随部署时区（容器/Dockerfile 已把 TZ 钉成约定值，见 DeploymentTimeZoneContractTest）。
        // 不写死某个时区：平台若迁到其它时区部署，写死的默认值会静默把事件时刻整体平移。
        this.zone = zoneId == null || zoneId.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zoneId);
    }

    /** 无参形态（单测/离线探针用）：等价于默认口径，避免测试里复制一份默认时区常量 */
    public ZabbixSourceAdapter() {
        this("");
    }

    @Override
    public boolean supports(Object rawPayload) {
        if (!(rawPayload instanceof ZabbixWebhook webhook) || webhook.isEmpty()) {
            return false;
        }
        Map<String, String> f = webhook.flattened();
        return IDENTITY_KEYS.stream().anyMatch(k -> {
            String v = f.get(k);
            return v != null && !v.isBlank();
        });
    }

    @Override
    public List<AlertSignal> normalize(Object rawPayload) {
        if (!supports(rawPayload)) {
            throw new IllegalArgumentException(
                    "ZabbixSourceAdapter 不支持的负载类型或缺少 Zabbix 事件标识: "
                            + (rawPayload == null ? "null" : rawPayload.getClass().getSimpleName()));
        }
        ZabbixWebhook webhook = (ZabbixWebhook) rawPayload;
        Map<String, String> f = webhook.flattened();

        String alertName = first(f.get("trigger_name"), f.get("event_name"), f.get("alert_subject"));
        if (alertName == null) {
            // 拒绝而不是「落一条名叫 null 的告警」：名字是去重键的一部分，
            // 缺名字的告警会把所有无名字件合并成同一条，静默且不可逆
            throw new IllegalArgumentException(
                    "Zabbix 负载缺少 trigger_name/event_name：无法构造告警名（请检查媒介类型的参数列表）");
        }

        // 主机级标签先铺底、事件级覆盖：同一键在两层都出现时，事件级描述的是「这一次
        // 问题的现场」，比挂在主机上的长期属性更贴近事实（顺序反了会让主机上的
        // 陈旧标注盖掉事件当下的标注，且不报错）
        Map<String, String> tags = new LinkedHashMap<>(parseTags(f.get("host_tags")));
        tags.putAll(parseTags(f.get("event_tags")));

        Map<String, String> labels = buildLabels(f, tags);
        Map<String, String> annotations = buildAnnotations(f, tags, alertName);

        OffsetDateTime eventTime = parseEventTime(f);
        boolean resolved = isResolved(f);

        return List.of(new AlertSignal(
                alertName,
                first(tags.get("service"), f.get("host_name"), f.get("host_ip")),
                mapSeverity(f.get("event_severity"), f.get("trigger_severity")),
                moduleOf(tags),
                fingerprintOf(f, alertName),
                SOURCE,
                labels,
                annotations,
                summarize(f, alertName),
                // 触发中：有开始、**没有结束**——endsAt 填上会让「已持续多久」算成 0，
                // 也会让下游把一条还在烧的告警当成已经收尾（CI 首轮就是这么红的）
                resolved ? null : eventTime,
                resolved,
                resolved ? eventTime : null));
    }

    // ==================================================================
    // 级别
    // ==================================================================

    /**
     * Zabbix 级别词 → 平台级别。
     * <p>认不出来时<b>原样透传</b>而不是猜一个：核心链的默认值（P3）会兜住，
     * 原始词仍留在 {@code event_severity} 标签里可追溯——猜一个 P0 会制造误报，
     * 猜一个 P4 会漏掉真故障，两种猜法都比「让它落到默认」更糟。</p>
     */
    private String mapSeverity(String eventSeverity, String triggerSeverity) {
        String raw = first(eventSeverity, triggerSeverity);
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        String mapped = SEVERITY_MAP.get(key);
        if (mapped == null) {
            log.warn("⚠️ [Zabbix] 未知严重级别「{}」，原样透传（核心链将回落到默认 P3）", sanitize(raw));
        }
        return mapped != null ? mapped : raw.trim();
    }

    /**
     * 操作数据（{@code {EVENT.OPDATA}} 宏）。
     * <p>参数名两种写法都收：Zabbix 侧「媒介类型参数」是管理员自定义的，
     * 官方文档示例用 {@code opdata}，而 {EVENT.OPDATA} 宏直译又常被写成
     * {@code event_opdata}——只认一个会让一半实施方的操作数据静默丢失。</p>
     */
    private String opdataOf(Map<String, String> f) {
        return first(f.get("event_opdata"), f.get("opdata"));
    }

    private String moduleOf(Map<String, String> tags) {
        String module = tags.get("module");
        return module == null || module.isBlank() ? DEFAULT_MODULE : module.trim().toUpperCase(Locale.ROOT);
    }

    // ==================================================================
    // 标签与注解
    // ==================================================================

    private Map<String, String> buildLabels(Map<String, String> f, Map<String, String> tags) {
        Map<String, String> labels = new LinkedHashMap<>();
        putIfPresent(labels, "host", f.get("host_name"));
        // instance 语义对齐 Alertmanager：同服务不同实例必须能分开（去重键的标签维度）
        putIfPresent(labels, "instance", first(f.get("host_ip"), f.get("host_name")));
        putIfPresent(labels, "trigger_id", f.get("trigger_id"));
        putIfPresent(labels, "event_severity", first(f.get("event_severity"), f.get("trigger_severity")));
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String key = tag.getKey();
            if (key == null || key.isBlank() || isReservedLabel(key)) {
                continue;
            }
            // 注解字典里的键是「给人看」的展示信息（影响/首步动作/看板链接…），
            // 不进 labels：labels 参与 SHA-256 去重键，把会随事件变化的文案放进去，
            // 同一条问题每次推送都会算出新键 → 表里堆出多条「同源不同键」的告警
            if (AlertAnnotationKeys.ALL.contains(key)) {
                continue;
            }
            putIfPresent(labels, key, tag.getValue());
        }
        return labels;
    }

    /**
     * 注解按 {@link AlertAnnotationKeys} 字典落位。
     * <p>只放「给人看」的键（字典纪律），另外补几个 {@code zabbix_} 前缀的原始事实键：
     * 卡片与工单需要能回答「这条是哪个事件、哪台机器、原始级别是什么」，
     * 而这些在标签里已被去重语义占用。</p>
     */
    private Map<String, String> buildAnnotations(Map<String, String> f, Map<String, String> tags,
                                                String alertName) {
        Map<String, String> annotations = new LinkedHashMap<>();
        putIfPresent(annotations, AlertAnnotationKeys.SUMMARY, first(f.get("event_name"), alertName));
        putIfPresent(annotations, AlertAnnotationKeys.DESCRIPTION, f.get("trigger_description"));
        putIfPresent(annotations, AlertAnnotationKeys.CURRENT_VALUE, f.get("item_value"));
        putIfPresent(annotations, "opdata", opdataOf(f));
        putIfPresent(annotations, "item_name", f.get("item_name"));
        putIfPresent(annotations, AlertAnnotationKeys.MONITOR_ITEM, f.get("item_name"));
        putIfPresent(annotations, AlertAnnotationKeys.TARGET_ADDRESS, f.get("host_ip"));
        putIfPresent(annotations, AlertAnnotationKeys.HOST_NAME, f.get("host_name"));
        putIfPresent(annotations, "zabbix_event_id", f.get("event_id"));
        putIfPresent(annotations, "zabbix_trigger_id", f.get("trigger_id"));
        putIfPresent(annotations, "zabbix_host_ip", f.get("host_ip"));
        putIfPresent(annotations, "zabbix_event_severity", first(f.get("event_severity"), f.get("trigger_severity")));
        putIfPresent(annotations, "zabbix_event_date", f.get("event_date"));
        putIfPresent(annotations, "zabbix_event_time", f.get("event_time"));
        putIfPresent(annotations, "zabbix_recovery_status", f.get("event_recovery_status"));
        // Zabbix 没有 Alertmanager 那样的自由 annotations：规则作者能填「给人看的上下文」
        // 的地方就是事件标签。故同名标签按注解字典补位（显式宏优先，标签只填空缺）——
        // 这样通知卡片里「影响 / 首步动作 / 看板链接」这些行在 Zabbix 源上也真的有内容，
        // 而不是永远落在「缺料省略」分支。字典键的登记纪律见 AlertAnnotationKeys。
        for (String key : AlertAnnotationKeys.ALL) {
            if (!annotations.containsKey(key)) {
                putIfPresent(annotations, key, tags.get(key));
            }
        }
        return annotations;
    }

    /**
     * 事件描述（工单正文与告警卡片的「现场」来源）。
     * <p>Zabbix 的 {@code event_name} 就是运维真正读到的那句话（含取值与失败原因，
     * 例如「Failed to receive data: volumes : Error: cannot get URL: Timeout was reached」），
     * 所以它优先；opdata/item_value 作为补充事实拼在后面。拼接而不是覆盖：
     * 卡片要能一眼看到「哪句话 + 什么值」。</p>
     */
    private String summarize(Map<String, String> f, String alertName) {
        StringBuilder sb = new StringBuilder();
        String head = first(f.get("event_name"), f.get("trigger_description"), alertName);
        sb.append(head);
        String value = f.get("item_value");
        if (value != null && !value.equals(head)) {
            sb.append("｜当前取值: ").append(value);
        }
        String opdata = opdataOf(f);
        if (opdata != null) {
            sb.append("｜opdata: ").append(opdata);
        }
        return sb.toString();
    }

    /**
     * 去重指纹。
     * <p>不能用 {@code event_id}：Zabbix 的恢复事件有<b>独立</b>的 event_id，
     * PROBLEM 与 OK 用同一个 id 的假设会让恢复找不到原告警（表现为「恢复了但告警一直挂着」）。
     * 用「触发器 + 主机」才是同一条问题生命周期的稳定标识。</p>
     */
    private String fingerprintOf(Map<String, String> f, String alertName) {
        String trigger = first(f.get("trigger_id"), alertName);
        String host = first(f.get("host_name"), f.get("host_ip"), "unknown-host");
        return SOURCE + ":" + trigger + "@" + host;
    }

    private boolean isResolved(Map<String, String> f) {
        String status = first(f.get("event_status"), f.get("trigger_status"));
        if (status != null) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if ("OK".equals(s) || "RESOLVED".equals(s) || "0".equals(s)) {
                return true;
            }
            if ("PROBLEM".equals(s) || "1".equals(s)) {
                return false;
            }
        }
        String value = f.get("event_value");
        if (value != null) {
            String v = value.trim();
            if ("0".equals(v)) {
                return true;
            }
            if ("1".equals(v)) {
                return false;
            }
        }
        // 兼容只传恢复宏的脚本：有 event_recovery_* 说明这条就是恢复通知
        return f.get("event_recovery_status") != null || f.get("event_recovery_time") != null;
    }

    // ==================================================================
    // 时间与标签解析
    // ==================================================================

    /**
     * {@code event_date} + {@code event_time} → 带偏移的时刻。
     * <p>解析失败返回 null（交给核心链标注「估算」），<b>不回退到 now()</b>——
     * 把「不知道」写成「就是现在」会让持续时长凭空归零，比缺数据更难发现。</p>
     */
    private OffsetDateTime parseEventTime(Map<String, String> f) {
        String date = f.get("event_date");
        if (date == null) {
            return null;
        }
        String d = date.trim();
        String t = f.get("event_time") == null ? null : f.get("event_time").trim();
        OffsetDateTime parsed = parseWallClock(d, t);
        if (parsed == null) {
            log.warn("⚠️ [Zabbix] 事件时间无法解析（date={}, time={}），交由核心链标注估算", d, t);
        }
        return parsed;
    }

    /**
     * 逐个形态试解析墙钟；全不匹配返回 null。
     * <p>返回 null 而不是抛：调用方要的只是「读没读出来」这一个结论，
     * 异常类型是内部实现细节。</p>
     */
    private OffsetDateTime parseWallClock(String d, String t) {
        if (d.contains("T")) {
            OffsetDateTime iso = parseIsoMoment(d);
            if (iso != null) {
                return iso;
            }
            // 形态兜底：日期与时间被拼在同一字段（脚本里写成 {EVENT.DATE}T{EVENT.TIME}），
            // 日期段可能是点分隔——拆开按各自的形态表解析，而不是整串放弃
            int split = d.indexOf('T');
            LocalDate day = parseDay(d.substring(0, split).trim());
            LocalTime time = parseAnyTime(d.substring(split + 1).trim());
            return day == null || time == null
                    ? null : LocalDateTime.of(day, time).atZone(zone).toOffsetDateTime();
        }
        LocalDate day = parseDay(d);
        if (day == null) {
            return null;
        }
        if (t == null || t.isEmpty()) {
            // 只有日期：按当天零点解释（Zabbix 的「每日」型触发器就是这种形态）
            return day.atStartOfDay(zone).toOffsetDateTime();
        }
        LocalTime time = parseAnyTime(t);
        return time == null ? null : LocalDateTime.of(day, time).atZone(zone).toOffsetDateTime();
    }

    /**
     * 单字段 ISO 形态：带偏移量最理想（照单全收、不做推断）；
     * 不带偏移量则按部署时区解释（{@code {EVENT.DATE}} 是服务器本地墙钟，本来就没有偏移量）。
     */
    private OffsetDateTime parseIsoMoment(String d) {
        try {
            return OffsetDateTime.parse(d);
        } catch (DateTimeParseException e) {
            log.debug("[Zabbix] 事件时间不是带偏移量的 ISO 形态（{}），改按部署时区解释", d);
        }
        try {
            return LocalDateTime.parse(d).atZone(zone).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            log.debug("[Zabbix] 事件时间不是合法 ISO 本地时间（{}）", d);
            return null;
        }
    }

    /** 日期段：ISO 与 Zabbix 默认的点分隔形态都试 */
    private LocalDate parseDay(String d) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(d, fmt);
            } catch (DateTimeParseException e) {
                log.debug("[Zabbix] 事件日期不匹配形态 [{}]（{}）", fmt, d);
            }
        }
        return null;
    }

    /** 时间段：默认 {@code H:mm:ss}，不补零/不补秒的写法一并收 */
    private LocalTime parseAnyTime(String t) {
        for (DateTimeFormatter fmt : TIME_FORMATS) {
            try {
                return LocalTime.parse(t, fmt);
            } catch (DateTimeParseException e) {
                log.debug("[Zabbix] 事件时间不匹配形态 [{}]（{}）", fmt, t);
            }
        }
        return null;
    }

    /**
     * 事件标签解析，三种真实形态都收：
     * <ol>
     *   <li>JSON 数组（媒介类型参数被声明成数组，Jackson 已给出 {@code List<Map>}）；</li>
     *   <li>{@code {EVENT.TAGSJSON}} 宏字符串 {@code [{"tag":"a","value":"b"}]}；</li>
     *   <li>{@code {EVENT.TAGS}} 宏形态 {@code a:b,c:d}。</li>
     * </ol>
     * <p>刻意不引 JSON 解析器：这个位置只需要 tag/value 两个键，
     * 而 full parser 会把「标签里出现反斜杠就整条拒绝」的风险带进主链路。
     * 形态不符时返回空表并留一行 warn——标签是增强信息，不该阻断告警入库。</p>
     */
    Map<String, String> parseTags(String raw) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            Matcher m = TAG_JSON_PAIR.matcher(s);
            while (m.find()) {
                if (m.group(1) != null) {
                    tags.put(m.group(1), m.group(2));
                } else {
                    tags.put(m.group(4), m.group(3));
                }
            }
            if (tags.isEmpty()) {
                log.warn("⚠️ [Zabbix] 事件标签形态未识别（非 tag/value 对），已忽略：{}", sanitize(s));
            }
            return tags;
        }
        for (String part : s.split(",")) {
            int idx = part.indexOf(':');
            if (idx > 0) {
                String k = part.substring(0, idx).trim();
                String v = part.substring(idx + 1).trim();
                if (!k.isEmpty()) {
                    tags.put(k, v);
                }
            }
        }
        return tags;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 第一个非空白值（Zabbix 的宏缺失时送空串，不是不送键，故必须逐个判空） */
    private static String first(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    /** 保留标签名判定（大小写不敏感；含大小写变体，防止 Host/host 两套写法绕过） */
    private static boolean isReservedLabel(String key) {
        return RESERVED_LABELS.contains(key) || RESERVED_LABELS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    /** 日志脱敏：Zabbix 的触发器名/标签里可能带连接串或口令，只留前 80 字符并去掉换行 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "null";
        }
        String s = raw.replaceAll("\\s+", " ");
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /** 来源标识：接入排错时报的是运维在 Zabbix 侧认识的词，不是 Java 类名 */
    @Override
    public String sourceName() {
        return SOURCE;
    }
}
