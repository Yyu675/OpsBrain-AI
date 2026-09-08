package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;

import java.util.List;

/**
 * 根因假设生成器抽象（S2-2，路线图 §6.2 2-2.3）。
 * <p>
 * 双轨设计（与本仓离线基调同族）：
 * <ul>
 *   <li><b>规则基线（{@link RuleBasedHypothesisGenerator}）永远可用</b>——
 *       LLM 停机/超时/越权时，诊断产出不退化为空；这是「推理栈瘫痪
 *       不掉假设生成能力」的工程承诺；</li>
 *   <li>LLM 版本（DevOpsAgentEngine 驱动）以可插拔 Bean 形态后补，
 *       失败时回落到规则基线——接口不变，编排器零感知。</li>
 * </ul>
 * </p>
 */
public interface HypothesisGenerator {

    /**
     * 生成 Top-3 根因假设（多在 1~3 条之间；INSUFFICIENT 态由编排器在
     * 更早一步终结，本接口只服务于 SUFFICIENT/WEAK 两态）。
     *
     * @param aggregated       聚合结果（方向成功计数 + 冲突 + 充分性）
     * @param evidenceWithIds  带持久化 id 的证据（support/contradict 关联键）
     * @return 按 rank 升序的假设列表；无物可说时返回空列表（不是异常）
     */
    List<Hypothesis> generate(EvidenceAggregator.AggregateResult aggregated,
                              List<RankedEvidence> evidenceWithIds);

    /**
     * 带持久化 id 的证据包装（落库 id ↔ 证据的桥，LLM 与规则版共用）。
     */
    record RankedEvidence(long id, Evidence evidence) {}
}
