package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.cache.QuotaCounterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CostQuotaManager} 配额判定测试。
 *
 * <p>保护的契约：<b>配额必须真的拦得住</b>。成本熔断是 README 宣称的核心能力之一，
 * 判定逻辑一旦出错（如分/元换算截断、除零、边界判反），故障是"钱悄悄花超"，
 * 没有任何显式报错，只能靠测试守住。</p>
 *
 * <p>这里用假的 {@link QuotaCounterStore} 而非真 Redis：被测对象是<b>判定逻辑</b>，
 * Redis 的原子性由 Lua 脚本保证，属另一层关注点。</p>
 */
class CostQuotaManagerTest {

    private Map<String, Long> tokens;
    private Map<String, Long> costFen;
    private long systemCostFen;
    private CostQuotaManager manager;

    @BeforeEach
    void setUp() {
        tokens = new HashMap<>();
        costFen = new HashMap<>();
        systemCostFen = 0L;

        QuotaCounterStore store = mock(QuotaCounterStore.class);
        when(store.getUserTokens(anyString())).thenAnswer(i -> tokens.getOrDefault(i.getArgument(0), 0L));
        when(store.getUserCostFen(anyString())).thenAnswer(i -> costFen.getOrDefault(i.getArgument(0), 0L));
        when(store.getSystemCostFen()).thenAnswer(i -> systemCostFen);
        doAnswer(i -> {
            String u = i.getArgument(0);
            tokens.merge(u, i.getArgument(1), Long::sum);
            costFen.merge(u, i.getArgument(2), Long::sum);
            systemCostFen += (long) i.getArgument(2);
            return null;
        }).when(store).recordUsage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());

        manager = new CostQuotaManager(store);
        // @Value 字段在单测中无 Spring 容器注入，显式设置
        ReflectionTestUtils.setField(manager, "maxTokensPerRequest", 8000);
        ReflectionTestUtils.setField(manager, "maxCostPerRequest", 0.50);
        ReflectionTestUtils.setField(manager, "userDailyTokenQuota", 100_000L);
        ReflectionTestUtils.setField(manager, "userDailyCostQuota", 10.00);
        ReflectionTestUtils.setField(manager, "systemDailyCostQuota", 1000.00);
        ReflectionTestUtils.setField(manager, "warnThreshold", 0.80);
        ReflectionTestUtils.setField(manager, "turboCostPer1kTokens", 0.002);
        ReflectionTestUtils.setField(manager, "reasonerCostPer1kTokens", 0.02);
    }

    @Test
    @DisplayName("额度充足时放行")
    void allowsWithinQuota() {
        var r = manager.preCheck("u1", 1000, CostQuotaManager.ModelType.TURBO);

        assertTrue(r.isAllowed(), "额度充足不应拦截");
    }

    @Test
    @DisplayName("单请求 token 超上限被拒，且理由可读")
    void rejectsOversizedSingleRequest() {
        var r = manager.preCheck("u1", 8001, CostQuotaManager.ModelType.TURBO);

        assertFalse(r.isAllowed());
        assertTrue(r.getReason().contains("单请求 Token 超限"));
    }

    @Test
    @DisplayName("单请求成本超上限被拒（reasoner 单价高，同样 token 数会触发）")
    void rejectsOversizedSingleRequestCost() {
        // 8000 tokens * 0.02/1k = ¥0.16 < 0.5，不触发；用接近上限的量配合高单价
        ReflectionTestUtils.setField(manager, "maxCostPerRequest", 0.10);

        var r = manager.preCheck("u1", 8000, CostQuotaManager.ModelType.REASONER);

        assertFalse(r.isAllowed());
        assertTrue(r.getReason().contains("单请求成本超限"));
    }

    @Test
    @DisplayName("用户日 token 配额耗尽后拦截后续请求")
    void rejectsWhenUserDailyTokenExhausted() {
        tokens.put("u1", 99_500L);

        var r = manager.preCheck("u1", 1000, CostQuotaManager.ModelType.TURBO);

        assertFalse(r.isAllowed());
        assertTrue(r.getReason().contains("用户日 Token 配额耗尽"));
    }

    @Test
    @DisplayName("恰好用满不拦，超一点就拦（边界不得判反）")
    void boundaryIsInclusive() {
        tokens.put("u1", 99_000L);
        assertTrue(manager.preCheck("u1", 1000, CostQuotaManager.ModelType.TURBO).isAllowed(),
                "累计恰好等于配额应放行");

        tokens.put("u1", 99_001L);
        assertFalse(manager.preCheck("u1", 1000, CostQuotaManager.ModelType.TURBO).isAllowed(),
                "超出一个 token 就应拦截");
    }

    @Test
    @DisplayName("系统日总成本耗尽触发全局熔断，即使该用户额度充足")
    void systemCircuitBreakerTripsRegardlessOfUser() {
        systemCostFen = 99_999L;   // ¥999.99 / ¥1000

        var r = manager.preCheck("fresh-user", 8000, CostQuotaManager.ModelType.REASONER);

        assertFalse(r.isAllowed(), "系统熔断优先于个人额度");
        assertTrue(r.getReason().contains("熔断"));
    }

    @Test
    @DisplayName("成本换算按四舍五入，不得截断——否则成本被系统性低估")
    void costRoundsInsteadOfTruncating() {
        // ¥0.29 在二进制浮点下 (long)(0.29*100) == 28
        manager.recordUsage("u1", 0, 0.29, CostQuotaManager.ModelType.TURBO);

        assertEquals(29L, costFen.get("u1"), "0.29 元应记为 29 分而非 28 分");
    }

    @Test
    @DisplayName("多次消费累加，用量随之增长")
    void usageAccumulates() {
        manager.recordUsage("u1", 1000, 0.10, CostQuotaManager.ModelType.TURBO);
        manager.recordUsage("u1", 2000, 0.20, CostQuotaManager.ModelType.TURBO);

        assertEquals(3000L, tokens.get("u1"));
        assertEquals(30L, costFen.get("u1"));
    }

    @Test
    @DisplayName("不同用户互不影响")
    void usersAreIsolated() {
        tokens.put("heavy", 99_999L);

        assertFalse(manager.preCheck("heavy", 1000, CostQuotaManager.ModelType.TURBO).isAllowed());
        assertTrue(manager.preCheck("light", 1000, CostQuotaManager.ModelType.TURBO).isAllowed());
    }

    @Test
    @DisplayName("配额状态用于看板展示，反映当前真实用量")
    void reportsStatus() {
        tokens.put("u1", 25_000L);
        costFen.put("u1", 250L);

        var s = manager.getUserStatus("u1");

        assertEquals(25_000L, s.getTokensUsed());
        assertEquals(250L, s.getCostFenUsed());
        assertEquals(0.25, s.getTokenUsageRate(), 0.0001);
    }
}
