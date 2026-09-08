package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.cache.QuotaCounterStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

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
 * <p><b>存储（A3 修复，2026-08-24）</b>：用量计数已从进程内存迁至 Redis
 * （{@link QuotaCounterStore}）。此前用 {@code ConcurrentHashMap} + {@code AtomicLong}
 * 保存，存在三个致命问题：重启即清零（跑满额度重启就能接着烧钱）、
 * 多实例各记各的（实际额度 = 配置值 × 实例数）、日重置依赖进程内时间戳。
 * 现以「日期分区 key + 到当日 24 点的 TTL」实现自然日重置，无需显式重置逻辑。</p>
 *
 * <p>userId 由调用方传入，已由 {@code DevOpsAgentServiceImpl.resolveQuotaKey()}
 * 解析为「优先真实 userId、回退 sessionId」。注意该解析<b>必须在请求线程完成</b>，
 * 因为 Sa-Token 的登录上下文是 ThreadLocal，切到异步线程后取不到。</p>
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
     * 配额计数器存储（Redis）。
     * <p>由构造器注入，Bean 在 {@code AgentEngineConfig.costQuotaManager()} 装配。</p>
     */
    private final QuotaCounterStore counters;

    public CostQuotaManager(QuotaCounterStore counters) {
        this.counters = counters;
    }

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
        // 无需再做 resetDailyIfNeeded()：计数器 key 含日期分区且 TTL 到当日 24 点，
        // 跨天自然归零（A3）。

        // 1. 单请求硬性上限检查
        if (estimatedTokens > maxTokensPerRequest) {
            return QuotaCheckResult.exceeded("单请求 Token 超限",
                    "estimatedTokens=" + estimatedTokens + " > maxTokensPerRequest=" + maxTokensPerRequest);
        }

        // 2. 单请求成本上限检查
        double estimatedCost = estimateCost(estimatedTokens, modelType);
        long estimatedCostFen = toFen(estimatedCost);
        if (estimatedCost > maxCostPerRequest) {
            return QuotaCheckResult.exceeded("单请求成本超限",
                    "estimatedCost=¥" + String.format("%.4f", estimatedCost) + " > maxCostPerRequest=¥" + maxCostPerRequest);
        }

        // 3. 用户日度配额检查（读 Redis，跨实例、跨重启一致）
        long tokensUsed = counters.getUserTokens(userId);
        long costFenUsed = counters.getUserCostFen(userId);
        if (tokensUsed + estimatedTokens > userDailyTokenQuota) {
            return QuotaCheckResult.exceeded("用户日 Token 配额耗尽",
                    "used=" + tokensUsed + " + estimated=" + estimatedTokens + " > quota=" + userDailyTokenQuota);
        }
        if (costFenUsed + estimatedCostFen > toFen(userDailyCostQuota)) {
            return QuotaCheckResult.exceeded("用户日成本配额耗尽",
                    "used=¥" + String.format("%.2f", costFenUsed / 100.0) + " > quota=¥" + userDailyCostQuota);
        }

        // 4. 系统日度总成本检查（熔断）
        long systemCostFen = counters.getSystemCostFen();
        if (systemCostFen + estimatedCostFen > toFen(systemDailyCostQuota)) {
            return QuotaCheckResult.exceeded("系统日总成本配额耗尽（熔断）",
                    "systemUsed=¥" + String.format("%.2f", systemCostFen / 100.0) + " > quota=¥" + systemDailyCostQuota);
        }

        // 5. 告警检查（使用率 > 阈值）
        checkAndWarn(userId, tokensUsed, costFenUsed, systemCostFen, estimatedTokens, estimatedCostFen);

        return QuotaCheckResult.ok();
    }

    /**
     * 调用成功后记录实际消耗（扣减配额）
     */
    public void recordUsage(String userId, int actualTokens, double actualCost, ModelType modelType) {
        long costFen = toFen(actualCost);
        counters.recordUsage(userId, actualTokens, costFen);

        if (log.isDebugEnabled()) {
            log.debug("💰 [Quota] 记录消耗 | user={} | tokens={} | cost=¥{} | model={} | 当日累计 tokens={}/{} | cost=¥{}/{} | 系统=¥{}/{}",
                    userId, actualTokens, String.format("%.4f", actualCost), modelType,
                    counters.getUserTokens(userId), userDailyTokenQuota,
                    String.format("%.2f", counters.getUserCostFen(userId) / 100.0), userDailyCostQuota,
                    String.format("%.2f", counters.getSystemCostFen() / 100.0), systemDailyCostQuota);
        }
    }

    /**
     * 获取用户当前配额使用情况（供前端看板展示）
     */
    public UserQuotaStatus getUserStatus(String userId) {
        return new UserQuotaStatus(
                userId,
                counters.getUserTokens(userId),
                userDailyTokenQuota,
                counters.getUserCostFen(userId),
                toFen(userDailyCostQuota),
                (int) counters.getUserRequests(userId),
                counters.getSystemCostFen(),
                toFen(systemDailyCostQuota),
                counters.resetInSeconds()
        );
    }

    // ==================== 内部辅助方法 ====================

    private double estimateCost(int tokens, ModelType modelType) {
        double costPer1k = (modelType == ModelType.REASONER) ? reasonerCostPer1kTokens : turboCostPer1kTokens;
        return (tokens / 1000.0) * costPer1k;
    }

    /**
     * 元 → 分。集中一处转换，避免各调用点重复写 {@code (long)(x * 100)}。
     * <p>用 {@link Math#round} 而非强制截断：{@code (long)(0.29 * 100)} 在
     * 二进制浮点下等于 28，会让成本被系统性低估。</p>
     */
    private static long toFen(double yuan) {
        return Math.round(yuan * 100);
    }

    /**
     * 配额使用率告警。
     * <p><b>修复</b>：原实现使用了 Python/Rust 风格的格式说明符占位符，
     * 而 SLF4J 只认识 <code>&#123;&#125;</code>，不支持任何格式说明符。
     * 结果是日志原样打印占位符文本且参数错位，等于告警从未真正生效。</p>
     */
    private void checkAndWarn(String userId, long tokensUsed, long costFenUsed, long systemCostFen,
                              int estimatedTokens, long estimatedCostFen) {
        double tokenUsageRate = safeRate(tokensUsed + estimatedTokens, userDailyTokenQuota);
        double costUsageRate = safeRate(costFenUsed + estimatedCostFen, toFen(userDailyCostQuota));
        double systemUsageRate = safeRate(systemCostFen, toFen(systemDailyCostQuota));

        if (tokenUsageRate > warnThreshold || costUsageRate > warnThreshold || systemUsageRate > warnThreshold) {
            log.warn("⚠️ [Quota] 配额告警 | user={} | tokenUsage={}% | costUsage={}% | systemUsage={}% | threshold={}%",
                    userId,
                    String.format("%.1f", tokenUsageRate * 100),
                    String.format("%.1f", costUsageRate * 100),
                    String.format("%.1f", systemUsageRate * 100),
                    String.format("%.0f", warnThreshold * 100));
        }
    }

    /** 除零保护：配额配置为 0 时视为 0% 而非 NaN/Infinity */
    private static double safeRate(long used, long quota) {
        return quota > 0 ? (double) used / quota : 0.0;
    }

    // ==================== 内部数据类 ====================

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