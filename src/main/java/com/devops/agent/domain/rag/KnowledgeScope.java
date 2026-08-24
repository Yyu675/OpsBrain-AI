package com.devops.agent.domain.rag;

import java.util.Objects;

/**
 * 知识库可见范围（C1）。
 *
 * <p>表示「当前请求方能看到哪些知识」，是检索层做权限过滤的唯一依据。
 * 不可变值对象，可安全地跨线程传递。</p>
 *
 * <h3>为什么需要显式传递，而不是在检索层现取当前登录人</h3>
 * 检索的实际调用链是：
 * <pre>
 *   请求线程（有 Sa-Token 上下文）
 *     → sessionExecutor 虚拟线程（已无上下文）
 *       → 模型 HTTP 回调线程（更没有）
 *         → DevOpsTools.searchDevOpsKnowledge
 *           → HybridRetrieverService.retrieveWithSource
 * </pre>
 * Sa-Token 的登录态存在 ThreadLocal 里，<b>到检索层时早已丢失</b>。
 * 若在检索层写 {@code StpUtil.getLoginId()}，得到的不是"未登录异常"就是 null，
 * 于是只能降级放行——权限过滤形同虚设，而且失效得悄无声息。
 * 项目里已有同类教训：{@code resolveQuotaKey} 必须在请求线程解析、
 * {@code ToolRuntimeManager} 的 traceId 在回调线程只能取到 null。
 *
 * <p>因此：<b>Scope 必须在请求线程解析出来，再作为参数一路传到检索层。</b></p>
 *
 * @param userId    用户标识，仅用于日志与审计
 * @param dept      所属部门，决定能看到哪些 RESTRICTED 文档；可为 null
 * @param admin     是否管理员（可见全部）
 * @param anonymous 是否匿名（无登录态）
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
public record KnowledgeScope(String userId, String dept, boolean admin, boolean anonymous) {

    /** 管理员可见全部 */
    public static KnowledgeScope admin(String userId, String dept) {
        return new KnowledgeScope(userId, dept, true, false);
    }

    /** 普通登录用户 */
    public static KnowledgeScope user(String userId, String dept) {
        return new KnowledgeScope(userId, dept, false, false);
    }

    /**
     * 匿名/无登录态。
     * <p>仅能看 PUBLIC。<b>不要</b>把它当成"取不到登录态时的安全兜底"来滥用——
     * 兜底应当是最小权限，这正是本值的语义。</p>
     */
    public static KnowledgeScope anonymous() {
        return new KnowledgeScope(null, null, false, true);
    }

    /**
     * 判断本范围能否看到给定可见性的文档。
     * <p>与检索 SQL 的过滤条件<b>必须保持一致</b>——两处逻辑分叉会导致
     * 「列表里看不到但 AI 能讲出内容」这类最难排查的越权。
     * 因此 SQL 条件由 {@link #toSqlPredicate()} 统一生成，本方法仅供
     * 单条判定（如详情接口）与测试对照。</p>
     */
    public boolean canSee(String visibility, String docDept) {
        if (admin) {
            return true;
        }
        String v = visibility == null ? "PUBLIC" : visibility;
        return switch (v) {
            case "PUBLIC" -> true;
            case "INTERNAL" -> !anonymous;
            case "RESTRICTED" -> !anonymous
                    && dept != null
                    && Objects.equals(dept, docDept);
            // 未知档位按最严处理：宁可少给，不可多给
            default -> false;
        };
    }

    /**
     * 生成 SQL 过滤谓词（配合 {@link #sqlParams()} 使用）。
     *
     * <p>用参数占位而非字符串拼接部门名 —— 部门名来自数据库，
     * 拼接会引入 SQL 注入面。</p>
     *
     * <p>返回的谓词假定查询中可直接引用 {@code visibility} 与
     * {@code owner_dept} 两列（chunk 表已冗余这两列，无需 JOIN）。</p>
     */
    public String toSqlPredicate() {
        if (admin) {
            return "TRUE";
        }
        if (anonymous) {
            return "visibility = 'PUBLIC'";
        }
        if (dept == null || dept.isBlank()) {
            // 登录但无部门：看不到任何 RESTRICTED
            return "visibility IN ('PUBLIC','INTERNAL')";
        }
        return "(visibility IN ('PUBLIC','INTERNAL') "
                + "OR (visibility = 'RESTRICTED' AND owner_dept = ?))";
    }

    /** 与 {@link #toSqlPredicate()} 配套的参数（顺序一致），可能为空数组 */
    public Object[] sqlParams() {
        if (admin || anonymous || dept == null || dept.isBlank()) {
            return new Object[0];
        }
        return new Object[]{dept};
    }

    /** 供日志使用的简短描述（不含敏感信息） */
    public String describe() {
        if (admin) return "ADMIN(全部可见)";
        if (anonymous) return "ANONYMOUS(仅 PUBLIC)";
        return "USER(dept=" + (dept == null ? "-" : dept) + ")";
    }

    /**
     * 语义缓存的权限域键（C2）。
     *
     * <h3>为什么不用 userId 做键</h3>
     * 那样每个用户各存一份缓存，命中率会从 85% 掉到接近 0，
     * 语义缓存这个功能就废了 —— 它的全部价值就在于跨用户复用。
     *
     * <h3>正确的粒度：按「能看到什么」而非「你是谁」</h3>
     * 两个用户只要可见范围相同，他们对同一问题应当得到同样的答案，
     * 缓存自然可以共享。所以键取决于可见性维度：
     * <ul>
     *   <li>{@code PUBLIC} —— 匿名，只可能引用公开内容</li>
     *   <li>{@code INTERNAL} —— 登录但无部门，可见 PUBLIC+INTERNAL</li>
     *   <li>{@code DEPT:研发} —— 该部门成员，额外可见本部门 RESTRICTED</li>
     *   <li>{@code ADMIN} —— 可见全部</li>
     * </ul>
     * 同一部门的 10 个人共享一份缓存，命中率基本不受影响，
     * 而跨部门/跨权限档不会互相命中。
     *
     * <h3>⚠️ 与 AI 链路当前行为的关系</h3>
     * 目前 Agent 工具检索多数时候退化为「仅 PUBLIC」
     * （见 {@code AgentKnowledgeScopeHolder}），也就是说答案实际只基于公开内容。
     * 此时用更细的 scope 做缓存键会造成不必要的缓存分裂。
     * 但这里<b>仍按用户真实范围分区</b>——因为一旦将来打通了工具侧的范围传递，
     * 缓存若还是共享的就会立刻变成越权通道。<b>安全边界要提前留好，
     * 而不是等出事再补。</b>
     */
    public String cacheScopeKey() {
        if (admin) {
            return "ADMIN";
        }
        if (anonymous) {
            return "PUBLIC";
        }
        if (dept == null || dept.isBlank()) {
            return "INTERNAL";
        }
        return "DEPT:" + dept;
    }
}
