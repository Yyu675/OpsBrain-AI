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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理类端点的<b>角色边界</b>集成测试。
 *
 * <h3>为什么必须单独写这一个类</h3>
 * 所有 {@code *ControllerWebTest} 都是 {@code @WebMvcTest} 切片，
 * 而切片<b>刻意排除了 {@code WebConfig}</b>——Sa-Token 的注解拦截器
 * 注册在那里，于是 {@code @SaCheckRole("ADMIN")} 在切片里<b>根本不会执行</b>。
 *
 * <p>那些切片测试的类注释都写明了这一点：「本类的请求都是已放行状态，
 * 不构成对权限的任何保证」。也就是说，
 * <b>此前整个项目没有一行代码验证过角色注解真的生效</b>——
 * 注解写错了、漏写了、或被某次重构删掉了，CI 全绿照旧。</p>
 *
 * <p>本类用 {@code @SpringBootTest + @AutoConfigureMockMvc}<b>不排除任何拦截器</b>，
 * 走完整的鉴权链：真实建号 → 真实 BCrypt 登录 → 带真 token 请求 →
 * 真实注解拦截器判定角色。这是唯一能证明「限权确实生效」的方式。</p>
 *
 * <h3>覆盖对象</h3>
 * 四个治理类控制器都标了 {@code @SaCheckRole("ADMIN")}，
 * 其中 {@code SagaController} 是上一轮才补上的——它此前是唯一的缺口，
 * 而它暴露的能力包括<b>逆向补偿</b>（回滚已落库的写操作）。
 * 本类同时验证「管理员能进」与「普通用户被挡」两个方向：
 * 只测前者，注解删掉了也发现不了；只测后者，可能是端点整个坏了而非权限起作用。
 *
 * @author OpsBrain AI
 * @since 2026-08-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("治理端点角色边界（真实鉴权链）")
class GovernanceRoleGuardIntegrationTest {

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

    private final String username = "rg_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RAW_PASSWORD = "RoleGuard#2026";

    /** 只读探测端点：验证角色边界不需要真的触发写操作 */
    private static final String SAGA_READ = "/api/v1/saga/attention";
    private static final String AUDIT_READ = "/api/v1/audit/filter-options";

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    private void seedUser(String role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(authService.encodePassword(RAW_PASSWORD));
        u.setDisplayName("角色边界测试用户");
        u.setRole(role);
        u.setStatus("ACTIVE");
        userRepository.insert(u);
    }

    /** 登录取 token（走真实 BCrypt 校验与真实签发） */
    private Map<String, String> login() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", RAW_PASSWORD);

        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(
                res.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");

        Map<String, String> out = new LinkedHashMap<>();
        out.put("tokenName", String.valueOf(data.get("tokenName")));
        out.put("token", String.valueOf(data.get("token")));
        return out;
    }

    // ==================================================================

