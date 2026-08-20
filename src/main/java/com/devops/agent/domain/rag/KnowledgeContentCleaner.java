package com.devops.agent.domain.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识文档内容清洗器（P1-3 数据清洗基础版）
 * <p>
 * 脏数据不进向量库（6.20 教训：检索到脏数据会伪装成「无相关文档」）。
 * 清洗规则分三类：
 * <ul>
 *   <li><b>确定性噪声</b>（空内容/纯符号/乱码）→ 拒收，抛 {@link IllegalArgumentException}</li>
 *   <li><b>规范性噪声</b>（HTML/多余空白/图片引用）→ 静默清洗，标记 {@code cleaned=true}</li>
 *   <li><b>重复段落</b> → 仅告警，不自动删除</li>
 * </ul>
 * </p>
 * <p>
 * <b>保守原则</b>：只剥离明确噪声，不删代码块/正文。最大风险是「漏检」而非「误删」。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-12
 */
@Slf4j
@Component
public class KnowledgeContentCleaner {

    // ==================== 正则常量 ====================

    /** 纯符号/标点/空白（无有效字符） */
    private static final Pattern PURE_SYMBOLS = Pattern.compile("^[\\p{P}\\p{S}\\s]+$");

    /** 替换字符 U+FFFD（）——非法 UTF-8 字节序列被解码器替换后的标记 */
    private static final char REPLACEMENT_CHAR = '\uFFFD';

    /** 图片引用（Markdown）: ![alt](url) 或 ![alt] 或 <img ...> */
    private static final Pattern IMAGE_REF = Pattern.compile(
            "!\\[([^\\]]*)\\]\\([^)]*\\)"    // ![alt](url)
            + "|!\\[([^\\]]*)\\]"             // ![alt]
            + "|<img\\s+[^>]*src\\s*=\\s*\"[^\"]*\"[^>]*/?>"  // <img ... src="...">
    );

    /** 连续 3 个及以上空行（含空白行） */
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(?:\\s*\\n){3,}");

    /** Fenced code block 标记（用于 HTML 剥离时跳过保护） */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    /** HTML 标签（用于剥离，需配合 code block 保护使用） */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 相邻段落重复检测——按行切分后的去重阈值 */
    private static final double DUPE_THRESHOLD = 0.20;

    /** 乱码检测阈值：替换字符占比超过此值即拒收 */
    private static final double GARBLED_THRESHOLD = 0.05;

    // ==================== 入口 ====================

    /**
     * 清洗文档内容。
     *
     * @param content 原始内容
     * @return 清洗结果（含清洗后文本、是否被清洗、拒收原因）
     * @throws IllegalArgumentException 确定性噪声时抛出
     */
    public CleanResult clean(String content) {
        if (content == null) {
            return CleanResult.rejected("内容为 null");
        }

        // 关卡 1：空/纯空白
        if (content.isBlank()) {
            return CleanResult.rejected("内容为空或纯空白，请填写有效内容");
        }

        // 关卡 2：纯符号/无有效字符
        if (PURE_SYMBOLS.matcher(content).matches()) {
            return CleanResult.rejected("内容仅含符号/标点，无有效字符");
        }

        // 关卡 3：乱码检测（U+FFFD 替换符密集）
        if (isGarbled(content)) {
            return CleanResult.rejected("内容含大量乱码字符（），请检查文档编码后重新提交");
        }

        boolean cleaned = false;
        String result = content;

        // 关卡 4：HTML 剥离（跳过 fenced code block）
        String afterHtml = stripHtml(result);
        if (!afterHtml.equals(result)) {
            log.debug("🧹 [ContentCleaner] HTML 标签已剥离 | 原始长度={} | 清洗后长度={}",
                    result.length(), afterHtml.length());
            result = afterHtml;
            cleaned = true;
        }

        // 关卡 5：Markdown 图片引用剥离
        String afterImages = stripImageRefs(result);
        if (!afterImages.equals(result)) {
            log.debug("🧹 [ContentCleaner] 图片引用已剥离 | 原始长度={} | 清洗后长度={}",
                    result.length(), afterImages.length());
            result = afterImages;
            cleaned = true;
        }

        // 关卡 6：多余空行折叠（3+ → 2）
        String afterBlanks = normalizeBlankLines(result);
        if (!afterBlanks.equals(result)) {
            log.debug("🧹 [ContentCleaner] 多余空行已折叠 | 原始长度={} | 清洗后长度={}",
                    result.length(), afterBlanks.length());
            result = afterBlanks;
            cleaned = true;
        }

        // 关卡 7：重复段落检测（仅告警，不自动删）
        DupeWarning dupeWarn = detectDuplicateParagraphs(result);
        if (dupeWarn != null) {
            log.warn("⚠️ [ContentCleaner] 检测到重复段落 | 占比={:.1%} | 相邻重复={} 组 | 文档内容可能含连续粘贴",
                    dupeWarn.ratio(), dupeWarn.groupCount());
        }

        // 清洗后二次检查：如果剥离 HTML 后变成空内容
        if (result.isBlank()) {
            return CleanResult.rejected("清洗后内容为空（原内容仅含 HTML 标签等无意义字符）");
        }

        return new CleanResult(result.trim(), cleaned, null, dupeWarn);
    }

