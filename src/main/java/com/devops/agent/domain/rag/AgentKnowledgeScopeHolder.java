package com.devops.agent.domain.rag;

import lombok.extern.slf4j.Slf4j;

/**
 * Agent 链路的知识可见范围持有者（C1）。
 *
 * <h3>要解决的问题</h3>
 * {@code DevOpsTools.searchDevOpsKnowledge} 的方法签名由 LangChain4j 的
 * {@code @Tool} 契约决定 —— 参数是**模型填的**，没法多塞一个 scope 进去。
 * 而检索又必须知道"谁在查"，否则权限过滤无从谈起。
 *
 * <h3>取舍：失败必须朝「更严」的方向</h3>
 * 本类用 ThreadLocal 传递。编排层在 {@code sessionExecutor} 线程上
 * {@link #set} ，工具若恰好在同一线程执行就能取到。
 * <p>
 * 但项目里已有明确记录：<b>工具实际运行在模型的 HTTP 回调线程</b>
 * （见 {@code ToolRuntimeManager.recordToolFailure} 的注释：
 * 「工具在模型 HTTP 回调线程执行，TraceContext 的 ThreadLocal 不跨线程，
 * getTraceId 多半返回 null」）。也就是说 {@link #get} <b>大概率取不到</b>。
 * <p>
 * 因此 {@link #getOrRestrictive()} 在取不到时返回
 * {@link KnowledgeScope#anonymous()}（<b>仅 PUBLIC</b>），而不是放行全部。
 * 这是刻意的：
 * <ul>
 *   <li>取不到就放行 = 权限过滤在最关键的 AI 链路上<b>静默失效</b>，
 *       而越权是无声的，没有任何报错会提示你；</li>
 *   <li>取不到就收紧 = 最坏情况是 AI 答不出受限文档的内容，
 *       用户会察觉并反馈，问题<b>会暴露</b>。</li>
 * </ul>
 * 两种失败模式的代价完全不对称，所以只能选后者。
 *
 * <h3>顺带的产品收益</h3>
 * 「AI 对话默认只引用 PUBLIC/INTERNAL 知识」本身就是合理策略：
 * 受限文档不该被 LLM 顺口讲出来，尤其它还会被写进对话历史与语义缓存。
 *
 * <h3>遗留项</h3>
 * 若将来要让 ADMIN 在 AI 对话里也能检索 RESTRICTED，需要改为
 * 「每请求构建 AiService 实例」或使用 LangChain4j 的工具上下文透传，
 * 不能靠 ThreadLocal。已登记在 AGENTS.md 待办中。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
public final class AgentKnowledgeScopeHolder {

    private static final ThreadLocal<KnowledgeScope> HOLDER = new ThreadLocal<>();

    private AgentKnowledgeScopeHolder() {
    }

    public static void set(KnowledgeScope scope) {
        HOLDER.set(scope);
    }

    /** 可能为 null —— 调用方通常应当用 {@link #getOrRestrictive()} */
    public static KnowledgeScope get() {
        return HOLDER.get();
    }

    /**
     * 取当前范围；取不到时返回<b>最小权限</b>（仅 PUBLIC）。
     * <p>永不返回 null，永不放行全部。</p>
     */
    public static KnowledgeScope getOrRestrictive() {
        KnowledgeScope scope = HOLDER.get();
        if (scope == null) {
            // debug 而非 warn：这是预期内的常态（工具跑在回调线程），
            // 用 warn 会在每次对话刷屏，反而淹没真正的告警。
            log.debug("🔒 [KnowledgeScope] 工具线程无可见范围上下文，按最小权限（仅 PUBLIC）检索");
            return KnowledgeScope.anonymous();
        }
        return scope;
    }

    /** 必须在 finally 中调用，否则线程复用会把上一个用户的范围带给下一个请求 */
    public static void clear() {
        HOLDER.remove();
    }
}
