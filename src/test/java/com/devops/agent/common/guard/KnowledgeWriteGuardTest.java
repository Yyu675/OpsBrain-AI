package com.devops.agent.common.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库写权限守卫的分级契约（F-5）。
 *
 * <h3>要防住什么</h3>
 * <p>
 * 知识库是 <b>AI 回答的事实来源</b>。修复前 4 个知识库控制器共
 * 16 个写端点、<b>15 个无任何角色校验</b>——任何登录用户都能改，
 * 等于任何人都能污染 AI 的输出。而被污染的答案还带着「引用出处」，
 * 使用者根本不会怀疑。
 * </p>
 *
 * <h3>分级的两侧都要测</h3>
 * <p>
 * 只测「无权限被拒」不够——那样把守卫写成「一律拒绝」也能通过，
 * 而那会让一线运维完全无法沉淀知识（<b>知识资产是本产品的差异化核心，
 * 没人写就没有资产</b>）。故每一级都同时测「该放行的放行」
 * 与「该拒绝的拒绝」，两侧夹住。
 * </p>
 *
 * <h3>为什么用测试子类而非 mockStatic</h3>
 * <p>
 * {@code StpUtil} 的状态来自 ThreadLocal 请求上下文，单测里没有真实请求。
 * 常见做法是 {@code Mockito.mockStatic}，但它依赖 {@code mockito-inline}，
 * 而<b>本项目从未引入过</b>——赌它可用是没必要的风险（同 {@code SaMode} 那次判断）。
 * 故生产代码把会话访问抽成 {@code protected} 接缝，测试子类覆写即可，
 * 零新依赖，也没有静态 mock 泄漏到同 JVM 其它测试的隐患。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@DisplayName("知识库写权限守卫")
class KnowledgeWriteGuardTest {

    /** 可控会话的守卫：角色与登录态由用例指定 */
    private static class TestGuard extends KnowledgeWriteGuard {
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
    @DisplayName("requireEdit：可逆操作，ADMIN 与 OPS 都放行")
    class Edit {

        @Test
        @DisplayName("ADMIN 放行")
        void adminAllowed() {
            assertThatCode(() -> new TestGuard("ADMIN").requireEdit())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPS 放行 —— 一线运维必须能沉淀知识，否则知识库不会有内容")
        void opsAllowed() {
            // 这条是分级设计的核心。若把知识库写权限全收给 ADMIN，
            // 「知识资产是差异化护城河」就成了空话——没人写就没有资产
            assertThatCode(() -> new TestGuard("OPS").requireEdit())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("无角色被拒，且提示写清需要哪些角色")
        void noRoleRejected() {
            assertThatThrownBy(() -> new TestGuard().requireEdit())
                    .isInstanceOf(KnowledgeWriteGuard.KnowledgeWriteForbiddenException.class)
                    .hasMessageContaining("ADMIN")
                    .hasMessageContaining("OPS");
        }

        @Test
        @DisplayName("未知角色被拒 —— 不能因为「有角色」就放行")
        void unknownRoleRejected() {
            // 防「只判 roles 非空」这类实现
            assertThatThrownBy(() -> new TestGuard("GUEST").requireEdit())
                    .isInstanceOf(KnowledgeWriteGuard.KnowledgeWriteForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("requireDestructive：不可逆操作，仅 ADMIN")
    class Destructive {

        @Test
        @DisplayName("ADMIN 放行")
        void adminAllowed() {
            assertThatCode(() -> new TestGuard("ADMIN").requireDestructive())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPS 被拒 —— 与 requireEdit 的关键分野")
        void opsRejected() {
            // 与 Edit.opsAllowed 构成分叉：若两个方法实现相同
            //（都放行 OPS 或都只放 ADMIN），必挂其一
            assertThatThrownBy(() -> new TestGuard("OPS").requireDestructive())
                    .isInstanceOf(KnowledgeWriteGuard.KnowledgeWriteForbiddenException.class)
                    .hasMessageContaining("不可逆");
        }

        @Test
        @DisplayName("无角色被拒")
        void noRoleRejected() {
            assertThatThrownBy(() -> new TestGuard().requireDestructive())
                    .isInstanceOf(KnowledgeWriteGuard.KnowledgeWriteForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("错误码与登录校验")
    class Contract {

        @Test
        @DisplayName("异常携带 40103，与 BizError.NO_PERMISSION 一致")
        void carriesForbiddenCode() {
            // 前端 bizCode.ts 里 40103 已有文案「权限不足 · 如需访问请联系管理员开通」。
            // 用别的码会让用户落进通用兜底提示（96 号同型问题）
            try {
                new TestGuard("OPS").requireDestructive();
                org.junit.jupiter.api.Assertions.fail("应当抛出权限异常");
            } catch (KnowledgeWriteGuard.KnowledgeWriteForbiddenException e) {
                assertThat(e.code()).isEqualTo(40103);
            }
        }

        @Test
        @DisplayName("requireEdit 先校验登录")
        void editChecksLogin() {
            TestGuard g = new TestGuard("ADMIN");
            g.requireEdit();
            // 不校验登录的话，未登录用户会走到角色判断，
            // 而未登录时 getRoleList 的行为依赖 Sa-Token 配置，不可依赖
            assertThat(g.loginChecked).isTrue();
        }

        @Test
        @DisplayName("requireDestructive 先校验登录")
        void destructiveChecksLogin() {
            TestGuard g = new TestGuard("ADMIN");
            g.requireDestructive();
            assertThat(g.loginChecked).isTrue();
        }
    }
}
