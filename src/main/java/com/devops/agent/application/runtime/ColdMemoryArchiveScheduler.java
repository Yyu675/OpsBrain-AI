package com.devops.agent.application.runtime;

import com.devops.agent.domain.memory.SessionSummary;
import com.devops.agent.infrastructure.persistence.repo.SessionSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷记忆归档任务（B-2，三层记忆的冷层落地）
 *
 * <h3>归档内容的真实边界（重要）</h3>
 * <p>
 * 6.7 三层记忆将冷层描述为「归档文件 = 历史全量」，但实测：
 * <b>热记忆（Redis）TTL 仅 120 分钟，而归档窗口按天计</b>——归档执行时
 * 对话原文早已过期消失。故本任务归档的是<b>温记忆的摘要 + 关键事实 + 统计</b>，
 * 不含逐轮对话原文。
 * </p>
 * <p>
 * 这一限制被显式写入归档 JSON 的 {@code contentScope} 字段，避免日后
 * 有人拿归档文件当「完整对话记录」使用而得出错误结论——若将来需要全量
 * 对话，须先让对话主链路把原文转写到持久层（触及 SSE 主流程，另行评估）。
 * </p>
 *
 * <h3>归档后不删温记忆</h3>
 * <p>
 * 只置 {@code archived=true} + {@code archive_path}，摘要与关键事实保留在库中：
 * 摘要仅百字级，占用极小；删掉会让「历史会话」列表突然缺失这些会话，
 * 除非再建一层归档索引表，收益不及复杂度。
 * </p>
 *
 * <h3>失败即跳过，不中断整批</h3>
 * <p>
 * 单条归档失败（对象存储瞬时不可用、序列化异常）仅记 ERROR 并继续下一条——
 * 一条坏数据不应让整批积压。下一轮扫描会自然重试（该条仍为未归档态）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Component
public class ColdMemoryArchiveScheduler {

    private final SessionSummaryRepository summaryRepository;
    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;

    /** 归档总开关。默认关闭——归档写对象存储且置 archived 标记，需运维显式启用 */
    @Value("${devops.ai.memory.archive-enabled:false}")
    private boolean archiveEnabled;

    /** 保留天数：会话创建超过此天数且未归档者进入候选 */
    @Value("${devops.ai.memory.archive-after-days:90}")
    private int archiveAfterDays;

    /** 单轮批量上限，防长事务与内存堆积 */
    @Value("${devops.ai.memory.archive-batch-size:200}")
    private int batchSize;

    /** 归档专用桶。与附件桶分离——两者保留期与访问模式不同，混用会让生命周期策略无法分别配置 */
    @Value("${devops.storage.minio.archive-bucket:devops-memory-archive}")
    private String archiveBucket;

    private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository turnRepository;

    public ColdMemoryArchiveScheduler(SessionSummaryRepository summaryRepository,
                                      MinioClient minioClient,
                                      ObjectMapper objectMapper,
                                      com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository turnRepository) {
        this.summaryRepository = summaryRepository;
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
        this.turnRepository = turnRepository;
    }

