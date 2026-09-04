package com.devops.agent.application.impl;

import com.devops.agent.application.context.ContextBudgetManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 温记忆锚点注入 + 上下文预算裁剪的行为测试。
 *
 * <h3>修复的两个缺陷（同源）</h3>
 * 三层记忆与上下文预算这两套机制<b>代码都写了，但在主链路上空转</b>：
 *
 * <ol>
 *   <li><b>预算裁剪结果被丢弃</b>：{@code budgetManager.allocate(...)} 精心算出
 *       {@code includedHistory}，主链路却只取 {@code isWithinBudget()} 做通过/拒绝，
 *       裁剪结果整个扔掉。真正生效的是 {@code MessageWindowChatMemory} 的
 *       <b>按条数</b>截断（20 条）——与按 token 预算不是一回事；</li>
 *   <li><b>温记忆从未进模型</b>：{@code loadContext} 把跨会话关键事实查出来后，
 *       只拼进一个用于预算计算的局部变量，而
 *       {@code engine.chat(sessionId, query)} 只带会话 ID 与当前问题。</li>
 * </ol>
 *
 * <h3>为什么这两件事很难被发现</h3>
 * 都<b>没有任何报错</b>：
 * <ul>
 *   <li>窗口溢出由模型侧静默截断，被切掉的往往是最早的关键前提，
 *       表现为「AI 忘了前面说过的约束」；</li>
 *   <li>温记忆丢失表现为「AI 重复追问已经回答过的事」。</li>
 * </ul>
 * 两者看起来都像「模型不太聪明」，而不像系统缺陷——
 * 排查方向会跑到提示词工程上去。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("温记忆锚点与上下文预算")
class MemoryAnchorAndBudgetTest {

    // ==================================================================
    // 温记忆锚点
    // ==================================================================

    @Nested
    @DisplayName("buildPromptWithMemoryAnchor 记忆锚点拼装")
    class MemoryAnchor {

        /** 反射调用 private 方法（它是主链路内部实现，不对外暴露） */
        private String build(String query, String facts) throws Exception {
            Method m = DevOpsAgentServiceImpl.class.getDeclaredMethod(
                    "buildPromptWithMemoryAnchor", String.class, String.class);
            m.setAccessible(true);
            // 该方法只依赖入参与静态常量，不碰任何注入字段。
            // 用 Mockito 造壳而非真实 new：构造器有 17 个依赖，
            // 为测一个纯字符串拼装方法去装配它们是本末倒置。
            // CALLS_REAL_METHODS 让被测方法走真实逻辑，其余成员保持 mock 默认值。
            DevOpsAgentServiceImpl instance = org.mockito.Mockito.mock(
                    DevOpsAgentServiceImpl.class,
                    org.mockito.Mockito.withSettings()
                            .defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
            return (String) m.invoke(instance, query, facts);
        }

        @Test
        @DisplayName("有关键事实时，事实与本轮提问都出现在提示词中")
        void injectsFactsAndQuery() throws Exception {
            String out = build("Pod 又挂了怎么办", "集群=prod-a；已排除镜像拉取失败");

            assertThat(out).contains("集群=prod-a");
            assertThat(out).contains("已排除镜像拉取失败");
            assertThat(out).contains("Pod 又挂了怎么办");
        }

        @Test
        @DisplayName("必须显式标注「非本轮提问内容」——否则模型会当成用户刚说的话")
        void marksFactsAsBackground() throws Exception {
            // 不加说明直接把事实丢进用户消息，模型可能复述
            // 「你刚才说集群是 prod-a」——而用户这轮根本没提。
            // 这类幻觉很隐蔽：内容本身是对的，只是归因错了
            String out = build("继续排查", "集群=prod-a");

            assertThat(out)
                    .as("需要一句话把事实与本轮输入区分开")
                    .contains("非本轮提问内容");
            assertThat(out).contains("本轮提问");
        }

        @Test
        @DisplayName("无关键事实时原样返回，不加任何包装")
        void noFactsReturnsQueryAsIs() throws Exception {
            assertThat(build("你好", null)).isEqualTo("你好");
            assertThat(build("你好", "   ")).isEqualTo("你好");
        }

        @Test
        @DisplayName("锚点超长时截断并标注，而不是无限制占用上下文")
        void oversizedAnchorIsTruncated() throws Exception {
            // 锚点自身也占上下文。不设上限等于把「防溢出」的口子
            // 重新开在这里——温记忆是会随会话轮次累积增长的
            String huge = "事".repeat(5000);
            String out = build("问题", huge);

            assertThat(out).contains("（已截断）");
            // 1200 字符上限 + 截断标记 + 模板本身，总长必须远小于原始 5000
            assertThat(out.length())
                    .as("截断后不应仍接近原始长度")
                    .isLessThan(2000);
        }

        @Test
        @DisplayName("恰好等于上限时不截断——边界不能少一位")
        void anchorAtLimitNotTruncated() throws Exception {
            // 只断言长度是分辨不出来的（截断后长度也接近上限），
            // 用首尾字符不同来验证内容完整
            String facts = "S" + "事".repeat(1198) + "E";
            assertThat(facts).hasSize(1200);

            String out = build("问题", facts);

            assertThat(out).doesNotContain("（已截断）");
            assertThat(out).contains("E");
        }

        @Test
        @DisplayName("事实两端空白被裁掉，不影响内容")
        void factsAreTrimmed() throws Exception {
            String out = build("问题", "  集群=prod-a  ");
            assertThat(out).contains("集群=prod-a");
        }
    }

