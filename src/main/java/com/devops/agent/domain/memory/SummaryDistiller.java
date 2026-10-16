package com.devops.agent.domain.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键事实蒸馏器
 * <p>
 * 参考 Agent Methodology §6.4：会话总结不是文学概括，而是<b>可执行事实蒸馏</b>。
 * </p>
 * <p>
 * <b>实现选型</b>：采用本地规则抽取，不额外调用大模型。理由：
 * <ul>
 *   <li>成本：每轮会话额外调一次模型做摘要，成本翻倍且延迟增加</li>
 *   <li>可控：正则抽取的资源 ID/错误码/版本号等硬信息比模型生成更可靠，
 *       符合"不让模型自己决定什么是事实"的原则</li>
 *   <li>可演进：接口保持稳定，后续可替换为模型蒸馏（见 {@code distillWithModel} TODO）</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class SummaryDistiller {

    // ==================== 事实抽取模式 ====================

    /** 工单号 */
    private static final Pattern TICKET_ID = Pattern.compile("TKT-\\d{8}-\\d{4}");

    /** 知识库文档编号 */
    private static final Pattern KB_REF = Pattern.compile("KB-[A-Z]+-\\d+");

    /** 常见运维错误码/状态 */
    private static final Pattern ERROR_CODE = Pattern.compile(
            "(?i)\\b(OOMKilled|CrashLoopBackOff|ImagePullBackOff|Evicted|Pending|FailedMount|" +
            "ErrImagePull|CreateContainerError|NodeNotReady|DiskPressure|MemoryPressure|" +
            "5\\d{2}\\s+(?:Bad Gateway|Internal Server Error|Service Unavailable|Gateway Timeout)|" +
            "ECONNREFUSED|ETIMEDOUT|EHOSTUNREACH)\\b");

    /** 版本号（如 1.28、v1.28.3、8.0.35） */
    private static final Pattern VERSION = Pattern.compile(
            "(?i)\\b(?:version\\s+|v)?(\\d+\\.\\d+(?:\\.\\d+)?)\\b");

    /** 资源配额（如 512Mi、2Gi、1000m、4 CPU） */
    private static final Pattern RESOURCE_SPEC = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?\\s*(?:Mi|Gi|Ki|MB|GB|m|CPU|core)\\b");

    /** K8s / 云资源名（如 payment-service、db-cluster-prod-01） */
    private static final Pattern RESOURCE_NAME = Pattern.compile(
            "\\b[a-z][a-z0-9]*(?:-[a-z0-9]+){1,4}\\b");

    /** 结论性语句引导词 */
    private static final Pattern CONCLUSION_LEAD = Pattern.compile(
            "(?:根因|根本原因|原因是|问题在于|建议|应该|推荐|需要|解决方案|处理方式)[：:]?\\s*(.{5,120}?)(?:[。\\n]|$)");

    /** 遗留风险引导词 */
    private static final Pattern RISK_LEAD = Pattern.compile(
            "(?:待|需要进一步|仍需|注意|风险|尚未|未验证|后续)[：:]?\\s*(.{5,100}?)(?:[。\\n]|$)");

    /** 单项事实最大长度，防止把整段话当事实 */
    private static final int MAX_FACT_LEN = 120;

    /** 各类事实最大条数，控制 Token 占用 */
    private static final int MAX_FACTS_PER_KIND = 6;

    // ==================== 公开接口 ====================

    /**
     * 从单轮对话蒸馏关键事实
     *
     * @param userQuery   用户提问
     * @param aiAnswer    AI 回答
     * @param toolResults 工具执行结果（toolName → 结果文本）
     * @return 关键事实
     */
    public KeyFacts distill(String userQuery, String aiAnswer, List<Map<String, Object>> toolResults) {
        KeyFacts facts = new KeyFacts();

        // 1. 意图：取用户提问首句（截断）
        facts.setIntent(extractIntent(userQuery));

        // 2. 硬事实：从提问与回答中抽取错误码、版本、配额、资源名
        String combined = safe(userQuery) + "\n" + safe(aiAnswer);
        for (String f : extractHardFacts(combined)) {
            facts.addConfirmedFact(f);
        }

        // 3. 工具痕迹
        if (toolResults != null) {
            for (Map<String, Object> tr : toolResults) {
                Object name = tr.get("toolName");
                Object result = tr.get("result");
                if (name == null) continue;
                String resultStr = result != null ? result.toString() : "";
                boolean success = !resultStr.contains("失败") && !resultStr.contains("❌");
                facts.addToolTrace(name.toString(), truncate(resultStr, 80), success);
            }
        }

        // 4. 结论
        facts.setConclusion(extractFirstMatch(aiAnswer, CONCLUSION_LEAD));

        // 5. 遗留风险
        String risk = extractFirstMatch(aiAnswer, RISK_LEAD);
        if (risk != null) {
            facts.getPendingRisks().add(risk);
        }

        // 6. 引用溯源：工单号 + 知识库编号
        for (String c : matchAll(combined, TICKET_ID, MAX_FACTS_PER_KIND)) {
            facts.addCitation(c);
        }
        for (String c : matchAll(combined, KB_REF, MAX_FACTS_PER_KIND)) {
            facts.addCitation(c);
        }

        return facts;
    }

    /**
     * 合并新旧事实（多轮累积）
     * <p>
     * 策略：意图与结论以最新为准（反映当前焦点），
     * 硬事实、工具痕迹、引用做去重累加（保留历史发现）。
     * </p>
     */
    public KeyFacts merge(KeyFacts existing, KeyFacts fresh) {
        if (existing == null) return fresh;
        if (fresh == null) return existing;

        KeyFacts merged = new KeyFacts();

        // 意图/结论：以最新为准，新值为空则沿用旧值
        merged.setIntent(firstNonBlank(fresh.getIntent(), existing.getIntent()));
        merged.setConclusion(firstNonBlank(fresh.getConclusion(), existing.getConclusion()));

        // 硬事实：去重累加，限制总量（保留最新，P2-30）
        Set<String> allFacts = new LinkedHashSet<>(existing.getConfirmedFacts());
        allFacts.addAll(fresh.getConfirmedFacts());
        merged.setConfirmedFacts(limitTail(new ArrayList<>(allFacts), MAX_FACTS_PER_KIND * 2));

        // 工具痕迹：累加，保留最近若干
        List<KeyFacts.ToolTrace> traces = new ArrayList<>(existing.getToolsUsed());
        traces.addAll(fresh.getToolsUsed());
        merged.setToolsUsed(limitTail(traces, MAX_FACTS_PER_KIND));

        // 风险：去重累加
        Set<String> allRisks = new LinkedHashSet<>(existing.getPendingRisks());
        allRisks.addAll(fresh.getPendingRisks());
        merged.setPendingRisks(limit(new ArrayList<>(allRisks), MAX_FACTS_PER_KIND));

        // 引用：去重累加
        Set<String> allCitations = new LinkedHashSet<>(existing.getCitations());
        allCitations.addAll(fresh.getCitations());
        merged.setCitations(limit(new ArrayList<>(allCitations), MAX_FACTS_PER_KIND * 2));

        return merged;
    }

    /**
     * 生成会话摘要文本（供人工浏览「历史会话」列表）
     */
    public String buildSummaryText(KeyFacts facts, int turnCount) {
        if (facts == null || facts.isEmpty()) {
            return "（本次会话无有效事实）";
        }
        StringBuilder sb = new StringBuilder();
        if (facts.getIntent() != null) {
            sb.append(facts.getIntent());
        }
        if (facts.getConclusion() != null) {
            sb.append(" → ").append(facts.getConclusion());
        }
        sb.append("（").append(turnCount).append(" 轮");
        if (!facts.getToolsUsed().isEmpty()) {
            sb.append("，调用 ").append(facts.getToolsUsed().size()).append(" 次工具");
        }
        sb.append("）");
        return truncate(sb.toString(), 300);
    }

    // ==================== 内部实现 ====================

    private String extractIntent(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) return null;
        // 取首句（以句号/换行/问号断句）
        String[] parts = userQuery.split("[。\\n？?！!]", 2);
        return truncate(parts[0].trim(), MAX_FACT_LEN);
    }

    /**
     * 抽取硬事实：错误码 > 资源配额 > 版本号 > 资源名
     * <p>按可靠性排序，错误码最可靠，资源名最容易误匹配故放最后且限量。</p>
     */
    private List<String> extractHardFacts(String text) {
        List<String> facts = new ArrayList<>();
        facts.addAll(matchAll(text, ERROR_CODE, MAX_FACTS_PER_KIND));
        facts.addAll(matchAll(text, RESOURCE_SPEC, 4));
        facts.addAll(matchAll(text, VERSION, 3));
        // 资源名限 3 个，避免噪音
        facts.addAll(matchAll(text, RESOURCE_NAME, 3));
        return facts;
    }

    private List<String> matchAll(String text, Pattern p, int max) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find() && found.size() < max) {
            String v = m.group().trim();
            if (!v.isEmpty() && v.length() <= MAX_FACT_LEN) {
                found.add(v);
            }
        }
        return new ArrayList<>(found);
    }

    private String extractFirstMatch(String text, Pattern p) {
        if (text == null || text.isBlank()) return null;
        Matcher m = p.matcher(text);
        if (m.find() && m.groupCount() >= 1) {
            String v = m.group(1);
            return v != null ? truncate(v.trim(), MAX_FACT_LEN) : null;
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    /** 保留尾部 N 项（工具痕迹取最近的更有价值） */
    private <T> List<T> limitTail(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(list.size() - max, list.size()));
    }

    // TODO: 后续可选的模型蒸馏实现
    //  优势：能提炼隐含语义结论；劣势：成本翻倍、延迟增加、事实可能被模型改写
    //  实现要点：用小模型（turbo）+ 严格 JSON Schema 约束输出，
    //  且硬事实（错误码/版本/资源）仍以本地正则为准，仅让模型补 intent/conclusion
    // public KeyFacts distillWithModel(String userQuery, String aiAnswer, ChatModel model) { ... }
}