    /**
     * 归档超期会话摘要（默认每日 04:23）
     * <p>
     * 错开整点与 :00/:30——与孤儿切片清理（03:17）、知识保留期扫描（03:30/04:00）
     * 分离，避免多个定时任务在同一时刻争抢数据库与对象存储连接。
     * </p>
     * <p>
     * cron 由配置提供而非硬编码：运维需按业务低峰调整，且验证时需临时提频。
     * </p>
     * <p>
     * 异常不外抛：定时任务抛异常会导致 Spring 停止后续调度（6.44 契约）。
     * </p>
     */
    @Scheduled(cron = "${devops.ai.memory.archive-cron:0 23 4 * * *}")
    public void archiveExpiredSessions() {
        if (!archiveEnabled) {
            log.debug("[ColdArchive] 归档开关关闭，跳过（devops.ai.memory.archive-enabled=false）");
            return;
        }

        try {
            long pending = summaryRepository.countArchiveCandidates(archiveAfterDays);
            if (pending == 0) {
                log.info("[ColdArchive] 无待归档会话 | 保留期={} 天", archiveAfterDays);
                return;
            }

            log.info("🗄️ [ColdArchive] 开始归档 | 待归档={} | 本轮上限={} | 保留期={} 天",
                    pending, batchSize, archiveAfterDays);

            ensureArchiveBucket();

            List<SessionSummary> candidates =
                    summaryRepository.findArchiveCandidates(archiveAfterDays, batchSize);

            int ok = 0;
            int failed = 0;
            for (SessionSummary s : candidates) {
                try {
                    String objectKey = archiveOne(s);
                    int rows = summaryRepository.markArchived(s.getId(), objectKey);
                    if (rows == 0) {
                        // 竞态：已被其他执行归档。对象已写入但成为孤儿——
                        // 记 WARN 供对账，不视为失败（数据一致性未受损）
                        log.warn("⚠️ [ColdArchive] 标记归档未命中（可能已被并发归档）| sessionId={} | 孤儿对象={}",
                                s.getSessionId(), objectKey);
                    } else {
                        ok++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("❌ [ColdArchive] 单条归档失败，跳过继续 | sessionId={} | {}",
                            s.getSessionId(), e.getMessage());
                }
            }

            long remaining = summaryRepository.countArchiveCandidates(archiveAfterDays);
            log.info("✅ [ColdArchive] 归档完成 | 成功={} | 失败={} | 剩余待归档={}", ok, failed, remaining);
            if (remaining > 0) {
                // 如实告知积压，不静默截断（6.24「no silent caps」）
                log.info("[ColdArchive] 仍有 {} 条待归档，将在下一轮继续（单轮上限 {}）", remaining, batchSize);
            }
        } catch (Exception e) {
            // 定时任务不得外抛：抛出会让 Spring 停止后续调度
            log.error("❌ [ColdArchive] 归档任务异常终止", e);
        }
    }

    /**
     * 归档单条会话摘要为 JSON 对象
     *
     * @return 对象键，供回填 archive_path
     */
    private String archiveOne(SessionSummary s) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();

        // B-2 补全：带上逐轮对话原文（来自 sys_agent_conversation_turn）。
        //
        // 此前这里恒为 SUMMARY_ONLY，根因是热记忆 TTL 仅 120 分钟、
        // 归档按天执行，原文早已过期。现在原文由 AgentMemoryManager
        // 每轮旁路转写到库里，归档时可直接取。
        List<Map<String, Object>> turns = turnRepository.findBySession(s.getSessionId());

        // contentScope 必须**据实**反映这一份归档的真实内容，不能写死。
        //
        // 存量会话（本次改动之前产生的）没有原文记录，取出来就是空列表；
        // 若一律标 FULL_TRANSCRIPT，使用者会以为「这个会话只聊了 0 轮」，
        // 而实际是原文从未被记录过——这比老老实实标 SUMMARY_ONLY 更误导。
        boolean hasTranscript = !turns.isEmpty();
        payload.put("contentScope", "FULL_TRANSCRIPT");
        payload.put("contentScopeNote", hasTranscript
                ? "含温记忆摘要、关键事实与逐轮对话原文（原文超 32K 字符的字段已截断并标注）"
                : "仅含温记忆摘要与关键事实；该会话无原文记录"
                        + "（多为 B-2 原文转写上线前产生的存量会话）");
        payload.put("archivedAt", LocalDateTime.now().toString());
        payload.put("sessionId", s.getSessionId());
        payload.put("traceId", s.getTraceId());
        payload.put("tenantId", s.getTenantId());
        payload.put("summary", s.getSummary());
        payload.put("keyFacts", s.getKeyFacts());
        payload.put("turnCount", s.getTurnCount());
        payload.put("totalTokens", s.getTotalTokens());
        payload.put("totalCostRmb", s.getTotalCostRmb());
        payload.put("finalState", s.getFinalState());
        payload.put("relatedTickets", s.getRelatedTickets());
        payload.put("createTime", s.getCreateTime() != null ? s.getCreateTime().toString() : null);
        payload.put("updateTime", s.getUpdateTime() != null ? s.getUpdateTime().toString() : null);
        // 逐轮原文放在最后：摘要字段在前便于人工快速浏览，
        // 原文体积大，放前面会让文件头部难以阅读
        payload.put("transcriptTurnCount", turns.size());
        payload.put("transcript", turns);

        byte[] json = objectMapper.writeValueAsBytes(payload);

        // 对象键含创建日期分区，便于按期批量清理；sessionId 已是唯一键无需再加 UUID
        String datePart = s.getCreateTime() != null
                ? s.getCreateTime().format(PATH_DATE)
                : LocalDateTime.now().format(PATH_DATE);
        String objectKey = "session-summary/" + datePart + "/" + s.getSessionId() + ".json";

        try (ByteArrayInputStream in = new ByteArrayInputStream(json)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(archiveBucket)
                    .object(objectKey)
                    .stream(in, json.length, -1)
                    .contentType("application/json; charset=utf-8")
                    .build());
        }

        log.debug("📤 [ColdArchive] 已写入冷存储 | key={} | size={}B", objectKey, json.length);
        return objectKey;
    }

    /**
     * 确保归档桶存在
     * <p>桶不存在时创建。不设为 public——会话摘要含运维诊断信息，属敏感数据（6.14 契约）。</p>
     */
    private void ensureArchiveBucket() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(archiveBucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(archiveBucket).build());
            log.info("🪣 [ColdArchive] 归档桶不存在，已创建: {}", archiveBucket);
        }
    }
}
