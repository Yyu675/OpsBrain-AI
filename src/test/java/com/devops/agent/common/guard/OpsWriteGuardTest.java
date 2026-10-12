package com.devops.agent.common.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单/告警写权限守卫的分级契约（T3，2026-10-12）。
 *
 * <h3>要防住什么</h3>
 * <p>
 * 修复前 TicketController / AlertController / TicketPostmortemController 的
 * 写端点全部只校验登录——只读账号绕开前端按钮直调 API 即可建单、改状态、
 * 确认告警。前端批90 的 ticket:write / alert:write 码门控是唯一防线，
 * 而「前端权限只是体验优化，后端必须独立校验」是前端 AGENTS.md 第五条原文。
 * </p>
 *
 * <h3>两侧都测（同 KnowledgeWriteGuardTest 的方法论）</h3>
 * <p>
 * 只测「拒绝」会让「一律拒绝」的实现蒙混过关——那会把值班运维挡在
 * 工单系统外面，比漏洞更快的事故。故每级同时夹住「该放行的放行」与
 * 「该拒绝的拒绝」，OPS 放行 / ADMIN 专属两级互为分叉。
 * </p>
 *
 * <h3>测试子类而非 mockStatic</h3>
 * <p>同 KnowledgeWriteGuardTest：本项目未引入 mockito-inline，
 * protected 接缝 + 子类覆写是零依赖方案。</p>
 *
 * @author OpsBrain AI
 * @since 2026-10-12
 */
@DisplayName("工单/告警写权限守卫")
class OpsWriteGuardTest {

    /** 可控会话的守卫：角色与登录态由用例指定 */
    private static class TestGuard extends OpsWriteGuard {
        private final List<String> roles;
        private boolean loginChecked = false;

        TestGuard(String... roles) {
            this.roles = List.of(roles);
        }

        @Override
        protected void checkLogin() {
            loginChecked = true;
        }

        @Override
        protected List<String> currentRoles() {
            return roles;
        }

        @Override
        protected Object currentLoginId() {
            return 1L;
        }
    }

    @Nested
    @DisplayName("requireWrite：日常处置，ADMIN 与 OPS 都放行")
    class Write {

        @Test
        @DisplayName("ADMIN 放行")
        void adminAllowed() {
            assertThatCode(() -> new TestGuard("ADMIN").requireWrite())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPS 放行 —— 值班处置是运维的本职工作，挡住等于逼人绕开系统")
        void opsAllowed() {
            // 与前端 OPERATOR_PERMISSIONS（ticket:write / alert:write 授给 operator）双向一致
            assertThatCode(() -> new TestGuard("OPS").requireWrite())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("无角色被拒，且提示写清需要哪些角色")
        void noRoleRejected() {
            assertThatThrownBy(() -> new TestGuard().requireWrite())
                    .isInstanceOf(OpsWriteGuard.OpsWriteForbiddenException.class)
                    .hasMessageContaining("ADMIN")
                    .hasMessageContaining("OPS");
        }

        @Test
        @DisplayName("未知角色（含 VIEWER）被拒 —— 白名单放行，新角色默认无写权限")
        void unknownRoleRejected() {
            // 前端把未知角色折进空权限码集合，后端同姿态：fail-closed
            assertThatThrownBy(() -> new TestGuard("VIEWER").requireWrite())
                    .isInstanceOf(OpsWriteGuard.OpsWriteForbiddenException.class);
            assertThatThrownBy(() -> new TestGuard("GUEST").requireWrite())
                    .isInstanceOf(OpsWriteGuard.OpsWriteForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("requireAdmin：删除类，仅 ADMIN")
    class Admin {

        @Test
        @DisplayName("ADMIN 放行")
        void adminAllowed() {
            assertThatCode(() -> new TestGuard("ADMIN").requireAdmin())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPS 被拒 —— 与 requireWrite 的关键分野")
        void opsRejected() {
            // 与 Write.opsAllowed 构成分叉：若两级实现相同必挂其一
            assertThatThrownBy(() -> new TestGuard("OPS").requireAdmin())
                    .isInstanceOf(OpsWriteGuard.OpsWriteForbiddenException.class)
                    .hasMessageContaining("不可恢复");
        }

        @Test
        @DisplayName("无角色被拒")
        void noRoleRejected() {
            assertThatThrownBy(() -> new TestGuard().requireAdmin())
                    .isInstanceOf(OpsWriteGuard.OpsWriteForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("错误码与登录校验")
    class Contract {

        @Test
        @DisplayName("异常携带 40103，与 BizError.NO_PERMISSION 一致")
        void carriesForbiddenCode() {
            // 前端 bizCode.ts 对 40103 已有文案；换码会让用户落进通用兜底提示
            try {
                new TestGuard("OPS").requireAdmin();
                org.junit.jupiter.api.Assertions.fail("应当抛出权限异常");
            } catch (OpsWriteGuard.OpsWriteForbiddenException e) {
                assertThat(e.code()).isEqualTo(40103);
            }
        }

        @Test
        @DisplayName("requireWrite 先校验登录")
        void writeChecksLogin() {
            TestGuard g = new TestGuard("ADMIN");
            g.requireWrite();
            assertThat(g.loginChecked).isTrue();
        }

        @Test
        @DisplayName("requireAdmin 先校验登录")
        void adminChecksLogin() {
            TestGuard g = new TestGuard("ADMIN");
            g.requireAdmin();
            assertThat(g.loginChecked).isTrue();
        }
    }
}