    @Test
    @DisplayName("普通用户访问 Saga 治理端点 → 403 且业务码 40103")
    void nonAdminIsBlockedFromSaga() throws Exception {
        seedUser("OPS");
        Map<String, String> t = login();

        // Saga 端点会列出所有半残事务的业务主键与失败原因，
        // 并能触发逆向补偿（回滚已落库的写操作）。
        // 它此前是四个治理控制器里唯一没有角色注解的，上一轮才补上——
        // 本用例就是那次修复的回归防线
        mockMvc.perform(get(SAGA_READ).header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isForbidden())
                // 403 而非 401：已登录但角色不够，两者的处置方式完全不同
                // （前者找管理员要权限，后者重新登录）
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("管理员访问 Saga 治理端点 → 放行")
    void adminPassesSaga() throws Exception {
        seedUser("ADMIN");
        Map<String, String> t = login();

        // 反向验证不可省：只测「普通用户被挡」的话，
        // 端点整个坏掉（比如路径写错返回 404/500）也会「看起来像权限生效」
        mockMvc.perform(get(SAGA_READ).header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("普通用户访问审计日志端点 → 403")
    void nonAdminIsBlockedFromAuditLogs() throws Exception {
        seedUser("OPS");
        Map<String, String> t = login();

        // 审计含操作者、IP、AI 问答原文，是全项目最敏感的数据
        mockMvc.perform(get(AUDIT_READ).header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("管理员访问审计日志端点 → 放行")
    void adminPassesAuditLogs() throws Exception {
        seedUser("ADMIN");
        Map<String, String> t = login();

        mockMvc.perform(get(AUDIT_READ).header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("不带 token 访问治理端点 → 401，且与 403 区分开")
    void anonymousGetsUnauthorizedNotForbidden() throws Exception {
        // 未登录与角色不足必须是两个不同的响应：
        // 前端据此决定「跳登录页」还是「提示联系管理员」。
        // 都返回 403 的话，token 过期的用户会被告知「权限不足」，
        // 然后一直等管理员给他加权限——而他其实只需要重新登录
        mockMvc.perform(get(SAGA_READ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("角色判定基于服务端会话，伪造 token 无效")
    void forgedTokenIsRejected() throws Exception {
        seedUser("ADMIN");
        Map<String, String> t = login();

        // 拿到合法的 tokenName，但塞一个伪造值。
        // 若角色判定读的是客户端传来的内容而非服务端会话，这里会被放行
        mockMvc.perform(get(SAGA_READ).header(t.get("tokenName"), "forged-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 写端点边界
    //
    // 上面六例全部打在**只读**端点上。那是刻意的第一步：
    // 先证明鉴权链本身通了，不引入「测试把数据改坏」的风险。
    //
    // 但只测只读端点留下了一个真实缺口：
    // <b>`@SaCheckRole` 标在类上，覆盖范围是否真的包含写方法？</b>
    // 若哪天有人给某个写方法单独加了 `@SaIgnore`（这是 Sa-Token 提供的
    // 方法级豁免注解，一行就能加、review 时极不显眼），
    // 只读端点的六例照样全绿，而逆向补偿对所有登录用户敞开。
    //
    // 这批用例把边界推到写端点上。做法是**挑选对不存在的 ID 天然无副作用
    // 的写操作**，不需要预先造可回滚的测试数据：
    //   - `POST /saga/{id}/compensate` —— sagaId 查不到待补偿步骤时
    //     直接返回 noop（SagaCompensationManager 第 58-62 行）；
    //   - `DELETE /governance/policies/{id}` —— 服务层先 findById，
    //     不存在则抛 IllegalArgumentException，不会走到 delete；
    //   - `POST /approvals/{id}/reject` —— 审批单不存在时返回 40004。
    //
    // 三者都在「权限判定之后、真正写库之前」失败，
    // 因此能验证权限，又保证测试跑完数据库状态不变。
    // ==================================================================

    /** 写端点：逆向补偿。用随机 sagaId——查不到待补偿步骤，天然 noop */
    private static final String SAGA_COMPENSATE = "/api/v1/saga/{id}/compensate";

    /** 写端点：删除自动化策略。用不存在的 id——服务层 findById 先抛错，不会真删 */
    private static final String POLICY_DELETE = "/api/v1/governance/policies/{id}";

    @Test
    @DisplayName("普通用户触发 Saga 逆向补偿 → 403，挡在执行之前")
    void nonAdminCannotTriggerCompensation() throws Exception {
        seedUser("OPS");
        Map<String, String> t = login();

        // 这是本项目权限敏感度最高的一个写端点：它会回滚**已经落库**的写操作
        // （如作废已创建的工单）。补偿动作本身幂等，但「谁都能触发一次回滚」
        // 本身就是问题——普通用户对着一个 sagaId 反复调用，
        // 就能把别人刚建好的单据撤掉
        mockMvc.perform(post(SAGA_COMPENSATE, "saga-" + UUID.randomUUID())
                        .header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("管理员触发 Saga 逆向补偿 → 放行（无待补偿步骤时 noop）")
    void adminCanTriggerCompensation() throws Exception {
        seedUser("ADMIN");
        Map<String, String> t = login();

        // 反向验证：证明 403 来自角色判定，而不是「这个写端点整个不通」。
        // 随机 sagaId 查不到任何待补偿步骤，compensateSaga 走 noop 分支返回，
        // 数据库状态不变——所以这一例可以放心跑在真实库上
        mockMvc.perform(post(SAGA_COMPENSATE, "saga-" + UUID.randomUUID())
                        .header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.compensatedCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0));
    }

    @Test
    @DisplayName("普通用户删除自动化策略 → 403，挡在 findById 之前")
    void nonAdminCannotDeletePolicy() throws Exception {
        seedUser("OPS");
        Map<String, String> t = login();

        // DELETE 也必须被类级注解覆盖。用不存在的 id：
        // 若权限没生效，请求会走到服务层并因「策略不存在」返回业务错误码，
        // 那时状态码是 200 而非 403——两者区分得很干净，不会误判
        mockMvc.perform(delete(POLICY_DELETE, 99_999_999L)
                        .param("version", "0")
                        .header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("普通用户驳回审批 → 403，审批权不因登录而获得")
    void nonAdminCannotRejectApproval() throws Exception {
        seedUser("OPS");
        Map<String, String> t = login();

        // 审批是高危动作放行的最后一道闸。若普通用户能驳回，
        // 也就意味着他能批准——那道闸等于不存在
        mockMvc.perform(post("/api/v1/approvals/{id}/reject", 99_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"角色边界测试\"}")
                        .header(t.get("tokenName"), t.get("token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("不带 token 触发逆向补偿 → 401，未登录与角色不足仍区分")
    void anonymousCannotTriggerCompensation() throws Exception {
        // 只读端点已验过这条边界，写端点单独再验一次：
        // 登录拦截器与角色拦截器是两个不同的组件，
        // 前者若漏配了写路径的匹配规则，未登录请求会直接落到角色判定上
        mockMvc.perform(post(SAGA_COMPENSATE, "saga-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
