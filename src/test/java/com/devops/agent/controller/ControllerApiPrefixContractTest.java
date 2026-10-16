package com.devops.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台 API 路径契约测试（2026-10-01，缺陷类级钉子）。
 *
 * <h3>为什么要有</h3>
 * <ul>
 *   <li><b>2026-10-01 前</b>：{@code DiagnosisController} 类级前缀误为
 *       {@code /api/diagnosis}（缺 v1）——诊断回放/假设反馈（含知识 boost 回流）
 *       自 S2-3 起对前端全部 404；{@code ChangeEventController} 同病
 *       （{@code /api/changes}）。两者单类测试都是纯方法直调，
 *       <b>从不校验 URL</b>，契约漏网零成本出厂。</li>
 *   <li>本测试扫包断言「每个 @RestController 必须提供 /api/v1 入口」——
 *       把 03-API接口设计.md 头部那句「统一前缀：/api/v1」从文档约定
 *       变成可执行断言，新增控制器忘带 v1 会在 CI 直接红。</li>
 * </ul>
 *
 * <p>断言口径：类级 {@code @RequestMapping} 的值里<b>至少一个</b>以
 * {@code /api/v1} 开头——允许遗留兼容别名共存（如 {@code /api/changes}
 * 服务外部 CI 回调），但主入口必须在 v1 下。</p>
 */
@DisplayName("平台 API 前缀契约：每个控制器必须提供 /api/v1 入口")
class ControllerApiPrefixContractTest {

    /**
     * 例外名单（当前为空 = 零容忍）。确需例外的控制器进这里并写明原因——
     * 历史先例：diagnosis / changes 两例都是「没人想到会没人测」。
     */
    private static final List<String> ALLOW_NO_V1 = List.of();

    @Test
    void everyRestControllerExposesV1Entry() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // include 过滤 @RestController（本 Spring 版本无 addAnnotationFilter 签名，用通用 include 等价）
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var beans = scanner.findCandidateComponents("com.devops.agent.controller");
        assertThat(beans).as("扫描目标包必须非空——包名挪动时本断言先于静默通过报警").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (BeanDefinition bean : beans) {
            Class<?> controller;
            try {
                controller = Class.forName(bean.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("控制器类不可加载: " + bean.getBeanClassName(), e);
            }
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            String[] values = (mapping == null || mapping.value().length == 0)
                    ? new String[0] : mapping.value();
            boolean hasV1 = Arrays.stream(values)
                    .anyMatch(v -> v != null && v.startsWith("/api/v1"));
            if (!hasV1 && !ALLOW_NO_V1.contains(controller.getSimpleName())) {
                violations.add(controller.getSimpleName() + " -> " + Arrays.toString(values));
            }
        }
        assertThat(violations)
                .as("类级 @RequestMapping 必须含 /api/v1 前缀入口（平台统一前缀；"
                        + "2026-10-01 诊断回放/变更回调两例缺 v1 对前端全 404，"
                        + "纯直调测试不看 URL 未拦住）。例外进 ALLOW_NO_V1 并写明原因")
                .isEmpty();
    }
}
