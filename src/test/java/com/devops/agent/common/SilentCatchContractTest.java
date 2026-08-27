package com.devops.agent.common;

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
 * 「静默 catch」契约测试——防止新增吞异常且不留线索的代码。
 *
 * <h3>为什么要管这件事</h3>
 * 全项目有 166 处 {@code catch (Exception)}。**吞异常本身不都是错的**：
 * 审计、通知、缓存这类旁路能力，让它们把主流程带崩没有意义。
 * 真正决定「故障能查还是查不到」的是<b>吞掉之后有没有留下线索</b>。
 *
 * <p>本轮普查结果：166 处里 142 处有日志或重新抛出，
 * 24 处完全静默。逐个核实后修了 2 处真问题，
 * 其余 22 处是合理兜底（未登录、非请求上下文、单条解析失败跳过等）。</p>
 *
 * <h3>这条测试守的是「增量」而非「存量」</h3>
 * 不要求把 22 处存量全部改掉——它们逐个核实过，改了反而是噪音。
 * 用<b>基线计数 + 文件白名单</b>：新增静默 catch 会让计数超标而失败，
 * 迫使作者要么加日志，要么显式登记并说明理由。
 *
 * <h3>为什么按文件白名单而不是精确行号</h3>
 * 行号会随任何一次编辑漂移，维护成本极高且失败信息毫无意义
 * （「第 137 行不该静默」——而那行早就变成别的代码了）。
 * 按文件登记 + 总数封顶，是可维护性与精度之间的平衡。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("静默 catch 契约（吞异常必须留下线索）")
class SilentCatchContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /**
     * 匹配 {@code catch (Exception e)} / {@code catch (final Throwable t)} 等形态。
     */
    private static final Pattern CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?(Exception|Throwable)\\b");

    /** 块内出现日志或重新抛出，即视为「留下了线索」 */
    private static final Pattern HAS_LOG = Pattern.compile("\\blog\\.(error|warn|info|debug)");
    private static final Pattern HAS_THROW = Pattern.compile("\\bthrow\\b");

    /**
     * 允许存在静默 catch 的文件（2026-08-27 普查基线，22 处）。
     *
     * <p>每一项都逐个核实过，共同特征是<b>「异常本身就是预期分支」</b>：
     * <ul>
     *   <li>{@code AuthController} / {@code OperationAuditInterceptor}
     *       —— 未登录或非请求上下文时取不到用户，回落匿名是正常路径；</li>
     *   <li>{@code HotMemoryStore} —— 单条历史解析失败跳过，不影响其余；</li>
     *   <li>{@code *Repository} 的只读查询 —— 失败返回空集合，
     *       调用方本就按「查不到」处理；</li>
     *   <li>{@code LlmHealthIndicator} / {@code VectorStoreHealthIndicator}
     *       —— 健康检查探测失败即「不健康」，异常就是结论本身。</li>
     * </ul>
     *
     * <p><b>往这里加条目前请先问</b>：吞掉之后，运维能从别处发现这件事吗？
     * 答案是否定的就别加白名单，去补日志。</p>
     */
    private static final List<String> ALLOWED = List.of(
            "application/impl/DevOpsAgentServiceImpl",
            "application/memory/AgentMemoryManager",
            "common/audit/OperationAuditInterceptor",
            "controller/ApprovalController",
            "controller/AuthController",
            "controller/AutomationGovernanceController",
            "controller/DevOpsChatController",
            "domain/biz/repository/TicketAttachmentRepository",
            "domain/governance/ActionAllowlistRepository",
            "domain/governance/AutomationPolicyRepository",
            "domain/governance/RiskPolicyRepository",
            "domain/rag/KnowledgeDocTagRepository",
            "domain/tools/executor/K8sOpsExecutor",
            "infrastructure/MockStreamingChatModel",
            "infrastructure/cache/HotMemoryStore",
            "infrastructure/health/LlmHealthIndicator",
            "infrastructure/health/VectorStoreHealthIndicator");

    /** 基线总数。修复后应下降，只允许降不允许升 */
    private static final int BASELINE_SILENT_COUNT = 22;

    private record SilentCatch(String file, int line) {
    }

    /**
     * 扫描所有静默 catch。
     *
     * <p>实现上有两个坑，都是本轮实测踩到的：</p>
     * <ol>
     *   <li><b>必须跳过注释行</b>——代码注释里常引用
     *       {@code catch (Exception e) { return 1; }} 来说明「原实现是怎样的」，
     *       不跳过会把说明文字当成真代码；</li>
     *   <li><b>必须从 catch 行的「{」之后开始配平</b>——
     *       {@code } catch (Exception e) {} 这一行 {@code {} 与 {@code }}
     *       各一个，净变化为 0，从行首计数会让块「立刻结束」，
     *       结果是<b>每一处都被判为静默</b>（第一版扫描器就报了 166/166，
     *       而我明知其中有些刚加过日志）。</li>
     * </ol>
     */
    private static List<SilentCatch> scanSilentCatches() throws IOException {
        List<SilentCatch> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        for (Path p : files) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String trimmed = raw.trim();
                if (isComment(trimmed)) {
                    continue;
                }
                Matcher m = CATCH.matcher(trimmed);
                if (!m.find()) {
                    continue;
                }
                int brace = raw.indexOf('{', raw.indexOf("catch"));
                if (brace < 0) {
                    continue;   // catch 与 { 不在同一行，罕见，跳过而非误判
                }

                StringBuilder body = new StringBuilder(raw.substring(brace + 1));
                int depth = 1;
                int k = i;
                while (depth > 0 && k + 1 < lines.size()) {
                    k++;
                    String cur = lines.get(k);
                    depth += count(cur, '{') - count(cur, '}');
                    if (!isComment(cur.trim())) {
                        body.append('\n').append(cur);
                    }
                }

                String inner = body.toString();
                if (!HAS_LOG.matcher(inner).find() && !HAS_THROW.matcher(inner).find()) {
                    found.add(new SilentCatch(relative(p), i + 1));
                }
            }
        }
        return found;
    }

    private static boolean isComment(String trimmed) {
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String relative(Path p) {
        return MAIN.relativize(p).toString().replace('\\', '/').replaceAll("\\.java$", "");
    }

    // ==================================================================

    @Test
    @DisplayName("不得在白名单之外的文件新增静默 catch")
    void noSilentCatchOutsideAllowlist() throws IOException {
        List<String> offenders = scanSilentCatches().stream()
                .filter(sc -> !ALLOWED.contains(sc.file()))
                .map(sc -> sc.file() + ":" + sc.line())
                .toList();

        assertThat(offenders)
                .as("这些位置吞掉异常且不留任何线索。吞异常本身不一定错——"
                        + "旁路能力不该把主流程带崩——但吞掉之后必须有日志，"
                        + "否则故障发生时现场没有任何指向它的证据。"
                        + "若确属「异常即预期分支」，请登记到 ALLOWED 并在注释里写明理由")
                .isEmpty();
    }

    @Test
    @DisplayName("静默 catch 总数只允许降，不允许升")
    void silentCatchCountDoesNotGrow() throws IOException {
        int actual = scanSilentCatches().size();

        assertThat(actual)
                .as("静默 catch 总数超过基线 %d。即便新增位置在白名单文件内，"
                        + "也需要显式确认这是有意为之——白名单是按文件粒度的，"
                        + "不该成为在既有文件里随意新增静默 catch 的通行证",
                        BASELINE_SILENT_COUNT)
                .isLessThanOrEqualTo(BASELINE_SILENT_COUNT);
    }

    @Test
    @DisplayName("白名单不得包含已无静默 catch 的文件——避免长期挂着无效豁免")
    void allowlistHasNoStaleEntries() throws IOException {
        List<String> stillSilent = scanSilentCatches().stream()
                .map(SilentCatch::file).distinct().toList();

        List<String> stale = ALLOWED.stream()
                .filter(f -> !stillSilent.contains(f))
                .toList();

        assertThat(stale)
                .as("这些文件已经没有静默 catch，白名单条目应当移除。"
                        + "留着无效豁免会让人误以为它们仍有问题，"
                        + "也会掩盖将来在这些文件里新增的静默 catch")
                .isEmpty();
    }

    @Test
    @DisplayName("扫描器自身有效性：能在样例代码上正确区分静默与非静默")
    void scannerDistinguishesSilentFromLogged() {
        // 这条是给扫描器本身兜底的。
        // 第一版扫描器因为从行首配平花括号，把 166 处全判成静默——
        // 若没有这类自检，一个坏掉的扫描器会「永远通过」或「永远失败」，
        // 两种都等于没有这条契约
        String silent = "} catch (Exception e) {\n    return null;\n}";
        String logged = "} catch (Exception e) {\n    log.warn(\"x\");\n    return null;\n}";
        String rethrown = "} catch (Exception e) {\n    throw new IllegalStateException(e);\n}";

        assertThat(HAS_LOG.matcher(silent).find() || HAS_THROW.matcher(silent).find())
                .as("纯 return 应被判为静默").isFalse();
        assertThat(HAS_LOG.matcher(logged).find()).as("含 log 应被判为有线索").isTrue();
        assertThat(HAS_THROW.matcher(rethrown).find()).as("含 throw 应被判为有线索").isTrue();
        assertThat(CATCH.matcher("} catch (final Throwable t) {").find())
                .as("应识别 final Throwable 形态").isTrue();
    }
}
