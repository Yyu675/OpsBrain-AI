package com.devops.agent.domain.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标查询目录：把 PromQL 模板从代码挪进配置（{@code devops.metrics.query-catalog.*}）。
 * <p>
 * 为什么不在代码里写死：PromQL 高度依赖现场的 label 约定
 * （k8s 用 {@code pod=}、Spring 微服务用 {@code application=}），
 * 本项目只提供可跑的默认值骨架（application.yml 中有中文注释），
 * 落地时按实际接入的指标规范改写——这是 1-1.4「PromQL 可粘贴复现」
 * 验收成立的前提。</p>
 * <p>
 * 模板用 {@code {service}} 占位符，渲染前对服务名做白名单校验
 * （只允许 {@code [a-zA-Z0-9._-]}），使 PromQL 注入在语法上不可能——
 * 工具层是模型可达面，这里的校验是真正的边界（路线图 §5.4 📌）。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "devops.metrics.query-catalog")
public class MetricsQueryCatalog {

    /** 服务名白名单：首字符必须是字母数字，总长 ≤128，禁止引号/花括号/空白。 */
    public static final Pattern SERVICE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._\\-]{0,127}$");

    /** 指标名 -> PromQL 模板（含 {service} 占位符）。Key 即工具入参 metrics 的合法值。 */
    private Map<String, String> templates = new LinkedHashMap<>();

    public Map<String, String> getTemplates() { return templates; }
    public void setTemplates(Map<String, String> templates) { this.templates = new LinkedHashMap<>(templates); }

    public Set<String> availableMetrics() { return templates.keySet(); }

    /** 校验服务名；不合法时抛 IllegalArgumentException（由工具层译为参数错误，不外泄细节）。 */
    public void requireValidService(String service) {
        if (service == null || !SERVICE_NAME_PATTERN.matcher(service).matches()) {
            throw new IllegalArgumentException(
                    "服务名不合法：只允许字母/数字/点/下划线/中划线，≤128 字符");
        }
    }

    /**
     * 渲染 PromQL。模板不存在时返回 null（调用方决定是参数错误还是跳过）。
     * service 必须先经 {@link #requireValidService}。
     */
    public String render(String metricName, String service) {
        String template = templates.get(metricName);
        return template == null ? null : template.replace("{service}", service);
    }
}
