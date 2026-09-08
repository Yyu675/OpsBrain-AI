package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库写端点的权限覆盖契约（F-5）。
 *
 * <h3>为什么要扫描而不是逐个写用例</h3>
 * <p>
 * 缺陷的形态是「16 个写端点里 15 个漏了」——逐个写用例既冗长，
 * 又<b>挡不住新增端点</b>。而新增一个知识库写端点忘了加守卫，
 * 不会有任何编译或运行时信号：功能完全正常，只是任何人都能调。
 * </p>
 *
 * <h3>什么算「有守卫」</h3>
 * <p>项目里有两种合法写法，都认：</p>
 * <ul>
 *   <li>{@code writeGuard.requireEdit()} / {@code requireDestructive()}
 *       —— 分级守卫（F-5 引入）；</li>
 *   <li>{@code @SaCheckRole("ADMIN")} —— 早先 {@code purge} 用的写法，
 *       语义等价于 requireDestructive，保留不改。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@DisplayName("知识库写端点权限覆盖")
class KnowledgeWriteGuardCoverageTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/devops/agent/controller");

    /** 知识库相关控制器 → 该文件里应受守卫的写端点数 */
    private static final Map<String, Integer> KNOWLEDGE_CONTROLLERS = Map.of(
            "KnowledgeDocController.java", 7,
            "KnowledgeCategoryController.java", 4,
            "KnowledgeTagController.java", 4
    );

    /** 写操作注解 */
    private static final Pattern WRITE_MAPPING = Pattern.compile(
            "@(Post|Put|Delete|Patch)Mapping");

    /** 合格守卫的两种写法 */
    private static final Pattern GUARDED = Pattern.compile(
            "writeGuard\\.require(Edit|Destructive)\\s*\\(\\s*\\)|@(?:[\\w.]+\\.)?SaCheckRole");

    @Test
    @DisplayName("每个知识库写端点都有权限守卫")
    void everyKnowledgeWriteEndpointIsGuarded() throws IOException {
        List<String> unguarded = new ArrayList<>();
        int scanned = 0;

        for (Map.Entry<String, Integer> e : KNOWLEDGE_CONTROLLERS.entrySet()) {
            Path f = CONTROLLERS.resolve(e.getKey());
            assertThat(Files.exists(f))
                    .as("控制器 %s 不存在——文件被重命名时本测试必须失败，而非静默跳过", e.getKey())
                    .isTrue();

            String code = stripCommentsKeepLines(Files.readString(f, StandardCharsets.UTF_8));

            // 逐个写端点：从 @XxxMapping 开始，到方法体的第一个 '{' 之后一段范围内
            // 找守卫调用。范围取到下一个 @XxxMapping 或文件尾，避免跨端点误判
            List<Integer> starts = new ArrayList<>();
            Matcher m = WRITE_MAPPING.matcher(code);
            while (m.find()) {
                starts.add(m.start());
            }

            for (int i = 0; i < starts.size(); i++) {
                scanned++;
                int from = starts.get(i);
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : code.length();
                String block = code.substring(from, to);
                if (!GUARDED.matcher(block).find()) {
                    unguarded.add(e.getKey() + " L" + lineOf(code, from)
                            + " → " + firstMethodSignature(block));
                }
            }

            assertThat(starts.size())
                    .as("%s 的写端点数变了（登记 %d，实际 %d）。"
                            + "新增写端点必须同时加守卫并更新此处登记",
                            e.getKey(), e.getValue(), starts.size())
                    .isEqualTo(e.getValue());
        }

        // 扫描器自检：正则脱节时会「一个都不匹配」，主断言恒真
        assertThat(scanned)
                .as("应当扫到写端点。为 0 说明 WRITE_MAPPING 正则与代码写法脱节，"
                        + "此时主断言形同虚设")
                .isGreaterThanOrEqualTo(15);

        assertThat(unguarded)
                .as("以下知识库写端点没有权限守卫。知识库是 AI 回答的事实来源——"
                        + "任何登录用户可改，等于任何人都能污染 AI 输出，"
                        + "而被污染的答案还带着「引用出处」，使用者不会怀疑。"
                        + "请按操作的可逆性加 writeGuard.requireEdit()（可逆，ADMIN+OPS）"
                        + "或 writeGuard.requireDestructive()（不可逆，仅 ADMIN）")
                .isEmpty();
    }

    @Test
    @DisplayName("不可逆操作只允许 ADMIN —— 分级不得退化为一刀切")
    void destructiveOperationsRestrictedToAdmin() throws IOException {
        // 这些操作改不回来或影响面大，必须是 requireDestructive 而非 requireEdit。
        // 若有人图省事把它们降级成 requireEdit，OPS 就能删分类、合并标签、
        // 触发全量重建索引（会打满 embedding 配额）
        Map<String, List<String>> mustBeDestructive = Map.of(
                "KnowledgeDocController.java", List.of("deprecate", "retryIndexing"),
                "KnowledgeCategoryController.java", List.of("delete"),
                "KnowledgeTagController.java", List.of("merge", "delete")
        );

        List<String> downgraded = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : mustBeDestructive.entrySet()) {
            String code = stripCommentsKeepLines(
                    Files.readString(CONTROLLERS.resolve(e.getKey()), StandardCharsets.UTF_8));
            for (String method : e.getValue()) {
                Matcher m = Pattern.compile(
                        "public\\s+[\\w<>,\\s.]+\\s+" + Pattern.quote(method) + "\\s*\\(")
                        .matcher(code);
                if (!m.find()) {
                    downgraded.add(e.getKey() + " → 找不到方法 " + method + "（被重命名？）");
                    continue;
                }
                // 只取「当前方法体」，不能按固定字符数截窗口。
                //
                // 首版用 from + 600 字符，结果 merge() 的窗口跨到了下一个方法
                // delete() 的 requireDestructive() 上——把 merge 降级成 requireEdit
                // 的注入没被抓住，CI 照常绿。这正是 85 号记过的
                //「匹配范围过宽命中别处同名内容」。
                //
                // 改为从方法体的 '{' 起花括号配平，边界精确到方法本身。
                int from = m.start();
                int brace = code.indexOf('{', m.end() - 1);
                int end = matchBrace(code, brace);
                if (end < 0) {
                    downgraded.add(e.getKey() + "#" + method + " 方法体括号不配平（扫描器需修正）");
                    continue;
                }
                String block = code.substring(from, end + 1);
                boolean ok = block.contains("requireDestructive()")
                        || Pattern.compile("@(?:[\\w.]+\\.)?SaCheckRole").matcher(block).find();
                if (!ok) {
                    downgraded.add(e.getKey() + "#" + method
                            + " 未使用 requireDestructive/SaCheckRole");
                }
            }
        }

        assertThat(downgraded)
                .as("以下不可逆操作被降级成了普通编辑权限。"
                        + "删分类会影响其下所有文档，合并标签会批量改写文档关联，"
                        + "全量重建索引会打满 embedding 配额——这些都不该由 OPS 单独决定")
                .isEmpty();
    }

    // ==================== 辅助 ====================

    /**
     * 从 {@code open} 处的 '{' 起做花括号配平，返回配对的 '}' 下标。
     *
     * <p>用它划定方法体边界，而不是按固定字符数截窗口——
     * 后者会跨到下一个方法上，让「本方法漏了守卫」被邻居的守卫掩盖。</p>
     */
    private static int matchBrace(String s, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 取块内第一个方法签名，让报错能直接看出是哪个端点 */
    private static String firstMethodSignature(String block) {
        Matcher m = Pattern.compile("public\\s+[\\w<>,\\s.]+\\s+(\\w+)\\s*\\(").matcher(block);
        return m.find() ? m.group(1) + "()" : "(未识别)";
    }

    /**
     * 剥注释但保留换行，使报出的行号与源文件一致。
     *
     * <p>93 号栽在「直接删块注释导致行号错位、报出的位置指到空行」上。
     * 且本项目的 javadoc 里常引用 {@code @SaCheckRole} 说明设计，
     * 不剥注释会把说明文字当成真实守卫。</p>
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
}
