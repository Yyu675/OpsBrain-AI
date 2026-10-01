package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断详情 API（S2-3，路线图 §6.3 2-3.2）。
 * <p>
 * 单端点多载荷：按 traceId 一次返回 会话 + 证据列表 + 假设列表——
 * 前端诊断页的「点开假设看证据」就靠这一个接口把回放链走完（§3 验收：
 * 完整证据链可打开）。
 * </p>
 * <p>
 * 读面只查既有仓储（V3/V4/V5 的索引都在 trace_id 链上），不在此层做
 * 二次聚合——详情页要的是「当时长什么样」，不是被再加工一次的叙事。
 * </p>
 */
@RestController
// 平台统一 /api/v1 前缀（前端 config/api.ts 的 DIAGNOSIS/DIAGNOSIS_FEEDBACK 均按 v1 取值）。
// 2026-10-01 前此处误为 "/api/diagnosis"：诊断回放、假设反馈（含知识 boost 回流）
// 自 S2-3 上线起对前端全部 404（测试纯直调不看 URL，故未拦住）——截图技能首跑揪出。
@RequestMapping("/api/v1/diagnosis")
@Tag(name = "诊断回放", description = "告警驱动诊断的证据/假设/会话回放 API（S2-3）")
public class DiagnosisController {

    private final DiagnosisSessionRepository sessionRepository;
    private final DiagnosisEvidenceRepository evidenceRepository;
    private final DiagnosisHypothesisRepository hypothesisRepository;
    private final com.devops.agent.domain.biz.repository.KnowledgeBoostRepository knowledgeBoostRepository;

    /**
     * 当前 AI 模式（MOCK/REAL）。MOCK 下语义检索走哈希假向量，知识证据恒为
     * NO_DATA——不回传这个口径，诊断页会把「未启用」误读成「知识库里没有」，
     * 排查方向整个偏掉（2026-09-25 实测踩中）。
     */
    @org.springframework.beans.factory.annotation.Value("${devops.ai.mode:MOCK}")
    private String aiMode;

    public DiagnosisController(DiagnosisSessionRepository sessionRepository,
                               DiagnosisEvidenceRepository evidenceRepository,
                               DiagnosisHypothesisRepository hypothesisRepository,
                               com.devops.agent.domain.biz.repository.KnowledgeBoostRepository knowledgeBoostRepository) {
        this.sessionRepository = sessionRepository;
        this.evidenceRepository = evidenceRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.knowledgeBoostRepository = knowledgeBoostRepository;
    }

