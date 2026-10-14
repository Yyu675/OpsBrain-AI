package com.devops.agent.domain.alert.DTO;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zabbix Webhook 负载（媒体类型脚本 POST 的 JSON，L2 多信号源接入第二源）。
 *
 * <h3>为什么不写成一个字段对齐的 POJO（关键取舍）</h3>
 * <p>
 * Alertmanager 的负载形状由上游项目规定，字段是稳定的，所以
 * {@link AlertmanagerWebhook} 是「一个字段一个属性」的强类型 DTO，读代码即知契约。
 * <b>Zabbix 不是这样</b>：媒体类型的 request body 由管理员在「管理 → 媒介类型 →
 * Webhook」里用参数列表自行拼装（官方脚本的 {@code params} 就是那一串
 * EVENT.ID / TRIGGER.NAME 宏），可以随版本与实施方改动：字段可能
 * 叫 {@code event_name} 也可能缺，数值可能是字符串也可能是数字，标签可能是数组
 * 也可能是 {@code {EVENT.TAGSJSON}} 那种字符串。写死字段的 POJO 会在别人改一个
 * 宏名字时直接反序列化失败——<b>失败方式还是 400，看起来像平台的问题</b>。
 * </p>
 * <p>
 * 所以本类只做一件事：把整份 JSON 原样收下（{@link JsonAnySetter}），取值交给
 * {@link #text(String)} 这类容错访问器。字段语义、缺省与回退属于
 * {@code ZabbixSourceAdapter}（「读懂一种源」的职责），不在 DTO 里藏业务判断。
 * </p>
 *
 * <h3>容错的边界</h3>
 * <ul>
 *   <li>数值/布尔 → 字符串（Zabbix 同一个宏在不同版本里编码不同）；</li>
 *   <li>数组 → 逗号拼接（多值宏）；</li>
 *   <li>外层套 {@code {"params": {...}}} 的写法在 {@link #flattened()} 里摊平——
 *       官方脚本直发扁平对象，但社区脚本常有这层包装；</li>
 *   <li>未知键一律保留：原始负载是事后复盘与接入排错的唯一现场，丢掉就没了。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-10-14
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZabbixWebhook {

    /** 外层包装键：社区脚本常见的 {"params": {...}} 写法（摊平时优先内层） */
    private static final String NESTED_PARAMS = "params";

    private final Map<String, Object> fields = new LinkedHashMap<>();

    /**
     * 由任意键值映射构造（HTTP 入口用）。
     * <p>控制器刻意按 {@code Map<String, Object>} 收 body 而不是直接收本类：
     * springdoc 对 {@code Map} 请求体渲染成内联对象（无新命名 schema），
     * 而本类没有可反射的属性，注册出的空 schema 反而会让「OpenAPI 快照新鲜度」
     * 门禁多出一条漂移项——为了一个纯转发用的 DTO 去动契约快照不值当。</p>
     */
    public static ZabbixWebhook of(Map<String, Object> raw) {
        ZabbixWebhook webhook = new ZabbixWebhook();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    webhook.fields.put(k, v);
                }
            });
        }
        return webhook;
    }

    /** Jackson 的兜底入口：任何键都收下，不做形状校验（见类注释）。 */
    @JsonAnySetter
    public void put(String key, Object value) {
        if (key != null) {
            fields.put(key, value);
        }
    }

    /** 原始字段快照（只读、保持到达顺序）——供适配器与排错使用。 */
    @JsonIgnore
    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** 没有任何字段：空负载。控制器据此按「空负载」处理（返回 200，避免上游重投）。 */
    @JsonIgnore
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * 取一个标量字段的文本值：null-safe、去空白、空串归一为 null。
     * <p>null 与空串对上层是同一件事——「这份负载没有这个字段」，
     * 让调用方少写一次判空（Zabbix 的宏在值为空时就是发空串，不是不发键）。</p>
     */
    public String text(String key) {
        Object v = fields.get(key);
        if (v == null) {
            return null;
        }
        String s = stringify(v);
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 是否存在非空白取值。 */
    @JsonIgnore
    public boolean has(String key) {
        return text(key) != null;
    }

    /**
     * 摊平后的字段视图（全部转成字符串）。
     * <p>内层 {@code params} 先铺底、外层覆盖——外层是「脚本最后拼装的结果」，
     * 与社区脚本 {@code params = {...}; request.setData(JSON.stringify(params))} 的
     * 语义一致。摊平后仍保持到达顺序，便于断言与日志肉眼比对。</p>
     */
    @JsonIgnore
    public Map<String, String> flattened() {
        Map<String, String> out = new LinkedHashMap<>();
        Object nested = fields.get(NESTED_PARAMS);
        if (nested instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), stringify(e.getValue()));
                }
            }
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (NESTED_PARAMS.equals(e.getKey()) && e.getValue() instanceof Map) {
                continue;
            }
            out.put(e.getKey(), stringify(e.getValue()));
        }
        return out;
    }

    /**
     * 把一层对象渲染成 JSON 形态（{@code {"tag":"a","value":"b"}}）。
     * <p>只为「事件标签」这类 tag/value 结构服务：不引 Jackson 是为了让本 DTO
     * 在纯单测与探针里能零依赖构造；值里的引号与反斜杠做最小转义，
     * 保证渲染结果仍是可被标签解析正则处理的文本。</p>
     */
    private static String mapText(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean firstEntry = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!firstEntry) {
                sb.append(",");
            }
            firstEntry = false;
            sb.append('"').append(escapeText(String.valueOf(e.getKey()))).append('"')
              .append(':')
              .append('"').append(escapeText(e.getValue() == null ? "" : String.valueOf(e.getValue())))
              .append('"');
        }
        return sb.append('}').toString();
    }

    /** JSON 字符串值里的最小转义：先把反斜杠翻倍，再把引号转义（顺序不能反）。 */
    private static String escapeText(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringify(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof List<?> list) {
            // 列表逐元素渲染后再拼接：元素本身可能是对象（Zabbix 参数被声明成数组时
            // 事件标签就是 [{"tag":"a","value":"b"}]），交给 mapText 渲染成 JSON 形态，
            // 适配器的标签解析就能用同一条路径吃下——否则会得到 "{tag=a, value=b}"
            // 这种谁都不认识的 Java map toString，标签静默全丢
            List<String> parts = new ArrayList<>(list.size());
            for (Object o : list) {
                parts.add(o == null ? "" : (o instanceof Map<?, ?> m ? mapText(m) : String.valueOf(o)));
            }
            // 补回外层方括号：列表值渲染成 JSON 数组文本，事件标签解析才有统一的
            // 「以 [ 开头」入口（少了这两个括号会掉进逗号分支，解析出垃圾键）
            return "[" + String.join(",", parts) + "]";
        }
        if (v instanceof Map<?, ?> m) {
            return mapText(m);
        }
        return String.valueOf(v);
    }
}
