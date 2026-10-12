package com.devops.agent.common.guard;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工单/告警写操作的角色守卫（T3，2026-10-12）。
 *
 * <h3>要防住什么</h3>
 * <p>
 * 修复前实测：TicketController 20 个写端点里 19 个、AlertController 的
 * 确认/恢复处置端点<b>全部只校验登录</b>（SaInterceptor 全局 {@code checkLogin}），
 * 无任何角色判断。也就是说任何登录账号（含只读账号）都能建单、改单、
 * 确认告警、作废工单——前端的 {@code ticket:write / alert:write} 码门控
 * （批90 R0-1）是<b>唯一防线</b>，绕开 UI 直接调 API 即穿透。
 * 前端 AGENTS.md 第五条写得很直白：「前端权限只是体验优化，后端必须独立校验」。
 * </p>
 *
 * <h3>分级：与 KnowledgeWriteGuard 同构</h3>
 * <table border="1">
 *   <tr><th>级别</th><th>操作</th><th>允许角色</th></tr>
 *   <tr>
 *     <td>{@link #requireWrite()}</td>
 *     <td>建/改/状态/接单/升级/处置动作/止损/根因/验证/转派/作废/附件上传/
 *         标签/回复/AI 分析保存；告警 acknowledge（确认）/resolve（恢复）</td>
 *     <td>ADMIN + OPS（对齐前端 OPERATOR_PERMISSIONS 码表，双向一致）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #requireAdmin()}</td>
 *     <td>删除附件（物理删 MinIO 对象）；工单物理删除沿用
 *         {@code @SaCheckRole("ADMIN")}（方向 F 既有写法，不改）</td>
 *     <td>仅 ADMIN</td>
 *   </tr>
 * </table>
 * <p>
 * AI 分析反馈（{@code POST /tickets/ai-analysis/{id}/feedback}）<b>有意豁免</b>：
 * 与知识库 {@code /feedback/citations} 同语义——「阅读反馈」不是内容编辑，
 * 是任何登录用户都可点的准确率遥测，加守卫会把产品语义收成 ADMIN/OPS 专属。
 * </p>
 *
 * <h3>为什么用显式方法而非注解</h3>
 * <p>
 * 「ADMIN 或 OPS」需要 {@code @SaCheckRole(mode = SaMode.OR)}，本项目从未用过
 * SaMode，注解语义写错的后果是<b>校验静默失效</b>——比不加还危险
 * （同 {@link KnowledgeWriteGuard} 的决策理由，不再重评）。
 * </p>
 *
 * <h3>失败关闭（fail-closed）</h3>
 * <p>
 * 未知角色（含 VIEWER 与任何未来新角色）一律拒绝。放行名单是白名单枚举，
 * 新增角色默认无写权限——与前端把未知角色折进空权限码集合同一姿态。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-10-12
 */
@Component
public class OpsWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(OpsWriteGuard.class);

    /** 管理员：可执行全部操作 */
    public static final String ROLE_ADMIN = "ADMIN";
    /** 运维：可做工单/告警的日常处置，不可执行删除类操作 */
    public static final String ROLE_OPS = "OPS";

    /** 日常写操作的角色集合（与前端 OPERATOR_PERMISSIONS 的授予面一致） */
    private static final List<String> WRITE_ROLES = List.of(ROLE_ADMIN, ROLE_OPS);

    /**
     * 校验「日常写」权限：ADMIN 或 OPS。
     *
     * <p>工单全生命周期处置与告警 acknowledge/resolve 都属可逆或可留痕操作：
     * 改错了有活动流/审计可追溯，一线必须能干，否则值班只能干看着。</p>
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 未登录
     * @throws OpsWriteForbiddenException 角色不足
     */
    public void requireWrite() {
        checkLogin();
        List<String> roles = currentRoles();
        for (String role : WRITE_ROLES) {
            if (roles.contains(role)) {
                return;
            }
        }
        log.warn("🚫 [OpsWriteGuard] 工单/告警写操作被拒 | loginId={} | 当前角色={} | 需要={}",
                currentLoginId(), roles, WRITE_ROLES);
        throw new OpsWriteForbiddenException(
                "该操作需要 " + ROLE_ADMIN + " 或 " + ROLE_OPS + " 角色");
    }

    /**
     * 校验「删除类」权限：仅 ADMIN。
     *
     * <p>删除附件会物理移除 MinIO 对象，删了就是没了——与工单物理删除同级，
     * 仅 ADMIN。工单本身的删除沿用注解写法，不在此重复。</p>
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 未登录
     * @throws OpsWriteForbiddenException 角色不足
     */
    public void requireAdmin() {
        checkLogin();
        List<String> roles = currentRoles();
        if (roles.contains(ROLE_ADMIN)) {
            return;
        }
        log.warn("🚫 [OpsWriteGuard] 删除类操作被拒 | loginId={} | 当前角色={} | 需要={}",
                currentLoginId(), roles, ROLE_ADMIN);
        throw new OpsWriteForbiddenException(
                "删除操作不可恢复，需要 " + ROLE_ADMIN + " 角色");
    }

    // ==================== 会话访问接缝（供测试覆写） ====================
    //
    // 与 KnowledgeWriteGuard 同一决策：StpUtil 状态在 ThreadLocal，单测无请求上下文；
    // 本项目未引入 mockito-inline，用 protected 接缝 + 测试子类，零新依赖。

    /** 校验已登录。未登录时由 Sa-Token 抛 NotLoginException，全局处理器转 40101 */
    protected void checkLogin() {
        StpUtil.checkLogin();
    }

    /** 当前会话的角色列表。来源 sys_user.role，见 SaTokenPermissionProvider */
    protected List<String> currentRoles() {
        return StpUtil.getRoleList();
    }

    /** 当前登录标识，仅用于日志 */
    protected Object currentLoginId() {
        return StpUtil.getLoginId();
    }

    /**
     * 工单/告警写权限不足。
     *
     * <p>不复用 Sa-Token 的 {@code NotRoleException}：其 message 由框架生成
     * （「无此角色：ADMIN」），说不出「下一步做什么」。码与 HTTP 状态与其
     * 保持一致（40103 / 403），前端 bizCode.ts 已有对应文案。</p>
     */
    public static class OpsWriteForbiddenException extends RuntimeException {
        public OpsWriteForbiddenException(String message) {
            super(message);
        }

        /** 对应 {@link BizError#NO_PERMISSION}（40103 / HTTP 403） */
        public int code() {
            return BizError.NO_PERMISSION.code();
        }
    }
}
