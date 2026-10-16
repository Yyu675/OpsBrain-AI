package com.devops.agent.domain.rag;

import com.devops.agent.domain.biz.service.AttachmentSecurityGuard;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 知识库文件上传服务（V2 对标补齐：二进制文档解析入库）。
 *
 * <h3>链路</h3>
 * <pre>
 *   安全校验（白名单/大小/路径穿越，复用 AttachmentSecurityGuard）
 *     → Tika 解析出纯文本
 *     → 原件留存 MinIO（审计/重解析；存储不可用降级为不留存，不阻断入库）
 *     → KnowledgeDocService.create（清洗/去重/SimHash/向量化全链复用）
 * </pre>
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>同步处理</b>：与手工新建文档同一链路——发布即触发索引，
 *       60s 超时与 FAILED 重试状态机（{@code /docs/reindex/pending}）天然兜底，
 *       不为上传单开异步通道。</li>
 *   <li><b>解析产物是纯文本</b>：PDF/Word 的排版结构（标题层级）在解析后
 *       已丢失，父子切片会退化为按大小切分——这是解析管线的固有损耗，
 *       对检索质量的影响由 L4 相似度熔断兜底。</li>
 *   <li><b>原件留存失败不阻断入库</b>：留存是审计增强（MinioConfig 的
 *       Fail-Safe 哲学），对象存储挂掉不应让知识库完全不可写入。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Slf4j
@Service
public class KnowledgeUploadService {

