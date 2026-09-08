package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Transactional} 自调用的事务边界契约。
 *
 * <h3>Spring 事务的这个坑为什么值得写成测试</h3>
 * <p>
 * {@code @Transactional} 靠 AOP 代理生效。类内部直接调用自己的方法
 * （{@code foo()} 或 {@code this.foo()}）<b>不经过代理</b>，
 * 被调方法上的 {@code @Transactional} 完全不起作用。
 * </p>
 * <p>
 * 危害取决于外层方法有没有事务：
 * </p>
 * <ul>
 *   <li><b>外层有事务</b>（本项目当前的情况）：内层自调用共用外层事务，
 *       语义正确，任一步失败整体回滚；</li>
 *   <li><b>外层无事务</b>：内层的每一步各自独立提交。
 *       中途失败会留下<b>写了一半的数据</b>，而调用方收到异常，
 *       会以为整个操作都没发生——两边认知不一致，且没有任何报错。</li>
 * </ul>
 *
 * <h3>这条契约守的是一个「当前没有缺陷」的结论</h3>
 * <p>
 * 全仓 13 处自调用，外层<b>全部</b>都有 {@code @Transactional}。
 * {@code TicketService.acknowledgeTicket} 的注释还明确记录了这次修复的动机：
 * 「转派成功但状态变更失败时，工单会停在『负责人已改、状态仍是待处理』的
 * 半截状态」。
 * </p>
 * <p>
 * 正因为结论是「都对」，它才<b>脆弱</b>：有人把外层的
 * {@code @Transactional} 摘掉（比如觉得"这方法只是读一下"），
 * 事务边界就静默塌了，没有任何编译错误或运行时报错。
 * 本测试把这个约束钉死。
 * </p>
 *
 * <h3>不覆盖的部分</h3>
 * <p>
 * 若将来引入 {@code Propagation.REQUIRES_NEW}，自调用会让「独立事务」的
 * 意图彻底落空——那时共用外层事务反而是错的。项目当前无
 * {@code REQUIRES_NEW}，故加一条断言守住这个前提：一旦引入，
 * 本测试失败并提醒重新评估自调用清单。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("@Transactional 自调用事务边界契约")
class TransactionalSelfInvocationContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /** 带 {@code @Transactional} 的方法声明（允许中间夹着其它注解） */
    private static final Pattern TX_METHOD = Pattern.compile(
            "@Transactional[^\\n]*\\n(?:\\s*@\\w+[^\\n]*\\n)*\\s*(?:public|private|protected)\\s+"
                    + "[\\w<>,\\[\\]\\s.]+?\\s+(\\w+)\\s*\\(");

    /** 任意方法声明，用于切出方法区间 */
    private static final Pattern ANY_METHOD = Pattern.compile(
            "(?m)^\\s{4}(?:@\\w+[^\\n]*\\n\\s*)*(?:public|private|protected)\\s+"
                    + "[\\w<>,\\[\\]\\s.]+?\\s+(\\w+)\\s*\\(");

    @Test
    @DisplayName("每处自调用 @Transactional 方法的外层方法自身也有事务")
    void selfInvocationAlwaysWrappedByOuterTransaction() throws IOException {
        List<String> unwrapped = new ArrayList<>();
        int selfCalls = 0;

        for (Path f : javaFiles()) {
            String code = stripCommentsKeepLines(Files.readString(f, StandardCharsets.UTF_8));
            if (!code.contains("@Transactional")) {
                continue;
            }

            Set<String> txMethods = txMethodNames(code);
            if (txMethods.isEmpty()) {
                continue;
            }
            List<MethodRange> ranges = methodRanges(code);

            for (String name : txMethods) {
                for (int pos : selfCallPositions(code, name)) {
                    selfCalls++;
                    MethodRange owner = ownerOf(ranges, pos);
                    if (owner == null) {
                        // 定位失败要显式暴露，不能当成"通过"
                        unwrapped.add(rel(f) + " L" + lineOf(code, pos)
                                + " → 调用 " + name + "()，但无法定位所在方法（扫描器需修正）");
                    } else if (!owner.transactional) {
                        unwrapped.add(rel(f) + " L" + lineOf(code, pos)
                                + " → " + owner.name + "() 调用 " + name + "()，"
                                + "但 " + owner.name + "() 自身没有 @Transactional");
                    }
                }
            }
        }

        // 扫描器自检：正则与写法脱节时会「一处都不匹配」，主断言恒真
        assertThat(selfCalls)
                .as("应当扫到自调用。为 0 说明正则与代码写法脱节，此时主断言形同虚设")
                .isGreaterThanOrEqualTo(10);

        assertThat(unwrapped)
                .as("以下自调用的外层方法没有事务。@Transactional 靠 AOP 代理生效，"
                        + "类内自调用不经过代理、被调方法的事务注解完全失效。"
                        + "外层又无事务时，内层每一步各自独立提交，中途失败会留下写了一半的数据，"
                        + "而调用方收到异常会以为整个操作都没发生——没有任何报错。"
                        + "修法：给外层方法补 @Transactional，内层自调用即共用同一事务")
                .isEmpty();
    }

    @Test
    @DisplayName("尚未引入 REQUIRES_NEW —— 引入后自调用清单必须重新评估")
    void noRequiresNewPropagationYet() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : javaFiles()) {
            String code = stripCommentsKeepLines(Files.readString(f, StandardCharsets.UTF_8));
            if (code.contains("REQUIRES_NEW")) {
                found.add(rel(f));
            }
        }

        // 上一条断言的前提是「共用外层事务是对的」。REQUIRES_NEW 会推翻这个前提：
        // 它要的恰恰是独立事务，而自调用让这个意图彻底落空且不报错。
        // 这条断言不是禁止使用 REQUIRES_NEW，是要求引入时回来重新评估
        assertThat(found)
                .as("检测到 Propagation.REQUIRES_NEW。它要求独立事务，"
                        + "而类内自调用会让这个意图彻底落空（不经代理，传播行为完全不生效）。"
                        + "请逐一核对本类第一条测试列出的自调用清单，"
                        + "确认没有一处 REQUIRES_NEW 方法是被自调用的，然后更新本断言")
                .isEmpty();
    }

    // ==================== 扫描实现 ====================

    private record MethodRange(int start, int end, String name, boolean transactional) {
    }

    private static Set<String> txMethodNames(String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TX_METHOD.matcher(code);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * 找出对 {@code name} 的自调用位置（无接收者的调用）。
     *
     * <p>关键排除项：</p>
     * <ul>
     *   <li>前面紧跟 {@code .} 的是<b>他调</b>（{@code repo.update(...)}），
     *       不算自调用。不排除的话
     *       {@code tagRepository.deleteByTicketId} 会被同名的
     *       {@code deleteByTicketId} 撞上——本轮扫描器第一版正是这么误报的；</li>
     *   <li>方法<b>定义处</b>本身；</li>
     *   <li>{@code new Xxx(} 构造调用。</li>
     * </ul>
     */
    private static List<Integer> selfCallPositions(String code, String name) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w])(this\\.)?" + Pattern.quote(name) + "\\s*\\(")
                .matcher(code);
        while (m.find()) {
            int start = m.start();
            if (m.group(1) == null) {
                int j = start - 1;
                while (j >= 0 && Character.isWhitespace(code.charAt(j))) {
                    j--;
                }
                if (j >= 0 && code.charAt(j) == '.') {
                    continue;
                }
            }
            String pre = code.substring(Math.max(0, start - 100), start);
            if (pre.matches("(?s).*(public|private|protected)\\s+[\\w<>,\\[\\]\\s.]+$")) {
                continue;
            }
            if (pre.matches("(?s).*\\bnew\\s+$")) {
                continue;
            }
            out.add(start);
        }
        return out;
    }

    private static List<MethodRange> methodRanges(String code) {
        List<MethodRange> out = new ArrayList<>();
        Matcher m = ANY_METHOD.matcher(code);
        while (m.find()) {
            int paren = code.indexOf('(', m.end() - 1);
            int parenEnd = matchDelim(code, paren, '(', ')');
            if (parenEnd < 0) {
                continue;
            }
            int brace = code.indexOf('{', parenEnd);
            if (brace < 0) {
                continue;
            }
            int end = matchDelim(code, brace, '{', '}');
            if (end < 0) {
                continue;
            }
            // 往前看 260 字符找注解：足够覆盖 @Transactional + 若干注解，
            // 又不至于跨到上一个方法的注解上
            String head = code.substring(Math.max(0, m.start() - 260), m.end());
            out.add(new MethodRange(m.start(), end, m.group(1), head.contains("@Transactional")));
        }
        return out;
    }

    /** 取包含该位置的最内层方法（起点最靠后的那个） */
    private static MethodRange ownerOf(List<MethodRange> ranges, int pos) {
        MethodRange best = null;
        for (MethodRange r : ranges) {
            if (r.start <= pos && pos <= r.end && (best == null || r.start > best.start)) {
                best = r;
            }
        }
        return best;
    }

    private static int matchDelim(String s, int open, char l, char r) {
        if (open < 0) {
            return -1;
        }
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

    /**
     * 剥注释但<b>保留换行</b>。
     *
     * <p>直接删掉块注释会让行号与源文件错位，报错信息指向错误的行——
     * 本轮扫描器第一版就因此把「L539」指到了一个空行上，
     * 差点据此误判扫描结果不可信。</p>
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

    private static List<Path> javaFiles() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("源码目录 %s 不存在——目录被重构时本测试必须失败，而非静默跳过", MAIN)
                .isTrue();
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString();
    }
}
