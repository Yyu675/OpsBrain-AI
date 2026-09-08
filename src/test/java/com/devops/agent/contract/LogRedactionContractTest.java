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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志脱敏契约。
 *
 * <h3>为什么要有它</h3>
 * <p>
 * 83 号修过一处「种子密码明文进日志」。那是<b>逐个发现</b>的——
 * 有人恰好读到那行代码。日志泄漏的特点是：不报错、不影响功能，
 * 只在某天有人翻日志（或日志被采集到外部系统）时才暴露，
 * 而那时凭证已经躺在里面很久了。
 * </p>
 * <p>
 * 本测试把「日志语句里不得出现敏感字段」变成每次 CI 都跑的检查。
 * </p>
 *
 * <h3>豁免必须精确到位置，且写明理由</h3>
 * <p>
 * 有些日志<b>提到</b>敏感字段名但并不泄漏值——比如「未配置
 * {@code webhook.secret}」这种配置缺失告警，或「加签=是/否」这种
 * 只输出布尔判断的。这类必须豁免，否则测试会逼着开发把有用的告警删掉。
 * </p>
 * <p>
 * 但豁免<b>不能按文件</b>放行：那样同一个文件里日后新增的真实泄漏
 * 会被一起放过。这里按「文件 + 该文件允许的处数」登记，
 * 处数对不上就失败——新增一处就得回来说明它为什么安全。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("日志脱敏契约")
class LogRedactionContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /** 日志调用起点 */
    private static final Pattern LOG_CALL = Pattern.compile(
            "log\\.(trace|debug|info|warn|error)\\s*\\(");

    /**
     * 敏感字段名。
     *
     * <p>用 {@code \b} 边界而非子串匹配：否则 {@code tokenCount}、
     * {@code totalTokens} 这类计数字段会被大量误报，
     * 而本项目的 AI 调用日志里到处都是 token 计数。</p>
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(password|passwd|secret|credential|apikey|api_key|privatekey|accesskey)\\b");

    /**
     * 已核对为安全的日志位置：{@code 文件相对路径 → 允许处数}。
     *
     * <p>每一条都写明了为什么安全。处数变化时测试失败，
     * 逼使新增者回到这里说明理由——而不是把整个文件加进白名单。</p>
     */
    private static final Map<String, Integer> REVIEWED_SAFE = new LinkedHashMap<>();

    static {
        // 只提示「未配置 devops.alert.webhook.secret」，不输出该配置的值。
        // 这条告警本身很重要：生产环境漏配会让告警端点无鉴权
        REVIEWED_SAFE.put("common/web/WebhookGuard.java", 1);
        // 输出的是「加签=是/否」这个布尔判断结果，不是 secret 本身
        REVIEWED_SAFE.put("domain/notify/DingTalkNotifier.java", 1);
    }

    @Test
    @DisplayName("日志语句不得输出敏感字段，未登记的一律失败")
    void noSensitiveFieldsInLogs() throws IOException {
        List<String> unreviewed = new ArrayList<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        int totalLogs = 0;

        for (Path f : javaFiles()) {
            String code = stripCommentsKeepLines(
                    Files.readString(f, StandardCharsets.UTF_8));
            String relative = rel(f).replace('\\', '/');

            Matcher m = LOG_CALL.matcher(code);
            while (m.find()) {
                totalLogs++;
                int close = matchParen(code, m.end() - 1);
                if (close < 0) {
                    continue;
                }
                String args = code.substring(m.end(), close);
                if (!SENSITIVE.matcher(args).find()) {
                    continue;
                }
                actual.merge(relative, 1, Integer::sum);
                if (!REVIEWED_SAFE.containsKey(relative)) {
                    unreviewed.add(relative + " L" + lineOf(code, m.start())
                            + " → " + oneLine(args));
                }
            }
        }

        // 扫描器自检：正则与写法脱节时会「一条都不匹配」，主断言恒真。
        // 574 条日志是实测值，取 400 留出删改余量
        assertThat(totalLogs)
                .as("应当扫到大量日志语句。数量过少说明 LOG_CALL 正则与代码写法脱节，"
                        + "此时主断言形同虚设")
                .isGreaterThan(400);

        assertThat(unreviewed)
                .as("以下日志语句里出现了敏感字段名，且未经核对。"
                        + "日志泄漏不报错、不影响功能，只在某天有人翻日志"
                        + "（或日志被采集到外部系统）时才暴露，而那时凭证已经躺在里面很久了。"
                        + "若确实不泄漏值（如只提示「未配置 xxx.secret」、只输出「已配置=是/否」），"
                        + "请登记到 REVIEWED_SAFE 并写明理由")
                .isEmpty();

        // 处数核对：已登记文件里新增的命中同样要失败。
        // 只按文件名放行的话，同一文件日后加进来的真实泄漏会被一起放过
        List<String> countMismatch = new ArrayList<>();
        for (Map.Entry<String, Integer> e : REVIEWED_SAFE.entrySet()) {
            int now = actual.getOrDefault(e.getKey(), 0);
            if (now != e.getValue()) {
                countMismatch.add(e.getKey() + " 登记 " + e.getValue()
                        + " 处，实际 " + now + " 处");
            }
        }
        assertThat(countMismatch)
                .as("已登记文件的命中处数变了。增加说明新写了一处含敏感字段的日志，"
                        + "需逐个核对后更新登记；减少说明原来那处已改掉，"
                        + "请下调登记数，避免留下一条永远为真的豁免")
                .isEmpty();
    }

    @Test
    @DisplayName("认证相关代码不得记录密码字段的值")
    void authCodeNeverLogsPasswordValue() throws IOException {
        // 83 号修过的那处就在认证链路上（种子密码明文进日志）。
        // 这条断言比上面更严：认证相关文件里，日志参数中出现
        // getPassword() / setPassword() 这类取值调用一律禁止 ——
        // 提到字段名可以，取出值来就不行
        Pattern passwordValue = Pattern.compile(
                "(?i)(get|set)Password\\s*\\(|\\bpassword\\s*\\)|\\bpassword\\s*,");

        List<String> offenders = new ArrayList<>();
        for (Path f : javaFiles()) {
            String relative = rel(f).replace('\\', '/');
            if (!relative.contains("auth") && !relative.contains("Auth")) {
                continue;
            }
            String code = stripCommentsKeepLines(
                    Files.readString(f, StandardCharsets.UTF_8));

            Matcher m = LOG_CALL.matcher(code);
            while (m.find()) {
                int close = matchParen(code, m.end() - 1);
                if (close < 0) {
                    continue;
                }
                String args = code.substring(m.end(), close);
                if (passwordValue.matcher(args).find()) {
                    offenders.add(relative + " L" + lineOf(code, m.start())
                            + " → " + oneLine(args));
                }
            }
        }

        assertThat(offenders)
                .as("认证相关代码把密码值传进了日志。83 号修过同型缺陷"
                        + "（种子密码明文进日志）——凭证一旦落盘，"
                        + "日志轮转、采集、备份会把它扩散到更多地方，事后无法收回")
                .isEmpty();
    }

    // ==================== 辅助 ====================

    private static List<Path> javaFiles() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("源码目录 %s 不存在——目录被重构时本测试必须失败，而非静默跳过", MAIN)
                .isTrue();
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 剥注释但保留换行，使报出的行号与源文件一致。
     *
     * <p>93 号栽在「直接删块注释导致行号错位、报出的位置指到空行」上，
     * 当时差点据此误判整个扫描结果不可信。</p>
     */
    private static String stripCommentsKeepLines(String src) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?s)/\\*.*?\\*/").matcher(src);
        int last = 0;
        while (m.find()) {
            sb.append(src, last, m.start());
            sb.append("\n".repeat((int) m.group().chars().filter(c -> c == '\n').count()));
            last = m.end();
        }
        sb.append(src.substring(last));
        return sb.toString().replaceAll("(?m)//[^\\n]*", "");
    }

    private static int lineOf(String code, int pos) {
        return (int) code.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String oneLine(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 95 ? one.substring(0, 95) + "…" : one;
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString();
    }
}
