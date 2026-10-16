package com.devops.agent.infrastructure.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.devops.agent.common.web.ClientIpResolver;

/**
 * API 入口限流过滤器登记（S5-3.5，批 45）。
 *
 * <p>{@link RateLimitFilter} 刻意不标 {@code @Component}（理由见其类注释：
 * {@code @WebMvcTest} 切片会把 Filter 扫进来、却扫不到它依赖的
 * {@link ClientIpResolver}，25 个 web 测试类将集体上下文装配失败）。
 * 纯 {@code @Configuration} 不进 web 测试切片，全应用上下文照常装配——
 * 测试面冲击半径=0 与生产生效两拿。
 *
 * <p>Order = TraceIdFilter(HIGHEST_PRECEDENCE) + 1:429 拒收的
 * 响应头同样带 X-Request-Id——被拒的请求恰恰最需要审计溯源。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitWebConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitProperties properties, ClientIpResolver clientIpResolver) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>(
                new RateLimitFilter(properties, clientIpResolver));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        // 显式全路径登记（上下文路径由容器自行加上）
        bean.addUrlPatterns("/*");
        return bean;
    }
}
