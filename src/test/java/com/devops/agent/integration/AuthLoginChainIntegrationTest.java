package com.devops.agent.integration;

import com.devops.agent.domain.auth.AuthService;
import com.devops.agent.domain.auth.User;
import com.devops.agent.domain.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权链路<b>端到端集成测试</b>。
 *
 * <h3>补的是切片测试明确覆盖不到的那一段</h3>
 * {@code AuthControllerWebTest} 在类注释里声明过：
 * {@code StpUtil.login()} / {@code isLogin()} / {@code logout()} 是静态调用，
 * 依赖完整的 Sa-Token 运行时（Redis + 配置），{@code @WebMvcTest} 切片不加载它，
 * 所以那组测试只覆盖到「Sa-Token 之前的那一段」。
 *
 * <p>本类把剩下的那段补上，跑在真实上下文里：
 * <b>登录 → 签发 token → 带 token 访问 /me → 登出 → token 立即失效</b>。</p>
 *
 * <h3>为什么这段必须端到端验证</h3>
 * 「登出」这件事有真假之分：
 * <ul>
 *   <li><b>假登出</b>——前端删掉本地 token 就算完。但那个 token 在服务端仍然有效，
 *       任何持有它的人（浏览器历史、日志、抓包）都能继续用；</li>
 *   <li><b>真登出</b>——服务端让 token 失效。</li>
 * </ul>
 * 两者在前端表现完全一样（都跳回登录页），<b>只有拿旧 token 再请求一次才能区分</b>。
 * 这正是本类最后一个用例做的事——而它无论如何都无法用 mock 验证，
 * 因为要验的恰恰是 Sa-Token 与 Redis 的真实交互。
 *
 * <h3>数据清理</h3>
 * 每个用例用随机用户名建账号，测完删除。不用 {@code @Transactional} 回滚——
 * Sa-Token 的会话写在 Redis 里，事务回滚管不到它，
 * 用回滚反而会造成「库里没这个用户但 Redis 里还有他的会话」的错位状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("鉴权链路端到端（登录 → 签发 → 校验 → 真登出）")
class AuthLoginChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String username = "it_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RAW_PASSWORD = "Integration#2026";

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    /** 建一个真实账号：密码走 AuthService 真实的 BCrypt 编码 */
    private User seedUser(String role, String status) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(authService.encodePassword(RAW_PASSWORD));
        u.setDisplayName("集成测试用户");
        u.setRole(role);
        u.setStatus(status);
        userRepository.insert(u);
        return userRepository.findByUsername(username).orElseThrow();
    }

    private String loginBody(String pwd) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("password", pwd);
        return objectMapper.writeValueAsString(m);
    }

    /** 登录并取回 token 与 tokenName */
    private Map<String, String> login(String pwd) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(pwd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(
                res.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");

        Map<String, String> out = new LinkedHashMap<>();
        out.put("token", String.valueOf(data.get("token")));
        out.put("tokenName", String.valueOf(data.get("tokenName")));
        return out;
    }

    // ==================================================================

    @Test
    @DisplayName("完整链路：登录签发 token → 带 token 访问 /me 拿到本人信息")
    void loginIssuesTokenAndMeReturnsCurrentUser() throws Exception {
        User seeded = seedUser("ADMIN", "ACTIVE");

        Map<String, String> session = login(RAW_PASSWORD);
        assertThat(session.get("token")).as("必须签发真实 token").isNotBlank();
        // tokenName 由后端下发，前端据此设置请求头名——写死在前端会与后端配置脱节
        assertThat(session.get("tokenName")).isNotBlank();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.get("tokenName"), session.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(seeded.getId().intValue()))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("登录成功的响应体里没有密码哈希 —— 端到端再确认一次")
    void loginResponseCarriesNoPasswordHash() throws Exception {
        seedUser("OPS", "ACTIVE");

        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        // 契约测试用 mock 的 User 验过一遍，这里用**真实库里的 BCrypt 哈希**再验一次：
        // toUserView 手工挑字段的做法一旦被改成序列化实体，这里会立刻红
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("$2a$");
        assertThat(body).doesNotContain(RAW_PASSWORD);
    }

    @Test
    @DisplayName("登录成功会刷新 last_login_at —— 它是排查「这个号最近有人用吗」的唯一依据")
    void loginRefreshesLastLoginAt() throws Exception {
        seedUser("OPS", "ACTIVE");
        assertThat(userRepository.findByUsername(username).orElseThrow().getLastLoginAt())
                .as("新建账号还没登录过").isNull();

        login(RAW_PASSWORD);

        assertThat(userRepository.findByUsername(username).orElseThrow().getLastLoginAt())
                .as("登录后必须落库，否则停用僵尸账号时无从判断").isNotNull();
    }

    @Test
    @DisplayName("不带 token 访问 /me → 40101，而不是把它当成某个默认用户")
    void meWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("伪造的 token 访问 /me → 40101")
    void meWithForgedTokenIsUnauthorized() throws Exception {
        seedUser("ADMIN", "ACTIVE");
        Map<String, String> session = login(RAW_PASSWORD);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.get("tokenName"), "obviously-not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("停用账号无法登录 —— 真实 BCrypt 密码正确也不行")
    void disabledAccountCannotLogIn() throws Exception {
        seedUser("OPS", "DISABLED");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("停用")));
    }

    @Test
    @DisplayName("密码错误无法登录（走真实 BCrypt 比对，不是 mock）")
    void wrongPasswordRejectedByRealBcrypt() throws Exception {
        seedUser("OPS", "ACTIVE");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(RAW_PASSWORD + "-wrong")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("登出是「真登出」：旧 token 立即失效，而不是只让前端删掉")
    void logoutInvalidatesTokenServerSide() throws Exception {
        seedUser("ADMIN", "ACTIVE");
        Map<String, String> session = login(RAW_PASSWORD);
        String tokenName = session.get("tokenName");
        String token = session.get("token");

        // 登出前：token 可用
        mockMvc.perform(get("/api/v1/auth/me").header(tokenName, token))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/v1/auth/logout").header(tokenName, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 登出后：**同一个 token** 必须立即失效。
        // 这条断言是本类存在的核心理由——「前端删 token」与「服务端失效 token」
        // 在界面上表现完全一样（都跳回登录页），只有拿旧 token 再请求一次才能区分。
        // 若这里仍返回 0，说明是假登出：那个 token 还在服务端有效，
        // 任何从日志/抓包/浏览器历史拿到它的人都能继续用
        mockMvc.perform(get("/api/v1/auth/me").header(tokenName, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("账号在登录后被停用：旧 token 应被拒绝并踢下线")
    void disablingUserInvalidatesExistingSession() throws Exception {
        User seeded = seedUser("OPS", "ACTIVE");
        Map<String, String> session = login(RAW_PASSWORD);

        // 管理员在后台停用该账号（模拟离职处理）
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = ?", seeded.getId());

        // 已签发的 token 不能继续通行——否则离职员工手里的 token
        // 会一直有效到自然过期，停用操作形同虚设
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.get("tokenName"), session.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("账号被删除后旧 token 同样失效")
    void deletedUserInvalidatesExistingSession() throws Exception {
        User seeded = seedUser("OPS", "ACTIVE");
        Map<String, String> session = login(RAW_PASSWORD);

        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", seeded.getId());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.get("tokenName"), session.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));

        Optional<User> gone = userRepository.findByUsername(username);
        assertThat(gone).isEmpty();
    }
}
