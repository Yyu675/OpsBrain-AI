package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数钳制契约。
 *
 * <h3>要防住什么</h3>
 * <p>
 * 每个接收 {@code page/size/limit/offset} 的 REST 入口，都必须在某一层
 * 把它夹进合理区间。没夹住的后果分两类：
 * </p>
 * <ul>
 *   <li><b>上界失守</b>：{@code size=100000} 一次拉爆内存与数据库；
 *       若该参数还驱动着昂贵的下游动作（如 {@code /reindex/pending}
 *       的每一条都触发一次远程 embedding 调用），后果直接是账单事故；</li>
 *   <li><b>下界失守</b>：{@code page=0} 让 {@code OFFSET} 变负，
 *       PostgreSQL 直接抛 SQL 异常 → 用户看到 500 而非「参数不合法」；
 *       {@code limit=0} 更糟，它<b>静默返回空</b>，调用方以为「没有数据」，
 *       而真相是参数写错了。</li>
 * </ul>
 *
 * <h3>为什么用扫描而不是逐个写单测</h3>
 * <p>
 * 全仓 25 个分页入口，逐个写单测既冗长又挡不住<b>新增</b>端点。
 * 这里扫的是「有没有钳制」这件事本身——新加一个不钳制的分页入口，
 * 本测试立刻失败并指名道姓。真实缺陷正是这么找到的：
 * 25 处里 24 处都钳制了，唯独 {@code retryFailedIndexing} 一路裸传。
 * </p>
 *
 * <h3>钳制可以发生在两层</h3>
 * <p>
 * 项目里两种写法都有，都算合格：
 * </p>
 * <ol>
 *   <li><b>Controller 内联钳制</b>——如 {@code AlertController}
 *       的 {@code Math.min(Math.max(1, size), 200)}；</li>
 *   <li><b>下游 service/repository 钳制</b>——如 {@code AuditLogController}
 *       透传，由 {@code AuditLogQueryRepository.clampSize} 兜住。</li>
 * </ol>
 * <p>
 * 故扫描器对「Controller 里没找到钳制」的入口，不直接判失败，
 * 而是要求它出现在 {@link #DOWNSTREAM_CLAMPED} 白名单里，
 * <b>并且反查白名单指向的那个文件确实还留着钳制代码</b>——
 * 只列白名单不反查，等于给「以后把下游钳制删掉」开了后门。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("分页参数钳制契约")
class PaginationClampContractTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/devops/agent/controller");

    /** 分页参数名。{@code offset} 一并纳入——它同样能变负 */
    private static final Pattern PAGING_PARAM = Pattern.compile(
            "@RequestParam\\(defaultValue = \"\\d+\"\\)\\s+int\\s+(page|size|pageNum|pageSize|limit|offset)\\b");

    /**
     * 钳制的写法特征。
     *
     * <p>只认 {@code Math.max} / {@code Math.min} 与显式的 {@code safeXxx} 命名——
     * 这是项目里既有的统一写法。若将来引入别的钳制方式，
     * 应当在这里补上，而不是把断言放宽。</p>
     */
    private static final Pattern CLAMP_HINT = Pattern.compile(
            "Math\\.(max|min)\\s*\\(|\\bsafe(Page|Size|Limit|Offset)\\b|\\bclamp");

    /**
     * 已知在下游钳制的入口：{@code 控制器类名#方法名} → {@code 兜住它的文件:关键词}。
     *
     * <p>每一条都必须能反查到——见 {@link #downstreamClampsStillExist()}。
     * 这样「以后有人把下游钳制删了」会被本测试当场抓住，
     * 而不是留下一条永远为真的豁免。</p>
     */
    private static final Map<String, String> DOWNSTREAM_CLAMPED = new LinkedHashMap<>();

    static {
        DOWNSTREAM_CLAMPED.put("ApprovalController#list",
                "src/main/java/com/devops/agent/domain/approval/ApprovalService.java:Math.min(Math.max(1, size), 200)");
        DOWNSTREAM_CLAMPED.put("AuditLogController#listOperations",
                "src/main/java/com/devops/agent/infrastructure/persistence/repo/AuditLogQueryRepository.java:clampSize");
        DOWNSTREAM_CLAMPED.put("AuditLogController#listAiCalls",
                "src/main/java/com/devops/agent/infrastructure/persistence/repo/AuditLogQueryRepository.java:clampSize");
        DOWNSTREAM_CLAMPED.put("AutomationGovernanceController#listActions",
                "src/main/java/com/devops/agent/domain/governance/ActionAllowlistRepository.java:int safePage");
        DOWNSTREAM_CLAMPED.put("AutomationGovernanceController#listPolicies",
                "src/main/java/com/devops/agent/domain/governance/AutomationPolicyRepository.java:int safePage");
        DOWNSTREAM_CLAMPED.put("KnowledgeDocController#retryIndexing",
                "src/main/java/com/devops/agent/domain/rag/KnowledgeDocService.java:MAX_REINDEX_BATCH");
        DOWNSTREAM_CLAMPED.put("TicketController#hotTags",
                "src/main/java/com/devops/agent/domain/biz/service/TicketService.java:Math.min(Math.max(1, limit), 100)");
    }

    @Test
    @DisplayName("每个分页入口都在 Controller 或下游被钳制，没有裸传的")
    void everyPagingEndpointIsClamped() throws IOException {
        List<String> unclamped = new ArrayList<>();
        int scanned = 0;

        for (Path f : controllerFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String cls = f.getFileName().toString().replace(".java", "");

            for (Method m : methodsWithPagingParams(src)) {
                scanned++;
                if (CLAMP_HINT.matcher(m.body).find()) {
                    continue;
                }
                String key = cls + "#" + m.name;
                if (!DOWNSTREAM_CLAMPED.containsKey(key)) {
                    unclamped.add(key + "（参数 " + m.params + "）");
                }
            }
        }

        // 先确认扫描器真的扫到了东西。
        // 正则写错时会「一条都不匹配」，此时上面的断言恒真——
        // 「全中」或「全不中」时先怀疑扫描器（84 号静默 catch 扫描器的教训）
        assertThat(scanned)
                .as("扫描器应当找到全部分页入口方法。若为 0，说明 PAGING_PARAM 正则"
                        + "与代码写法脱节（如注解格式变了），此时主断言恒真、形同虚设")
                .isGreaterThanOrEqualTo(15);

        assertThat(unclamped)
                .as("以下分页入口既没在 Controller 钳制，也不在下游钳制白名单里。"
                        + "上界失守会一次拉爆内存/数据库（若参数驱动昂贵下游动作则是账单事故）；"
                        + "下界失守会让 OFFSET 变负抛 SQL 异常，或 limit=0 静默返回空。"
                        + "请就近钳制（参考 AlertController 的 "
                        + "Math.min(Math.max(1, size), 200)），"
                        + "或在下游钳制后登记到 DOWNSTREAM_CLAMPED")
                .isEmpty();
    }

    @Test
    @DisplayName("下游钳制白名单的每一条都能反查到，没有过期豁免")
    void downstreamClampsStillExist() throws IOException {
        List<String> stale = new ArrayList<>();

        for (Map.Entry<String, String> e : DOWNSTREAM_CLAMPED.entrySet()) {
            String spec = e.getValue();
            int sep = spec.indexOf(".java:");
            Path file = Path.of(spec.substring(0, sep + ".java".length()));
            String keyword = spec.substring(sep + ".java:".length());

            if (!Files.exists(file)) {
                stale.add(e.getKey() + " → 文件不存在: " + file);
                continue;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (!body.contains(keyword)) {
                stale.add(e.getKey() + " → 钳制代码已消失: " + file + " 中找不到 '" + keyword + "'");
            }
        }

        assertThat(stale)
                .as("白名单是「这个入口的钳制在下游」的承诺。承诺失效时必须立刻失败，"
                        + "否则会留下一条永远为真的豁免，掩盖真实的裸传")
                .isEmpty();
    }

    @Test
    @DisplayName("白名单不含已在 Controller 内联钳制的入口，避免冗余豁免堆积")
    void whitelistHasNoRedundantEntries() throws IOException {
        List<String> redundant = new ArrayList<>();

        for (Path f : controllerFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String cls = f.getFileName().toString().replace(".java", "");
            for (Method m : methodsWithPagingParams(src)) {
                String key = cls + "#" + m.name;
                if (CLAMP_HINT.matcher(m.body).find() && DOWNSTREAM_CLAMPED.containsKey(key)) {
                    redundant.add(key);
                }
            }
        }

        // 冗余条目本身无害，但会让白名单逐渐失去「这些是特例」的信号价值。
        // 有人后来给 Controller 补了内联钳制，白名单里的旧条目就该删掉
        assertThat(redundant)
                .as("这些入口已在 Controller 内联钳制，不该再出现在下游白名单里")
                .isEmpty();
    }

    // ==================== 扫描实现 ====================

    private record Method(String name, String params, String body) {
    }

    private static List<Path> controllerFiles() throws IOException {
        assertThat(Files.isDirectory(CONTROLLERS))
                .as("控制器目录 %s 不存在——目录被重构时本测试必须失败，而非静默跳过", CONTROLLERS)
                .isTrue();
        try (Stream<Path> s = Files.walk(CONTROLLERS)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * 找出所有形参里带分页参数的方法，并截出各自的方法体。
     *
     * <p>方法体靠花括号配平截取，<b>从形参列表右括号之后的第一个
     * {@code &#123;} 开始计数</b>。这一点是关键：从方法签名行首开始配平，
     * 会被形参里的括号与注解干扰（静默 catch 扫描器就栽在
     * {@code &#125; catch (Exception e) &#123;} 净变化为 0 上）。</p>
     */
    private static List<Method> methodsWithPagingParams(String src) {
        List<Method> found = new ArrayList<>();
        // 定位 public 方法签名的起点，再往后找形参列表
        Matcher sig = Pattern.compile(
                        "(?m)^\\s{4}public\\s+[\\w<>,\\s\\[\\].?]+?\\s+(\\w+)\\s*\\(")
                .matcher(src);

        while (sig.find()) {
            String name = sig.group(1);
            int parenOpen = sig.end() - 1;
            int parenClose = matchParen(src, parenOpen);
            if (parenClose < 0) {
                continue;
            }
            String params = src.substring(parenOpen + 1, parenClose);

            Set<String> hit = new java.util.LinkedHashSet<>();
            Matcher pm = PAGING_PARAM.matcher(params);
            while (pm.find()) {
                hit.add(pm.group(1));
            }
            if (hit.isEmpty()) {
                continue;
            }

            int braceOpen = src.indexOf('{', parenClose);
            if (braceOpen < 0) {
                continue;
            }
            int braceClose = matchBrace(src, braceOpen);
            if (braceClose < 0) {
                continue;
            }
            found.add(new Method(name, String.join(",", hit),
                    src.substring(braceOpen, braceClose + 1)));
        }
        return found;
    }

    private static int matchParen(String s, int open) {
        return matchDelimiter(s, open, '(', ')');
    }

    private static int matchBrace(String s, int open) {
        return matchDelimiter(s, open, '{', '}');
    }

    private static int matchDelimiter(String s, int open, char l, char r) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == l) {
                depth++;
            } else if (c == r) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
