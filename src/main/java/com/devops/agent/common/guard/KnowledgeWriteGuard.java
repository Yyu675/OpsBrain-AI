package com.devops.agent.common.guard;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库写操作的角色守卫。
 *
 * <h3>为什么知识库需要写权限控制</h3>
 * <p>
 * 知识库是 <b>AI 回答的事实来源</b>——RAG 检索的每一条引用都来自这里。
 * 任何登录用户都能改，意味着<b>任何人都能污染 AI 的输出</b>：
 * 改一篇「生产数据库重启流程」，之后所有问到这个问题的 SRE 拿到的
 * 都是被篡改的答案，而且他们不会怀疑——因为答案带着「引用出处」。
 * </p>
 * <p>
 * 修复前实测：4 个知识库控制器共 <b>16 个写端点，15 个无任何角色校验</b>
 * （仅 {@code purge} 物理删除限了 ADMIN）。而同项目的审批、审计、治理、
 * Saga、Agent 轨迹 5 个控制器都是类级 {@code @SaCheckRole("ADMIN")}。
 * </p>
 *
 * <h3>分级策略：为什么不是「全限 ADMIN」</h3>
 * <p>
 * 系统只有 {@code ADMIN} / {@code OPS} 两种角色（见 {@code User.java}，
 * <b>没有 viewer</b>）。若把知识库写权限全收给 ADMIN，
 * 一线运维就无法沉淀故障处置经验——而「知识资产」正是本产品的差异化核心，
 * <b>没人写就没有资产</b>。
 * </p>
 * <p>
 * 故按操作的<b>可逆性</b>分两级：
 * </p>
 * <table border="1">
 *   <caption>知识库写操作权限分级</caption>
 *   <tr><th>级别</th><th>操作</th><th>允许角色</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@link #requireEdit()}</td>
 *     <td>新建、编辑、发布、恢复、移动分类、建标签、重命名标签</td>
 *     <td>ADMIN + OPS</td>
 *     <td>可逆——有版本历史（{@code sys_knowledge_doc_history}）可回滚，
 *         且改错了能再改回来</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #requireDestructive()}</td>
 *     <td>废弃、删除分类、删除标签、合并标签、全量重建索引</td>
 *     <td>仅 ADMIN</td>
 *     <td>不可逆或影响面大——标签合并会批量改写文档关联，
 *         删分类影响其下所有文档，重建索引会打满 embedding 配额</td>
 *   </tr>
 * </table>
 *
 * <h3>为什么用显式方法而非注解</h3>
 * <p>
 * 「ADMIN 或 OPS」需要 Sa-Token 的 {@code @SaCheckRole(mode = SaMode.OR)}，
 * 而本项目<b>从未用过 {@code SaMode}</b>。注解语义写错的后果是
 * <b>校验静默失效</b>——比不加还危险，因为看代码会以为已经保护了。
 * 显式的 {@code hasRole} 判断没有这个风险，且能在日志里留下拒绝原因。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@Component
public class KnowledgeWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWriteGuard.class);

    /** 管理员：可执行全部操作 */
    public static final String ROLE_ADMIN = "ADMIN";
    /** 运维：可编辑知识，不可执行不可逆操作 */
    public static final String ROLE_OPS = "OPS";

    /** 可编辑知识库的角色集合 */
    private static final List<String> EDIT_ROLES = List.of(ROLE_ADMIN, ROLE_OPS);

    /**
     * 校验「可编辑」权限：ADMIN 或 OPS。
     *
     * <p>用于可逆操作。改错了有版本历史兜底，故对一线运维开放——
     * 否则知识沉淀不起来。</p>
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 未登录
     * @throws KnowledgeWriteForbiddenException 角色不足
     */
    public void requireEdit() {
        checkLogin();
        List<String> roles = currentRoles();
        for (String role : EDIT_ROLES) {
            if (roles.contains(role)) {
                return;
            }
        }
        log.warn("🚫 [KnowledgeGuard] 编辑知识库被拒 | loginId={} | 当前角色={} | 需要={}",
                currentLoginId(), roles, EDIT_ROLES);
        throw new KnowledgeWriteForbiddenException(
                "编辑知识库需要 " + ROLE_ADMIN + " 或 " + ROLE_OPS + " 角色");
    }

    /**
     * 校验「不可逆操作」权限：仅 ADMIN。
     *
     * <p>用于废弃、删除、合并、全量重建索引这类改不回来或影响面大的操作。</p>
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 未登录
     * @throws KnowledgeWriteForbiddenException 角色不足
     */
    public void requireDestructive() {
        checkLogin();
        List<String> roles = currentRoles();
        if (roles.contains(ROLE_ADMIN)) {
            return;
        }
        log.warn("🚫 [KnowledgeGuard] 不可逆操作被拒 | loginId={} | 当前角色={} | 需要={}",
                currentLoginId(), roles, ROLE_ADMIN);
        throw new KnowledgeWriteForbiddenException(
                "该操作不可逆，需要 " + ROLE_ADMIN + " 角色");
    }

    // ==================== 会话访问接缝（供测试覆写） ====================
    //
    // StpUtil 是静态工具类，状态来自 ThreadLocal 里的请求上下文。
    // 单元测试里没有真实请求，直接调用会抛异常。
    //
    // 抽成 protected 方法而非用 mockStatic：本项目从未引入 mockito-inline，
    // 而 mockStatic 依赖它。用「可覆写方法 + 测试子类」是纯 Java 方案，
    // 零新依赖，也不会有静态 mock 泄漏到同 JVM 其它测试的风险。

    /** 校验已登录。未登录时由 Sa-Token 抛 NotLoginException，全局处理器转 40101 */
    protected void checkLogin() {
        StpUtil.checkLogin();
    }

    /** 当前会话的角色列表。来源 {@code sys_user.role}，见 SaTokenPermissionProvider */
    protected List<String> currentRoles() {
        return StpUtil.getRoleList();
    }

    /** 当前登录标识，仅用于日志 */
    protected Object currentLoginId() {
        return StpUtil.getLoginId();
    }

    /**
     * 知识库写权限不足。
     *
     * <p>单独定义而非复用 Sa-Token 的 {@code NotRoleException}：
     * 后者的 message 由框架生成（「无此角色：ADMIN」），
     * 说不清「为什么这个操作需要这个角色」。
     * 而权限提示是用户唯一能看到的解释——它应该告诉人下一步做什么。</p>
     */
    public static class KnowledgeWriteForbiddenException extends RuntimeException {
        public KnowledgeWriteForbiddenException(String message) {
            super(message);
        }

        /** 对应 {@link BizError#NO_PERMISSION}（40103 / HTTP 403） */
        public int code() {
            return BizError.NO_PERMISSION.code();
        }
    }
}
