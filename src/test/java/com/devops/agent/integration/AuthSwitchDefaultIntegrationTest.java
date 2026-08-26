package com.devops.agent.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权开关的<b>安全默认值</b>回归测试。
 *
 * <h3>为什么需要这个类</h3>
 * 2026-08-26 出现过一次「为 UI 预览把 {@code WebConfig} 里的登录拦截器
 * 整段注释掉」并提交的改动。后果不是「少了一层登录」——
 * 拦截器不注册时 Sa-Token 上下文里没有会话，
 * {@code @SaCheckRole} 的角色判定<b>也一并失效</b>，整条鉴权链被摘除。
 *
 * <p>那次问题被 {@link GovernanceRoleGuardIntegrationTest} 抓到了
 * （403 变成 200），但那是<b>侧面撞上</b>的：那批用例本意是验角色边界，
 * 不是验「鉴权有没有被整体关掉」。如果当时恰好没有治理端点的用例，
 * 这个改动会一路合进生产。</p>
 *
 * <p>本类把这件事变成<b>正面断言</b>：不依赖任何业务端点，
 * 只回答一个问题——<b>在不做任何配置的默认状态下，未登录请求是否被拦</b>。</p>
 *
 * <h3>为什么不测「关闭开关后放行」</h3>
 * 那需要用 {@code @TestPropertySource} 起一个 auth-enabled=false 的独立上下文。
 * 它验证的是「不安全配置确实不安全」——没有防护价值，
 * 却会在测试套件里留下一个<b>鉴权全关</b>的上下文，
 * 万一被别的用例复用（Spring 上下文按配置缓存复用）反而制造隐患。
 * 安全类测试只钉安全的那一侧。
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("鉴权开关：默认必须开启（安全默认值回归）")
class AuthSwitchDefaultIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 直接读绑定后的值：断言「默认就是 true」，而不是「我在测试里设成了 true」 */
    @Value("${devops.security.auth-enabled:true}")
    private boolean authEnabled;

    @Test
    @DisplayName("不配置任何属性时，auth-enabled 解析为 true")
    void defaultsToEnabled() {
        // 这条守的是 application.yml 里的默认值与 @Value 兜底值。
        // 若有人把 yml 改成 false 提交，这里立刻红
        assertTrue(authEnabled,
                "devops.security.auth-enabled 默认必须为 true；"
                        + "本地免登录预览请用环境变量 AUTH_ENABLED=false，不要改默认值");
    }

    @Test
    @DisplayName("未登录访问受保护的读端点 → 401")
    void protectedGetRequiresLogin() throws Exception {
        // 用治理端点：它同时受登录拦截器与 @SaCheckRole 保护。
        // 未登录时应当止步于**登录校验**（401），而不是走到角色判定（403）——
        // 两者的区分本身就说明拦截器确实在链上
        mockMvc.perform(get("/api/v1/saga/attention"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问受保护的写端点 → 401，不得因方法不同而漏配")
    void protectedPostRequiresLogin() throws Exception {
        // 写端点单独验一次：addPathPatterns("/api/**") 是按路径而非方法匹配，
        // 但历史上出现过「只给读路径配拦截」的实现，值得钉住
        mockMvc.perform(post("/api/v1/saga/{id}/compensate", "saga-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("白名单端点仍免登录——探针不能被拦")
    void whitelistStaysOpen() throws Exception {
        // 反向验证：不能为了「更安全」把探针也拦了。
        // /api/v1/health/** 被拦会让 K8s 判定 Pod 永远 NotReady，
        // 那是把安全做成了可用性故障
        mockMvc.perform(get("/api/v1/health/ping"))
                .andExpect(status().isOk());
    }
}
