package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态流转 UPDATE 的并发守卫契约。
 *
 * <h3>要防住什么</h3>
 * <p>
 * 把一行数据从一个状态改到另一个状态时，{@code WHERE} 里必须带上
 * <b>对 {@code status} 的条件</b>（前置状态守卫）。只按主键更新
 * （{@code WHERE id = ?}）在并发下会出两类问题：
 * </p>
 * <ol>
 *   <li><b>重复消费</b>：两个并发请求都读到「还是 PENDING」，
 *       双双执行状态流转，各自都认为自己成功了；</li>
 *   <li><b>状态覆盖</b>：后到的写入把前一次已经写好的状态抹掉。
 *       实际发生过——{@code KnowledgeTagRepository.delete} 走 merge 分支后
 *       无条件置 {@code DELETED}，把 merge 刚写的 {@code MERGED} 覆盖掉，
 *       而这两种状态语义不同（合并可追溯到目标标签，删除不可）。</li>
 * </ol>
 *
 * <h3>光有守卫还不够，本测试只是第一道</h3>
 * <p>
 * 守卫让并发写不会互相覆盖，但<b>调用方还必须检查返回行数</b>——
 * 0 行意味着「这次流转没发生」，吞掉它会让后续代码在错误的前提下继续跑。
 * 实际缺陷正是这个形态：{@code KnowledgeTagRepository.rename} 的第一条
 * UPDATE 有守卫但返回值被丢弃，于是竞态时第二条无守卫的 UPDATE 照样执行，
 * 把全库文档上的标签名改成了标签表里已不存在的值（按标签检索为空，
 * 且没有任何报错）。
 * </p>
 * <p>
 * 「返回行数有没有被检查」不适合用文本扫描判定（调用方可能隔着几层），
 * 那部分由 {@code KnowledgeTagConcurrencyTest} 这类针对性单测覆盖。
 * 本测试守住的是可以机械判定的那一半：<b>SQL 里有没有前置状态守卫</b>。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("状态流转并发守卫契约")
class StateTransitionGuardContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /** 形如 {@code UPDATE 表名 SET status = '常量'} 的状态流转语句 */
    private static final Pattern STATE_UPDATE = Pattern.compile(
            "UPDATE\\s+(\\w+)\\s+SET\\s+status\\s*=\\s*'(\\w+)'");

    /**
     * WHERE 子句里对 status 的任意条件都算合格守卫。
     *
     * <p>包含 {@code =} / {@code IN} / {@code <>} / {@code !=} 四种形态——
     * 项目里都用到了（{@code markExpired} 用 {@code status = 'PENDING'}，
     * 告警侧用 {@code status IN (...)}）。
     * <b>只认 {@code AND status} 会把 {@code WHERE status = 'PENDING'} 判成无守卫</b>，
     * 这正是本轮扫描器第一版踩的坑。</p>
     */
    private static final Pattern STATUS_GUARD = Pattern.compile(
            "\\bstatus\\s*(=|IN\\b|<>|!=)");

    @Test
    @DisplayName("每条状态流转 UPDATE 都带前置状态守卫")
    void everyStateTransitionHasGuard() throws IOException {
        List<String> unguarded = new ArrayList<>();
        int scanned = 0;

        for (Path f : javaFiles()) {
            // 剥注释：javadoc 里会引用 SQL 片段说明设计，
            // 不剥会把说明文字当成真实语句扫进来
            String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));

            Matcher m = STATE_UPDATE.matcher(code);
            while (m.find()) {
                scanned++;
                String where = whereClauseAfter(code, m.end());
                if (!STATUS_GUARD.matcher(where).find()) {
                    unguarded.add(rel(f) + " → " + m.group(1) + ".status='" + m.group(2)
                            + "'  WHERE: " + brief(where));
                }
            }
        }

        // 扫描器自检：正则与 SQL 写法脱节时会「一条都不匹配」，
        // 此时主断言恒真、形同虚设（84 号静默 catch 扫描器的教训）
        assertThat(scanned)
                .as("应当扫到状态流转语句。为 0 说明 STATE_UPDATE 正则与代码写法脱节，"
                        + "此时主断言恒真")
                .isGreaterThanOrEqualTo(6);

        assertThat(unguarded)
                .as("以下状态流转只按主键更新，没有前置状态守卫。"
                        + "并发下会重复消费（两个请求都认为自己成功）"
                        + "或状态覆盖（后到者抹掉前一次写好的状态，语义丢失）。"
                        + "请在 WHERE 里加上对 status 的条件，"
                        + "并让调用方检查返回行数——0 行意味着流转没发生")
                .isEmpty();
    }

    @Test
    @DisplayName("本轮修复的两处标签状态流转不回退")
    void knowledgeTagTransitionsStayGuarded() throws IOException {
        Path tagRepo = MAIN.resolve("infrastructure/persistence/repo/KnowledgeTagRepository.java");
        String code = flattenJavaStrings(
                stripComments(Files.readString(tagRepo, StandardCharsets.UTF_8)));

        // 置 MERGED / 置 DELETED 各一处，都必须带 ACTIVE 守卫。
        // 用整句匹配而非分别找关键词：后者会被
        // 「同文件里另一条含 status 的语句」误判为通过
        assertThat(code)
                .as("merge 的状态闸门必须带 ACTIVE 守卫，否则两个并发 merge 会双双成功，"
                        + "文档上的标签被改写两次")
                .contains("SET status = 'MERGED', update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'ACTIVE'");
        assertThat(code)
                .as("delete 的状态流转必须带 ACTIVE 守卫")
                .contains("SET status = 'DELETED', update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'ACTIVE'");
    }

    // ==================== 扫描实现 ====================

    private static List<Path> javaFiles() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("源码目录 %s 不存在——目录被重构时本测试必须失败，而非静默跳过", MAIN)
                .isTrue();
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * 取 {@code UPDATE ... SET} 之后那段 SQL 里的 WHERE 子句。
     *
     * <p>以文本块结束符或字符串结束为界，最多向后看 500 字符——
     * 状态流转语句都很短，放宽窗口反而会跨到<b>下一条语句</b>的 WHERE 上
     * （85 号踩过的「匹配范围过宽命中别处同名内容」）。</p>
     */
    private static String whereClauseAfter(String code, int from) {
        int end = Math.min(code.length(), from + 500);
        String seg = code.substring(from, end);
        // 文本块的结束符先到就在那儿截断，避免跨语句
        int block = seg.indexOf("\"\"\"");
        if (block >= 0) {
            seg = seg.substring(0, block);
        }
        int where = seg.toUpperCase().indexOf("WHERE");
        return where < 0 ? "" : seg.substring(where);
    }

    /**
     * 消除 Java 源码里的字符串拼接，让跨行 SQL 还原成连续文本。
     *
     * <p>长 SQL 常写成 {@code "UPDATE ... SET ... " + "WHERE ..."}，
     * 源码里这中间夹着 {@code " + "} 与换行缩进。直接按整句 {@code contains}
     * 匹配会落空——<b>本测试第一版就栽在这里，CI 报了一次红</b>。
     * 值得记：这是「断言写错」而非「产品代码有问题」，
     * 而两者在 CI 上的表现完全一样，只能靠读失败详情区分。</p>
     */
    private static String flattenJavaStrings(String src) {
        // 把 " + "（含两侧任意空白与换行）折叠掉，使相邻字面量首尾相接
        return src.replaceAll("\"\\s*\\+\\s*\"", "");
    }

    private static String stripComments(String src) {
        // 先块注释再行注释：反过来会破坏块注释的配对
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString();
    }

    private static String brief(String where) {
        String one = where.replaceAll("\\s+", " ").trim();
        return one.length() > 90 ? one.substring(0, 90) + "…" : one;
    }
}