    // ==================== 清洗规则实现 ====================

    /**
     * 乱码检测：统计 U+FFFD 替换字符占比。
     */
    boolean isGarbled(String content) {
        if (content == null || content.isEmpty()) return false;
        long replacementCount = content.chars().filter(c -> c == REPLACEMENT_CHAR).count();
        return (double) replacementCount / content.length() > GARBLED_THRESHOLD;
    }

    /**
     * HTML 标签剥离，跳过 fenced code block 段。
     * <p>
     * 运维知识核心在命令行/配置，误删 code block 即破坏内容。
     * 实现：先提取所有 code block 段并占位，非 code 段剥 HTML，再回填。
     * </p>
     */
    String stripHtml(String content) {
        if (content == null) return null;
        if (!content.contains("<") && !content.contains("&")) {
            return content; // 快速路径：不含 HTML 特征
        }

        // 暂存 code block 段
        Matcher codeMatcher = FENCED_CODE.matcher(content);
        List<String> codeBlocks = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        while (codeMatcher.find()) {
            codeBlocks.add(codeMatcher.group());
            codeMatcher.appendReplacement(sb, "___CODE_BLOCK_" + (codeBlocks.size() - 1) + "___");
        }
        codeMatcher.appendTail(sb);

        // 在非 code 段剥离 HTML 标签
        String stripped = HTML_TAG.matcher(sb.toString()).replaceAll("");

        // 回填 code block
        for (int i = 0; i < codeBlocks.size(); i++) {
            stripped = stripped.replace("___CODE_BLOCK_" + i + "___", codeBlocks.get(i));
        }

        // 剥离 HTML 实体（&amp; &lt; &gt; &quot; &#x27; 等常见转义）
        stripped = stripped.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");

        return stripped;
    }

    /**
     * 剥离 Markdown 图片引用，保留普通链接文本。
     * <p>
     * {@code ![alt](url)} → 空
     * {@code ![alt]} → 空
     * {@code [text](url)} → 保留（普通链接）
     * </p>
     */
    String stripImageRefs(String content) {
        if (content == null) return null;
        if (!content.contains("![") && !content.contains("<img")) {
            return content; // 快速路径
        }
        return IMAGE_REF.matcher(content).replaceAll("");
    }

    /**
     * 折叠连续 3 个及以上空白行为最多 2 个。
     */
    String normalizeBlankLines(String content) {
        if (content == null) return null;
        // 替换为两个换行符（即一个空行）
        return EXCESS_BLANK_LINES.matcher(content).replaceAll("\n\n");
    }

    /**
     * 检测相邻重复段落（按行切分，连续相同行占比 > 阈值即告警）。
     *
     * @return 重复占比超过阈值时返回告警信息，否则 null
     */
    DupeWarning detectDuplicateParagraphs(String content) {
        if (content == null || content.isBlank()) return null;

        String[] lines = content.split("\n");
        if (lines.length < 4) return null; // 至少 4 行才有意义

        int totalPairs = 0;
        int dupePairs = 0;

        for (int i = 0; i < lines.length - 1; i++) {
            String current = lines[i].trim();
            String next = lines[i + 1].trim();
            if (current.isEmpty() || next.isEmpty()) continue;
            totalPairs++;
            if (current.equals(next)) {
                dupePairs++;
            }
        }

        if (totalPairs == 0) return null;

        double ratio = (double) dupePairs / totalPairs;
        if (ratio >= DUPE_THRESHOLD) {
            return new DupeWarning(ratio, dupePairs);
        }
        return null;
    }

    // ==================== 返回类型 ====================

    /**
     * 清洗结果。
     *
     * @param content      清洗后的内容（拒收时可能为 null）
     * @param cleaned      是否发生过清洗（HTML/空白/图片剥离）
     * @param rejectReason 拒收原因（null 表示通过）
     * @param dupeWarning  重复段落告警（null 表示无重复）
     */
    public record CleanResult(
            String content,
            boolean cleaned,
            String rejectReason,
            DupeWarning dupeWarning
    ) {
        /** 是否被拒收 */
        public boolean isRejected() {
            return rejectReason != null;
        }

        static CleanResult rejected(String reason) {
            return new CleanResult(null, false, reason, null);
        }
    }

    /**
     * 重复段落告警信息。
     *
     * @param ratio      相邻重复行占比
     * @param groupCount 相邻重复组数
     */
    public record DupeWarning(double ratio, int groupCount) {
    }
}