package com.devops.agent.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子管理员凭据的安全约定测试。
 *
 * <h3>修复的缺陷：初始密码被明文打进日志</h3>
 * {@code AuthDataInitializer} 原先这样写：
 * <pre>{@code
 * log.warn("已创建种子管理员 | username={} | 默认密码={} ...", seedUsername, seedPassword);
 * }</pre>
 *
 * <p>后果不是「日志难看」。日志会被采集到 ELK / 对象存储 / 监控平台，
 * <b>这些系统的访问面通常远宽于数据库</b>——一个只有看板权限的人
 * 也能搜到管理员密码。而且日志一旦归档就<b>收不回来</b>：
 * 即便运维事后改了密码，历史日志里那条记录仍然有效地泄露了
 * 「这套系统的初始密码是什么」，而多数部署根本不会改。</p>
 *
 * <h3>为什么不是简单地把日志删掉</h3>
 * 删掉之后本地开发起不来会不知道密码。折中是：
 * <b>只在「用的是内置默认密码」时提示去查文档</b>，
 * 用了自定义密码则什么都不说（说明部署方已经知道自己设了什么）。
 *
 * <h3>连带修复：生产 profile 未收紧种子账号</h3>
 * 默认配置是 {@code seed.enabled=true} + {@code password=admin123}。
 * 生产部署若没设 {@code AUTH_SEED_PASSWORD}，启动时会自动建出
 * 用户名 admin / 密码 admin123 的<b>管理员</b>——一个在源码里公开可查的弱口令。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("种子管理员凭据安全")
class SeedCredentialSafetyTest {

    private static final Path INITIALIZER = Path.of(
            "src/main/java/com/devops/agent/domain/auth/AuthDataInitializer.java");
    private static final Path PROD_YML = Path.of("src/main/resources/application-prod.yml");

    /**
     * 读文件并剔除注释行——注释里常有「反例字面量」，会让断言恒假。
     *
     * <p>必须同时剔除 Java（{@code //} {@code *}）与 YAML（{@code #}）两种注释：
     * 本类既扫 .java 也扫 .yml，而生产配置的注释里正好写着
     * 「默认密码是 admin123」这样的说明文字。
     * 只按 Java 风格过滤时 {@code doesNotContain("admin123")} 会恒假——
     * 本地预跑时实测撞到过。</p>
     */
    private static String codeLinesOf(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.startsWith("//") && !l.startsWith("*")
                            && !l.startsWith("/*") && !l.startsWith("#"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + p, e);
        }
    }

    @Nested
    @DisplayName("密码不得进入日志")
    class NoPasswordInLogs {

        @Test
        @DisplayName("日志语句中不得出现 seedPassword 变量")
        void logStatementsDoNotReferencePassword() {
            // ── 本类最重要的一条 ──────────────────────────────
            // 逐行检查：凡是 log.xxx( 开头的语句，其参数里不得带 seedPassword。
            // 只断言「整个文件不含某字符串」是不行的——encodePassword(seedPassword)
            // 这一行是必须保留的正常用法
            List<String> offenders;
            try {
                offenders = Files.readAllLines(INITIALIZER, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                        .filter(l -> l.contains("log.") && l.contains("seedPassword"))
                        .toList();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            assertThat(offenders)
                    .as("日志会被采集到访问面远宽于数据库的系统里，且归档后收不回来。"
                            + "即便事后改密，历史日志仍泄露了「这套系统的初始密码是什么」")
                    .isEmpty();
        }

        @Test
        @DisplayName("密码仍然被正常用于 BCrypt 编码——不能为了不打日志把功能删掉")
        void passwordStillUsedForEncoding() {
            // 防「为了让上面那条通过，把整个 seedPassword 都删了」这种改法
            assertThat(codeLinesOf(INITIALIZER))
                    .contains("encodePassword(seedPassword)");
        }

        @Test
        @DisplayName("仍保留可发现性：用内置默认密码时给出提示")
        void stillWarnsWhenUsingBuiltinDefault() {
            // 完全不提示会让本地开发起不来时无从下手。
            // 折中是提示「当前在用默认密码，去查文档」，而不是直接打出密码
            String code = codeLinesOf(INITIALIZER);
            assertThat(code)
                    .as("需要按「是否仍在用内置默认值」分支给出不同提示")
                    .contains("BUILTIN_DEFAULT_PASSWORD");
        }
    }

    @Nested
    @DisplayName("生产 profile 收紧种子账号")
    class ProdHardening {

        @Test
        @DisplayName("生产必须显式要求 AUTH_SEED_PASSWORD，不接受内置默认值")
        void prodRequiresExplicitPassword() {
            String prod = codeLinesOf(PROD_YML);

            assertThat(prod)
                    .as("生产未覆盖种子密码时，会自动建出 admin/admin123 的管理员账号——"
                            + "一个在源码里公开可查的弱口令，且拥有全部权限")
                    .contains("AUTH_SEED_PASSWORD");
            assertThat(prod)
                    .as("占位值应当是一个明显无法当密码用的串，"
                            + "使未配置时退化为「账号存在但登不进」而非「谁都能进」")
                    .contains("__MUST_SET_AUTH_SEED_PASSWORD__");
        }

        @Test
        @DisplayName("生产 profile 里不得出现内置默认密码字面量")
        void prodDoesNotContainBuiltinPassword() {
            assertThat(codeLinesOf(PROD_YML))
                    .as("生产配置里出现 admin123 等同于把弱口令固化进部署产物")
                    .doesNotContain("admin123");
        }
    }
}
