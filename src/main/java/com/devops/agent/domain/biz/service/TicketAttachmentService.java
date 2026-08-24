package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAttachment;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketAttachmentRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 工单附件服务
 * <p>
 * 编排安全校验 → MinIO 上传 → 元数据落库。
 * </p>
 * <p>
 * <b>写入顺序：先传对象存储，再写元数据</b>。反序会在写库成功、
 * 上传失败时留下指向不存在对象的元数据（下载必然 404）。
 * 当前顺序的失败模式是产生无元数据的孤儿对象——虽占用存储，
 * 但不会让用户看到坏链接，且可由定时任务比对清理。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Service
public class TicketAttachmentService {

    private final MinioClient minioClient;
    private final TicketAttachmentRepository attachmentRepository;
    private final DevOpsTicketRepository ticketRepository;
    private final AttachmentSecurityGuard securityGuard;
    private final TicketService ticketService;

    @Value("${devops.storage.minio.bucket}")
    private String bucket;

    @Value("${devops.storage.minio.presign-expiry-seconds:300}")
    private int presignExpirySeconds;

    @Value("${devops.storage.attachment.max-count-per-ticket:10}")
    private int maxCountPerTicket;

    public TicketAttachmentService(MinioClient minioClient,
                                   TicketAttachmentRepository attachmentRepository,
                                   DevOpsTicketRepository ticketRepository,
                                   AttachmentSecurityGuard securityGuard,
                                   TicketService ticketService) {
        this.minioClient = minioClient;
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.securityGuard = securityGuard;
        this.ticketService = ticketService;
    }

