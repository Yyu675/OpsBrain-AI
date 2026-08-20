package com.devops.agent.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 父子切片器测试
 * <p>
 * 重点覆盖 {@code splitBySize} 的尾部死循环缺陷：
 * 原实现在 {@code len - start <= OVERLAP_SIZE} 时 start 会回退，
 * 循环永不终止且反复 add 同一切片直至 OutOfMemoryError。
 * 任何长于 CHILD_CHUNK_SIZE(600) 的文档都必然命中，
 * 导致知识库摄取从未成功过。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
class ParentChildDocumentSplitterTest {

    private final ParentChildDocumentSplitter splitter = new ParentChildDocumentSplitter();

    /**
     * 构造指定长度的中文文本，含句号与换行以触发边界调整逻辑
     */
    private String buildText(int targetLength) {
        StringBuilder sb = new StringBuilder(targetLength + 64);
        int i = 0;
        while (sb.length() < targetLength) {
            sb.append("这是第").append(i).append("句用于验证切片边界的测试文本。");
            if (i % 5 == 0) {
                sb.append('\n');
            }
            i++;
        }
        return sb.substring(0, targetLength);
    }

    private Document docOf(String body) {
        Metadata meta = new Metadata();
        meta.put("doc_title", "切片器测试.md");
        meta.put("source", "test");
        return Document.from(body, meta);
    }

    // ==================== 死循环回归测试 ====================

