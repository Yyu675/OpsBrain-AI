package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 文档端点的暴露面契约（P0-2）。
 *
 * <h3>为什么这条要写成测试</h3>
 * <p>
 * springdoc 的两个路径 —— {@code /v3/api-docs} 与 {@code /swagger-ui/**} ——
 * <b>不在 {@code /api/**} 之下</b>，而 Sa-Token 拦截器只注册了 {@code /api/**}。
 * 也就是说这两个端点<b>不受登录校验保护</b>。
 * </p>
 * <p>
 * 开发环境这是便利；生产环境这是<b>把 130 个端点的结构、参数、示例
 * 无鉴权地公开</b>——对攻击者是一份现成的攻击面地图。
 * </p>
 * <p>
 * 防线是 {@code application-prod.yml} 里的 {@code enabled: false}。
 * 它是一行 YAML，删掉不会有任何编译或运行时报错，
 * <b>只是线上悄悄多了两个公开端点</b>。本测试把它钉住。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@DisplayName("OpenAPI 暴露面契约")
class OpenApiExposureContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path POM = Path.of("pom.xml");

    @Test
    @DisplayName("生产 profile 必须同时关闭 api-docs 与 swagger-ui")
    void productionDisablesBothEndpoints() throws IOException {
        String prod = Files.readString(RESOURCES.resolve("application-prod.yml"),
                StandardCharsets.UTF_8);

        // 只判「有 springdoc 段」不够——段在但值为 true 一样是裸奔。
        // 故断言落在两个 enabled 的实际取值上
        assertThat(springdocFlag(prod, "api-docs"))
                .as("生产环境 springdoc.api-docs.enabled 必须为 false。"
                        + "Sa-Token 拦截器只管 /api/**，而 /v3/api-docs 不在其下——"
                        + "开着等于无鉴权公开全部端点的结构与参数")
                .isEqualTo("false");

        assertThat(springdocFlag(prod, "swagger-ui"))
                .as("生产环境 springdoc.swagger-ui.enabled 必须为 false。"
                        + "只关 api-docs 不够：Swagger UI 页面本身也会暴露端点清单")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("默认 profile 的开关可由环境变量覆盖 —— 不写死 true")
    void defaultProfileIsOverridable() throws IOException {
        String main = Files.readString(RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        // 写死 true 会让「临时关掉文档」只能改代码重新发版。
        // 用 ${SPRINGDOC_ENABLED:true} 则运维可用环境变量即时关闭
        assertThat(springdocFlag(main, "api-docs"))
                .as("主配置的 api-docs.enabled 应可由环境变量覆盖（形如 ${SPRINGDOC_ENABLED:true}），"
                        + "写死 true 会让临时关闭文档只能改代码重新发版")
                .contains("SPRINGDOC_ENABLED");
    }

    @Test
    @DisplayName("springdoc 版本锁定在与 Spring Boot 3.5.x 匹配的基线上")
    void versionMatchesSpringBootBaseline() throws IOException {
        String pom = Files.readString(POM, StandardCharsets.UTF_8);

        Matcher m = Pattern.compile(
                "<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>\\s*"
                        + "<version>([^<]+)</version>")
                .matcher(pom);
        assertThat(m.find())
                .as("pom.xml 里找不到 springdoc-openapi-starter-webmvc-ui 的显式版本。"
                        + "springdoc 不在 Spring Boot BOM 管理范围内，必须显式锁版本")
                .isTrue();

        String version = m.group(1).trim();

        // 版本不是随手挑最新：
        // - springdoc 官方版本表给 Spring Boot [3.5.0, 4.0.0) 的基线是 2.8.15；
        // - 2.8.1~2.8.12 与 Spring Boot 3.5.x 有已知不兼容；
        // - 3.x 系列是给 Spring Boot 4 的，装上会启动失败。
        // 升级时请先查官方版本表，别只看「有没有更新的」
        assertThat(version)
                .as("当前 Spring Boot 是 3.5.6，springdoc 应使用 2.8.x 系列"
                        + "（3.x 系列对应 Spring Boot 4，装上会启动失败）。"
                        + "实测版本：%s", version)
                .startsWith("2.8.");

        int patch = Integer.parseInt(version.substring("2.8.".length()));
        assertThat(patch)
                .as("2.8.1~2.8.12 与 Spring Boot 3.5.x 有已知不兼容（社区实测），"
                        + "应使用官方版本表给出的 3.5.x 基线 2.8.15 或更高")
                .isGreaterThanOrEqualTo(13);
    }

    @Test
    @DisplayName("扫描范围限定在业务控制器包 —— 不把框架端点也写进契约")
    void scanScopeLimitedToBusinessControllers() throws IOException {
        String main = Files.readString(RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        // 不限定的话 actuator 等框架端点会混进契约，
        // 前端据此生成类型时会多出一堆用不上的定义
        assertThat(main)
                .as("应配置 springdoc.packages-to-scan 限定扫描范围")
                .contains("packages-to-scan");
        assertThat(main).contains("com.devops.agent.controller");
    }

    // ==================== 辅助 ====================

    /**
     * 取 {@code springdoc.<section>.enabled} 的原始文本值。
     *
     * <p>按缩进层级解析而非全文搜 {@code enabled:}——后者会命中
     * 配置文件里其它模块的同名键（本项目有十几个 {@code enabled} 开关）。
     * 这是 85 号「匹配范围过宽命中别处同名内容」的同型防范。</p>
     *
     * @return 原始值文本；找不到时返回 {@code null}
     */
    private static String springdocFlag(String yaml, String section) {
        int root = yaml.indexOf("\nspringdoc:");
        if (root < 0) {
            return null;
        }
        // springdoc 段到下一个顶层键（行首非空白）为止
        Matcher next = Pattern.compile("(?m)^[a-zA-Z]").matcher(yaml);
        int end = yaml.length();
        if (next.find(root + "\nspringdoc:".length())) {
            end = next.start();
        }
        String block = yaml.substring(root, end);

        Matcher m = Pattern.compile(
                "(?m)^\\s{2}" + Pattern.quote(section) + ":\\s*$\\n((?:^\\s{4,}.*$\\n?)*)")
                .matcher(block);
        if (!m.find()) {
            return null;
        }
        Matcher flag = Pattern.compile("(?m)^\\s+enabled:\\s*(\\S+)\\s*$").matcher(m.group(1));
        return flag.find() ? flag.group(1) : null;
    }
}
