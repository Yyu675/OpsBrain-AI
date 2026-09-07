package com.devops.agent.domain.healing;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.governance.AutomationPolicy;
import com.devops.agent.domain.governance.AutomationPolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 告警 → 治理策略 → 自愈动作 的触发引擎（S4-1 批次 7：补上三表拼图的最后一块）。
 *
 * <h3>拼图位置</h3>
 * <ul>
 *   <li>{@code sys_action_allowlist}：能不能做（授权清单）；</li>
 *   <li>{@code sys_risk_policy}：怎么做（风险管控）；</li>
 *   <li>{@code sys_automation_policy}：什么时候做（本引擎消费它）。</li>
 * </ul>
 * 此前三表俱备而无人调用——自愈只在人工点按钮时发生。本引擎把
 * 「告警入库」与「策略求值」接上，但<b>只做构造与递交</b>：能否执行、
 * 要不要审批，仍由 {@link HealingOrchestrator} 的治理门裁决（单点准入
 * 不因自动化复制一份）。</p>
 *
 * <h3>信任阶梯（L5 证据门的第一级）</h3>
 * <ol>
 *   <li>策略默认 {@code dryRun=true}：演练命中只留 REJECTED 行
 *       （{@code gate_decision=POLICY_DRYRUN}），自愈中心可见、执行器零调用；</li>
 *   <li>演练数据攒够了，运维把策略 dryRun 关掉 → 真执行仍走治理门+幂等闸；</li>
 *   <li>冷却期与日上限是策略自带的两把保险（冷却/日限额维度=动作×环境×目标服务，
 *       {@code REJECTED} 行不计入——被拒/演练的尝试不该挡住下一次认真尝试）。</li>
 * </ol>
 *
 * <h3>异步与失败基调</h3>
 * 告警入库链路不可被策略引擎拖慢或阻断（告警可见性铁律在 S2 已定）。
 * 因此：单线程池异步求值（策略量小，串行足够防雪崩）、逐策略 try-catch
 * 隔离（一条坏策略不连坐其余）、全局开关 {@code devops.healing.policy-trigger.enabled}。
 */
@Component
public class HealingAutoTrigger {

    private static final Logger log = LoggerFactory.getLogger(HealingAutoTrigger.class);

