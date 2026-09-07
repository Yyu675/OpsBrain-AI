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
@RequestMapping("/api/diagnosis")
@Tag(name = "诊断回放", description = "告警驱动诊断的证据/假设/会话回放 API（S2-3）")
public class DiagnosisController {

    private final DiagnosisSessionRepository sessionRepository;
    private final DiagnosisEvidenceRepository evidenceRepository;
    private final DiagnosisHypothesisRepository hypothesisRepository;

    public DiagnosisController(DiagnosisSessionRepository sessionRepository,
                               DiagnosisEvidenceRepository evidenceRepository,
                               DiagnosisHypothesisRepository hypothesisRepository) {
        this.sessionRepository = sessionRepository;
        this.evidenceRepository = evidenceRepository;
        this.hypothesisRepository = hypothesisRepository;
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
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", traceId);
        view.put("session", session);
        // 会话缺失时证据/假设仍可能是孤证→各自按仓储返回（不掩盖已落数据）
        view.put("evidences", evidenceRepository.findByTraceId(traceId));
        view.put("hypotheses", hypothesisRepository.findBySessionTraceId(traceId));
        return ApiResponse.success(view);
    }
}