    /**
     * 允许上传的文档类型（小写扩展名，不含点）。
     * <p>白名单而非黑名单，理由见 {@link AttachmentSecurityGuard}。</p>
     */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "html", "htm");

    /** 解析结果低于此长度视为「没解析出有效内容」（扫描件 PDF 等） */
    private static final int MIN_PARSED_LENGTH = 10;

    private final AttachmentSecurityGuard securityGuard;
    private final DocumentParser documentParser;
    private final MinioClient minioClient;
    private final KnowledgeDocService docService;

    @Value("${devops.storage.minio.knowledge-bucket:devops-knowledge-source}")
    private String knowledgeBucket;

    /** 原件下载链接有效期（秒），与工单附件同一配置项 */
    @Value("${devops.storage.minio.presign-expiry-seconds:300}")
    private int presignExpirySeconds;

    /** 桶存在性确认标记：首次上传时检查一次，避免每次上传多一次 HEAD */
    private volatile boolean bucketEnsured = false;

    public KnowledgeUploadService(AttachmentSecurityGuard securityGuard,
                                  DocumentParser documentParser,
                                  MinioClient minioClient,
                                  KnowledgeDocService docService) {
        this.securityGuard = securityGuard;
        this.documentParser = documentParser;
        this.minioClient = minioClient;
        this.docService = docService;
    }

    /**
     * 上传文件并解析入库。
     *
     * @param file     上传文件（二进制文档或纯文本/Markdown）
     * @param title    文档标题；为空取文件名（去扩展名）
     * @param kbId     目标知识库；null 落默认库
     * @param publish  true=发布并立即向量化；false=存草稿
     * @param operator 操作人（审计留痕）
     * @return 入库结果（含文档 ID、索引状态、近似重复告警、原件对象键）
     * @throws IllegalArgumentException 校验或解析失败（消息可直接展示）
     */
    public UploadResult upload(MultipartFile file, String title, Long kbId,
                               Long categoryId, String category, String author,
                               List<String> tags, boolean publish, String knowledgeSource,
                               String operator) {
        // 1. 安全校验（知识库白名单，攻击面检查与工单附件同一份实现）
        securityGuard.validate(file, ALLOWED_EXTENSIONS);
        String originalFilename = securityGuard.sanitizeForDisposition(file.getOriginalFilename());

        // 2. Tika 解析
        String text = parse(file, originalFilename);

        // 3. 原件留存 MinIO（Fail-Safe：失败降级为不留存，见类注释）
        String objectKey = tryStoreOriginal(file, originalFilename);

        // 4. 走标准创建链路（清洗/精确去重/SimHash 近似告警/向量化全部复用）
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle((title == null || title.isBlank()) ? stripExtension(originalFilename) : title.trim());
        doc.setContent(text);
        doc.setCategoryId(categoryId);
        doc.setCategory(category);
        doc.setAuthor(author);
        doc.setKbId(kbId);
        doc.setKnowledgeSource(knowledgeSource != null ? knowledgeSource : "SOP");
        doc.setSourceType("UPLOAD");
        doc.setOriginalFilename(originalFilename);
        doc.setOriginalFilePath(objectKey);

        KnowledgeDocService.SaveResult r = docService.create(doc, tags, publish, operator);
        log.info("📄 [KB-Upload] 文件入库 | docId={} | file={} | 解析长度={} | 索引={}",
                r.docId(), originalFilename, text.length(), r.indexOutcome().status());

        return new UploadResult(r.docId(), doc.getTitle(), r.version(),
                r.indexOutcome(), r.nearDuplicates(), objectKey, text.length());
    }

    // ==================== 原件下载 ====================

    /**
     * 为上传文档的原件生成预签名下载 URL。
     *
     * <p>走预签名而非后端流式转发的理由与工单附件一致
     * （见 {@code TicketAttachmentService.presignDownloadUrl}）：
     * 文件不经应用进程、桶为 private、链接短时效。</p>
     *
     * @param docId 文档 ID
     * @return 下载 URL + 原始文件名 + 有效期秒数
     * @throws IllegalStateException 文档无留存原件（手工录入，或上传时留存降级），
     *         或对象存储签名失败
     */
    public OriginalDownload presignOriginalUrl(Long docId) {
        KnowledgeDoc doc = docService.findById(docId, false);
        if (doc == null) {
            return null;
        }
        String objectKey = doc.getOriginalFilePath();
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException(
                    "该文档无留存原件（手工录入，或上传时对象存储不可用降级为未留存）");
        }
        String filename = doc.getOriginalFilename() != null ? doc.getOriginalFilename() : "download";
        String disposition = "attachment; filename*=UTF-8''"
                + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(knowledgeBucket)
                    .object(objectKey)
                    .expiry(presignExpirySeconds, TimeUnit.SECONDS)
                    .extraQueryParams(Map.of("response-content-disposition", disposition))
                    .build());
            return new OriginalDownload(url, filename, presignExpirySeconds);
        } catch (Exception e) {
            log.error("❌ [KB-Upload] 生成原件下载链接失败 | docId={} | key={} | {}",
                    docId, objectKey, e.getMessage());
            throw new IllegalStateException("生成下载链接失败: " + e.getMessage(), e);
        }
    }

    /** 原件下载信息 */
    public record OriginalDownload(String url, String filename, int expiresInSeconds) {}

    // ==================== 内部 ====================

    /**
     * Tika 解析文件内容为纯文本。
     *
     * @throws IllegalArgumentException 解析失败或无有效内容
     */
    private String parse(MultipartFile file, String originalFilename) {
        String text;
        try (InputStream in = file.getInputStream()) {
            Document parsed = documentParser.parse(in);
            text = parsed != null ? parsed.text() : null;
        } catch (Exception e) {
            log.warn("⚠️ [KB-Upload] 文件解析失败 | file={} | {}", originalFilename, e.getMessage());
            throw new IllegalArgumentException(
                    "文件解析失败，可能已损坏或实际格式与扩展名不符: " + originalFilename);
        }

        if (text == null || text.trim().length() < MIN_PARSED_LENGTH) {
            // 典型场景：扫描件 PDF（全是图片无文本层）。如实告知而非存一篇空文档
            throw new IllegalArgumentException(
                    "未能从文件中提取到有效文本（可能是扫描件或图片型文档）: " + originalFilename);
        }
        return text.trim();
    }

    /**
     * 原件留存 MinIO。失败仅告警并返回 null——入库主流程不受影响。
     */
    private String tryStoreOriginal(MultipartFile file, String originalFilename) {
        String objectKey = securityGuard.generateObjectKey(originalFilename);
        try {
            ensureBucket();
            try (InputStream in = file.getInputStream()) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(knowledgeBucket)
                        .object(objectKey)
                        .stream(in, file.getSize(), -1)
                        .contentType(file.getContentType() != null
                                ? file.getContentType() : "application/octet-stream")
                        .build());
            }
            log.info("🪣 [KB-Upload] 原件已留存 | bucket={} | key={} | size={}",
                    knowledgeBucket, objectKey, file.getSize());
            return objectKey;
        } catch (Exception e) {
            log.warn("⚠️ [KB-Upload] 原件留存失败（不影响入库）| file={} | {}",
                    originalFilename, e.getMessage());
            return null;
        }
    }

    /** 桶兜底创建（幂等，仅首次执行；与 MinioConfig 同一 Fail-Safe 风格） */
    private void ensureBucket() throws Exception {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(knowledgeBucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(knowledgeBucket).build());
                log.info("🪣 [KB-Upload] 知识原件桶不存在，已创建: {}", knowledgeBucket);
            }
            bucketEnsured = true;
        }
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return "未命名文档";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 上传结果。
     *
     * @param indexOutcome  向量化结果（INDEXED/FAILED/SKIPPED，如实透传）
     * @param nearDuplicates 近似重复告警（不阻断）
     * @param objectKey     原件 MinIO 对象键；留存失败为 null
     * @param parsedLength  解析出的文本长度（供前端展示「解析出 N 字符」）
     */
    public record UploadResult(
            Long docId,
            String title,
            Integer version,
            KnowledgeDocService.IndexOutcome indexOutcome,
            List<KnowledgeDocService.NearDuplicate> nearDuplicates,
            String objectKey,
            int parsedLength
    ) {}
}