    private final AutomationPolicyRepository policyRepository;
    private final HealingExecutionRepository executionRepository;
    private final HealingOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService enginePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "healing-policy-engine");
        t.setDaemon(true);
        return t;
    });

    /** 全局开关。关闭只影响自动触发；人工触发/审批回放照常（引擎本就是旁路增强） */
    @Value("${devops.healing.policy-trigger.enabled:true}")
    private boolean enabled;

    public HealingAutoTrigger(AutomationPolicyRepository policyRepository,
                              HealingExecutionRepository executionRepository,
                              HealingOrchestrator orchestrator) {
        this.policyRepository = policyRepository;
        this.executionRepository = executionRepository;
        this.orchestrator = orchestrator;
    }

    /**
     * 告警入库后的引擎入口（AlertService 在非聚合抑制路径调用）。
     * 本方法只做 submit，不抛异常——告警链的不可阻断性绝不被自动化绑架。
     */
    public void onAlertFired(Alert alert) {
        if (!enabled || alert == null) {
            return;
        }
        try {
            enginePool.submit(() -> firePolicies(alert));
        } catch (Exception ex) {
            log.warn("⚠️ [PolicyEngine] 策略求值提交失败（不影响告警） | alertId={} | {}",
                    alert.getId(), ex.getMessage());
        }
    }

    /** 逐策略求值。测试直调本方法保证确定性（池化只是生产链路的防阻层）。 */
    void firePolicies(Alert alert) {
        List<AutomationPolicy> policies;
        try {
            policies = policyRepository.findEnabledInEvalOrder();
        } catch (Exception ex) {
            log.warn("⚠️ [PolicyEngine] 策略读取失败 | alertId={} | {}", alert.getId(), ex.getMessage());
            return;
        }
        for (AutomationPolicy p : policies) {
            try {
                if (!p.matches(alert.getLevel(), alert.getModule(),
                        alert.getService(), alert.getAlertName())) {
                    continue;
                }
                boolean acted = handleMatch(alert, p);
                // stopOnMatch：「拿到这单」才停。冷却/日上限跳过的策略不算拿到单，
                // 允许后续策略继续评估——否则一条冷却中的宽策略会饿死所有窄策略。
                if (acted && p.isStopOnMatch()) {
                    return;
                }
            } catch (Exception ex) {
                log.warn("⚠️ [PolicyEngine] 单条策略求值失败（不连坐其余） | policyId={} | alertId={} | {}",
                        p.getId(), alert.getId(), ex.getMessage());
            }
        }
    }

    /** @return 是否真正「拿到这单」（演练留痕或递交编排）——stopOnMatch 的判定依据 */
    private boolean handleMatch(Alert alert, AutomationPolicy p) {
        String target = alert.getService();
        Map<String, Object> params = buildParams(p, alert);

        // 第一信任级：演练命中只留痕（自愈中心 REJECTED/POLICY_DRYRUN 行可见）
        if (p.isDryRun()) {
            long id = executionRepository.insert(HealingExecution.draft(
                    p.getActionKey(), p.getEnvironment(), target, toJson(params),
                    alert.getId(), "auto", "POLICY_DRYRUN", null, null,
                    HealingExecution.Status.REJECTED,
                    "策略演练命中（不执行）: policy #" + p.getId() + " " + p.getName(),
                    null, null, null));
            writeTraceSteps(id, p);
            log.info("🧪 [PolicyEngine] 演练命中 | policy=#{} {} | alert=#{} | action={} | 台账=#{}",
                    p.getId(), p.getName(), alert.getId(), p.getActionKey(), id);
            return true;
        }

        // 冷却期：同一（动作×环境×目标）在窗口内动过手就静默跳过
        if (p.getCooldownMinutes() > 0) {
            Optional<LocalDateTime> last =
                    executionRepository.lastExecutionAt(p.getActionKey(), p.getEnvironment(), target);
            if (last.isPresent()
                    && last.get().isAfter(LocalDateTime.now().minusMinutes(p.getCooldownMinutes()))) {
                log.info("🧊 [PolicyEngine] 冷却中跳过 | policy=#{} | alert=#{} | 上次执行={}",
                        p.getId(), alert.getId(), last.get());
                return false;
            }
        }

        // 日上限：防「策略配宽了」在夜深人静时变成执行风暴
        if (p.getMaxExecutionsPerDay() > 0) {
            int today = executionRepository.countSince(p.getActionKey(), p.getEnvironment(),
                    LocalDateTime.now().toLocalDate().atStartOfDay());
            if (today >= p.getMaxExecutionsPerDay()) {
                log.warn("🛑 [PolicyEngine] 日上限跳过 | policy=#{} | alert=#{} | 今日={}/{}",
                        p.getId(), alert.getId(), today, p.getMaxExecutionsPerDay());
                return false;
            }
        }

        HealingAction action = new HealingAction(p.getActionKey(), p.getEnvironment(), target,
                params, alert.getId(), "auto", Instant.now());
        log.warn("🚀 [PolicyEngine] 策略命中递交治理门 | policy=#{} {} | alert=#{} | action={} | target={}",
                p.getId(), p.getName(), alert.getId(), p.getActionKey(), target);
        orchestrator.handle(action);
        return true;
    }

    /** 演练留痕的时间线：回放页要能看出「这台账来自策略演练」而不是门拒绝 */
    private void writeTraceSteps(long executionId, AutomationPolicy p) {
        try {
            List<Map<String, Object>> steps = new ArrayList<>();
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("name", "POLICY_MATCH");
            match.put("status", "DRYRUN");
            match.put("detail", "policy #" + p.getId() + " " + p.getName());
            match.put("at", Instant.now().toString());
            steps.add(match);
            Map<String, Object> dry = new LinkedHashMap<>();
            dry.put("name", "POLICY_DRYRUN");
            dry.put("status", "REJECTED");
            dry.put("detail", "演练模式：照常匹配与记录，但不真正执行");
            dry.put("at", Instant.now().toString());
            steps.add(dry);
            executionRepository.updateStepsJson(executionId, objectMapper.writeValueAsString(steps));
        } catch (Exception ex) {
            log.warn("⚠️ [PolicyEngine] 演练步骤落库失败（不影响留痕主行） | id={} | {}",
                    executionId, ex.getMessage());
        }
    }

    /**
     * 参数 = 策略 actionParams(JSON) + 模板替换 + 溯源键。
     * 模板只认三个：{@code ${service}} / {@code ${alertName}} / {@code ${alertId}}——
     * 超出这个动作面的取值能力先不做（标签驱动的复杂模板是下一批的账）。
     */
    Map<String, Object> buildParams(AutomationPolicy p, Alert alert) {
        Map<String, Object> params = new LinkedHashMap<>();
        String raw = p.getActionParams();
        if (raw != null && !raw.isBlank()) {
            try {
                params.putAll(objectMapper.readValue(raw, new TypeReference<>() { }));
            } catch (Exception ex) {
                log.warn("⚠️ [PolicyEngine] 策略参数 JSON 解析失败，按空参数处理 | policy=#{} | {}",
                        p.getId(), ex.getMessage());
            }
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() instanceof String v) {
                e.setValue(v.replace("${service}", nullToEmpty(alert.getService()))
                        .replace("${alertName}", nullToEmpty(alert.getAlertName()))
                        .replace("${alertId}", String.valueOf(alert.getId())));
            }
        }
        params.put("__policyId", p.getId());
        params.put("__policyName", p.getName());
        return params;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            log.warn("⚠️ [PolicyEngine] 参数序列化失败，落空 JSON | {}", ex.getMessage());
            return "{}";
        }
    }

    @PreDestroy
    public void shutdown() {
        enginePool.shutdown();
    }
}
