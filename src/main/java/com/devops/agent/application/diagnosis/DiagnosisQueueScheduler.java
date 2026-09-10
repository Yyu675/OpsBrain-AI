package com.devops.agent.application.diagnosis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 诊断排队扫描器（批 76 / P2-3，报告 174 审计件）。
 *
 * <h3>背景</h3>
 * <p>
 * 诊断池（core=4/max=8/queue=100）满负荷时，原拒绝策略是「丢弃 + WARN」——
 * 风暴高峰期被丢的告警<b>永远不再有诊断</b>，且只有一条日志证明它来过。
 * 批 76 改为落库排队（QUEUED 会话行占位），本扫描器周期性捞起重跑。
 * </p>
 *
 * <h3>捞起语义</h3>
 * <ul>
 *   <li><b>先进先出</b>：按 created_at ASC，老风暴先消化；</li>
 *   <li><b>跳过仍在 RUNNING 的同告警</b>：NOT EXISTS 子查询保证不与
 *       进行中的诊断重复（诊断幂等铁律 §6.1 验收 4）；</li>
 *   <li><b>CAS 置位</b>：QUEUED→RUNNING 带 status='QUEUED' 谓词，
 *       多实例部署时两台扫描器同时捞同一行只有一方成功——天然分布式安全；</li>
 *   <li><b>捞取限量</b>：每轮最多捞起「池队列余量」条——按池当前水位注入，
 *       不在池满时再触发一轮拒绝排队（排队风暴自锁）。</li>
 * </ul>
 *
 * <p>QUEUED 行滞留上限：3 轮扫描（约 3 分钟）后仍无法置位（同告警 RUNNING
 * 一直不结束）属诊断线卡死——保持 QUEUED 不动，由告警链路的
 * 诊断会话超时治理收口（诊断 RUNNING 行的卡死检测是另一个问题面，不在本件）。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-10（批 76，报告 174 P2-3）
 */
@Component
public class DiagnosisQueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisQueueScheduler.class);

    private final DiagnosisOrchestrator orchestrator;
    private final com.devops.agent.domain.biz.repository.DiagnosisSessionRepository sessionRepository;

    public DiagnosisQueueScheduler(DiagnosisOrchestrator orchestrator,
                                   com.devops.agent.domain.biz.repository.DiagnosisSessionRepository sessionRepository) {
        this.orchestrator = orchestrator;
        this.sessionRepository = sessionRepository;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void drainQueued() {
        try {
            // 按池余量限量捞起——池满时不注入（下轮再看），防排队→拒绝→再排队的自锁
            int capacity = orchestrator.remainingQueueCapacity();
            if (capacity <= 0) {
                log.debug("🧵 [DiagnosisQueue] 池仍满负荷，本轮不捞起");
                return;
            }
            var queued = sessionRepository.findQueued(Math.min(capacity, 20));
            if (queued.isEmpty()) {
                return;
            }
            int resumed = 0;
            for (Map<String, Object> row : queued) {
                Long id = ((Number) row.get("id")).longValue();
                // CAS 置位：赢了才提交（多实例安全；输家=别的实例已捞起）
                if (sessionRepository.markQueuedRunning(id) > 0) {
                    Long alertId = ((Number) row.get("alert_id")).longValue();
                    String ticketId = (String) row.get("ticket_id");
                    String service = (String) row.get("service");
                    String traceId = (String) row.get("trace_id");
                    orchestrator.resumeQueued(traceId, alertId, ticketId, service);
                    resumed++;
                }
            }
            if (resumed > 0) {
                log.info("🧵 [DiagnosisQueue] 本轮捞起排队诊断 {} 条（候选 {}）", resumed, queued.size());
            }
        } catch (Exception e) {
            // 扫描器自身故障留痕不炸线程（与 ApprovalExpireScheduler 同纪律）
            log.error("❌ [DiagnosisQueue] 排队扫描失败（下一轮 60s 后重试）", e);
        }
    }
}
