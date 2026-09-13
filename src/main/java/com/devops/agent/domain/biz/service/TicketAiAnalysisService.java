package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.TicketAiAnalysis;
import com.devops.agent.domain.biz.repository.TicketAiAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 工单 AI 分析服务（策略 B）
 * <p>
 * 职责：持久化 AI 分析（结构化字段 + 多版本）、记录用户反馈、准确率统计。
 * </p>
 * <p>
 * 结构化字段（reasons/commands/citations/confidence）由前端解析后传入——
 * 前端已有 {@code parseStructuredAnalysis} / {@code extractCitationsFromText}，
 * content 是真相源。后端不重复实现一套解析器，避免两套解析逻辑漂移。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
@Service
public class TicketAiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TicketAiAnalysisService.class);

    /** content 长度上限，防止超大文本撑爆存储（AI 分析通常几百字到 2K） */
    private static final int MAX_CONTENT_LEN = 20000;

    private final TicketAiAnalysisRepository repository;

    public TicketAiAnalysisService(TicketAiAnalysisRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一次分析（version 由仓储自增）
     *
     * @param ticketId   工单号
     * @param content    原始 markdown 全文
     * @param reasons    可能原因（前端解析）
     * @param commands   排查命令（前端解析）
     * @param citations  引用来源（前端解析）
     * @param confidence 置信度 0-100，可空
     * @param costRmb    本次成本
     * @return 回填 id/version 的实体
     */
    public TicketAiAnalysis save(String ticketId, String content,
                                 List<String> reasons, List<String> commands,
                                 List<String> citations, Integer confidence, BigDecimal costRmb) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("工单号不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("分析内容不能为空");
        }
        String safeContent = content.length() > MAX_CONTENT_LEN
                ? content.substring(0, MAX_CONTENT_LEN) : content;

        // 置信度越界纠偏：模型偶发给出 >100 或负数
        Integer conf = confidence;
        if (conf != null) conf = Math.max(0, Math.min(100, conf));

        TicketAiAnalysis a = new TicketAiAnalysis();
        a.setTicketId(ticketId.trim());
        a.setContent(safeContent);
        a.setReasons(reasons);
        a.setCommands(commands);
        a.setCitations(citations);
        a.setConfidence(conf);
        a.setCostRmb(costRmb != null ? costRmb : BigDecimal.ZERO);
        return repository.insert(a);
    }

    /** 取最新分析（当前结论），无则 null */
    public TicketAiAnalysis getLatest(String ticketId) {
        return repository.findLatest(ticketId);
    }

    /**
     * 工单近期是否已有分析（方案 3 批 78：诊断自动回填前的查重）
     *
     * <p>背景：AI 分析有两条独立写库路径——前端详情页手动/自动生成、
     * 告警诊断编排器自动回填。BUG.md 现场（2026-09-13）暴露同一工单
     * 两条路径各写一版：用户打开详情页看到「版本 1」的诊断结论，点开
     * 历史又冒出一版前端生成——重复且成本翻倍。</p>
     *
     * <p>去重口径：工单在 {@code withinMinutes} 内已有任何版本即视为重复——
     * 自动回填是「附属增值」不是「权威结论」，既然近期已有人（或另一条
     * 自动链路）产出过分析，就不必再插一版。用户真想要新结论，
     * 「重新分析」按钮永远可用（手动路径不受此限）。</p>
     *
     * @return true = 近期已有分析，调用方应跳过自动写入
     */
    public boolean hasRecentAnalysis(String ticketId, int withinMinutes) {
        if (ticketId == null || ticketId.isBlank()) return false;
        return repository.existsSince(ticketId, withinMinutes);
    }

    /** 取全部版本（version 倒序） */
    public List<TicketAiAnalysis> listVersions(String ticketId) {
        return repository.findByTicketId(ticketId);
    }

    /**
     * 记录反馈
     *
     * @param analysisId 分析 id
     * @param helpful    true=有用 / false=没用
     * @return true=记录成功，false=分析不存在
     */
    public boolean recordFeedback(Long analysisId, boolean helpful) {
        String fb = helpful ? TicketAiAnalysis.FEEDBACK_HELPFUL : TicketAiAnalysis.FEEDBACK_UNHELPFUL;
        int rows = repository.updateFeedback(analysisId, fb);
        if (rows == 0) {
            log.warn("⚠️ [AiAnalysisService] 反馈记录失败，分析不存在 | analysisId={}", analysisId);
            return false;
        }
        log.info("👍 [AiAnalysisService] 反馈已记录 | analysisId={} | feedback={}", analysisId, fb);
        return true;
    }

    /**
     * 准确率统计
     * <p>在计数基础上补充 helpfulRate（有用数 / 已评价数），已评价为 0 时为 0。</p>
     */
    public Map<String, Object> accuracyStats() {
        Map<String, Long> raw = repository.feedbackStats();
        long rated = raw.getOrDefault("rated", 0L);
        long helpful = raw.getOrDefault("helpful", 0L);
        double rate = rated > 0 ? (double) helpful / rated : 0.0;
        return Map.of(
                "total", raw.getOrDefault("total", 0L),
                "rated", rated,
                "helpful", helpful,
                "unhelpful", raw.getOrDefault("unhelpful", 0L),
                // 保留 3 位小数，前端按百分比展示
                "helpfulRate", Math.round(rate * 1000) / 1000.0
        );
    }

    /** 工单删除时级联清理 */
    public int deleteByTicketId(String ticketId) {
        return repository.deleteByTicketId(ticketId);
    }
}