    // ==================================================================
    // 主链路接线（防「拼装方法写好了却没接上」）
    // ==================================================================

    @Nested
    @DisplayName("主链路接线")
    class Wiring {

        private static final java.nio.file.Path IMPL = java.nio.file.Path.of(
                "src/main/java/com/devops/agent/application/impl/DevOpsAgentServiceImpl.java");

        /**
         * 这条用例补的是一个真实盲区。
         *
         * <p>注入验证时把 {@code engine.chat(sessionId, promptWithMemory)} 改回
         * {@code engine.chat(sessionId, query)}——也就是本次修复的缺陷本身——
         * 上面那些锚点拼装用例<b>全部照常通过</b>：它们只测了拼装函数，
         * 没人验证拼装结果有没有被真正用出去。</p>
         *
         * <p>而「函数写好了却没接上主链路」正是这两个缺陷的共同形态
         * （预算裁剪结果被丢弃、温记忆加载后没送进模型）。
         * 只测纯函数会让同样的错误再犯一次而测试全绿。</p>
         *
         * <p>用源码断言而非行为断言，是因为要真跑到 {@code engine.chat}
         * 需要装配 17 个依赖 + SSE 上下文，成本远高于收益；
         * 而这条契约的本质就是「调用点必须传拼装后的值」。</p>
         */
        /** 读取源码并剔除注释行——注释里常有「反例字面量」，会让断言恒假 */
        private String codeLinesOf(java.nio.file.Path p) {
            try {
                return java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)
                        .stream()
                        .map(String::trim)
                        .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                        .collect(java.util.stream.Collectors.joining("\n"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取源码失败: " + p, e);
            }
        }

        @Test
        @DisplayName("engine.chat 必须传入拼装了记忆锚点的提示词，而不是原始 query")
        void enginePromptCarriesMemoryAnchor() {
            // 只看代码行：注释里为了说明「此前是怎么错的」正好写了
            // engine.chat(sessionId, query) 这个字样，
            // 不剔除注释会让这条断言恒假（本地预跑时已实测撞到）
            String code = codeLinesOf(IMPL);

            assertThat(code)
                    .as("温记忆锚点必须真正送进模型，否则三层记忆的温层在读取侧空转")
                    .contains("engine.chat(sessionId, promptWithMemory)");
            assertThat(code)
                    .as("不得退回只传原始 query 的写法")
                    .doesNotContain("engine.chat(sessionId, query)");
        }

        @Test
        @DisplayName("预算裁剪结果必须被取用，而不是只看 isWithinBudget")
        void budgetTrimResultIsConsumed() {
            // 同源盲区：allocate() 算得再准，主链路不取 includedHistory
            // 就等于没算。此前全仓 grep 该 getter 零调用点
            assertThat(codeLinesOf(IMPL))
                    .as("裁剪结果必须被消费，否则 ContextBudgetManager 空转")
                    .contains("budgetCheck.getIncludedHistory()");
        }
    }

