package com.devops.agent.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * P2-3：Caffeine 本地缓存配置（2026-09-14 批 86）
 *
 * <p>用于缓存高频读取、低变更的热数据：</p>
 * <ul>
 *   <li><b>users</b>：用户信息（登录/权限检查），5 分钟过期</li>
 *   <li><b>knowledge-meta</b>：知识文档元数据（标题/状态），10 分钟过期</li>
 * </ul>
 *
 * <p><b>为什么不用 Redis</b>：这些数据访问频率极高（每个请求都检查权限），
 * 网络往返（1-5ms）会累积成显著延迟；本地缓存访问 < 1μs。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-14
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine 缓存管理器
     *
     * <p>配置策略：</p>
     * <ul>
     *   <li>最大容量 1000 条（单应用实例用户数 < 1000）</li>
     *   <li>过期时间 5 分钟（平衡一致性与命中率）</li>
     *   <li>写后过期（用户信息变更后立即失效）</li>
     * </ul>
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats());  // 开启统计（可通过 /actuator/metrics 查看命中率）
        return cacheManager;
    }
}
