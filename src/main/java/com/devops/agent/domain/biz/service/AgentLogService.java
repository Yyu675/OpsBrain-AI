package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.AgentCallLog;
import com.devops.agent.domain.biz.repository.AgentCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent 调用日志服务
 * <p>
 * 职责: 记录每次 Agent 调用的详细信息(成本、延迟、缓存命中等)
 * <p>
 * MVP-4 审计增强：新增 operation_type、affected_resources、operator_id 参数
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Service
public class AgentLogService {

    private static final Logger log = LoggerFactory.getLogger(AgentLogService.class);

    private final AgentCallLogRepository logRepository;

    public AgentLogService(AgentCallLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    /**
     * 记录 Agent 调用日志（基础版，兼容旧调用）
     */
    public void saveLog(String traceId, String userQuery, String agentAnswer, String modelName,
                        boolean isCached, int latencyMs, double costRmb, String citations) {
        saveLog(traceId, userQuery, agentAnswer, modelName, isCached, latencyMs, costRmb, citations,
                "CHAT", "[]", "SYSTEM");
    }

    /**
     * 记录 Agent 调用日志（完整审计版，MVP-4）
     *
     * @param traceId           追踪 ID
     * @param userQuery         用户提问
     * @param agentAnswer       Agent 回答
     * @param modelName         使用的模型名称
     * @param isCached          是否命中缓存
     * @param latencyMs         调用耗时(毫秒)
     * @param costRmb           成本(人民币元)
     * @param citations         引用出处(JSON字符串)
     * @param operationType     操作类型（NEW/SEARCH/CREATE_TICKET/APPROVE/EXECUTE/COMPENSATE/VOID等）
     * @param affectedResources 影响的资源标识(JSON数组，如 ["TKT-20260808-0001", "pod-xyz"]）
     * @param operatorId        操作人（系统/用户ID/定时器）
     */
    public void saveLog(String traceId, String userQuery, String agentAnswer, String modelName,
                        boolean isCached, int latencyMs, double costRmb, String citations,
                        String operationType, String affectedResources, String operatorId) {
        AgentCallLog callLog = new AgentCallLog();
        callLog.setTraceId(traceId);
        callLog.setUserQuery(userQuery);
        callLog.setAgentAnswer(agentAnswer);
        callLog.setModelName(modelName);
        callLog.setIsCached(isCached);
        callLog.setLatencyMs(latencyMs);
        callLog.setCostRmb(costRmb);
        callLog.setCitations(citations);
        callLog.setOperationType(operationType);
        callLog.setAffectedResources(affectedResources);
        callLog.setOperatorId(operatorId);
        callLog.setCreateTime(LocalDateTime.now());

        logRepository.save(callLog);

        log.debug("📊 [AgentLog] 记录成功 | traceId={} | opType={} | resources={} | operator={} | 耗时={}ms | 成本=¥{} | 缓存={}",
                traceId, operationType, affectedResources, operatorId, latencyMs, costRmb, isCached);
    }

    /**
     * 查询调用总数(供看板使用)
     */
    public long getTotalCalls() {
        return logRepository.countAll();
    }

    /**
     * 查询缓存命中数(供看板使用)
     */
    public long getCacheHits() {
        return logRepository.countCacheHits();
    }

    /**
     * 查询平均成本(供看板使用)
     */
    public double getAvgCost() {
        return logRepository.getAvgCost();
    }
}