    // ==================================================================
    // 预算裁剪
    // ==================================================================

    @Nested
    @DisplayName("ContextBudgetManager 按 token 裁剪历史")
    class BudgetTrimming {

        private ContextBudgetManager manager(int window) {
            ContextBudgetManager m = new ContextBudgetManager();
            ReflectionTestUtils.setField(m, "modelContextWindow", window);
            ReflectionTestUtils.setField(m, "reservedResponseTokens", 1500);
            ReflectionTestUtils.setField(m, "systemPromptTokens", 800);
            return m;
        }

        @Test
        @DisplayName("历史总量超预算时只纳入放得下的部分——这正是主链路要用的裁剪结果")
        void trimsHistoryToBudget() {
            // ── 本组最重要的一条 ──────────────────────────────
            // 运维场景贴一段 5000 字符的日志/堆栈很常见。
            // 按 CHARS_PER_TOKEN=1.5 折算，单条约 3333 token，
            // MessageWindowChatMemory 的 20 条窗口 ≈ 6.7 万 token，
            // 是 32k 窗口的 2 倍多。按条数截断挡不住这种溢出，
            // 只有按 token 预算裁剪才行
            ContextBudgetManager m = manager(32000);
            List<String> history = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                history.add("日志片段" + i + "：" + "x".repeat(5000));
            }

            var alloc = m.allocate("为什么 Pod 一直重启", history, List.of(), List.of());

            assertThat(alloc.getIncludedHistory())
                    .as("20 条 5000 字符的消息远超 32k 窗口，必须被裁剪")
                    .hasSizeLessThan(20);
            assertThat(alloc.getUsedTokens())
                    .as("裁剪后必须落在可用预算内")
                    .isLessThanOrEqualTo(32000 - 1500);
        }

        @Test
        @DisplayName("预算充裕时历史全部纳入，不做无谓裁剪")
        void keepsAllWhenBudgetAllows() {
            ContextBudgetManager m = manager(32000);
            List<String> history = List.of("Q1", "A1", "Q2", "A2");

            var alloc = m.allocate("下一步", history, List.of(), List.of());

            assertThat(alloc.getIncludedHistory()).hasSize(4);
            assertThat(alloc.isWithinBudget()).isTrue();
        }

        @Test
        @DisplayName("裁剪保持时间顺序（旧在前）——顺序错乱会让模型误解因果")
        void preservesChronologicalOrder() {
            ContextBudgetManager m = manager(32000);
            List<String> history = List.of("最近的", "较早的", "最早的");

            var alloc = m.allocate("问题", history, List.of(), List.of());

            // allocate 的入参约定是「最近在前」，输出需转为「旧在前」
            assertThat(alloc.getIncludedHistory())
                    .containsExactly("最早的", "较早的", "最近的");
        }

        @Test
        @DisplayName("单个问题就撑爆窗口时明确拒绝，而不是截断后硬答")
        void rejectsOversizedQuery() {
            // 截断用户问题后作答，等于回答了一个「用户没问的问题」，
            // 而用户无从知晓自己的输入被切了
            ContextBudgetManager m = manager(4000);

            var alloc = m.allocate("x".repeat(100000), List.of(), List.of(), List.of());

            assertThat(alloc.isWithinBudget()).isFalse();
            assertThat(alloc.getDegradationReason()).contains("QUERY_TOO_LONG");
            assertThat(alloc.getIncludedHistory()).isEmpty();
        }

        @Test
        @DisplayName("历史为 null 时安全返回空，不抛 NPE")
        void nullHistoryIsSafe() {
            ContextBudgetManager m = manager(32000);
            var alloc = m.allocate("问题", null, List.of(), List.of());
            assertThat(alloc.getIncludedHistory()).isEmpty();
        }
    }
}
