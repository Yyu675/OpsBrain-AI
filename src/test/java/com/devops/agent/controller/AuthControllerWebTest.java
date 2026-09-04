package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.auth.AuthService;
import com.devops.agent.domain.auth.User;
import com.devops.agent.domain.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} HTTP 契约测试。
 *
 * <h3>为什么登录端点的校验比别处更要紧</h3>
 * {@code /api/v1/auth/**} 在鉴权白名单里——<b>任何人都能调，不需要登录</b>。
 * 这让它成为唯一一个「未认证流量直达业务代码」的入口，
 * 也因此它的入参约束是<b>安全措施</b>而不是体验优化：
 *
 * <ul>
 *   <li><b>长度上限是防 DoS 的</b>。BCrypt 是刻意设计的慢哈希，
 *       成本随输入增长。不限长时，攻击者可提交超长字符串迫使服务端
 *       反复做昂贵的哈希运算，<b>少量并发就能耗尽 CPU</b>。
 *       {@code @Size(max=64/128)} 就是拦这个的——
 *       删掉它编译照样通过、功能照样正常，只有被打的时候才知道；</li>
 *   <li><b>响应绝不能含 password 字段</b>。{@code toUserView} 手工挑字段
 *       而不是直接序列化 {@link User}，正是为此。
 *       哪天有人图省事改成返回实体，BCrypt 哈希就随登录响应发给了客户端。</li>
 * </ul>
 *
 * <h3>测试边界：不覆盖 Sa-Token 的会话行为</h3>
 * {@code StpUtil.login()} / {@code isLogin()} / {@code logout()} 是静态调用，
 * 依赖完整的 Sa-Token 运行时（Redis + 配置），而 {@code @WebMvcTest}
 * 切片不加载它。因此本类<b>只覆盖到 Sa-Token 之前的那一段</b>：
 * 参数校验、密码校验失败的映射、以及不涉及会话的响应结构。
 *
 * <p>登录成功后的 token 签发、{@code /me} 的登录态判定、{@code /logout}
 * 的服务端失效，都需要真实 Sa-Token 上下文，应由集成测试覆盖。
 * <b>把它们硬塞进切片测试只会得到一堆断言「抛异常了」的假测试</b>，
 * 看着覆盖率上去了，实际什么都没保证。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.devops.agent.controller.config.WebConfig.class,
                        com.devops.agent.common.audit.OperationAuditInterceptor.class
                }),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, com.devops.agent.common.web.TraceIdFilter.class})
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private String loginBody(String username, String password) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("password", password);
        return objectMapper.writeValueAsString(m);
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ==================================================================

    @Nested
    @DisplayName("入参校验（免鉴权端点的第一道防线）")
    class Validation {

        @Test
        @DisplayName("用户名为空 → 40001，且不去查库、不做 BCrypt")
        void blankUsernameRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("   ", "secret123")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("用户名")));

            // 校验必须在触达业务之前完成——否则空用户名也会走一遍查库
            verify(authService, never()).login(anyString(), anyString());
        }

        @Test
        @DisplayName("密码为空 → 40001")
        void blankPasswordRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(authService, never()).login(anyString(), anyString());
        }

        @Test
        @DisplayName("超长用户名（>64）被拒 —— 上限对齐 sys_user.username VARCHAR(64)")
        void oversizedUsernameRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(repeat('a', 65), "secret123")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(authService, never()).login(anyString(), anyString());
        }

        @Test
        @DisplayName("超长密码（>128）被拒 —— 这条是防 BCrypt DoS，不是体验优化")
        void oversizedPasswordRejected() throws Exception {
            // BCrypt 成本随输入增长，不限长时少量并发即可耗尽 CPU。
            // 删掉 @Size 编译照样过、功能照样正常，只有被打的时候才知道，
            // 所以必须有测试钉住
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", repeat('x', 10_000))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(authService, never()).login(anyString(), anyString());
        }

        @Test
        @DisplayName("边界值放行：用户名 64、密码 128 恰好合法")
        void boundaryLengthsAccepted() throws Exception {
            when(authService.login(anyString(), anyString()))
                    .thenThrow(new AuthService.AuthException("用户名或密码错误"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(repeat('a', 64), repeat('x', 128))))
                    .andExpect(status().isOk())
                    // 走到了业务层（返回 40100 而非 40001），说明校验放行了
                    .andExpect(jsonPath("$.code").value(40100));

            verify(authService).login(eq(repeat('a', 64)), eq(repeat('x', 128)));
        }

        @Test
        @DisplayName("请求体为空或 JSON 畸形 → 400，而不是 500")
        void malformedBodyIsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ this is not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("GET 打到登录端点 → 405，不是 500")
        void wrongMethodIsMethodNotAllowed() throws Exception {
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/v1/auth/login"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("登录失败的语义")
    class LoginFailure {

        @Test
        @DisplayName("密码错误 → 40100，且响应不泄漏「用户是否存在」")
        void wrongPasswordMapsTo40100() throws Exception {
            // AuthService 对「用户不存在」与「密码错误」返回同一句话，
            // 避免账号枚举。控制器必须原样透传，不能自作主张细化
            when(authService.login(anyString(), anyString()))
                    .thenThrow(new AuthService.AuthException("用户名或密码错误"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", "wrong-password")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40100))
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                    // 失败响应里绝不能有 data（更不能有用户信息）
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("账号停用给出明确提示 —— 与「密码错误」区分是刻意的")
        void disabledAccountHasDistinctMessage() throws Exception {
            // 这不涉及账号枚举风险：账号确实存在，且停用是管理动作，
            // 让用户知道「去找管理员」比让他反复试密码有用得多
            when(authService.login(anyString(), anyString()))
                    .thenThrow(new AuthService.AuthException("账号已停用，请联系管理员"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("leaver", "correct-password")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40100))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("停用")));
        }

        @Test
        @DisplayName("未预期异常 → 50001，且不透传内部细节")
        void unexpectedErrorMapsTo50001() throws Exception {
            when(authService.login(anyString(), anyString()))
                    .thenThrow(new RuntimeException(
                            "org.postgresql.util.PSQLException: relation \"sys_user\" does not exist"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", "secret123")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50001))
                    // 表名、驱动类名不能出现在响应里——对攻击者是信息泄漏
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("sys_user"))))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("PSQLException"))));
        }

        @Test
        @DisplayName("任何失败响应都不得出现 password 字段")
        void failureResponseNeverLeaksPassword() throws Exception {
            when(authService.login(anyString(), anyString()))
                    .thenThrow(new AuthService.AuthException("用户名或密码错误"));

            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", "hunter2")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("password"))))
                    // 用户提交的明文口令也不能被回显
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("hunter2"))))
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(body).doesNotContain("$2a$");
        }
    }
}
