package com.devops.agent.application.runtime;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本/Token 配额管理器
 * <p>
 * 职责：实施单请求、单用户、单日成本与 Token 配额控制，防止成本失控（Agent Methodology §16）。
 * <p>
 * 配额维度：
 * <ol>
 *   <li>单请求 Token 上限（防止单次调用爆炸）</li>
 *   <li>单请求成本上限（人民币元）</li>
 *   <li>单用户日 Token 配额</li>
 *   <li>单用户日成本配额</li>
 *   <li>系统级日总成本上限（熔断保护）</li>
 * </ol>
 * </p>
 * <p>
 * 执行策略：
 * <ul>
 *   <li>预检：调用前估算，超限直接拒绝</li>
 *   <li>实时扣减：调用成功后扣减配额</li>
 *   <li>熔断：日配额耗尽触发熔断，返回降级响应</li>
 *   <li>告警：配额使用率 > 80% 记录告警日志</li>
 * </ul>
 * </p>
 *
 * <p><b>TODO(P2-鉴权)</b>：当前 userId 参数由调用方传入 sessionId 作为过渡（P1-4 修复），
 * 待真实登录鉴权落地后需替换为 userId：
 * <ol>
 *   <li>改所有 userId 形参类型为真实用户标识（非 traceId/sessionId）</li>
 *   <li>配额 key 从 sessionId 升级为 userId，实现跨会话/跨请求的日限额累计</li>
 *   <li>接入登录回调写入真实 user 后，{@link #preCheck} 与 {@link #recordUsage} 获取
 *       当前登录用户 ID 而非依赖调用方传入</li>
 * </ol>
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
// 注意：Bean 由 AgentEngineConfig.costQuotaManager() 以 @Bean 方式创建，
// 此处不加 @Component 以避免双重定义（P2-24）。
public class CostQuotaManager {

    // ==================== 配置参数 ====================

    @Value("${devops.ai.quota.max-tokens-per-request:8000}")
    private int maxTokensPerRequest;

    @Value("${devops.ai.quota.max-cost-per-request:0.50}")
    private double maxCostPerRequest;

    @Value("${devops.ai.quota.user-daily-tokens:100000}")
    private long userDailyTokenQuota;

    @Value("${devops.ai.quota.user-daily-cost:10.00}")
    private double userDailyCostQuota;

    @Value("${devops.ai.quota.system-daily-cost:1000.00}")
    private double systemDailyCostQuota;

    @Value("${devops.ai.quota.warn-threshold:0.80}")
    private double warnThreshold;

    // Token 单价（人民币/千Token），用于成本估算
    @Value("${devops.ai.quota.turbo-cost-per-1k:0.002}")
    private double turboCostPer1kTokens;

    @Value("${devops.ai.quota.reasoner-cost-per-1k:0.02}")
    private double reasonerCostPer1kTokens;

    // ==================== 运行时状态 ====================

    /**
     * 用户日度配额使用情况：userId -> UserQuotaUsage
     */
    private final Map<String, UserQuotaUsage> userQuotas = new ConcurrentHashMap<>();

    /**
     * 系统日度总成本
     */
    private final AtomicLong systemDailyCostFen = new AtomicLong(0); // 存储为分，避免浮点精度问题

    /**
     * 最后重置时间
     */
    private volatile LocalDateTime lastResetTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

    // ==================== 配额检查入口 ====================

    /**
     * 请求前预检：估算 Token 与成本，判断是否超配额
     *
     * @param userId       用户标识（可为 traceId 或 真实用户ID）
     * @param estimatedTokens 估算 Token 数（含输入+输出预留）
     * @param modelType    模型类型：TURBO / REASONER
     * @return 预检结果
     */
    public QuotaCheckResult preCheck(String userId, int estimatedTokens, ModelType modelType) {
        // 1. 重置日度配额（跨天自动重置）
        resetDailyIfNeeded();

        // 2. 单请求硬性上限检查
        if (estimatedTokens > maxTokensPerRequest) {
            return QuotaCheckResult.exceeded("单请求 Token 超限",
                    "estimatedTokens=" + estimatedTokens + " > maxTokensPerRequest=" + maxTokensPerRequest);
        }

        // 3. 单请求成本上限检查
        double estimatedCost = estimateCost(estimatedTokens, modelType);
        if (estimatedCost > maxCostPerRequest) {
            return QuotaCheckResult.exceeded("单请求成本超限",
                    "estimatedCost=¥" + String.format("%.4f", estimatedCost) + " > maxCostPerRequest=¥" + maxCostPerRequest);
        }

        // 4. 用户日度配额检查
        UserQuotaUsage usage = userQuotas.computeIfAbsent(userId, k -> new UserQuotaUsage());
        if (usage.tokensUsed + estimatedTokens > userDailyTokenQuota) {
            return QuotaCheckResult.exceeded("用户日 Token 配额耗尽",
                    "used=" + usage.tokensUsed + " + estimated=" + estimatedTokens + " > quota=" + userDailyTokenQuota);
        }
        if (usage.costFen + (long)(estimatedCost * 100) > userDailyCostQuota * 100) {
            return QuotaCheckResult.exceeded("用户日成本配额耗尽",
                    "used=¥" + String.format("%.2f", usage.costFen / 100.0) + " > quota=¥" + userDailyCostQuota);
        }

        // 5. 系统日度总成本检查
        long systemCostFen = systemDailyCostFen.get();
        if (systemCostFen + (long)(estimatedCost * 100) > systemDailyCostQuota * 100) {
            return QuotaCheckResult.exceeded("系统日总成本配额耗尽（熔断）",
                    "systemUsed=¥" + String.format("%.2f", systemCostFen / 100.0) + " > quota=¥" + systemDailyCostQuota);
        }

        // 6. 告警检查（使用率 > 80%）
        checkAndWarn(userId, usage, estimatedTokens, estimatedCost);

        return QuotaCheckResult.ok();
    }

    /**
     * 调用成功后记录实际消耗（扣减配额）
     */
    public void recordUsage(String userId, int actualTokens, double actualCost, ModelType modelType) {
        resetDailyIfNeeded();

        UserQuotaUsage usage = userQuotas.computeIfAbsent(userId, k -> new UserQuotaUsage());
        usage.tokensUsed += actualTokens;
        usage.costFen += (long)(actualCost * 100);
        usage.requestCount++;

        systemDailyCostFen.addAndGet((long)(actualCost * 100));

        log.debug("💰 [Quota] 记录消耗 | user={} | tokens={} | cost=¥{} | model={} | userDailyTokens={}/{} | userDailyCost=¥{}/{} | systemDailyCost=¥{}/{}",
                userId, actualTokens, String.format("%.4f", actualCost), modelType,
                usage.tokensUsed, userDailyTokenQuota,
                String.format("%.2f", usage.costFen / 100.0), userDailyCostQuota,
                String.format("%.2f", systemDailyCostFen.get() / 100.0), systemDailyCostQuota);
    }

    /**
     * 获取用户当前配额使用情况（供前端看板展示）
     */
    public UserQuotaStatus getUserStatus(String userId) {
        resetDailyIfNeeded();
        UserQuotaUsage usage = userQuotas.get(userId);
        if (usage == null) {
            return new UserQuotaStatus(userId, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new UserQuotaStatus(
                userId,
                usage.tokensUsed,
                userDailyTokenQuota,
                usage.costFen,
                (long)(userDailyCostQuota * 100),
                usage.requestCount,
                systemDailyCostFen.get(),
                (long)(systemDailyCostQuota * 100),
                calculateResetTimeSeconds()
        );
    }

    // ==================== 内部辅助方法 ====================

    private double estimateCost(int tokens, ModelType modelType) {
        double costPer1k = (modelType == ModelType.REASONER) ? reasonerCostPer1kTokens : turboCostPer1kTokens;
        return (tokens / 1000.0) * costPer1k;
    }

    private void resetDailyIfNeeded() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
        if (lastResetTime.isBefore(todayStart)) {
            synchronized (this) {
                if (lastResetTime.isBefore(todayStart)) {
                    log.info("🔄 [Quota] 日度配额重置 | 重置前 systemCost=¥{} | userCount={}",
                            String.format("%.2f", systemDailyCostFen.get() / 100.0), userQuotas.size());
                    userQuotas.clear();
                    systemDailyCostFen.set(0);
                    lastResetTime = todayStart;
                }
            }
        }
    }

    private void checkAndWarn(String userId, UserQuotaUsage usage, int estimatedTokens, double estimatedCost) {
        double tokenUsageRate = (double)(usage.tokensUsed + estimatedTokens) / userDailyTokenQuota;
        double costUsageRate = (double)(usage.costFen + (long)(estimatedCost * 100)) / (userDailyCostQuota * 100);
        double systemUsageRate = (double)systemDailyCostFen.get() / (systemDailyCostQuota * 100);

        if (tokenUsageRate > warnThreshold || costUsageRate > warnThreshold || systemUsageRate > warnThreshold) {
            log.warn("⚠️ [Quota] 配额告警 | user={} | tokenUsage={:.1%} | costUsage={:.1%} | systemUsage={:.1%} | threshold={:.0%}",
                    userId, tokenUsageRate, costUsageRate, systemUsageRate, warnThreshold);
        }
    }

    private long calculateResetTimeSeconds() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrowStart = now.plusDays(1).withHour(0).withMinute(0).withSecond(0);
        return java.time.Duration.between(now, tomorrowStart).getSeconds();
    }

    // ==================== 内部数据类 ====================

    private static class UserQuotaUsage {
        long tokensUsed = 0;
        long costFen = 0; // 存储为分
        int requestCount = 0;
    }

    public enum ModelType {
        TURBO, REASONER
    }

    // ==================== 返回类型 ====================

    @Data
    public static class QuotaCheckResult {
        private final boolean allowed;
        private final String reason;
        private final String detail;

        private QuotaCheckResult(boolean allowed, String reason, String detail) {
            this.allowed = allowed;
            this.reason = reason;
            this.detail = detail;
        }

        public static QuotaCheckResult ok() {
            return new QuotaCheckResult(true, "OK", "");
        }

        public static QuotaCheckResult exceeded(String reason, String detail) {
            return new QuotaCheckResult(false, reason, detail);
        }
    }

    @Data
    public static class UserQuotaStatus {
        private final String userId;
        private final long tokensUsed;
        private final long tokensQuota;
        private final long costFenUsed;
        private final long costFenQuota;
        private final int requestCount;
        private final long systemCostFenUsed;
        private final long systemCostFenQuota;
        private final long resetInSeconds;

        public double getTokenUsageRate() { return tokensQuota > 0 ? (double) tokensUsed / tokensQuota : 0; }
        public double getCostUsageRate() { return costFenQuota > 0 ? (double) costFenUsed / costFenQuota : 0; }
        public double getSystemUsageRate() { return systemCostFenQuota > 0 ? (double) systemCostFenUsed / systemCostFenQuota : 0; }
    }
}