    /**
     * 上传附件
     *
     * @param ticketId 工单号
     * @param file     上传文件
     * @param uploader 上传人
     * @return 附件元数据
     * @throws IllegalStateException    工单不存在 / 数量超限 / 存储不可用
     * @throws IllegalArgumentException 文件校验不通过
     */
    public TicketAttachment upload(String ticketId, MultipartFile file, String uploader) {
        // 1. 工单存在性
        DevOpsTicket ticket = ticketRepository.findById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        // 2. 安全校验（扩展名白名单、双扩展名、路径穿越、大小）
        securityGuard.validate(file);

        // 3. 数量上限
        int existing = attachmentRepository.countByTicketId(ticketId);
        if (existing >= maxCountPerTicket) {
            throw new IllegalStateException(String.format(
                    "附件数量已达上限（%d 个），请先删除不需要的附件", maxCountPerTicket));
        }

        // 4. 计算内容哈希用于查重
        String sha256 = computeSha256(file);
        if (sha256 != null) {
            TicketAttachment dup = attachmentRepository.findByTicketIdAndSha256(ticketId, sha256);
            if (dup != null) {
                throw new IllegalStateException(String.format(
                        "该文件已上传过（%s），无需重复上传", dup.getOriginalName()));
            }
        }

        // 5. 生成对象键（服务端生成，不含用户文件名）
        String originalName = securityGuard.sanitizeForDisposition(file.getOriginalFilename());
        String objectKey = securityGuard.generateObjectKey(file.getOriginalFilename());

        // 6. 先上传对象存储
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, file.getSize(), -1)
                    .contentType(resolveContentType(file))
                    .build());
            log.info("📤 [Attachment] 对象已上传 | bucket={} | key={} | size={}",
                    bucket, objectKey, file.getSize());
        } catch (Exception e) {
            log.error("❌ [Attachment] 对象上传失败 | ticketId={} | name={} | {}",
                    ticketId, originalName, e.getMessage());
            throw new IllegalStateException("附件上传失败，对象存储不可用: " + e.getMessage(), e);
        }

        // 7. 再写元数据
        TicketAttachment meta = new TicketAttachment();
        meta.setTicketId(ticketId);
        meta.setBucket(bucket);
        meta.setObjectKey(objectKey);
        meta.setOriginalName(originalName);
        meta.setContentType(resolveContentType(file));
        meta.setSizeBytes(file.getSize());
        meta.setSha256(sha256);
        meta.setUploader((uploader == null || uploader.isBlank()) ? "当前用户" : uploader.trim());
        meta.setCreateTime(LocalDateTime.now());

        try {
            Long id = attachmentRepository.insert(meta);
            meta.setId(id);
        } catch (Exception e) {
            // 元数据写库失败：回滚已上传的对象，避免孤儿数据
            log.error("❌ [Attachment] 元数据入库失败，回滚已上传对象 | key={}", objectKey);
            silentRemoveObject(objectKey);
            throw new IllegalStateException("附件元数据保存失败: " + e.getMessage(), e);
        }

        // 8. 活动流留痕
        ticketService.recordActivity(ticketId, "primary", "上传附件",
                originalName + "（" + meta.getSizeText() + "）", meta.getUploader(), false);

        return meta;
    }

    /**
     * 查询工单附件列表
     */
    public List<TicketAttachment> list(String ticketId) {
        return attachmentRepository.findByTicketId(ticketId);
    }

    /**
     * 生成下载用预签名 URL
     * <p>
     * 走预签名而非后端流式转发的理由：
     * <ul>
     *   <li>文件不经应用进程，不占用应用带宽与线程</li>
     *   <li>桶为 private，URL 带签名且有效期短（默认 5 分钟），
     *       链接泄露的时间窗有限</li>
     *   <li>响应头由参数指定原始文件名，用户下载得到正确名称</li>
     * </ul>
     *
     * @param attachmentId 附件 ID
     * @return 预签名下载 URL
     * @throws IllegalStateException 附件不存在或签名失败
     */
    public String presignDownloadUrl(Long attachmentId) {
        TicketAttachment meta = attachmentRepository.findById(attachmentId);
        if (meta == null) {
            throw new IllegalStateException("附件不存在: " + attachmentId);
        }

        // 让浏览器按原始文件名保存，而非对象键的 UUID
        String disposition = "attachment; filename*=UTF-8''"
                + java.net.URLEncoder.encode(meta.getOriginalName(),
                        java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");

        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(meta.getBucket())
                    .object(meta.getObjectKey())
                    .expiry(presignExpirySeconds, TimeUnit.SECONDS)
                    .extraQueryParams(java.util.Map.of(
                            "response-content-disposition", disposition))
                    .build());
        } catch (Exception e) {
            log.error("❌ [Attachment] 生成预签名 URL 失败 | id={} | key={} | {}",
                    attachmentId, meta.getObjectKey(), e.getMessage());
            throw new IllegalStateException("生成下载链接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除附件
     * <p>
     * <b>先删元数据再删对象</b>。反序会在删对象成功、删元数据失败时
     * 留下指向不存在对象的元数据（用户看到附件但下载 404）。
     * 当前顺序的失败模式是孤儿对象，用户无感知。
     * </p>
     *
     * @return 被删除的附件元数据
     */
    // 事务：附件元数据 + 工单活动流两张表，须整体回滚。
    //
    // ⚠️ 对象存储删除（silentRemoveObject）**不在事务保护范围内**——
    // MinIO 没有事务，一旦删掉就无法随数据库回滚一起恢复。
    // 因此顺序刻意设计为「先删库、后删对象」：
    //   - 若删库失败 → 事务回滚，对象仍在，数据一致（只是多占存储）；
    //   - 若删库成功但删对象失败 → 留下孤儿对象，由存储侧生命周期策略回收，
    //     用户视角是正确的（附件已消失）。
    // 反过来「先删对象后删库」则会在删库失败时留下「记录还在但文件已没」的
    // 死链，用户点下载报 404——那才是真正的不一致。
    @Transactional(rollbackFor = Exception.class)
    public TicketAttachment delete(Long attachmentId) {
        TicketAttachment meta = attachmentRepository.findById(attachmentId);
        if (meta == null) {
            throw new IllegalStateException("附件不存在: " + attachmentId);
        }

        int rows = attachmentRepository.deleteById(attachmentId);
        if (rows == 0) {
            throw new IllegalStateException("附件删除失败: " + attachmentId);
        }

        silentRemoveObject(meta.getObjectKey());

        ticketService.recordActivity(meta.getTicketId(), "gray", "删除附件",
                meta.getOriginalName(), "当前用户", false);

        log.info("🗑️ [Attachment] 附件已删除 | id={} | ticketId={} | name={}",
                attachmentId, meta.getTicketId(), meta.getOriginalName());
        return meta;
    }

    /**
     * 级联清理工单全部附件
     * <p>工单物理删除时调用。</p>
     *
     * @return 清理的附件数
     */
    // 事务同上：库先于对象存储，理由见 delete() 注释
    @Transactional(rollbackFor = Exception.class)
    public int deleteAllByTicketId(String ticketId) {
        List<TicketAttachment> removed = attachmentRepository.deleteByTicketId(ticketId);
        for (TicketAttachment a : removed) {
            silentRemoveObject(a.getObjectKey());
        }
        return removed.size();
    }

    // ==================== 内部方法 ====================

    /**
     * 删除对象，失败仅告警
     * <p>
     * 元数据已删的情况下对象删除失败只是占用存储，
     * 不影响用户体验，不应因此让整个操作失败。
     * 遗留的孤儿对象可由定时任务比对清理。
     * </p>
     */
    private void silentRemoveObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            log.debug("🗑️ [Attachment] 对象已删除 | key={}", objectKey);
        } catch (Exception e) {
            log.warn("⚠️ [Attachment] 对象删除失败，将成为孤儿对象 | key={} | {}",
                    objectKey, e.getMessage());
        }
    }

    /**
     * 计算文件内容 SHA-256
     * <p>失败返回 null——查重是优化项，不应阻塞上传。</p>
     */
    private String computeSha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("⚠️ [Attachment] 计算哈希失败，跳过查重 | {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 Content-Type
     * <p>
     * 浏览器上报的 content-type 不可信（可伪造），但此处仅用于
     * 下载时的响应头，不参与安全决策——安全靠扩展名白名单。
     * </p>
     */
    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct == null || ct.isBlank()) ? "application/octet-stream" : ct;
    }
}