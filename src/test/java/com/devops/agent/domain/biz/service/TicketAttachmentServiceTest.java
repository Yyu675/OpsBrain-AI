package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAttachment;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketAttachmentRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketAttachmentService} 单元测试。
 *
 * <h3>为什么这个服务必须测：它同时操作两套无法互相回滚的存储</h3>
 * 一次上传要动 MinIO（对象）、附件元数据表、工单活动流表三处。
 * 数据库那两张表能靠 {@code @Transactional} 一起回滚，
 * 但<b>对象存储没有事务、删除不可撤销</b>。三者的先后顺序只要错一步，
 * 结果就不是报错，而是「用户看得见但下不下来」或「说删掉了其实还在」——
 * 两种都不会有任何告警。
 *
 * <h3>本类写作过程中查出的三个真实缺陷</h3>
 * <ol>
 *   <li><b>delete() 在事务中间就把对象删了。</b>原顺序是
 *       {@code deleteById → removeObject → recordActivity}。
 *       最后一步写活动流失败时，{@code @Transactional(rollbackFor=Exception.class)}
 *       会把删库<b>回滚</b>，元数据完好回到库里，可 MinIO 里的文件已经没了。
 *       用户刷新后附件仍在列表上，点下载得到 404——
 *       正是方法注释里信誓旦旦说要避免的那种死链。<b>顺序对了，时机不对，一样不成立</b>；</li>
 *   <li><b>删除对象用的是当前配置的 bucket，而不是记录里存的 bucket。</b>
 *       元数据表专门存了 bucket 列，{@code presignDownloadUrl} 也确实用
 *       {@code meta.getBucket()}，唯独删除路径写死 {@code @Value} 注入的值。
 *       一旦迁移存储或分环境改了桶名，删除就打到新桶里那个不存在的 key 上，
 *       旧桶里的真实文件<b>永远删不掉</b>。按合规要求删敏感文件时，
 *       用户得到的是「已删除」的假象；</li>
 *   <li><b>upload() 末尾的活动流留痕失败会让整次上传报错。</b>
 *       可此刻文件已在 MinIO、元数据也已落库。用户看到「上传失败」，
 *       刷新却发现附件在列表里；重传一次又被 SHA-256 查重拦下报「该文件已上传过」，
 *       陷入「说失败又说重复」的死结。</li>
 * </ol>
 */
@DisplayName("TicketAttachmentService 工单附件服务")
class TicketAttachmentServiceTest {

    private MinioClient minioClient;
    private TicketAttachmentRepository attachmentRepository;
    private DevOpsTicketRepository ticketRepository;
    private AttachmentSecurityGuard securityGuard;
    private TicketService ticketService;
    private TicketAttachmentService service;

    private static final String BUCKET = "opsbrain-attachments";

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        attachmentRepository = mock(TicketAttachmentRepository.class);
        ticketRepository = mock(DevOpsTicketRepository.class);
        securityGuard = mock(AttachmentSecurityGuard.class);
        ticketService = mock(TicketService.class);

