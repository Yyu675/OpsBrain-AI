package com.devops.agent.domain.evidence;

import com.devops.agent.domain.rag.KnowledgeScopeResolver;
import com.devops.agent.domain.rag.RetrievedChunk;
import com.devops.agent.domain.rag.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识证据采集器（S2-1 第四方向，P0 2026-09-24：诊断接入知识库）。
 *
 * <p>
 * 在证据采集阶段检索知识库，把「这类故障在历史文档里怎么处理的」作为一条
 * KNOWLEDGE 证据纳入诊断。三重价值：
 * </p>
 * <ol>
 *   <li><b>假设有据可依</b>：假设生成器的提示词会拿到知识片段与出处，
 *       根因推理被手册/案例「压住」，不再是纯指标猜测；</li>
 *   <li><b>出处入账</b>：citation（【来源：标题 - 章节】）存进证据 sourceRef，
 *       假设的 evidenceIds 链到这条知识证据——诊断反馈即可按 citation
 *       反查 chunk 回流检索权重（飞轮最后一环真正打通）；</li>
 *   <li><b>四态自洽</b>：检索链路故障=FAILED、无相关文档=NO_DATA、
 *       命中=SUCCESS，与既有三方向同构，不污染充分性判定
 *       （KNOWLEDGE 不在 KEY_DIRECTIONS 里，只补证据不扰口径）。</li>
 * </ol>
 *
 * <p>失败方向与其他采集器一致：检索异常不抛、落 FAILED 证据继续；
 * 诊断是附属增值，知识缺位绝不能反噬告警入库/建单主流程。</p>
 */
@Component
public class KnowledgeEvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEvidenceCollector.class);

    /** 检索返回的引用片段数（诊断需要的是「最贴题的几篇」，不是越多越好） */
    private static final int TOP_K = 3;

    private final Retriever retriever;
    private final KnowledgeScopeResolver scopeResolver;

    public KnowledgeEvidenceCollector(Retriever retriever, KnowledgeScopeResolver scopeResolver) {
        this.retriever = retriever;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 按服务名检索知识库，产出一条 KNOWLEDGE 证据。
     * <p>service 为空时直接给 NO_DATA（无检索锚点）；scope 恒用系统范围——
     * 诊断是系统内部任务，不套调用人的可见权限。</p>
     */
    public Evidence collect(String service, String range) {
        return collect(service, range, null, null);
    }

    /**
     * 带症状锚点的知识检索。
     * <p>
     * 查询构造（2026-09-25 REAL 模式实测定稿）：告警名 + 告警描述，
     * <b>不带服务名</b>。实测数据（text-embedding 真实向量）：
     * 「内存使用率持续升高 kb-probe-svc 故障排查」得 0.66（熔断线 0.73 以下），
     * 而「内存使用率持续升高」得 0.78、「HostHighMemoryUsage + 描述」得 0.81——
     * 服务名是语义噪声，会把本来能命中的查询稀释到阈值线下。
     * 服务归属已由诊断会话承载，检索阶段只负责找「长得像的故障」。
     * </p>
     */
    public Evidence collect(String service, String range, String alertName, String alertSummary) {
        String title = "知识库相似文档";
        boolean hasAnchor = (alertName != null && !alertName.isBlank())
                || (alertSummary != null && !alertSummary.isBlank());
        if (!hasAnchor && (service == null || service.isBlank())) {
            return new Evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.KNOWLEDGE, title,
                    Map.of("reason", "服务与告警锚点均为空，无检索依据"),
                    "", null, Instant.now());
        }
        // 症状文本优先：告警名 + 摘要/描述是检索语义所在；服务名只在无线索时兜底
        String query;
        if (hasAnchor) {
            StringBuilder q = new StringBuilder();
            if (alertName != null && !alertName.isBlank()) q.append(alertName.trim());
            if (alertSummary != null && !alertSummary.isBlank()) {
                if (!q.isEmpty()) q.append(' ');
                q.append(alertSummary.trim());
            }
            query = q.toString();
        } else {
            query = service + " 故障排查";
        }
        List<RetrievedChunk> chunks;
        try {
            chunks = retriever.retrieveWithSource(query, TOP_K, scopeResolver.systemScope());
        } catch (Exception ex) {
            // 检索链路异常：按约定应为 null，这里再兜一道防异常上冒
            log.warn("⚠️ [KnowledgeEvidence] 检索异常（不反噬诊断主流程）| service={} | {}",
                    service, ex.getMessage());
            chunks = null;
        }

        if (chunks == null) {
            // 链路故障 ≠ 无文档（Retriever 约定 1）：落 FAILED 证据，提示检索服务不可用
            log.warn("⚠️ [KnowledgeEvidence] 知识检索不可用（链路故障，非无文档）| service={}", service);
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.KNOWLEDGE, title,
                    Map.of("query", query, "reason", "知识检索链路不可用"),
                    "", null, Instant.now());
        }
        if (chunks.isEmpty()) {
            return new Evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.KNOWLEDGE, title,
                    Map.of("query", query, "reason", "知识库无相关文档（低于相似度阈值）"),
                    "", null, Instant.now());
        }

        // SUCCESS：内容带片段正文（供假设生成器读），sourceRef 带 citation（供回流反查）
        List<Map<String, Object>> items = new ArrayList<>(chunks.size());
        StringBuilder refs = new StringBuilder();
        double maxScore = 0.0;
        for (RetrievedChunk c : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("citation", c.citation());
            item.put("text", c.text() == null ? "" : truncate(c.text(), 1200));
            item.put("score", c.score());
            // chunkId 随证据落库：回放接口原样返回 content，诊断页反馈时
            // 直接带回本次引用的切片 id，不再只靠 citation 字符串反查
            if (c.chunkId() != null) {
                item.put("chunkId", c.chunkId());
            }
            items.add(item);
            if (!refs.isEmpty()) refs.append(";");
            refs.append(c.citation());
            maxScore = Math.max(maxScore, c.score());
        }
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.KNOWLEDGE, title,
                Map.of("query", query, "hits", items),
                refs.toString(), clamp01(maxScore), Instant.now());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}