    @Test
    @DisplayName("长文档不应死循环——原实现会在尾部无限循环直至 OOM")
    void shouldTerminateOnLongDocument() {
        // 6918 是实测触发 OOM 的真实文档长度（K8s故障排查手册.md）
        String body = "## 章节一\n" + buildText(6918);

        // 超时保护：死循环时此断言会因超时失败，而非等到 OOM 拖垮测试进程
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));
            assertFalse(segments.isEmpty(), "长文档应产出切片");
        }, "切片超过 5 秒未完成，疑似死循环");
    }

    @Test
    @DisplayName("切片数量应有界——死循环会产出天量重复切片")
    void chunkCountShouldBeBounded() {
        String body = "## 章节一\n" + buildText(6918);
        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        // 6918 字符、子切片 600、重叠 100 → 上界约 6918/500 + 章节数，
        // 给足余量取 100。死循环时会是数十万级
        assertTrue(segments.size() < 100,
                "切片数应有界，实际 " + segments.size() + " 个，疑似重复产出");
    }

    @Test
    @DisplayName("尾部长度小于重叠量时仍应正常终止")
    void shouldTerminateWhenTailShorterThanOverlap() {
        // 构造让最后一段恰好短于 OVERLAP_SIZE(100) 的长度
        for (int extra = 1; extra <= 100; extra += 33) {
            String body = "## 章节\n" + buildText(600 * 3 + extra);
            int finalExtra = extra;
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));
                assertFalse(segments.isEmpty(), "extra=" + finalExtra + " 应产出切片");
            }, "extra=" + extra + " 时疑似死循环");
        }
    }

    // ==================== 正确性测试 ====================

    @Test
    @DisplayName("切片应覆盖原文——不能丢内容")
    void chunksShouldCoverSourceText() {
        String marker = "这是一个不会被重复的唯一标记字符串XYZ";
        String body = "## 章节一\n" + buildText(1500) + marker + buildText(1500);

        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        boolean found = segments.stream().anyMatch(s -> s.text().contains(marker))
                || segments.stream().anyMatch(s -> {
                    String pt = s.metadata().getString("parent_text");
                    return pt != null && pt.contains(marker);
                });
        assertTrue(found, "文档中部的内容不应在切片中丢失");
    }

    @Test
    @DisplayName("短文档应产出单个切片且父子指向自身")
    void shortDocumentProducesSingleChunk() {
        String body = "## 简短章节\n这是一段很短的内容。";
        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        assertFalse(segments.isEmpty(), "短文档也应产出切片");
        TextSegment first = segments.get(0);
        assertNotNull(first.metadata().getString("parent_id"), "应带 parent_id");
        assertNotNull(first.metadata().getString("parent_text"), "应带 parent_text 供检索返回完整上下文");
    }

    @Test
    @DisplayName("仅有标题无正文的文档不应抛异常")
    void headerOnlyDocumentShouldNotThrow() {
        // 注：不测真正的空文档——LangChain4j 的 Document.from("") 自身即拒绝，
        // 切片器根本收不到，测它没有意义
        assertDoesNotThrow(() -> {
            List<TextSegment> segments = splitter.splitWithParentChild(docOf("## 只有标题"));
            assertNotNull(segments, "应返回列表而非 null");
        });
    }

    @Test
    @DisplayName("正文为纯空白的章节不应产出空切片")
    void blankSectionShouldNotProduceEmptyChunk() {
        String body = "## 章节一\n   \n\n   \n## 章节二\n有效内容。";
        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        for (TextSegment s : segments) {
            assertFalse(s.text().isBlank(), "不应产出空白切片，会污染向量库");
        }
    }

    @Test
    @DisplayName("每个子切片都必须带 parent_text——检索依赖它返回完整上下文")
    void everyChildMustCarryParentText() {
        String body = "## 章节一\n" + buildText(3000) + "\n## 章节二\n" + buildText(2000);
        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        for (TextSegment s : segments) {
            String parentText = s.metadata().getString("parent_text");
            assertNotNull(parentText, "切片缺 parent_text，HybridRetrieverService 将退化为返回子片段");
            assertFalse(parentText.isBlank(), "parent_text 不应为空白");
        }
    }

    // ==================== fence 代码块原子化测试 ====================

    /**
     * 构造一个横跨「本应按 600 字符窗口切一刀」位置的代码块。
     * <p>几何（实测值）：header=9 + 填充×38=190 + 换行=1 → 前缀恰好 200 字符；
     * 代码块 = ```bash\n + 5×89 + \n``` = 457 字符，落在 [200, 657)。
     * 窗口边界 600 落在块内——无 fence 感知时此块会被从中间切断；
     * 有感知时窗口扩到 657 保持块原子。</p>
     * <p>注意：前缀末尾必须有换行——围栏正则 `(?m)^```[^\n]*$` 锚定行首，
     * 紧贴正文的围栏不会被识别为代码块（真实 Markdown 中围栏必然独占一行）。</p>
     */
    private String buildCodeBlockDoc() {
        String filler = "填充文本。";                        // 5 字符
        String prefix = "## 代码块章节\n" + filler.repeat(38) + "\n"; // 9 + 190 + 1 = 200 字符
        String blockLine = "kubectl logs payment-service-7c8d9 -n default --tail=5000 --prefix=true --timestamps=true"; // 89 字符
        String codeBlock = "```bash\n" + blockLine.repeat(5) + "\n```"; // 8 + 445 + 4 = 457 字符
        return prefix + codeBlock;
    }

    @Test
    @DisplayName("fence 代码块应原子化切分——size 窗口不得从代码块中间切断")
    void codeBlockShouldNotBeCutThrough() {
        String body = buildCodeBlockDoc();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));
            assertFalse(segments.isEmpty(), "应产出切片");

            // 整个代码块（含开闭围栏）必须完整落在同一切片内。
            // 用内容标记而非硬编码偏移判定：开围栏 ```bash 与闭围栏 \n```
            // 必须出现在同一切片——切断了则二者必然分家
            boolean atomic = segments.stream().anyMatch(s ->
                    s.text().contains("```bash\n") && s.text().contains("\n```"));
            assertTrue(atomic, "代码块被从中间切断：开闭围栏不在同一切片内");
        }, "切片超过 5 秒未完成，疑似死循环");
    }

    @Test
    @DisplayName("fence 内的 ## 是代码不是标题——不得按它切分父段落")
    void fenceHeaderShouldNotBeTreatedAsSection() {
        String body = """
                ## 章节一
                这里介绍第一节的普通内容。没有任何代码块。
                ## 章节二
                ```yaml
                ## fake-section
                  name: payment-service
                  replicas: 2
                ```
                真实内容在代码块之后。
                """;

        List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));

        long realSections = segments.stream()
                .map(s -> s.metadata().getString("section_header"))
                .distinct().count();
        assertEquals(2, realSections,
                "fence 内的 ## 被误当章节标题，父段落被从中拆断（实际 " + realSections + " 个）");
    }

    @Test
    @DisplayName("未闭合的围栏不应抛异常——文档被截断时按到末尾处理")
    void unclosedFenceShouldNotThrow() {
        String body = """
                ## 章节
                ```yaml
                name: payment-service
                ## 这行在未闭合代码块内
                  replicas: 2
                """;

        assertDoesNotThrow(() -> {
            List<TextSegment> segments = splitter.splitWithParentChild(docOf(body));
            assertFalse(segments.isEmpty(), "未闭合围栏也应产出切片");
            // 围栏内伪标题不得产生额外章节
            long realSections = segments.stream()
                    .map(s -> s.metadata().getString("section_header"))
                    .distinct().count();
            assertEquals(1, realSections, "未闭合围栏内的 ## 被误当章节标题");
        });
    }
}