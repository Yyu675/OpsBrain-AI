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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
            silentRemoveObject(bucket, objectKey);
            throw new IllegalStateException("附件元数据保存失败: " + e.getMessage(), e);
        }

        // 8. 活动流留痕
        //
        // ⚠️ 刻意不让活动流失败连累上传结果。
        //
        // 此前这行是裸调用：activityRepository.insert 抛异常时整个 upload 抛出，
        // Controller 返回失败。但此刻文件<b>已经躺在 MinIO、元数据也已经落库</b>——
        // 用户看到「上传失败」，刷新页面附件却好端端在列表里；
        // 于是他重传一次，被查重逻辑拦下报「该文件已上传过」，
        // 陷入「说失败又说重复」的死结，只能找运维。
        //
        // 活动流是留痕，不是上传成功的必要条件。这里降级为告警：
        // 主链路（对象 + 元数据）已经一致，缺一条活动记录不影响用户拿到文件。
        try {
            ticketService.recordActivity(ticketId, "primary", "上传附件",
                    originalName + "（" + meta.getSizeText() + "）", meta.getUploader(), false);
        } catch (Exception e) {
            log.warn("⚠️ [Attachment] 附件已上传成功，但活动流留痕失败 | ticketId={} | id={} | {}",
                    ticketId, meta.getId(), e.getMessage());
        }

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
    // ⚠️ 对象存储删除**不在事务保护范围内**——
    // MinIO 没有事务，一旦删掉就无法随数据库回滚一起恢复。
    // 因此对象删除通过 removeObjectAfterCommit 挂到「事务提交之后」执行：
    //   - 事务中任一步失败 → 回滚，回调不触发，对象原封不动，数据一致；
    //   - 提交成功但删对象失败 → 留下孤儿对象，由存储侧生命周期策略回收，
    //     用户视角是正确的（附件已消失）。
    // 早期实现是在事务<b>中间</b>同步删对象，看似也是「先删库后删对象」，
    // 但后面的 recordActivity 一旦抛异常，删库被回滚而对象已经没了，
    // 留下「记录还在但文件已没」的死链，用户点下载报 404——
    // 那正是本注释想要避免的不一致。顺序对了，时机不对，一样不成立。
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

        ticketService.recordActivity(meta.getTicketId(), "gray", "删除附件",
                meta.getOriginalName(), "当前用户", false);

        // 对象删除推迟到「事务提交之后」，而不是在事务中间就动手。
        //
        // 原先的顺序是 deleteById → removeObject → recordActivity，
        // 这恰恰破坏了本方法注释所声称的一致性保证：
        // recordActivity 写活动流表若失败，@Transactional(rollbackFor=Exception.class)
        // 会把 deleteById <b>回滚</b>——元数据完好如初回到库里，
        // 可 MinIO 里的对象<b>已经删掉且无法随事务回滚恢复</b>。
        // 用户刷新后附件仍在列表上，点下载得到 404，
        // 正是注释里明确要避免的那种「记录还在但文件已没」的死链。
        //
        // 改为 afterCommit 后：事务里任何一步失败都不会碰对象存储，
        // 只有库侧确定落盘了才去删文件。最坏情况退化为孤儿对象（用户无感知）。
        removeObjectAfterCommit(meta.getBucket(), meta.getObjectKey());

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
            // 同 delete()：先确保库侧提交，再动不可回滚的对象存储。
            // 且用每条记录自己的 bucket，而不是当前配置的 bucket——见 removeObjectAfterCommit 注释。
            removeObjectAfterCommit(a.getBucket(), a.getObjectKey());
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
    /**
     * 事务提交成功后再删除对象；无事务时立即删除
     * <p>
     * 对象存储没有事务、删除不可撤销，因此它必须是整条链路的<b>最后一步</b>，
     * 且只在数据库那边已经板上钉钉之后才执行。回滚时注册的回调不会触发，
     * 对象自然原样保留。
     * </p>
     * <p>
     * 无事务上下文（如被非事务方法直接调用）时退化为立即删除，
     * 保持行为可预期，不会静默什么都不做。
     * </p>
     */
    private void removeObjectAfterCommit(String objectBucket, String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            silentRemoveObject(objectBucket, objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                silentRemoveObject(objectBucket, objectKey);
            }
        });
    }

    private void silentRemoveObject(String objectBucket, String objectKey) {
        // 用附件记录里存下的 bucket，而不是当前 @Value 注入的 bucket。
        //
        // bucket 名来自配置 devops.storage.minio.bucket，是会变的：
        // 迁移存储、分环境改桶名、或历史数据本就写在旧桶里。
        // 元数据表专门存了 bucket 列、presignDownloadUrl 也是用 meta.getBucket() 取的，
        // 唯独删除路径写死当前配置——配置一改，删除就会打到<b>新桶里那个不存在的 key</b>，
        // 而旧桶里的真实文件永远删不掉。
        //
        // 用户可见后果：附件从列表消失，文件却仍留在对象存储里；
        // 若是按合规要求删除敏感文件，会得到「已删除」的假象，实际数据仍然可被访问。
        String targetBucket = (objectBucket == null || objectBucket.isBlank()) ? bucket : objectBucket;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(targetBucket)
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