package com.devops.agent.domain.governance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 治理模块的响应视图 records（P0-2b 第三步）。
 *
 * <h3>为什么把 {@code Map<String, Object>} 换成 record</h3>
 * <p>
 * 与工单/知识库两轮（T8/T9）同一理由：OpenAPI 对 {@code Map} 只能生成
 * {@code additionalProperties: true}——<b>等于没有契约</b>。治理模块尤甚：
 * 这里配置的是「AI 能不能自动动生产系统」的边界，前端把 {@code liveCount}
 * 读成 {@code undefined} 时管理员看到的风险敞口是 0。
 * 换成 record 后字段改名有编译错误兜底，不会再静默漂移。
 * </p>
 *
 * <h3>为什么放在 domain 而不是 controller.dto</h3>
 * <p>
 * 这些 Map 原先是由 repository/service 组装的（分页钳制贴着 SQL 的
 * {@code LIMIT/OFFSET} 计算，{@code evaluate} 的判定逻辑在 service），
 * 六层依赖方向不允许 domain import controller，所以 record 随组装者
 * 部署在 domain 层。controller 只负责把它们包进 {@code ApiResponse}。
 * 这与告警模块相反——告警由控制器组装，record 才落在 controller.dto。
 * </p>
 *
 * <h3>线上报文形态：与 Map 时代逐字段对齐</h3>
 * <p>
 * Map 时代有两类键是<b>按条件省略</b>的：{@code evaluate} 拒绝时不带
 * {@code requiresApproval} 等四个字段，{@code simulate} 未命中的行不带
 * {@code outcome}/{@code actionVerdict}。record 用
 * {@link JsonInclude.Include#NON_NULL} 精确复刻这个形态——
 * 否快照旧的 {@code 'outcome' in row} 式消费会拿到不同的答案，
 * 而本轮的目的恰恰是「契约不许有肉眼看不见的漂移」。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07
 */
public class GovernanceViews {

    private GovernanceViews() {
    }

    // ==================================================================
    // 分页。两组刻意拆成两个 record 而不是一个泛型 Page<T>：
    // OpenAPI 对泛型生成的 schema 名（PageXxx）随实现漂移，
    // 具体 record 的名字稳定、可读，前端 openapi-typescript 直接消费。
    // ==================================================================

    /**
     * 动作白名单分页。对应 {@code GET /api/v1/governance/actions}。
     * 字段名冻结——前端 {@code governance.ts} 的 {@code PagedResult} 逐字段读取。
     */
    public record ActionPage(
            List<ActionAllowlistEntry> items,
            long total,
            /** 生效页码（已按 totalPages 钳制，可能与请求值不同） */
            int page,
            /** 生效每页大小（已钳制到 [1, 200]） */
            int size,
            int totalPages
    ) {
    }

    /**
     * 自动化策略分页。对应 {@code GET /api/v1/governance/policies}。
     * 字段命名与 {@link ActionPage} 相同是<b>前端既有契约</b>（同一个
     * {@code PagedResult<T>} 类型），不是可以「顺手优化」的巧合。
     */
    public record AutomationPolicyPage(
            List<AutomationPolicy> items,
            long total,
            int page,
            int size,
            int totalPages
    ) {
    }

    // ==================================================================
    // 统计条。这些是「该警惕」的数字——风险敞口的唯一权威来源。
    // ==================================================================

    /**
     * 白名单顶部统计。对应 {@code GET /api/v1/governance/actions/stats}。
     *
     * @param highRiskEnabled 已启用的高危动作数——需要警惕的风险敞口
     * @param prodEnabled     已启用且覆盖 prod 环境的动作数
     */
    public record ActionStats(
            long total,
            long enabledCount,
            long highRiskEnabled,
            long prodEnabled
    ) {
    }

    /**
     * 策略顶部统计。对应 {@code GET /api/v1/governance/policies/stats}。
     *
     * @param liveCount     「启用且非演练」的策略数——真正会动手的策略数
     * @param prodLiveCount 上述策略中作用于 prod 的数量——核心风险敞口
     */
    public record PolicyStats(
            long total,
            long enabledCount,
            long dryRunCount,
            long liveCount,
            long prodLiveCount
    ) {
    }

    // ==================================================================
    // 风险等级策略页
    // ==================================================================

    /** 审批门槛词表项：value 是枚举名（提交用），label 是展示名 */
    public record ApprovalModeOption(String value, String label, int requiredApprovers) {
    }

    /** 升级目标词表项 */
    public record EscalateTargetOption(String value, String label) {
    }

    /**
     * 风险等级策略列表。对应 {@code GET /api/v1/governance/risk-policies}。
     *
     * <p>词表随数据一起下发是刻意的：前端不维护枚举镜像——
     * 镜像必然漂移，本项目已因此踩过工单状态机 8 处不一致。</p>
     */
    public record RiskPolicyOverview(
            List<RiskPolicy> items,
            List<ApprovalModeOption> approvalModes,
            List<EscalateTargetOption> escalateTargets
    ) {
    }

    // ==================================================================
    // 筛选候选值
    // ==================================================================

    /** 风险等级词表项：含说明文案，新建表单里要展示每个等级的含义 */
    public record RiskLevelOption(String value, String label, String description) {
    }

    /**
     * 白名单筛选下拉候选。对应 {@code GET /api/v1/governance/actions/filter-options}。
     *
     * <p>{@code categories} 从实际数据聚合（不列出库里没有的选项），
     * {@code riskLevels} 来自 Java 枚举（即便库里当前没有该等级的动作，
     * 新建表单也必须能选到它）——两者的来源差异是刻意的，不要合并。</p>
     */
    public record ActionFilterOptions(
            List<String> categories,
            List<RiskLevelOption> riskLevels,
            List<String> environments,
            List<String> knownCategories
    ) {
    }

    // ==================================================================
    // 执行判定与匹配预演
    // ==================================================================

    /**
     * 执行判定结果。对应 {@code POST /api/v1/governance/evaluate}，
     * 也是匹配预演里每条命中策略的 {@code actionVerdict}。
     *
     * <p><b>拒绝时四个约束字段为 null（且线上报文中省略）</b>：
     * 地图时代的 deny 分支根本没 put 这些键。给拒绝也填上
     * 「其实需要的审批门槛」是危险的误导——读的人会以为
     * 「补上审批就能放行」，但拒绝原因可能是「动作根本没登记」。</p>
     *
     * @param requiresApproval  是否需审批（合并条目覆盖与策略默认后的<b>生效值</b>）
     * @param approvalMode      审批门槛枚举名（NONE/SINGLE/DUAL）
     * @param blastRadiusCount  生效爆炸半径（条目与策略取小）
     * @param cooldownSeconds   观察窗口秒数
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvaluateResult(
            String actionKey,
            String environment,
            boolean allowed,
            /** 人类可读的判定原因——拒绝时必定有值，是这个接口的核心价值 */
            String reason,
            Boolean requiresApproval,
            String approvalMode,
            Integer blastRadiusCount,
            Integer cooldownSeconds
    ) {
        public static EvaluateResult deny(String actionKey, String environment, String reason) {
            return new EvaluateResult(actionKey, environment, false, reason, null, null, null, null);
        }

        public static EvaluateResult allow(String actionKey, String environment,
                                           Boolean requiresApproval, String approvalMode,
                                           Integer blastRadiusCount, Integer cooldownSeconds) {
            return new EvaluateResult(actionKey, environment, true, "允许自动执行",
                    requiresApproval, approvalMode, blastRadiusCount, cooldownSeconds);
        }
    }

    /** 匹配预演的输入回显：一个假想的告警（前端把它展示在结果上方） */
    public record SimulateInput(
            String level,
            String module,
            String service,
            String alertName,
            String environment
    ) {
    }

    /**
     * 匹配预演中单条策略的求值结果。
     *
     * <p>三种行，靠工厂的语义分开、不允许混用：</p>
     * <ul>
     *   <li>{@link #skipped} —— 前序策略「命中即停」，引擎<b>根本没求值到这里</b>。
     *       与「求值了但没匹配」是两件不同的事，排查方向完全不同；</li>
     *   <li>{@link #unmatched} —— 求值了，条件或环境不符；</li>
     *   <li>{@link #matched} —— 命中，带最终结论 {@code outcome}
     *       （BLOCKED / DRY_RUN / PENDING_APPROVAL / EXECUTE）与动作判定。</li>
     * </ul>
     *
     * @param outcome    结论枚举名（前端表格按此渲染标签），未命中时为 null
     * @param actionVerdict  命中后经白名单+风险策略的联合判定，未命中时为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimulatedRow(
            Long policyId,
            String policyName,
            int priority,
            String actionKey,
            boolean dryRun,
            boolean matched,
            boolean skipped,
            String reason,
            String outcome,
            EvaluateResult actionVerdict
    ) {
        /** 前序「命中即停」截断：引擎不会求值到本条 */
        public static SimulatedRow skipped(AutomationPolicy p, String reason) {
            return new SimulatedRow(p.getId(), p.getName(), p.getPriority(), p.getActionKey(),
                    p.isDryRun(), false, true, reason, null, null);
        }

        /** 求值了但条件/环境不符 */
        public static SimulatedRow unmatched(AutomationPolicy p, String reason) {
            return new SimulatedRow(p.getId(), p.getName(), p.getPriority(), p.getActionKey(),
                    p.isDryRun(), false, false, reason, null, null);
        }

        /** 命中，带最终结论与动作联合判定 */
        public static SimulatedRow matched(AutomationPolicy p, String reason,
                                           String outcome, EvaluateResult actionVerdict) {
            return new SimulatedRow(p.getId(), p.getName(), p.getPriority(), p.getActionKey(),
                    p.isDryRun(), true, false, reason, outcome, actionVerdict);
        }
    }

    /**
     * 匹配预演结果。对应 {@code POST /api/v1/governance/policies/simulate}。
     *
     * <p>{@code firstEffective} 允许为 null（无任何命中时），且线上报文里
     * 这个键<b>始终存在</b>（Map 时代就是 put 了一个 null 值）——
     * 前端用 {@code firstEffective ? ... : ...} 判断，null 与缺键
     * 对它等价，但保持既有形态不额外引入变化面。</p>
     */
    public record SimulateResult(
            SimulateInput input,
            List<SimulatedRow> evaluated,
            long matchedCount,
            /** 第一条命中的策略（求值顺序即引擎真实顺序），无命中为 null */
            SimulatedRow firstEffective,
            /** 一句话总结，可直接当页面标题展示 */
            String summary
    ) {
    }

    /**
     * 删除结果。对应 {@code DELETE /api/v1/governance/policies/{id}}。
     * 显式回显被删的 id：异步审计日志与响应能对上号。
     */
    public record DeleteResult(long id, boolean deleted) {
    }
}
