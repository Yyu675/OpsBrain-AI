package com.devops.agent.domain.rag;

import com.devops.agent.domain.biz.service.AttachmentSecurityGuard;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link KnowledgeUploadService} 测试。
 *
 * <p>协作者全部 mock：被测的是编排逻辑——校验→解析→原件留存→入库的顺序、
 * 失败路径的降级行为，而不是 Tika 或 MinIO 本身。</p>
 *
 * <h3>最重要的两条断言</h3>
 * <ul>
 *   <li>解析失败/无有效文本 → 拒绝入库（不存空文档污染知识库）</li>
 *   <li>MinIO 原件留存失败 → <b>不阻断入库</b>（Fail-Safe 降级，
 *       与 MinioConfig 同哲学），但结果里必须如实标记 objectKey=null</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@DisplayName("KnowledgeUploadService")
class KnowledgeUploadServiceTest {

    private AttachmentSecurityGuard securityGuard;
    private DocumentParser documentParser;
    private MinioClient minioClient;
    private KnowledgeDocService docService;
    private KnowledgeUploadService service;

    @BeforeEach
    void setUp() {
        securityGuard = mock(AttachmentSecurityGuard.class);
        documentParser = mock(DocumentParser.class);
        minioClient = mock(MinioClient.class);
        docService = mock(KnowledgeDocService.class);
        service = new KnowledgeUploadService(securityGuard, documentParser, minioClient, docService);
        // @Value 字段在纯单测里不经过 Spring 注入，显式补上
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "knowledgeBucket", "test-kb-bucket");

        // 默认桩：文件名清洗与对象键生成透传（真实实现已由 AttachmentSecurityGuardTest 覆盖）
        when(securityGuard.sanitizeForDisposition(anyString())).thenAnswer(i -> i.getArgument(0));
        when(securityGuard.generateObjectKey(anyString())).thenReturn("2026/09/18/abc.pdf");
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "binary-content".getBytes());
    }

    private KnowledgeDocService.SaveResult okSave() {
        return new KnowledgeDocService.SaveResult(7L, 1, List.of(),
                KnowledgeDocService.IndexOutcome.indexed(3, 0));
    }

    @Test
    @DisplayName("上传成功：解析文本走标准创建链路，sourceType=UPLOAD，原件键入库")
    void uploadSuccess() throws Exception {
        when(documentParser.parse(any())).thenReturn(Document.from("## 故障手册\n重启 Pod 的步骤……"));
        when(minioClient.bucketExists(any())).thenReturn(true);
        when(docService.create(any(), any(), eq(true), anyString())).thenReturn(okSave());

        KnowledgeUploadService.UploadResult r = service.upload(
                file("k8s手册.pdf"), null, null, null, null, "张明", null, true, "SOP", "UPLOAD");

        assertEquals(7L, r.docId());
        assertEquals("k8s手册", r.title());  // 标题缺省取文件名去扩展名
        assertEquals("2026/09/18/abc.pdf", r.objectKey());
        assertTrue(r.parsedLength() > 10);

        // 落库文档的关键字段：来源类型 + 原件出处 + 解析文本
        verify(docService).create(argThat(doc ->
                "UPLOAD".equals(doc.getSourceType())
                        && "k8s手册.pdf".equals(doc.getOriginalFilename())
                        && "2026/09/18/abc.pdf".equals(doc.getOriginalFilePath())
                        && doc.getContent().contains("故障手册")), any(), eq(true), eq("UPLOAD"));
    }

    @Test
    @DisplayName("解析失败 → 400 语义（IllegalArgumentException），不入库")
    void parseFailureRejected() throws Exception {
        when(documentParser.parse(any())).thenThrow(new RuntimeException("corrupted zip"));

        assertThrows(IllegalArgumentException.class, () -> service.upload(
                file("broken.pdf"), null, null, null, null, null, null, true, null, "UPLOAD"));
        verify(docService, never()).create(any(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("解析无有效文本（扫描件）→ 拒绝，不入库")
    void emptyParseRejected() throws Exception {
        // Document.from 拒绝空白文本，用「短于有效阈值」的合法文本触发同一分支
        when(documentParser.parse(any())).thenReturn(Document.from("短"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.upload(file("scan.pdf"), null, null, null, null, null, null,
                        true, null, "UPLOAD"));
        assertTrue(e.getMessage().contains("有效文本"));
        verify(docService, never()).create(any(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("MinIO 留存失败 → 入库照常，objectKey 为 null 如实标记")
    void minioFailureDoesNotBlockIngestion() throws Exception {
        when(documentParser.parse(any())).thenReturn(Document.from("这是一段足够长的解析文本内容"));
        when(minioClient.bucketExists(any())).thenThrow(new RuntimeException("connection refused"));
        when(docService.create(any(), any(), anyBoolean(), anyString())).thenReturn(okSave());

        KnowledgeUploadService.UploadResult r = service.upload(
                file("手册.pdf"), "指定标题", 2L, null, null, null, null, true, null, "UPLOAD");

        assertEquals(7L, r.docId());
        assertNull(r.objectKey());
        assertEquals("指定标题", r.title());  // 显式标题优先于文件名
    }
}