    /**
     * 按 traceId 取完整诊断回放链。
     *
     * @return {session, evidences, hypotheses}；会话不存在时返回空对象（
     *         不抛 404——traceId 前端agnostically传进来，空对象比对空更友好）
     */
    @GetMapping("/{traceId}")
    @Operation(summary = "按 traceId 回放完整诊断链（会话+证据+假设）")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String traceId) {
        Map<String, Object> session = sessionRepository.findByTraceId(traceId);
        // 工单「查看诊断回放」带来的是告警去重键（source_trace_id 存的就是它），
        // 不是诊断自己的 traceId。按 traceId 查空时，尝试把它当去重键桥接到
        // 该告警最新一次诊断——桥不上就维持原样返回空，不改变旧调用方的语义。
        if (session.isEmpty()) {
            String resolved = sessionRepository.findTraceIdByAlertDedupKey(traceId);
            if (resolved != null) {
                traceId = resolved;
                session = sessionRepository.findByTraceId(traceId);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", traceId);
        view.put("session", session);
        view.put("aiMode", aiMode);
        // 会话缺失时证据/假设仍可能是孤证→各自按仓储返回（不掩盖已落数据）
        view.put("evidences", evidenceRepository.findByTraceId(traceId));
        view.put("hypotheses", hypothesisRepository.findBySessionTraceId(traceId));
        return ApiResponse.success(view);
    }

    /**
     * 用户反馈入口（路线图 §6.3 2-3.5）：标记假设质量 + 可选的知识回流。
     * <p>
     * 语义契约：HELPFUL（完全有用）/ PARTIAL（部分正确）/ WRONG（错误）。
     * 前端传回 hypothesisId + 本次诊断引用的 chunkIds（从知识证据载荷的
     * chunkId 提取，取不到传空数组）。字段缺失（null）时后端才按 citation
     * 反查兜底——空数组是「本次无可引用切片」的明确结论，不再兜底。
     * </p>
     */
    @org.springframework.web.bind.annotation.PostMapping("/hypothesis/feedback")
    @Operation(summary = "标记假设质量 + 可选的知识 boost 回流")
    public ApiResponse<java.util.Map<String, Object>> feedback(
            @org.springframework.web.bind.annotation.RequestBody FeedbackRequest req) {
        String verdict = normalizeVerdict(req.feedback());
        if (verdict == null) {
            return ApiResponse.error(400, "feedback 必须是 HELPFUL / PARTIAL / WRONG 之一");
        }
        int updated = hypothesisRepository.updateFeedback(req.hypothesisId(), verdict);
        if (updated == 0) {
            return ApiResponse.error(404, "hypothesisId 不存在: " + req.hypothesisId());
        }
        int boosted = 0;
        // 飞轮闭环（2026-09-24，P0）：前端没传 chunkIds 时，从该假设引用的
        // 知识证据 citation 里反查 chunk——诊断已接入知识库（KnowledgeEvidenceCollector），
        // 假设引用的证据里若是知识文档，反馈就应回流其检索权重，不再依赖前端自觉。
        //
        // 判据是「字段缺失」而不是「为空」：前端始终传本次引用的切片 id
        // （#6，证据载荷里的 chunkId），取不到时传空数组——那是「本次没有
        // 可引用切片」的明确结论，不该再被 citation 反查覆盖（反查按标题
        // 命中，会把同名文档的全部切片算进来，比不回流更糟）。
        List<Long> chunkIds = req.chunkIds();
        if (chunkIds == null) {
            chunkIds = resolveChunksFromEvidence(req.hypothesisId());
        }
        if (chunkIds != null) {
            for (Long chunkId : chunkIds) {
                if (chunkId != null) {
                    knowledgeBoostRepository.recordFeedback(chunkId, verdict);
                    boosted++;
                }
            }
        }
        return ApiResponse.success(java.util.Map.of(
                "hypothesisId", req.hypothesisId(),
                "feedback", verdict,
                "knowledgeBoosted", boosted));
    }

    /**
     * 从假设引用的知识证据反查被引 chunk（feedback 未带 chunkIds 时的回流兜底）。
     * <p>任何一步取不到（无引用证据 / 无知识证据 / 反查为空）都返回 null，
     * 上游按「无可回流」处理——绝不让回流兜底反噬反馈主流程。</p>
     */
    private java.util.List<Long> resolveChunksFromEvidence(long hypothesisId) {
        try {
            List<Long> evidenceIds = hypothesisRepository.findEvidenceIdsById(hypothesisId);
            if (evidenceIds.isEmpty()) {
                return null;
            }
            List<String> citations = evidenceRepository.findKnowledgeSourceRefs(evidenceIds);
            if (citations.isEmpty()) {
                return null;
            }
            return new java.util.ArrayList<>(knowledgeBoostRepository.resolveChunkIds(citations));
        } catch (Exception e) {
            // 回流是增值冒险：解析失败只告警，不反噬「反馈已落库」这一事实
            org.slf4j.LoggerFactory.getLogger(DiagnosisController.class)
                    .warn("⚠️ [Diagnosis] 反馈回流解析失败（不影响反馈本身）| hypothesisId={} | {}",
                            hypothesisId, e.getMessage());
            return null;
        }
    }

    /** 反馈请求体（record 字段即 JSON 键名，零转换）。 */
    public record FeedbackRequest(
            long hypothesisId,
            String feedback,
            java.util.List<Long> chunkIds) {}

    private static String normalizeVerdict(String verdict) {
        if (verdict == null) return null;
        String upper = verdict.trim().toUpperCase();
        return switch (upper) {
            case "HELPFUL", "PARTIAL", "WRONG" -> upper;
            default -> null;
        };
    }

}
