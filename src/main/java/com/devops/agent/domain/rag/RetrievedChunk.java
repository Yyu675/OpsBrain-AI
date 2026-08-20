package com.devops.agent.domain.rag;

/**
 * 检索命中的知识片段（含出处）
 * <p>
 * 存在的原因是一处真实缺陷：此前 {@code HybridRetrieverService.retrieve()}
 * 返回 {@code List<String>}，只有正文，把 SQL 已查出的 {@code doc_title}
 * 与 {@code section_header} 丢掉了。
 * </p>
 * <p>
 * 后果并非「少显示两个字段」这么轻。System Prompt 强制要求
 * <blockquote>每条建议标注【来源：文档标题 - 章节】</blockquote>
 * 而工具返回的文本里根本没有标题与章节，模型无法满足该约束，
 * 于是退到唯一可用的出口——回答「当前知识库暂无相关文档」。
 * </p>
 * <p>
 * 实测表现：检索明明返回 3 个父段落、工具正常执行，用户看到的却是
 * 「知识库暂无 Pod CrashLoopBackOff 相关文档」——而该文档就在库里。
 * 这比检索失败更具误导性：运维会去补一份已经存在的文档。
 * </p>
 *
 * @param docTitle      来源文档标题（如「K8s故障排查手册.md」）
 * @param sectionHeader 章节标题（如「## Pod CrashLoopBackOff 问题排查」），可为空
 * @param text          片段正文，优先为父段落（上下文更完整）
 * @param score         相似度得分，用于调试与可观测
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public record RetrievedChunk(
        String docTitle,
        String sectionHeader,
        String text,
        double score
) {

    /**
     * 渲染为供模型消费的引用标签
     * <p>
     * 与 System Prompt 要求的【来源：文档标题 - 章节】格式对齐，
     * 使模型能够直接照抄，而非自行拼装（自行拼装容易编造章节名）。
     * </p>
     */
    public String citation() {
        String title = (docTitle != null && !docTitle.isBlank()) ? docTitle : "未知文档";
        // 去掉 Markdown 标题符号，避免出现「## ## 章节」这类重复标记
        String section = sectionHeader != null ? sectionHeader.replaceAll("^#+\\s*", "").trim() : "";
        return section.isEmpty()
                ? String.format("【来源：%s】", title)
                : String.format("【来源：%s - %s】", title, section);
    }
}
