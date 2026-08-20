package com.devops.agent.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 父子结构化文档切片器
 * <p>职责：
 * 1. 按 Markdown 二级标题 (## ) 切分父段落（目标 ~800 token）
 * 2. 父段落内再切子段落（目标 ~200 token）
 * 3. 子段落 metadata 绑定 parent_id + parent_text，检索时返回完整上下文
 * 4. fenced code block（``` 围栏）原子化切分：size 窗口不切断代码块，
 *    块内的 ## 不当章节标题
 * <p>
 * <p>架构层级：Domain Layer - RAG
 * <p>依赖：LangChain4j DocumentSplitter 接口
 *
 * @author OpsBrain AI Team
 * @since 2026-07-15
 */
@Slf4j
@Component
public class ParentChildDocumentSplitter implements DocumentSplitter {

    /**
     * 父段落目标大小（约 800 token，按 3 字符 ≈ 1 token 估算）
     */
    private static final int PARENT_CHUNK_SIZE = 2400;

    /**
     * 子段落目标大小（约 200 token）
     */
    private static final int CHILD_CHUNK_SIZE = 600;

    /**
     * 段落重叠大小（用于保持上下文连贯性）
     */
    private static final int OVERLAP_SIZE = 100;

    /**
     * Markdown 二级标题正则（## 开头）
     */
    private static final Pattern SECTION_PATTERN = Pattern.compile("(?m)^##\\s+(.+)$");

    /**
     * Markdown 代码块围栏行（行首 ```，可带语言标识如 ```bash）
     * <p>与 SECTION_PATTERN 配合实现「代码块原子化切分」：
     * 围栏内的 ## 是代码不是标题，围栏不能被 size 窗口从中间截断。</p>
     */
    private static final Pattern FENCE_PATTERN = Pattern.compile("(?m)^```[^\\n]*$");

    @Override
    public List<TextSegment> split(Document document) {
        return splitWithParentChild(document);
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        List<TextSegment> allSegments = new ArrayList<>();
        for (Document doc : documents) {
            allSegments.addAll(split(doc));
        }
        return allSegments;
    }

    /**
     * 父子结构化切片核心逻辑
     *
     * @param document 原始文档
     * @return 切片列表（父段落 + 子段落）
     */
    public List<TextSegment> splitWithParentChild(Document document) {
        List<TextSegment> allSegments = new ArrayList<>();
        String content = document.text();
        Metadata baseMetadata = document.metadata();

        // 1. 按 ## 标题切分父段落
        List<Section> sections = extractSections(content);

        log.debug("文档 [{}] 共切分出 {} 个父段落", baseMetadata.getString("doc_title"), sections.size());

        // 2. 对每个父段落进行子切片
        for (Section section : sections) {
            String parentId = UUID.randomUUID().toString();
            String parentText = section.content;
            String sectionHeader = section.header;

            // 2.1 如果父段落本身就很小（< 子段落阈值），直接作为一个切片
            if (parentText.length() <= CHILD_CHUNK_SIZE) {
                Metadata meta = Metadata.from(baseMetadata.toMap());
                meta.put("parent_id", parentId);
                meta.put("parent_text", parentText);
                meta.put("section_header", sectionHeader);
                meta.put("is_parent", "false");  // 标记为子段落（字符串类型）

                allSegments.add(TextSegment.from(parentText, meta));
                continue;
            }

            // 2.2 父段落较大，需要切子段落
            List<String> childChunks = splitIntoChildren(parentText);

            log.debug("  父段落 [{}] 切分出 {} 个子段落", sectionHeader, childChunks.size());

            for (String childText : childChunks) {
                Metadata meta = Metadata.from(baseMetadata.toMap());
                meta.put("parent_id", parentId);
                meta.put("parent_text", parentText);
                meta.put("section_header", sectionHeader);
                meta.put("is_parent", "false");  // 标记为子段落（字符串类型）

                allSegments.add(TextSegment.from(childText, meta));
            }
        }

        log.info("文档 [{}] 父子切片完成：共 {} 个切片",
                 baseMetadata.getString("doc_title"), allSegments.size());

        return allSegments;
    }

    /**
     * 按 ## 标题提取章节（父段落）
     *
     * @param content 文档内容
     * @return 章节列表
     */
    private List<Section> extractSections(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = SECTION_PATTERN.matcher(content);
        // fenced code block 区间：块内的 ## 是代码不是标题，必须跳过，
        // 否则会把代码行误判为章节标题，父段落被从中拆断
        List<int[]> codeBlocks = findCodeBlocks(content);

        List<Integer> headerPositions = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        // 找出所有 ## 标题的位置（跳过代码块内的误匹配）
        while (matcher.find()) {
            if (codeBlockAt(codeBlocks, matcher.start()) != null) {
                continue;
            }
            headerPositions.add(matcher.start());
            headers.add(matcher.group(1).trim());
        }

        // 如果没有 ## 标题，整个文档作为一个父段落
        if (headerPositions.isEmpty()) {
            sections.add(new Section("文档内容", content.trim()));
            return sections;
        }

        // 按标题切分段落
        for (int i = 0; i < headerPositions.size(); i++) {
            int start = headerPositions.get(i);
            int end = (i + 1 < headerPositions.size()) ? headerPositions.get(i + 1) : content.length();

            String sectionContent = content.substring(start, end).trim();
            String header = headers.get(i);

            // 如果父段落过大，按字符数强制切分
            if (sectionContent.length() > PARENT_CHUNK_SIZE * 2) {
                List<String> largeSectionChunks = splitBySize(sectionContent, PARENT_CHUNK_SIZE);
                for (int j = 0; j < largeSectionChunks.size(); j++) {
                    String subHeader = header + " (part " + (j + 1) + ")";
                    sections.add(new Section(subHeader, largeSectionChunks.get(j)));
                }
            } else {
                sections.add(new Section(header, sectionContent));
            }
        }

        return sections;
    }

    /**
     * 找出所有 fenced code block 的区间（左闭右开 [start, end)）。
     * <p>按行扫描围栏行（```）：奇数次遇到即开块，偶数次即闭块。
     * 未闭合的围栏（文档被截断等）按到文本末尾处理，避免后续逻辑失焦。</p>
     *
     * @param content 文档内容
     * @return 代码块区间列表，形如 [start, end)（不含末尾换行）
     */
    private List<int[]> findCodeBlocks(String content) {
        List<int[]> blocks = new ArrayList<>();
        Matcher matcher = FENCE_PATTERN.matcher(content);

        int blockStart = -1;
        while (matcher.find()) {
            if (blockStart < 0) {
                blockStart = matcher.start();
            } else {
                blocks.add(new int[]{blockStart, matcher.end()});
                blockStart = -1;
            }
        }
        if (blockStart >= 0) {
            // 围栏未闭合：按到文本末尾处理
            blocks.add(new int[]{blockStart, content.length()});
        }
        return blocks;
    }

    /**
     * 判断位置 {@code pos} 是否落在某个代码块区间内。
     *
     * @param blocks 代码块区间列表
     * @param pos    目标位置（字符下标）
     * @return 命中的区间（[start, end)），未命中返回 null
     */
    private static int[] codeBlockAt(List<int[]> blocks, int pos) {
        for (int[] block : blocks) {
            if (pos >= block[0] && pos < block[1]) {
                return block;
            }
        }
        return null;
    }

    /**
     * 将父段落切分为子段落（固定大小 + 重叠，fence 感知）
     *
     * @param parentText 父段落文本
     * @return 子段落列表
     */
    private List<String> splitIntoChildren(String parentText) {
        return splitBySize(parentText, CHILD_CHUNK_SIZE);
    }

    /**
     * 按固定大小切分文本（带重叠）
     *
     * @param text      原始文本
     * @param chunkSize 切片大小
     * @return 切片列表
     */
    private List<String> splitBySize(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        // 防御非法配置：重叠必须小于切片大小，否则窗口无法前进
        int overlap = Math.min(OVERLAP_SIZE, Math.max(0, chunkSize - 1));

        // fenced code block 区间：size 窗口不得从围栏中间切断代码块
        List<int[]> codeBlocks = findCodeBlocks(text);

        int start = 0;
        final int len = text.length();

        while (start < len) {
            int end = Math.min(start + chunkSize, len);

            // 尝试在句子边界处切分（避免切断句子），
            // 但切点不得落在代码块内部——围栏内换行是代码不是句界
            if (end < len) {
                int lastPeriod = text.lastIndexOf('。', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int boundary = Math.max(lastPeriod, lastNewline);

                // 确保切片不会太小
                if (boundary > start + chunkSize / 2
                        && codeBlockAt(codeBlocks, boundary) == null) {
                    end = boundary + 1;
                }
            }

            // 代码块原子化：若窗口尾落在围栏内部（会把代码块从中间切断），
            // 扩到围栏结束，让整个代码块作为整体进入同一切片。
            // 仅当代码块可在单窗口内容纳时如此——超长代码块（如整段日志）
            // 无法保持原子，退化为块内切分（不可避免）。
            if (end < len) {
                int[] cut = codeBlockAt(codeBlocks, end);
                if (cut != null && cut[1] - start <= chunkSize + OVERLAP_SIZE) {
                    end = cut[1];
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 已切到文本末尾，收尾退出。
            //
            // 这一步是必须的：原实现无此判断，在尾部会死循环。
            // 例如 len=6918、chunkSize=600、overlap=100，
            // 当 start=6850 时 end=6918（已到末尾），
            // 下一轮 start = 6918-100 = 6818 < 6850 —— start 回退，
            // 循环永不终止且反复 add 同一切片，直至 OutOfMemoryError。
            // 触发条件是 len - start <= overlap，
            // 任何长于 chunkSize 的文档都必然命中，
            // 因此知识库摄取从未成功过。
            if (end >= len) {
                break;
            }

            // 下一个切片起点（带重叠）。
            // 强制至少前进 1 个字符，兜住边界调整使 end 过小的情况
            int next = end - overlap;
            start = Math.max(next, start + 1);
        }

        return chunks;
    }

    /**
     * 章节结构（内部辅助类）
     */
    private static class Section {
        String header;
        String content;

        Section(String header, String content) {
            this.header = header;
            this.content = content;
        }
    }
}
