package com.devops.agent.common.guard;

import com.devops.agent.common.exception.SecurityGuardException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Injection 检测卫士
 * <p>
 * 职责：检测并拦截恶意提示词注入攻击，参考 Agent Methodology §8.3。
 * <p>
 * 威胁模型：假设所有外部文本不可信，包括：
 * <ul>
 *   <li>用户输入</li>
 *   <li>工单备注/描述</li>
 *   <li>邮件正文</li>
 *   <li>日志内容</li>
 *   <li>OCR 文本</li>
 *   <li>爬虫结果</li>
 *   <li>第三方接口返回</li>
 *   <li>知识库文档（可能被污染）</li>
 * </ul>
 * </p>
 * <p>
 * 检测策略：
 * <ol>
 *   <li>模式匹配：正则表达式识别常见注入模式</li>
 *   <li>关键词检测：敏感指令词汇</li>
 *   <li>结构异常：异常的标记、分隔符、编码</li>
 *   <li>长度异常：超长输入可能隐藏注入</li>
 * </ol>
 * </p>
 * <p>
 * 处置动作：
 * <ul>
 *   <li>阻断：抛出 SecurityGuardException，终止处理</li>
 *   <li>告警：记录详细审计日志（traceId、来源、匹配规则、原文片段）</li>
 *   <li>标记：在上下文中标记来源为"非可信"</li>
 * </ul>
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class PromptInjectionGuard {

    /**
     * 注入来源枚举（用于审计和差异化策略）
     */
    public enum InjectionSource {
        USER_INPUT("用户输入"),
        KNOWLEDGE_DOC("知识库文档"),
        TICKET_DESC("工单描述"),
        TOOL_RESULT("工具结果"),
        EXTERNAL_API("外部接口"),
        LOG_CONTENT("日志内容"),
        UNKNOWN("未知来源");

        private final String displayName;

        InjectionSource(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * 检测结果
     */
    public static class DetectionResult {
        private final boolean injected;
        private final List<String> matchedPatterns;
        private final String severity; // LOW/MEDIUM/HIGH/CRITICAL
        private final String recommendation; // BLOCK/SANITIZE/MONITOR

        private DetectionResult(boolean injected, List<String> matchedPatterns, String severity, String recommendation) {
            this.injected = injected;
            this.matchedPatterns = matchedPatterns;
            this.severity = severity;
            this.recommendation = recommendation;
        }

        public static DetectionResult clean() {
            return new DetectionResult(false, List.of(), "NONE", "ALLOW");
        }

        public static DetectionResult injected(List<String> patterns, String severity, String recommendation) {
            return new DetectionResult(true, patterns, severity, recommendation);
        }

        public boolean isInjected() { return injected; }
        public List<String> getMatchedPatterns() { return matchedPatterns; }
        public String getSeverity() { return severity; }
        public String getRecommendation() { return recommendation; }
    }

    // ==================== 注入模式规则库 ====================

    /**
     * 高危注入模式（直接阻断）
     * 匹配：忽略指令、角色扮演、系统提示词覆盖、代码执行
     */
    private static final List<PatternRule> CRITICAL_PATTERNS = List.of(
            new PatternRule("IGNORE_INSTRUCTIONS",
                    Pattern.compile("(?i)(ignore|disregard|forget|override|bypass).{0,20}(previous|above|system|initial|original).{0,20}(instruction|prompt|rule|direction|guideline)",
                            Pattern.CASE_INSENSITIVE),
                    "忽略/覆盖系统指令"),
            new PatternRule("ROLE_PLAY",
                    Pattern.compile("(?i)(you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be|roleplay|role\\s*play).{0,30}(admin|root|system|developer|unrestricted|unfiltered|unlimited)",
                            Pattern.CASE_INSENSITIVE),
                    "角色扮演绕过"),
            new PatternRule("SYSTEM_PROMPT_LEAK",
                    Pattern.compile("(?i)(system\\s*[:=]|<\s*system\s*>|system\\s+prompt|initial\\s+prompt).{0,20}(reveal|show|print|output|display|tell\\s+me)",
                            Pattern.CASE_INSENSITIVE),
                    "系统提示词泄露诱导"),
            new PatternRule("CODE_EXECUTION",
                    Pattern.compile("(?i)(execute|run|eval|exec|system|shell|bash|cmd|powershell|python|javascript|java).{0,10}(code|command|script|payload)",
                            Pattern.CASE_INSENSITIVE),
                    "代码/命令执行诱导"),
            new PatternRule("SQL_INJECTION",
                    Pattern.compile("(?i)(union\\s+select|drop\\s+table|delete\\s+from|truncate\\s+table|insert\\s+into|update\\s+set|alter\\s+table|create\\s+table)",
                            Pattern.CASE_INSENSITIVE),
                    "SQL 注入模式"),
            new PatternRule("PROMPT_CONTINUATION",
                    Pattern.compile("(?i)(continue|complete|finish).{0,10}(the\\s+prompt|this\\s+prompt|above\\s+text|previous\\s+message)",
                            Pattern.CASE_INSENSITIVE),
                    "提示词延续攻击")
    );

    /**
     * 中危注入模式（清洗+告警）
     * 匹配：敏感信息窃取、编码混淆、结构破坏
     */
    private static final List<PatternRule> HIGH_PATTERNS = List.of(
            // 语序无关：动词在前（show me the api_key）
            new PatternRule("SENSITIVE_EXTRACTION",
                    Pattern.compile("(?i)(show|reveal|print|output|display|give\\s+me|tell\\s+me|dump|leak|list)" +
                                    ".{0,30}" +
                                    "(api[_-]?key|secret|password|passwd|token|credential|private\\s+key|access\\s+key|环境变量|密钥|密码)",
                            Pattern.CASE_INSENSITIVE),
                    "敏感信息窃取（动词在前）"),
            // 语序无关：名词在前（api_key, show it）
            new PatternRule("SENSITIVE_EXTRACTION_REVERSE",
                    Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token|credential|private\\s+key|access\\s+key|环境变量|密钥|密码)" +
                                    ".{0,30}" +
                                    "(show|reveal|print|output|display|give\\s+me|tell\\s+me|dump|leak|是什么|多少)",
                            Pattern.CASE_INSENSITIVE),
                    "敏感信息窃取（名词在前）"),
            new PatternRule("ENCODING_OBFUSCATION",
                    Pattern.compile("(?i)(base64|rot13|hex|unicode|urlencode|escape).{0,10}(decode|decrypt|convert|transform)",
                            Pattern.CASE_INSENSITIVE),
                    "编码混淆绕过"),
            new PatternRule("STRUCTURE_BREAK",
                    Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]", Pattern.CASE_INSENSITIVE),
                    "控制字符注入"),
            new PatternRule("DELIMITER_INJECTION",
                    Pattern.compile("(?i)(###|---|===|\\|\\|\\||<<<|>>>|```|```).{0,20}(system|user|assistant|instruction|prompt)",
                            Pattern.CASE_INSENSITIVE),
                    "分隔符注入")
    );

    /**
     * 低危注入模式（监控+标记）
     * 匹配：异常长度、重复模式、语言切换
     */
    private static final List<PatternRule> MEDIUM_PATTERNS = List.of(
            new PatternRule("EXCESSIVE_LENGTH",
                    null, // 长度检查单独处理
                    "输入长度异常"),
            new PatternRule("REPETITIVE_PATTERN",
                    Pattern.compile("(.{10,})\\1{3,}", Pattern.CASE_INSENSITIVE),
                    "重复模式填充"),
            new PatternRule("LANGUAGE_SWITCH",
                    Pattern.compile("(?i)(switch|change|speak|write|reply).{0,10}(language|lang|中文|英文|english|chinese)",
                            Pattern.CASE_INSENSITIVE),
                    "语言切换诱导")
    );

    /**
     * 敏感信息模式（数据脱敏保护）
     */
    private static final List<PatternRule> SENSITIVE_PATTERNS = List.of(
            new PatternRule("API_KEY_PATTERN",
                    Pattern.compile("(sk|pk|ak|api)[_-]?[a-zA-Z0-9]{20,}", Pattern.CASE_INSENSITIVE),
                    "API Key 疑似泄露"),
            new PatternRule("JWT_TOKEN",
                    Pattern.compile("eyJ[A-Za-z0-9_-]*\\.eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*", Pattern.CASE_INSENSITIVE),
                    "JWT Token 疑似泄露"),
            new PatternRule("PRIVATE_KEY",
                    Pattern.compile("-----BEGIN (RSA|EC|DSA|OPENSSH) PRIVATE KEY-----", Pattern.CASE_INSENSITIVE),
                    "私钥泄露"),
            new PatternRule("CONNECTION_STRING",
                    Pattern.compile("(jdbc:|mongodb://|redis://|postgresql://|mysql://)[^\\s]+", Pattern.CASE_INSENSITIVE),
                    "连接串泄露")
    );

    /**
     * 长度阈值
     */
    private static final int MAX_SAFE_LENGTH = 10000;
    private static final int WARNING_LENGTH = 5000;

    /**
     * 规则定义
     */
    private record PatternRule(String name, Pattern pattern, String description) {}

    // ==================== 公开检测方法 ====================

    /**
     * 检测输入是否包含注入攻击
     *
     * @param input    待检测文本
     * @param source   来源分类
     * @return 检测结果
     */
    public DetectionResult detect(String input, InjectionSource source) {
        if (input == null || input.isEmpty()) {
            return DetectionResult.clean();
        }

        List<String> matchedPatterns = new ArrayList<>();
        String maxSeverity = "NONE";
        String recommendation = "ALLOW";

        // 1. 长度检查
        if (input.length() > MAX_SAFE_LENGTH) {
            matchedPatterns.add("EXCESSIVE_LENGTH(" + input.length() + ">" + MAX_SAFE_LENGTH + ")");
            maxSeverity = "HIGH";
            recommendation = "BLOCK";
        } else if (input.length() > WARNING_LENGTH) {
            matchedPatterns.add("WARNING_LENGTH(" + input.length() + ">" + WARNING_LENGTH + ")");
            if (maxSeverity.equals("NONE")) maxSeverity = "LOW";
        }

        // 2. 关键模式检测（按严重级顺序）
        matchedPatterns.addAll(checkPatterns(input, CRITICAL_PATTERNS, matchedPatterns));
        if (matchedPatterns.stream().anyMatch(p -> p.startsWith("IGNORE_INSTRUCTIONS") || p.startsWith("ROLE_PLAY") || p.startsWith("CODE_EXECUTION"))) {
            maxSeverity = "CRITICAL";
            recommendation = "BLOCK";
        }

        matchedPatterns.addAll(checkPatterns(input, HIGH_PATTERNS, matchedPatterns));
        if (matchedPatterns.stream().anyMatch(p ->
                p.startsWith("SENSITIVE_EXTRACTION") ||   // 覆盖正反两种语序
                p.startsWith("ENCODING_OBFUSCATION") ||
                p.startsWith("STRUCTURE_BREAK") ||
                p.startsWith("DELIMITER_INJECTION"))) {
            if (!maxSeverity.equals("CRITICAL")) {
                maxSeverity = "HIGH";
                recommendation = "BLOCK";
            }
        }

        matchedPatterns.addAll(checkPatterns(input, MEDIUM_PATTERNS, matchedPatterns));
        if (maxSeverity.equals("NONE") && !matchedPatterns.isEmpty()) {
            maxSeverity = "LOW";
            recommendation = "MONITOR";
        }

        matchedPatterns.addAll(checkPatterns(input, SENSITIVE_PATTERNS, matchedPatterns));
        boolean hasSensitivePattern = matchedPatterns.stream()
                .anyMatch(p -> p.startsWith("API_KEY") || p.startsWith("JWT") || p.startsWith("PRIVATE_KEY") || p.startsWith("CONNECTION"));
        if (hasSensitivePattern) {
            if (maxSeverity.equals("NONE") || maxSeverity.equals("LOW")) {
                maxSeverity = "MEDIUM";
                recommendation = "SANITIZE";
            }
        }

        // 去重
        List<String> uniquePatterns = matchedPatterns.stream().distinct().toList();

        if (!uniquePatterns.isEmpty()) {
            log.warn("🚨 [PromptInjection] 检测到疑似注入 | source={} | severity={} | patterns={} | inputPreview={}",
                    source.getDisplayName(), maxSeverity, uniquePatterns,
                    input.substring(0, Math.min(100, input.length())).replaceAll("[\\r\\n]", " "));
            return DetectionResult.injected(uniquePatterns, maxSeverity, recommendation);
        }

        return DetectionResult.clean();
    }

    /**
     * 检测并阻断（如果检测到高危以上直接抛异常）
     *
     * @param input  待检测文本
     * @param source 来源分类
     * @throws SecurityGuardException 检测到 CRITICAL/HIGH 时抛出
     */
    public void checkAndBlock(String input, InjectionSource source) {
        DetectionResult result = detect(input, source);

        if (result.isInjected()) {
            if ("CRITICAL".equals(result.getSeverity()) || "HIGH".equals(result.getSeverity())) {
                throw new SecurityGuardException(
                        40003,
                        String.format("检测到提示词注入攻击 [%s]: %s，已拦截",
                                result.getSeverity(), String.join(", ", result.getMatchedPatterns()))
                );
            } else if ("MEDIUM".equals(result.getSeverity())) {
                // 中危：清洗模式，记录告警但不阻断
                log.warn("⚠️ [PromptInjection] 中危注入已标记清洗 | source={} | patterns={}",
                        source.getDisplayName(), result.getMatchedPatterns());
            } else {
                // 低危：仅监控记录
                log.info("📝 [PromptInjection] 低危模式已记录 | source={} | patterns={}",
                        source.getDisplayName(), result.getMatchedPatterns());
            }
        }
    }

    /**
     * 批量检测多个输入（如工具结果列表、历史消息）
     */
    public List<DetectionResult> detectBatch(List<String> inputs, InjectionSource source) {
        List<DetectionResult> results = new ArrayList<>();
        for (String input : inputs) {
            results.add(detect(input, source));
        }
        return results;
    }

    // ==================== 内部辅助方法 ====================

    private List<String> checkPatterns(String input, List<PatternRule> rules, List<String> existingMatches) {
        List<String> matches = new ArrayList<>();
        for (PatternRule rule : rules) {
            if (rule.pattern() == null) continue; // 长度检查等特殊规则跳过
            if (rule.pattern().matcher(input).find()) {
                String matchKey = rule.name();
                if (!existingMatches.contains(matchKey)) {
                    matches.add(matchKey);
                }
            }
        }
        return matches;
    }

    /**
     * 简单清洗：移除控制字符、标准化分隔符
     * 供中危模式使用
     */
    public String sanitize(String input) {
        if (input == null) return "";
        return input
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]", "") // 移除控制字符
                .replaceAll("(?i)(###|---|===|\\|\\|\\||<<<|>>>)(?=\\s*(system|user|assistant|instruction|prompt))", "[FILTERED_DELIMITER]") // 过滤分隔符注入
                .trim();
    }
}