        service = new TicketAttachmentService(minioClient, attachmentRepository,
                ticketRepository, securityGuard, ticketService);
        // @Value 字段在单元测试里不会被注入，须手工设置，
        // 否则 bucket 恒为 null，「用哪个桶」这类断言全是假通过
        ReflectionTestUtils.setField(service, "bucket", BUCKET);
        ReflectionTestUtils.setField(service, "presignExpirySeconds", 300);
        ReflectionTestUtils.setField(service, "maxCountPerTicket", 10);
    }

    private MultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    private MultipartFile file(String name) {
        return file(name, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void ticketExists(String id) {
        DevOpsTicket t = new DevOpsTicket();
        t.setId(id);
        when(ticketRepository.findById(id)).thenReturn(t);
    }

    private void guardPassthrough() {
        doNothing().when(securityGuard).validate(any());
        when(securityGuard.sanitizeForDisposition(anyString())).thenAnswer(i -> i.getArgument(0));
        when(securityGuard.generateObjectKey(anyString())).thenReturn("2026/08/uuid-key.txt");
    }

    private TicketAttachment meta(Long id, String bucket, String key) {
        TicketAttachment m = new TicketAttachment();
        m.setId(id);
        m.setTicketId("TK-1");
        m.setBucket(bucket);
        m.setObjectKey(key);
        m.setOriginalName("报告.txt");
        m.setSizeBytes(1024L);
        return m;
    }

    // ==================== upload：前置校验 ====================

    @Nested
    @DisplayName("upload 前置校验")
    class UploadGuards {

        @Test
        @DisplayName("工单不存在时拒绝，且不碰对象存储")
        void rejectsMissingTicket() {
            when(ticketRepository.findById("TK-X")).thenReturn(null);

            assertThatThrownBy(() -> service.upload("TK-X", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工单不存在");

            // 工单不存在还上传，等于往存储里塞永远无人认领的孤儿对象
            verifyNoUpload();
        }

        @Test
        @DisplayName("安全校验不通过时直接抛出，不上传也不写库")
        void propagatesSecurityRejection() {
            ticketExists("TK-1");
            doThrow(new IllegalArgumentException("不支持的文件类型: .jsp"))
                    .when(securityGuard).validate(any());

            assertThatThrownBy(() -> service.upload("TK-1", file("shell.jsp"), "张三"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不支持的文件类型");

            verifyNoUpload();
            verify(attachmentRepository, never()).insert(any());
        }

        @Test
        @DisplayName("数量达到上限时拒绝，提示里带上具体上限数字")
        void rejectsWhenCountLimitReached() {
            ticketExists("TK-1");
            guardPassthrough();
            when(attachmentRepository.countByTicketId("TK-1")).thenReturn(10);

            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    // 只说「已达上限」而不说上限是几，用户无从判断该删几个
                    .hasMessageContaining("10");

            verifyNoUpload();
        }

        @Test
        @DisplayName("上限判断用 >= 而非 >：正好等于上限时就该拒绝")
        void limitIsInclusive() {
            ticketExists("TK-1");
            guardPassthrough();
            // 已有 10 个、上限 10，再传就是第 11 个。
            // 写成 > 会让实际上限变成 11，是最典型的差一错误
            when(attachmentRepository.countByTicketId("TK-1")).thenReturn(10);
            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("未达上限时放行")
        void allowsBelowLimit() throws Exception {
            ticketExists("TK-1");
            guardPassthrough();
            when(attachmentRepository.countByTicketId("TK-1")).thenReturn(9);
            when(attachmentRepository.insert(any())).thenReturn(7L);

            TicketAttachment result = service.upload("TK-1", file("a.txt"), "张三");

            assertThat(result.getId()).isEqualTo(7L);
            verify(minioClient).putObject(any(PutObjectArgs.class));
        }

        @Test
        @DisplayName("同工单内内容重复时拒绝，并指出与哪个文件重复")
        void rejectsDuplicateContent() {
            ticketExists("TK-1");
            guardPassthrough();
            when(attachmentRepository.countByTicketId("TK-1")).thenReturn(1);
            TicketAttachment dup = meta(1L, BUCKET, "old-key");
            dup.setOriginalName("上周报告.txt");
            when(attachmentRepository.findByTicketIdAndSha256(eq("TK-1"), anyString())).thenReturn(dup);

            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("上周报告.txt");

            verifyNoUpload();
        }

        private void verifyNoUpload() {
            try {
                verify(minioClient, never()).putObject(any(PutObjectArgs.class));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    // ==================== upload：写入顺序与失败补偿 ====================

    @Nested
    @DisplayName("upload 写入顺序与失败补偿")
    class UploadWriteOrder {

        @BeforeEach
        void ready() {
            ticketExists("TK-1");
            guardPassthrough();
            when(attachmentRepository.countByTicketId("TK-1")).thenReturn(0);
        }

        @Test
        @DisplayName("元数据落库使用服务端生成的对象键，绝不使用用户文件名")
        void objectKeyComesFromGuard() throws Exception {
            when(attachmentRepository.insert(any())).thenReturn(1L);

            service.upload("TK-1", file("../../etc/passwd"), "张三");

            ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
            verify(minioClient).putObject(put.capture());
            // 用户文件名进对象键就是路径穿越漏洞
            assertThat(put.getValue().object()).isEqualTo("2026/08/uuid-key.txt");
            assertThat(put.getValue().bucket()).isEqualTo(BUCKET);

            ArgumentCaptor<TicketAttachment> saved = ArgumentCaptor.forClass(TicketAttachment.class);
            verify(attachmentRepository).insert(saved.capture());
            assertThat(saved.getValue().getObjectKey()).isEqualTo("2026/08/uuid-key.txt");
        }

        @Test
        @DisplayName("落库的 bucket 是本次实际上传到的桶——下载与删除都依赖它")
        void persistsBucketUsedForUpload() throws Exception {
            when(attachmentRepository.insert(any())).thenReturn(1L);

            service.upload("TK-1", file("a.txt"), "张三");

            ArgumentCaptor<TicketAttachment> saved = ArgumentCaptor.forClass(TicketAttachment.class);
            verify(attachmentRepository).insert(saved.capture());
            // 不落 bucket，配置一改历史附件就再也定位不到
            assertThat(saved.getValue().getBucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("对象上传失败时不写元数据——绝不留下指向不存在对象的记录")
        void noMetadataWhenObjectUploadFails() throws Exception {
            doThrow(new RuntimeException("connection refused"))
                    .when(minioClient).putObject(any(PutObjectArgs.class));

            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("对象存储不可用");

            // 反序（先写库）会让用户在列表里看到一个必然 404 的附件
            verify(attachmentRepository, never()).insert(any());
        }

        @Test
        @DisplayName("元数据入库失败时回滚已上传的对象，避免孤儿文件")
        void removesObjectWhenInsertFails() throws Exception {
            when(attachmentRepository.insert(any())).thenThrow(new RuntimeException("duplicate key"));

            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("元数据保存失败");

            ArgumentCaptor<RemoveObjectArgs> rm = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient).removeObject(rm.capture());
            assertThat(rm.getValue().object()).isEqualTo("2026/08/uuid-key.txt");
            assertThat(rm.getValue().bucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("补偿删除本身失败时，仍抛出原始的入库失败原因")
        void compensationFailureDoesNotMaskRootCause() throws Exception {
            when(attachmentRepository.insert(any())).thenThrow(new RuntimeException("duplicate key"));
            doThrow(new RuntimeException("minio down"))
                    .when(minioClient).removeObject(any(RemoveObjectArgs.class));

            // 补偿失败只是多个孤儿对象；把它抛出去会盖掉真正的病因，
            // 让排查方向从「为什么入库失败」跑偏到「为什么删不掉」
            assertThatThrownBy(() -> service.upload("TK-1", file("a.txt"), "张三"))
                    .hasMessageContaining("元数据保存失败");
        }

        @Test
        @DisplayName("活动流留痕失败不影响上传结果——文件与元数据都已就位")
        void activityFailureDoesNotFailUpload() {
            when(attachmentRepository.insert(any())).thenReturn(42L);
            doThrow(new RuntimeException("活动流表锁等待超时"))
                    .when(ticketService).recordActivity(anyString(), anyString(), anyString(),
                            anyString(), anyString(), anyBoolean());

            // 修复前这里会抛异常，用户看到「上传失败」，
            // 刷新页面附件却在，重传又被查重拦下——彻底的死结
            TicketAttachment result = service.upload("TK-1", file("a.txt"), "张三");

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("上传成功时活动流记下文件名与大小")
        void recordsActivityWithNameAndSize() {
            when(attachmentRepository.insert(any())).thenReturn(1L);

            service.upload("TK-1", file("报告.txt", new byte[2048]), "张三");

            ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
            verify(ticketService).recordActivity(eq("TK-1"), anyString(), eq("上传附件"),
                    detail.capture(), eq("张三"), eq(false));
            assertThat(detail.getValue()).contains("报告.txt").contains("KB");
        }

        @Test
        @DisplayName("上传人为空白时回落为默认值，不写入空字符串")
        void blankUploaderFallsBack() {
            when(attachmentRepository.insert(any())).thenReturn(1L);

            TicketAttachment a = service.upload("TK-1", file("a.txt"), "   ");
            assertThat(a.getUploader()).isEqualTo("当前用户");

            TicketAttachment b = service.upload("TK-1", file("a.txt"), null);
            assertThat(b.getUploader()).isEqualTo("当前用户");
        }

        @Test
        @DisplayName("上传人两侧空白被裁剪——否则「张三」和「张三 」会被当成两个人")
        void uploaderIsTrimmed() {
            when(attachmentRepository.insert(any())).thenReturn(1L);
            assertThat(service.upload("TK-1", file("a.txt"), "  张三  ").getUploader())
                    .isEqualTo("张三");
        }

        @Test
        @DisplayName("内容相同的文件算出相同 SHA-256，内容不同则不同")
        void sha256ReflectsContent() {
            when(attachmentRepository.insert(any())).thenReturn(1L);

            service.upload("TK-1", file("a.txt", "same".getBytes()), "张三");
            service.upload("TK-1", file("b.txt", "same".getBytes()), "张三");
            service.upload("TK-1", file("c.txt", "diff".getBytes()), "张三");

            ArgumentCaptor<TicketAttachment> saved = ArgumentCaptor.forClass(TicketAttachment.class);
            verify(attachmentRepository, times(3)).insert(saved.capture());
            List<TicketAttachment> all = saved.getAllValues();
            // 查重靠内容哈希而非文件名：改个名重传应当被识别为重复
            assertThat(all.get(0).getSha256()).isEqualTo(all.get(1).getSha256());
            assertThat(all.get(0).getSha256()).isNotEqualTo(all.get(2).getSha256());
            assertThat(all.get(0).getSha256()).hasSize(64);
        }

        @Test
        @DisplayName("Content-Type 缺失时回落为 octet-stream，不写 null")
        void missingContentTypeFallsBack() {
            when(attachmentRepository.insert(any())).thenReturn(1L);
            MultipartFile noType = new MockMultipartFile("file", "a.txt", null, "x".getBytes());

            service.upload("TK-1", noType, "张三");

            ArgumentCaptor<TicketAttachment> saved = ArgumentCaptor.forClass(TicketAttachment.class);
            verify(attachmentRepository).insert(saved.capture());
            assertThat(saved.getValue().getContentType()).isEqualTo("application/octet-stream");
        }
    }

    // ==================== presignDownloadUrl ====================

    @Nested
    @DisplayName("presignDownloadUrl 预签名下载")
    class Presign {

        @Test
        @DisplayName("附件不存在时抛出明确异常，不返回空 URL")
        void rejectsMissingAttachment() {
            when(attachmentRepository.findById(99L)).thenReturn(null);

            // 返回 null 会让前端打开一个空白页而非给出提示
            assertThatThrownBy(() -> service.presignDownloadUrl(99L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("附件不存在");
        }

        @Test
        @DisplayName("使用记录里存的 bucket 与 objectKey，并带上配置的有效期")
        void usesStoredBucketAndConfiguredExpiry() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, "legacy-bucket", "k/1.txt"));
            when(minioClient.getPresignedObjectUrl(any())).thenReturn("https://minio/x?sig=1");

            assertThat(service.presignDownloadUrl(1L)).isEqualTo("https://minio/x?sig=1");

            ArgumentCaptor<GetPresignedObjectUrlArgs> args =
                    ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
            verify(minioClient).getPresignedObjectUrl(args.capture());
            // 历史附件可能在旧桶里，写死当前配置会签出一个 404 的链接
            assertThat(args.getValue().bucket()).isEqualTo("legacy-bucket");
            assertThat(args.getValue().object()).isEqualTo("k/1.txt");
            assertThat(args.getValue().expiry()).isEqualTo(300);
        }

        @Test
        @DisplayName("中文与空格文件名做 RFC 5987 编码，空格用 %20 而非 +")
        void encodesFilenameForContentDisposition() throws Exception {
            TicketAttachment m = meta(1L, BUCKET, "k/1.txt");
            m.setOriginalName("季度 报告.txt");
            when(attachmentRepository.findById(1L)).thenReturn(m);
            when(minioClient.getPresignedObjectUrl(any())).thenReturn("u");

            service.presignDownloadUrl(1L);

            ArgumentCaptor<GetPresignedObjectUrlArgs> args =
                    ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
            verify(minioClient).getPresignedObjectUrl(args.capture());
            // extraQueryParams() 返回的是 Guava Multimap（同一个 key 可有多个值），
            // 不是 java.util.Map——取值要走 get(key) 得到集合
            String disposition = args.getValue().extraQueryParams()
                    .get("response-content-disposition").iterator().next();

            assertThat(disposition).startsWith("attachment; filename*=UTF-8''");
            // URLEncoder 是表单编码，空格编成 +；在 header 里 + 不会被还原成空格，
            // 用户会下到一个名叫「季度+报告.txt」的文件
            assertThat(disposition).doesNotContain("+");
            assertThat(disposition).contains("%20");
        }

        @Test
        @DisplayName("签名失败时抛出面向用户的提示，并保留原始异常")
        void wrapsSigningFailure() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(minioClient.getPresignedObjectUrl(any())).thenThrow(new RuntimeException("clock skew"));

            assertThatThrownBy(() -> service.presignDownloadUrl(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("生成下载链接失败")
                    // 丢掉 cause 会让日志里只剩一句无用的中文提示
                    .hasRootCauseMessage("clock skew");
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete 删除附件")
    class Delete {

        @Test
        @DisplayName("附件不存在时抛出，不做任何删除动作")
        void rejectsMissing() throws Exception {
            when(attachmentRepository.findById(9L)).thenReturn(null);

            assertThatThrownBy(() -> service.delete(9L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("附件不存在");

            verify(attachmentRepository, never()).deleteById(anyLong());
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("元数据删除影响 0 行时抛出——记录已被并发删掉，不能谎报成功")
        void rejectsZeroRows() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(0);

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("删除失败");

            // 库里没删掉却把文件删了，就是「记录还在、文件没了」的死链
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("删除对象用记录里存的 bucket，而非当前配置的 bucket")
        void removesFromStoredBucket() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, "legacy-bucket", "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);

            ArgumentCaptor<RemoveObjectArgs> rm = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient).removeObject(rm.capture());
            // 打到当前配置的新桶上，旧桶里的真实文件永远删不掉；
            // 若是合规删除，用户拿到的是「已删除」的假象
            assertThat(rm.getValue().bucket()).isEqualTo("legacy-bucket");
            assertThat(rm.getValue().object()).isEqualTo("k/1.txt");
        }

        @Test
        @DisplayName("记录里 bucket 为空（历史数据）时回落到当前配置")
        void fallsBackToConfiguredBucket() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, null, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);

            ArgumentCaptor<RemoveObjectArgs> rm = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient).removeObject(rm.capture());
            // 早于 bucket 列存在的历史记录不该因为字段为空就删不掉
            assertThat(rm.getValue().bucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("对象删除失败不影响删除结果——用户视角附件确实已消失")
        void objectRemovalFailureIsTolerated() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);
            doThrow(new RuntimeException("minio down"))
                    .when(minioClient).removeObject(any(RemoveObjectArgs.class));

            // 元数据已删的前提下，删不掉对象只是多占存储，
            // 为此把整个操作判失败反而会诱导用户重试一个已经完成的动作
            TicketAttachment removed = service.delete(1L);
            assertThat(removed.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("删除后记录活动流，颜色与文案表明这是删除动作")
        void recordsActivity() {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);

            verify(ticketService).recordActivity(eq("TK-1"), eq("gray"), eq("删除附件"),
                    eq("报告.txt"), anyString(), eq(false));
        }

        @Test
        @DisplayName("返回被删附件的快照，供前端展示「已删除 xxx」")
        void returnsSnapshot() {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            assertThat(service.delete(1L).getOriginalName()).isEqualTo("报告.txt");
        }
    }

    // ==================== 缺陷：事务提交后才删对象 ====================

    @Nested
    @DisplayName("对象删除时机（缺陷：曾在事务中间就删，回滚后成死链）")
    class RemoveAfterCommit {

        @BeforeEach
        void bindTransaction() {
            // 模拟处于事务中：Spring 真实运行时 @Transactional 会激活同步器。
            // 单元测试直接调 service 方法不经过事务代理，必须手工激活，
            // 否则 removeObjectAfterCommit 走的是「无事务→立即删除」分支，
            // 这组用例就测不到真正要验证的行为
            TransactionSynchronizationManager.initSynchronization();
        }

        @org.junit.jupiter.api.AfterEach
        void unbind() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("事务未提交前不删对象——回滚时文件必须还在")
        void doesNotRemoveBeforeCommit() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);

            // 方法返回时事务尚未提交。此刻若已经删掉对象，
            // 后续任何一步失败导致回滚，元数据会回到库里而文件已经没了
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        }

        @Test
        @DisplayName("事务提交后才真正删除对象")
        void removesAfterCommit() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

            verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("活动流失败导致回滚时，对象删除回调不触发，文件完好")
        void rollbackKeepsObjectIntact() throws Exception {
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);
            doThrow(new RuntimeException("活动流表锁等待超时"))
                    .when(ticketService).recordActivity(anyString(), anyString(), anyString(),
                            anyString(), anyString(), anyBoolean());

            assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(RuntimeException.class);

            // 这正是缺陷的核心场景：删库会被事务回滚，
            // 而对象存储的删除不可撤销。只要还没提交就不动文件，
            // 回滚后库与存储仍然一致
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("无事务上下文时立即删除，不静默跳过")
        void removesImmediatelyWithoutTransaction() throws Exception {
            TransactionSynchronizationManager.clearSynchronization();
            when(attachmentRepository.findById(1L)).thenReturn(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.deleteById(1L)).thenReturn(1);

            service.delete(1L);

            // 退化行为必须可预期：注册不了回调就当场删，
            // 否则文件会因为「没人触发提交」而永远留在存储里
            verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        }
    }

    // ==================== deleteAllByTicketId ====================

    @Nested
    @DisplayName("deleteAllByTicketId 级联清理")
    class DeleteAll {

        @Test
        @DisplayName("逐个删除每条记录对应的对象，并用各自记录里的 bucket")
        void removesEachWithItsOwnBucket() throws Exception {
            when(attachmentRepository.deleteByTicketId("TK-1")).thenReturn(List.of(
                    meta(1L, "bucket-a", "k/1.txt"),
                    meta(2L, "bucket-b", "k/2.txt")));

            assertThat(service.deleteAllByTicketId("TK-1")).isEqualTo(2);

            ArgumentCaptor<RemoveObjectArgs> rm = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient, times(2)).removeObject(rm.capture());
            // 跨桶的历史附件必须各回各家，统一用当前配置会漏删
            assertThat(rm.getAllValues()).extracting(RemoveObjectArgs::bucket)
                    .containsExactly("bucket-a", "bucket-b");
            assertThat(rm.getAllValues()).extracting(RemoveObjectArgs::object)
                    .containsExactly("k/1.txt", "k/2.txt");
        }

        @Test
        @DisplayName("工单无附件时返回 0，不调用对象存储")
        void noAttachmentsIsZero() throws Exception {
            when(attachmentRepository.deleteByTicketId("TK-2")).thenReturn(List.of());

            assertThat(service.deleteAllByTicketId("TK-2")).isZero();
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("其中一个对象删除失败不影响其余对象与整体结果")
        void oneFailureDoesNotStopTheRest() throws Exception {
            when(attachmentRepository.deleteByTicketId("TK-1")).thenReturn(List.of(
                    meta(1L, BUCKET, "k/1.txt"),
                    meta(2L, BUCKET, "k/2.txt"),
                    meta(3L, BUCKET, "k/3.txt")));
            doThrow(new RuntimeException("minio down"))
                    .when(minioClient).removeObject(argThatObject("k/2.txt"));

            // 工单删除是不可逆操作，不能因为中间一个文件删不掉就整体失败——
            // 那会让工单主体删不掉，用户反复重试反复失败
            assertThat(service.deleteAllByTicketId("TK-1")).isEqualTo(3);
            verify(minioClient, times(3)).removeObject(any(RemoveObjectArgs.class));
        }

        private RemoveObjectArgs argThatObject(String key) {
            return org.mockito.ArgumentMatchers.argThat(
                    (RemoveObjectArgs a) -> a != null && key.equals(a.object()));
        }
    }

    // ==================== list ====================

    @Nested
    @DisplayName("list 附件列表")
    class ListAttachments {

        @Test
        @DisplayName("透传仓储结果")
        void delegatesToRepository() {
            List<TicketAttachment> data = List.of(meta(1L, BUCKET, "k/1.txt"));
            when(attachmentRepository.findByTicketId("TK-1")).thenReturn(data);

            assertThat(service.list("TK-1")).isEqualTo(data);
        }

        @Test
        @DisplayName("无附件时返回空列表而非 null")
        void emptyNotNull() {
            when(attachmentRepository.findByTicketId("TK-9")).thenReturn(List.of());
            // 返回 null 会让前端 v-for 直接报错
            assertThat(service.list("TK-9")).isNotNull().isEmpty();
        }
    }
}
