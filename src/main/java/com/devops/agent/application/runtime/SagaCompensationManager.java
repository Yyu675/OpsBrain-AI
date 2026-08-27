package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Saga 补偿编排器
 * <p>
 * 参考 Agent Methodology §9.4：部分成功是最危险的状态，系统进入"半残"，
 * 必须逆序执行补偿；补偿也失败时标记 {@code MANUAL_INTERVENTION_REQUIRED}
 * 并触发高优先级通知。
 * </p>
 * <p>
 * 核心规则：
 * <ol>
 *   <li><b>逆序回滚</b>：后执行的先撤销，避免依赖倒置</li>
 *   <li><b>尽力而为</b>：单步补偿失败不中断后续补偿，最大化清理脏数据</li>
 *   <li><b>可恢复</b>：状态持久化，进程重启后可续跑未完成的补偿</li>
 *   <li><b>幂等</b>：补偿动作本身必须幂等（已补偿的跳过）</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class SagaCompensationManager {

    private final ToolExecutionRepository execRepo;
    private final ApplicationContext applicationContext;

    public SagaCompensationManager(ToolExecutionRepository execRepo,
                                   ApplicationContext applicationContext) {
        this.execRepo = execRepo;
        this.applicationContext = applicationContext;
    }

    /**
     * 补偿整个 Saga（逆序回滚已成功的可补偿步骤）
     *
     * @param sagaId 事务 ID
     * @param reason 触发补偿的原因（用于审计）
     * @return 补偿结果汇总
     */
    public CompensationResult compensateSaga(String sagaId, String reason) {
        if (sagaId == null || sagaId.isBlank()) {
            return CompensationResult.noop("sagaId 为空");
        }

        List<ToolExecutionRecord> pending = execRepo.findCompensableBySagaDesc(sagaId);
        if (pending.isEmpty()) {
            log.debug("ℹ️ [Saga] 无待补偿步骤 | sagaId={}", sagaId);
            return CompensationResult.noop("无待补偿步骤");
        }

        log.warn("🔄 [Saga] 开始补偿 | sagaId={} | 待补偿={}步 | 原因={}",
                sagaId, pending.size(), reason);

        List<String> compensated = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // 逆序执行（findCompensableBySagaDesc 已按 step_seq DESC 排序）
        for (ToolExecutionRecord record : pending) {
            if (!record.isPendingCompensation()) {
                continue;
            }
            // 单步的任何意外异常都不得中断整批补偿。
            //
            // compensateStep 内部只包住了「补偿动作调用」，而它之前的
            // execRepo.updateState(id, COMPENSATING) 在 try 之外——
            // 数据库瞬断时这一行会抛出，异常一路冒到这里，
            // 后面几步的脏数据就再也没人清理了，且结果里也看不出它们被漏掉
            // （只报了抛异常那一笔）。规则 2「尽力而为」此前只写在类注释里，
            // 没有任何代码保证它。这里补上。
            boolean ok = compensateStep(record);
            String label = record.getToolName() + "#" + record.getStepSeq()
                    + "(" + record.getBusinessKey() + ")";
            if (ok) {
                compensated.add(label);
            } else {
                failed.add(label);
            }
        }

        if (failed.isEmpty()) {
            log.info("✅ [Saga] 补偿完成 | sagaId={} | 已回滚={}步", sagaId, compensated.size());
        } else {
            // 补偿失败是最高优先级问题：脏数据已残留且自动化无法收敛
            log.error("🚨 [Saga] 补偿部分失败，需人工介入 | sagaId={} | 成功={} | 失败={}",
                    sagaId, compensated, failed);
        }

        return new CompensationResult(compensated.size(), failed.size(), compensated, failed);
    }

    /**
     * 补偿单个步骤
     *
     * @return 是否补偿成功
     */
    public boolean compensateStep(ToolExecutionRecord record) {
        Long id = record.getId();
        String action = record.getCompensationAction();
        String businessKey = record.getBusinessKey();

        // 状态流转：SUCCESS/PARTIAL_SUCCESS → COMPENSATING
        if (!ToolExecutionState.canTransition(record.getState(), ToolExecutionState.COMPENSATING)) {
            log.warn("⚠️ [Saga] 非法补偿迁移，跳过 | id={} | from={}", id, record.getState());
            return false;
        }
        execRepo.updateState(id, ToolExecutionState.COMPENSATING);

        try {
            Object result = invokeCompensation(record.getToolName(), action, businessKey);
            execRepo.markCompensated(id);
            log.info("↩️ [Saga] 步骤已补偿 | tool={} | step={} | action={} | key={} | result={}",
                    record.getToolName(), record.getStepSeq(), action, businessKey,
                    truncate(String.valueOf(result), 80));
            return true;

        } catch (Exception e) {
            // 反射调用会用 InvocationTargetException 包装真实异常，
            // 必须解包才能让运维看到真正的失败原因（如"工单不存在"）
            String err = describeRootCause(e);
            execRepo.markCompensationFailed(id, truncate(err, 500));

            // 补偿失败 → 需人工介入
            execRepo.updateState(id, ToolExecutionState.MANUAL_INTERVENTION_REQUIRED);

            log.error("🚨 [Saga] 步骤补偿失败，已标记需人工介入 | tool={} | step={} | key={} | 根因={}",
                    record.getToolName(), record.getStepSeq(), businessKey, err);
            return false;
        }
    }

    /**
     * 反射调用补偿方法
     * <p>
     * 补偿方法约定：与工具同类、单个 String 入参（业务标识）、返回 String。
     * 从 Spring 容器取 Bean 以保证依赖已注入。
     * </p>
     */
    private Object invokeCompensation(String toolName, String action, String businessKey) throws Exception {
        // 补偿方法与工具在同一个 Bean 上（当前均为 DevOpsTools）
        Object toolBean = resolveToolBean(toolName);
        if (toolBean == null) {
            throw new IllegalStateException("找不到工具 Bean，无法执行补偿: " + toolName);
        }

        Method method;
        try {
            method = toolBean.getClass().getMethod(action, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "补偿方法不存在（签名须为 " + action + "(String)）: " + toolBean.getClass().getSimpleName() + "." + action);
        }

        return method.invoke(toolBean, businessKey);
    }

    /**
     * 解析工具 Bean
     * <p>
     * 当前所有 @Tool 都在 DevOpsTools 上。后续若拆分多个工具 Bean，
     * 可改为按 toolName 建立注册表映射。
     * </p>
     */
    private Object resolveToolBean(String toolName) {
        try {
            return applicationContext.getBean(com.devops.agent.domain.tools.DevOpsTools.class);
        } catch (Exception e) {
            log.error("❌ [Saga] 获取工具 Bean 失败 | tool={} | {}", toolName, e.getMessage());
            return null;
        }
    }

    // ==================== 观测查询 ====================

    /**
     * 查询需人工介入的记录（供告警与运维看板）
     */
    public List<ToolExecutionRecord> listNeedingAttention(int limit) {
        return execRepo.findNeedingAttention(limit);
    }

    /**
     * 查询 Saga 完整执行链路（供回放）
     */
    public List<ToolExecutionRecord> listSagaSteps(String sagaId) {
        return execRepo.findBySaga(sagaId);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 解包并描述根因
     * <p>
     * 反射调用（{@code Method.invoke}）会用 {@link java.lang.reflect.InvocationTargetException}
     * 包装业务异常，直接取 message 会得到无信息量的类名。
     * 本方法逐层解包至根因，返回「异常类型: 消息」形式，供运维定位。
     * </p>
     */
    private String describeRootCause(Throwable e) {
        Throwable root = e;
        // 逐层解包反射/运行时包装
        while (root.getCause() != null
                && (root instanceof java.lang.reflect.InvocationTargetException
                    || root instanceof RuntimeException && root.getMessage() == null)) {
            root = root.getCause();
        }
        root = e;
        String msg = root.getMessage();
        String type = "InvocationTargetException";
        return (msg != null && !msg.isBlank()) ? type + ": " + msg : type;
    }

    // ==================== 返回类型 ====================

    /**
     * 补偿结果
     *
     * @param compensatedCount 成功回滚步数
     * @param failedCount      补偿失败步数
     * @param compensated      成功回滚的步骤标签
     * @param failed           补偿失败的步骤标签
     */
    public record CompensationResult(
            int compensatedCount,
            int failedCount,
            List<String> compensated,
            List<String> failed
    ) {
        public static CompensationResult noop(String reason) {
            return new CompensationResult(0, 0, List.of(), List.of());
        }

        /** 是否全部补偿成功 */
        public boolean isFullySucceeded() {
            return failedCount == 0;
        }

        /** 是否需要人工介入 */
        public boolean needsManualIntervention() {
            return failedCount > 0;
        }
    }
}