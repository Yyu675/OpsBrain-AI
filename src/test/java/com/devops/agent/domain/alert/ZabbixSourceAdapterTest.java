package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.DTO.ZabbixWebhook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zabbix 源适配器单测（L2 多信号源第二源的解析边界）。
 *
 * <h3>为什么这层必须单独锁住</h3>
 * <p>
 * 适配器是「读懂 Zabbix」的唯一位置，写错的后果都不报错：级别翻译漏一档会让
 * 宕机与磁盘快满落到同一级别；恢复判定认错字段会让「恢复了但告警一直挂着」；
 * 指纹里混进 event_id 会让 PROBLEM 与 OK 配不上对。这些缺陷在核心链上
 * 全都表现为「看起来正常的告警」，只能靠本类逐条钉死。
 * </p>
 *
 * <h3>为什么用真 JSON 而不是手工 new 一个 DTO</h3>
 * <p>
 * {@link ZabbixWebhook} 的整个价值在「字段形状不确定也能收下」——用 setter 手工
 * 构造就绕过了 Jackson 的 any-setter，测试会变成「我构造什么就读到什么」的
 * 自证。故本类一律走 {@code ObjectMapper.readValue(json, ZabbixWebhook.class)}，
 * 与线上 HTTP 入口同一条绑定路径。
 * </p>
 *
 * <h3>样本来自哪里</h3>
 * <p>
 * {@link #PROBLEM_JSON} 是真实接入方给的一份 Zabbix 事件（2026-10-13
 * primera01 / 10.210.44.11，Average，event_id 12002061，
 * 取值含 {@code Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.}），
 * 只把与本次断言无关的宏补全。用真样本而不是构造样本，是因为「接入方实际会送
 * 哪些键」这件事只能由现场回答。
 * </p>
 */
@DisplayName("ZabbixSourceAdapter 归一化解析")
class ZabbixSourceAdapterTest {

    /**
     * 时区显式钉成部署时区：适配器默认口径是 {@code ZoneId.systemDefault()}
     * （跟随部署环境），而 CI runner 是 UTC——测试若吃默认值，
     * 「17:37:55 → +08:00」这类断言就会看机器的时区脸色，本机绿、CI 红。
     */
    private final ZabbixSourceAdapter adapter = new ZabbixSourceAdapter("Asia/Shanghai");
    private final ObjectMapper mapper = new ObjectMapper();

    /** 真实样本：PROBLEM 事件（Average），取值里就是那句超时报错原文 */
    static final String PROBLEM_JSON = """
            {
              "event_id": "12002061",
              "event_name": "primera01 数据接收异常：Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.",
              "event_severity": "Average",
              "event_date": "2026.10.13",
              "event_time": "17:37:55",
              "event_status": "PROBLEM",
              "event_value": "1",
              "event_tags": "[{\\"tag\\":\\"service\\",\\"value\\":\\"primera-portal\\"},{\\"tag\\":\\"module\\",\\"value\\":\\"db\\"}]",
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "trigger_description": "卷数据采集连续超时",
              "host_name": "primera01",
              "host_ip": "10.210.44.11",
              "item_name": "receive.data.status",
              "item_value": "Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.",
              "opdata": "volume check timeout"
            }
            """;

    /** 同一条问题的恢复事件（OK）：Zabbix 里它是另一条事件、另一个 event_id */
    static final String OK_JSON = """
            {
              "event_id": "12002062",
              "event_name": "primera01 数据接收异常：Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.",
              "event_severity": "Average",
              "event_date": "2026.10.13",
              "event_time": "17:38:47",
              "event_status": "OK",
              "event_value": "0",
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "host_name": "primera01",
              "host_ip": "10.210.44.11"
            }
            """;

    /** 参数被声明成数组时的形态：{@code event_tags} 直接是 {"tag":...} 对象数组 */
    static final String ARRAY_TAGS_JSON = """
            {
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "host_name": "primera01",
              "host_ip": "10.210.44.11",
              "event_severity": "Average",
              "event_status": "PROBLEM",
              "event_date": "2026.10.13",
              "event_time": "17:37:55",
              "event_tags": [{"tag": "service", "value": "primera-portal"},
                             {"tag": "module", "value": "db"},
                             {"tag": "env", "value": "prod"}]
            }
            """;

    /** 主机级标签与事件级标签同键并存：事件级必须胜出（order 反了会让主机上的陈旧标注盖掉现场） */
    static final String HOST_TAGS_JSON = """
            {
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "host_name": "primera01",
              "host_ip": "10.210.44.11",
              "event_severity": "Average",
              "event_status": "PROBLEM",
              "event_date": "2026.10.13",
              "event_time": "17:37:55",
              "host_tags": [{"tag": "env", "value": "staging"}, {"tag": "rack", "value": "r12"}],
              "event_tags": [{"tag": "env", "value": "prod"}]
            }
            """;

    /** 通知卡片的上下文键（impact/first_action/runbook_url）在 Zabbix 侧只能落在事件标签上 */
    static final String DICT_TAGS_JSON = """
            {
              "trigger_id": "20531",
              "trigger_name": "NodeDown-IT44c08600 数据接收异常",
              "host_name": "primera01",
              "host_ip": "10.210.44.11",
              "event_severity": "Average",
              "event_status": "PROBLEM",
              "event_date": "2026.10.13",
              "event_time": "17:37:55",
              "event_tags": [{"tag": "impact", "value": "volumes 采集全链路不可见"},
                             {"tag": "first_action", "value": "1) 查 primera01 到存储的网络 2) 看 collector 日志"},
                             {"tag": "runbook_url", "value": "https://wiki.example.com/runbook/node-down"},
                             {"tag": "env", "value": "prod"}]
            }
            """;

    private ZabbixWebhook parse(String json) {
        try {
            return mapper.readValue(json, ZabbixWebhook.class);
        } catch (Exception e) {
            throw new IllegalStateException("样本 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private AlertSignal normalize(String json) {
        List<AlertSignal> signals = adapter.normalize(parse(json));
        assertThat(signals).hasSize(1);
        return signals.get(0);
    }

    // ==================================================================
    // 字段映射
    // ==================================================================

    @Test
    @DisplayName("真样本：级别 Average→P2、host→labels、tag service→service、module 大写化")
    void mapsRealSample() {
        AlertSignal s = normalize(PROBLEM_JSON);

        assertThat(s.alertName()).isEqualTo("NodeDown-IT44c08600 数据接收异常");
        // Zabbix 没有「业务服务」概念：tag service 是接入方显式声明的语义，优先于 host 回退
        assertThat(s.service()).isEqualTo("primera-portal");
        assertThat(s.severity()).isEqualTo("P2");
        assertThat(s.module()).isEqualTo("DB");
        assertThat(s.source()).isEqualTo("zabbix");
        assertThat(s.resolved()).isFalse();

        assertThat(s.labels()).containsEntry("host", "primera01")
                .containsEntry("instance", "10.210.44.11")
                .containsEntry("trigger_id", "20531")
                // 原始级别留在标签里：排查时要能回答「Zabbix 当时报的是什么级别」
                .containsEntry("event_severity", "Average");
        // service/module 是信号的一等字段，不再重复进 labels（避免同一语义两处存在）
        assertThat(s.labels()).doesNotContainKeys("service", "module");
        // 自定义标签进 labels 参与去重（ARRAY_TAGS_JSON 里的 env）
        assertThat(normalize(ARRAY_TAGS_JSON).labels()).containsEntry("env", "prod");

        assertThat(s.annotations())
                .containsEntry(AlertAnnotationKeys.SUMMARY,
                        "primera01 数据接收异常：Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.")
                .containsEntry(AlertAnnotationKeys.DESCRIPTION, "卷数据采集连续超时")
                .containsEntry(AlertAnnotationKeys.CURRENT_VALUE,
                        "Failed to receive data: volumes : Error: cannot get URL: Timeout was reached.")
                .containsEntry("zabbix_event_id", "12002061")
                .containsEntry("zabbix_host_ip", "10.210.44.11")
                .containsEntry("zabbix_event_severity", "Average")
                .containsEntry("zabbix_event_date", "2026.10.13");
    }

    @Test
    @DisplayName("事件时间按部署时区解释（17:37:55 → +08:00），不猜 UTC")
    void eventTimeUsesDeployZone() {
        AlertSignal s = normalize(PROBLEM_JSON);

        assertThat(s.startsAt()).isEqualTo(OffsetDateTime.parse("2026-10-13T17:37:55+08:00"));
        assertThat(s.endsAt()).isNull();
    }

    @Test
    @DisplayName("描述拼接：event_name 打头 + 当前取值（卡片要能一眼看到「哪句话 + 什么值」）")
    void descriptionCarriesNameAndValue() {
        String description = normalize(PROBLEM_JSON).description();

        assertThat(description).startsWith("primera01 数据接收异常")
                .contains("当前取值: Failed to receive data: volumes")
                .contains("opdata: volume check timeout");
    }

    // ==================================================================
    // 恢复语义
    // ==================================================================

    @Test
    @DisplayName("OK 事件 → resolved=true、endsAt 有值、startsAt 为 null（持续时长由核心链按行算）")
    void okEventMarksResolved() {
        AlertSignal s = normalize(OK_JSON);

        assertThat(s.resolved()).isTrue();
        assertThat(s.endsAt()).isEqualTo(OffsetDateTime.parse("2026-10-13T17:38:47+08:00"));
        // 恢复信号本身不是「一次发生」，给 startsAt 会让核心链把它当成新告警
        assertThat(s.startsAt()).isNull();
    }

    @Test
    @DisplayName("PROBLEM 与 OK 的指纹必须相同，且指纹里不能出现 event_id")
    void fingerprintIsStableAcrossRecovery() {
        AlertSignal problem = normalize(PROBLEM_JSON);
        AlertSignal ok = normalize(OK_JSON);

        assertThat(problem.fingerprint()).isEqualTo(ok.fingerprint());
        assertThat(problem.fingerprint()).isEqualTo("zabbix:20531@primera01");
        // event_id 在 PROBLEM/OK 上不同，混进指纹会让恢复永远配不上原告警
        assertThat(problem.fingerprint()).doesNotContain("12002061", "12002062");
    }

    @Test
    @DisplayName("同一触发器在不同主机 → 指纹不同（否则一台机器的恢复会关掉另一台的告警）")
    void fingerprintSeparatesHosts() {
        String other = PROBLEM_JSON.replace("primera01", "primera02");
        assertThat(normalize(other).fingerprint()).isNotEqualTo(normalize(PROBLEM_JSON).fingerprint());
    }

    @Test
    @DisplayName("PROBLEM 17:37:55 → OK 17:38:47 的间隔 = 52 秒（现场报的「持续 54s」量级）")
    void problemToOkSpanIsExposedByTimestamps() {
        Duration span = Duration.between(normalize(PROBLEM_JSON).startsAt(), normalize(OK_JSON).endsAt());
        assertThat(span.getSeconds()).isEqualTo(52);
    }

    // ==================================================================
    // 级别翻译
    // ==================================================================

    @Test
    @DisplayName("六级严重度 → P0~P4：英文/数字/中文三种形态都认")
    void severityMappingTable() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("Disaster", "P0"), Map.entry("5", "P0"), Map.entry("灾难", "P0"),
                Map.entry("High", "P1"), Map.entry("4", "P1"), Map.entry("严重", "P1"),
                Map.entry("Average", "P2"), Map.entry("3", "P2"), Map.entry("一般严重", "P2"),
                Map.entry("Warning", "P2"), Map.entry("2", "P2"), Map.entry("警告", "P2"),
                Map.entry("Information", "P4"), Map.entry("1", "P4"), Map.entry("信息", "P4"),
                Map.entry("Not classified", "P4"), Map.entry("0", "P4"));

        cases.forEach((zabbixWord, expected) ->
                assertThat(normalize(severity(zabbixWord)).severity())
                        .as("Zabbix 级别「%s」", zabbixWord)
                        .isEqualTo(expected));
    }

    @Test
    @DisplayName("级别大小写与下划线变体同样认（DISASTER / not_classified）")
    void severityMappingIsCaseInsensitive() {
        assertThat(normalize(severity("DISASTER")).severity()).isEqualTo("P0");
        assertThat(normalize(severity("warning")).severity()).isEqualTo("P2");
        assertThat(normalize(severity("not_classified")).severity()).isEqualTo("P4");
    }

    @Test
    @DisplayName("认不出的级别原样透传 —— 不让适配器猜（猜 P0 制造误报、猜 P4 漏真故障）")
    void unknownSeverityPassesThrough() {
        AlertSignal s = normalize(severity("Weird"));

        assertThat(s.severity()).isEqualTo("Weird");
        // 原始词始终可追溯
        assertThat(s.labels()).containsEntry("event_severity", "Weird");
    }

    @Test
    @DisplayName("无严重度字段 → null，交由核心链回落默认（不在这里拍一个值）")
    void missingSeverityIsNull() {
        // Zabbix 宏取不到值时送空串而非不发键，这里按真实形态模拟
        assertThat(normalize(severity("")).severity()).isNull();
    }

    // ==================================================================
    // 标签解析（三种真实形态）
    // ==================================================================

    @Test
    @DisplayName("event_tags 为 {EVENT.TAGSJSON} 字符串形态 → service/module 升为一等字段，其余进 labels")
    void tagsJsonStringFormFeedsFieldsAndLabels() {
        AlertSignal s = normalize(ARRAY_TAGS_JSON);

        assertThat(s.service()).isEqualTo("primera-portal");
        assertThat(s.module()).isEqualTo("DB");
        assertThat(s.labels()).containsEntry("env", "prod").doesNotContainKeys("service", "module");
    }

    @Test
    @DisplayName("event_tags 直接是对象数组（参数被声明成数组）→ 同样解析")
    void tagsArrayFormIsParsed() {
        Map<String, String> tags = adapter.parseTags(
                "[{\"tag\":\"service\",\"value\":\"primera-portal\"},{\"tag\":\"env\",\"value\":\"prod\"}]");

        assertThat(tags).containsEntry("service", "primera-portal").containsEntry("env", "prod");
    }

    @Test
    @DisplayName("event_tags 为 {EVENT.TAGS} 逗号形态（a:b,c:d）同样解析")
    void tagsCommaFormIsParsed() {
        String json = PROBLEM_JSON.replace(
                "\"event_tags\": \"[{\\\"tag\\\":\\\"service\\\",\\\"value\\\":\\\"primera-portal\\\"},{\\\"tag\\\":\\\"module\\\",\\\"value\\\":\\\"db\\\"}]\"",
                "\"event_tags\": \"service:primera-portal,module:db,env:prod\"");

        AlertSignal s = normalize(json);

        assertThat(s.service()).isEqualTo("primera-portal");
        assertThat(s.module()).isEqualTo("DB");
        assertThat(s.labels()).containsEntry("env", "prod");
    }

    @Test
    @DisplayName("host_tags 与 event_tags 合并：事件级覆盖同键，主机级独有键保留")
    void hostTagsMergedBeforeEventTags() {
        AlertSignal s = normalize(HOST_TAGS_JSON);

        // 事件级 env=prod 覆盖主机级 env=staging（顺序反了会拿到陈旧值，且不报错）
        assertThat(s.labels()).containsEntry("env", "prod");
        // 主机级独有键照常保留
        assertThat(s.labels()).containsEntry("rack", "r12");
    }

    @Test
    @DisplayName("字典键标签 → 注解（喂给通知卡片），且不进 labels（去重键必须稳定）")
    void dictionaryKeysGoToAnnotationsNotLabels() {
        AlertSignal s = normalize(DICT_TAGS_JSON);

        // 通知卡片里「影响 / 首步动作 / 看板链接」这些行因此真的有内容
        assertThat(s.annotations())
                .containsEntry(AlertAnnotationKeys.IMPACT, "volumes 采集全链路不可见")
                .containsEntry(AlertAnnotationKeys.FIRST_ACTION, "1) 查 primera01 到存储的网络 2) 看 collector 日志")
                .containsEntry(AlertAnnotationKeys.RUNBOOK_URL, "https://wiki.example.com/runbook/node-down");
        // 会随事件变化的文案绝不进 labels：进了就会让同一条问题每次推送都换去重键
        assertThat(s.labels()).doesNotContainKeys("impact", "first_action", "runbook_url");
        // 非字典键的自定义标签照旧进 labels
        assertThat(s.labels()).containsEntry("env", "prod");
    }

    @Test
    @DisplayName("撞名保留字的事件标签不覆盖一等字段（拒让而非报错）")
    void reservedTagDoesNotHijackFields() {
        String json = PROBLEM_JSON.replace(
                "{\\\"tag\\\":\\\"module\\\",\\\"value\\\":\\\"db\\\"}",
                "{\\\"tag\\\":\\\"module\\\",\\\"value\\\":\\\"db\\\"},{\\\"tag\\\":\\\"service\\\",\\\"value\\\":\\\"evil-svc\\\"},{\\\"tag\\\":\\\"host\\\",\\\"value\\\":\\\"evil-host\\\"}");

        AlertSignal s = normalize(json);

        // host 用真实主机名，不是标签里的伪造值——去重维度不能被负载篡改
        assertThat(s.labels()).containsEntry("host", "primera01");
        assertThat(s.service()).isEqualTo("primera-portal");
    }

    @Test
    @DisplayName("标签形态无法识别时不抛异常（告警本体比标签重要）")
    void unparsableTagsDoNotBreakNormalization() {
        String json = PROBLEM_JSON.replace(
                "\"event_tags\": \"[{\\\"tag\\\":\\\"service\\\",\\\"value\\\":\\\"primera-portal\\\"},{\\\"tag\\\":\\\"module\\\",\\\"value\\\":\\\"db\\\"}]\"",
                "\"event_tags\": \"[{weird}]\"");
        Map<String, String> tags = adapter.parseTags("[{weird}]");

        assertThat(tags).isEmpty();
        // 降级行为：service 失去标签来源后回退到主机名，告警本体照常成立
        assertThat(normalize(json).service()).isEqualTo("primera01");
    }

    /** 社区脚本常见写法：把参数包一层 params 再发 */
    static final String NESTED_PARAMS_JSON = """
            {"params": {
              "trigger_id": "9001",
              "trigger_name": "DB Connection Failed",
              "host_name": "db-01",
              "host_ip": "10.0.0.9",
              "event_severity": "High",
              "event_status": "PROBLEM",
              "event_date": "2026.10.14",
              "event_time": "09:15:00"
            }}
            """;

    @Test
    @DisplayName("包一层 params 的社区脚本写法：摊平后字段照常可见")
    void nestedParamsWrapperIsFlattened() {
        AlertSignal s = normalize(NESTED_PARAMS_JSON);

        assertThat(s.alertName()).isEqualTo("DB Connection Failed");
        assertThat(s.severity()).isEqualTo("P1");
        assertThat(s.labels()).containsEntry("host", "db-01");
    }

    // ==================================================================
    // 时间解析
    // ==================================================================

    @Test
    @DisplayName("时间缺秒（H:mm）照样解析；带偏移量的 ISO 形态直接采用")
    void timeFormatsCovered() {
        assertThat(normalize(PROBLEM_JSON.replace("\"17:37:55\"", "\"17:37\"")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-13T17:37:00+08:00"));

        // 带偏移量的 ISO 形态：日期字段整体就是 ISO 串，事件时间不做任何推断
        assertThat(normalize(PROBLEM_JSON.replace("\"2026.10.13\"",
                        "\"2026-10-13T17:37:55+08:00\""))
                .startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-13T17:37:55+08:00"));
    }

    @Test
    @DisplayName("日期是 Zabbix 默认的点分隔形态（2026.10.13）；只给日期按当天零点解释")
    void dottedDateIsTheDefaultShape() {
        // PROBLEM_JSON 用的就是点分隔——能解析出 +08:00 才说明「只认 ISO」那个坑没复发
        assertThat(normalize(PROBLEM_JSON).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-13T17:37:55+08:00"));

        // {EVENT.TIME} 缺失（「每日」型触发器）：按当天零点，而不是整天不解析
        assertThat(normalize(PROBLEM_JSON.replace("\"17:37:55\"", "")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-13T00:00:00+08:00"));
    }

    @Test
    @DisplayName("日期与时间被拼在一个字段里（点分隔日期 + T）：拆开解析，不整串放弃")
    void concatenatedDottedMomentIsSplit() {
        assertThat(normalize(PROBLEM_JSON.replace("\"2026.10.13\"", "\"2026.10.13T17:37:55\""))
                .startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-13T17:37:55+08:00"));
    }

    @Test
    @DisplayName("时间解析不出来 → null（交核心链标「估算」），绝不回退成 now()")
    void unparsableTimeYieldsNull() {
        AlertSignal s = normalize(PROBLEM_JSON.replace("\"2026.10.13\"", "\"not-a-date\""));

        assertThat(s.startsAt()).isNull();
        assertThat(s.resolved()).isFalse();
    }

    // ==================================================================
    // 入口判定
    // ==================================================================

    @Test
    @DisplayName("supports：有 Zabbix 身份键才认；Alertmanager 负载与空对象一律不认")
    void supportsByIdentityKeys() {
        assertThat(adapter.supports(parse(PROBLEM_JSON))).isTrue();
        assertThat(adapter.supports(parse("{\"host_name\":\"h1\"}"))).isTrue();
        assertThat(adapter.supports(parse("{}"))).isFalse();
        assertThat(adapter.supports(parse("{\"something\":\"else\"}"))).isFalse();
        // 第二源上线后最容易漏的一条：Alertmanager 负载不能被他源截胡
        assertThat(adapter.supports(new AlertmanagerWebhook())).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("不支持的负载 → 带来源名的拒绝（接入方看得懂「哪里错了」）")
    void unsupportedPayloadRejected() {
        assertThatThrownBy(() -> adapter.normalize(new AlertmanagerWebhook()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ZabbixSourceAdapter");
        assertThatThrownBy(() -> adapter.normalize(parse("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺 trigger_name/event_name → 拒绝，不留一条「无名告警」在库里")
    void missingNameRejected() {
        // 两个键都要抹掉：只抹一个的话适配器会回退到另一个，造不出「无名」场景
        String json = PROBLEM_JSON
                .replace("\"trigger_name\"", "\"trigger_name_unused\"")
                .replace("\"event_name\"", "\"event_name_unused\"");

        assertThatThrownBy(() -> adapter.normalize(parse(json)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trigger_name");
    }

    @Test
    @DisplayName("sourceName 报的是运维在 Zabbix 侧认识的词")
    void sourceNameIsStable() {
        assertThat(adapter.sourceName()).isEqualTo("zabbix");
    }

    /**
     * 由基础样本换掉级别取值（其余字段不变）。
     * <p>"Average" 在样本里只出现一次，直接替换即可——不要用「删掉整行」的写法：
     * 文本块里换了行、缩进就失配，replace 会静默不生效，测试却看着还在跑
     * （本轮实测踩到过：断言因此变成「什么都没测但恒过/恒败」）。</p>
     */
    private static String severity(String word) {
        return PROBLEM_JSON.replace("Average", word);
    }
}
