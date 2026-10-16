package com.devops.agent.infrastructure.guard;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * REAL 模式启动期 AI 渠道契约自检（方案 C 第一道防线，批 74；P1 适配 DB 权威源，批 82）。
 *
 * <h3>P1 适配（批 82）</h3>
 * 占位 key 检测从 yml 升级为 DB 优先：chat / embedding 渠道的 API key 先从
 * {@code sys_ai_channel} 取（解密后），DB 缺失或无 key 才回落 yml。
 * 消掉「yml 占位 key 未改但 DB 已编辑真 key → 启动被误拦」的假阳性。
 */
@Component
@ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
public class RealModeStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(RealModeStartupGuard.class);

    private final EmbeddingModel embeddingModel;
    private final ModelFingerprintGuard fingerprintGuard;
    private final SemanticCacheService semanticCacheService;
    private final AiChannelRepository channelRepo;

    @Value("${devops.ai.channels.chat.api-key:}")
    private String ymlChatKey;

    @Value("${devops.ai.channels.embedding.api-key:}")
    private String ymlEmbeddingKey;

    @Value("${MODEL_KEY_CRYPT_SECRET:}")
    private String cryptSecret;

    @Value("${devops.ai.vector.dimension:1536}")
    private int vectorDimension;

    public RealModeStartupGuard(@Qualifier("embeddingModel") EmbeddingModel embeddingModel,
                                 ModelFingerprintGuard fingerprintGuard,
                                 SemanticCacheService semanticCacheService,
                                 AiChannelRepository channelRepo) {
        this.embeddingModel = embeddingModel;
        this.fingerprintGuard = fingerprintGuard;
        this.semanticCacheService = semanticCacheService;
        this.channelRepo = channelRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        // ---- -1) 主密钥在位性（批89 P0 可见性）：REAL=库里是真 Key，没主密钥
        // 就是明文落库——这个事实必须在启动日志里可见，不能等 DB dump 才发现。
        // 只 WARN 不拒启：拒启与否留给占位 key/维度/指纹三条既有硬闸裁量。
        if (cryptSecret == null || cryptSecret.isBlank()) {
            log.warn("⚠️ [RealModeStartupGuard] MODEL_KEY_CRYPT_SECRET 未配置——REAL 模式下 API Key 正以明文落库"
                    + "（DB dump/备份=Key 泄露）。生产必配：openssl rand -hex 32 写入 .env 后重启"
                    + "，存量明文行在「模型渠道配置」页重新保存一次即转 enc:v1: 密文。");
        }

        // ---- 0) 占位 key 检测（P1：DB 优先，yml 回落） ----
        String chatKey = effectiveKey(AiChannel.KEY_CHAT, ymlChatKey);
        String embKey = effectiveKey(AiChannel.KEY_EMBEDDING, ymlEmbeddingKey);
        for (String key : new String[]{chatKey, embKey}) {
            if (key == null || key.isBlank() || key.startsWith("your-") || key.contains("-here")) {
                throw new IllegalStateException(
                        "[RealModeStartupGuard] AI 渠道 api-key 为空或占位符（" + key + "）——"
                        + "REAL 模式拒绝启动。请在「模型渠道配置」页面编辑 Key 或设置环境变量。");
            }
        }

        // ---- 1) 维度契约：实测一次 embed ----
        Embedding probe;
        try {
            probe = embeddingModel.embed("维度契约自检：opsbrain startup guard").content();
        } catch (Exception e) {
            // 批91 §五欠账销账（批93）：把「额度枯竭」从「配置不可达」里分出来。
            // 上游配额耗尽回 400 + AllocationQuota.FreeTierOnly（2026-10-13 探针实测），
            // 修法是充值/关免费档，跟 base-url/api-key 打错毫无关系——混成一句
            // 「检查配置」会把值班人往错方向指。硬闸照旧拒启，只是把话说对。
            if (isQuotaExhausted(e)) {
                throw new IllegalStateException(
                        "[RealModeStartupGuard] embedding 配额已耗尽——REAL 模式拒绝启动（配置无误，是上游额度问题）。"
                        + "修法：厂商控制台充值，或关闭「仅免费额度」档位（上游回执 code=AllocationQuota.FreeTierOnly）；"
                        + "额度恢复后重启即可，无需改 base-url / api-key / model。"
                        + "最近一次错误：" + e.getMessage(), e);
            }
            throw new IllegalStateException(
                    "[RealModeStartupGuard] embedding 渠道不可达——REAL 模式拒绝启动。"
                    + "检查 devops.ai.embedding.base-url / api-key / model 配置。"
                    + "最近一次错误：" + e.getMessage(), e);
        }
        int actual = probe.vector().length;
        if (actual != vectorDimension) {
            throw new IllegalStateException(String.format(
                    "[RealModeStartupGuard] 维度铁律违约：embedding 模型实测输出 %d 维 ≠ 配置 %d 维。"
                    + "修法二选一：① 换支持 %d 维的模型（推荐，基线/索引不动）；"
                    + "② 全链路改维（V1 基线 VECTOR(n) + vector.dimension + 全库向量重建 + 索引重建）。",
                    actual, vectorDimension, vectorDimension));
        }
        log.info("✅ [RealModeStartupGuard] 维度契约自检通过：实测 {} 维 = 配置 {} 维", actual, vectorDimension);

        // ---- 2) 指纹锁 ----
        String stale = fingerprintGuard.verifyOrRecord();
        if (stale != null) {
            try {
                semanticCacheService.clearAllCache();
                log.warn(" [RealModeStartupGuard] 检测到模型变更，语义缓存已同步清空");
            } catch (Exception cacheEx) {
                log.error("[RealModeStartupGuard] 语义缓存清空失败——需手工清 devops:cache:ans:*", cacheEx);
            }
            throw new IllegalStateException(String.format(
                    "[RealModeStartupGuard] embedding 模型已变更（库中指纹 %s ≠ 当前 %s）。"
                    + "库内旧向量与新模型语义空间不兼容，静默混用会返回无报错的垃圾检索。"
                    + "恢复步骤：① 重算全库切片向量（重摄取或批量 embed）；"
                    + "② Redis 语义缓存已自动清空（若失败手工清 devops:cache:ans:*）；"
                    + "③ 调 ModelFingerprintGuard.recordAfterRebuild() 解锁。",
                    stale, fingerprintGuard.currentFingerprint()));
        }
        log.info("✅ [RealModeStartupGuard] 模型指纹锁校验通过");
    }

    /**
     * 配额枯竭识别（批93；回执形态按 2026-10-13 本机 /embeddings 直连探针实测）：
     * 上游 400 回执体 {@code {"error":{"type":"AllocationQuota.FreeTierOnly",...}}}，
     * 各传输层把码文挂 message / cause 链上，逐层拼起来做不区分大小写匹配；
     * 前缀「Error: 400 - {json}」这类噪音把 JSON 原样带进来，contains 逐层命中即可。
     * 词面刻意收窄（quota/free tier/insufficient_quota/中文欠费词），
     * 不拿通用词兜底——误报会把网络故障说成额度问题，指错方向比含糊更糟。
     */
    private static boolean isQuotaExhausted(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        for (int hop = 0; t != null && hop < 8; hop++) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        String s = sb.toString().toLowerCase(Locale.ROOT);
        return s.contains("quota") || s.contains("free tier")
                || s.contains("insufficient_quota") || s.contains("欠费") || s.contains("余额不足");
    }

    /** DB 优先取加密 key 并解密，缺失/异常回落 yml。 */
    private String effectiveKey(String channelKey, String ymlKey) {
        try {
            AiChannel ch = channelRepo.findByKey(channelKey).orElse(null);
            if (ch != null && ch.apiKeyEnc() != null && !ch.apiKeyEnc().isBlank()) {
                return ApiKeyCrypt.decrypt(ch.apiKeyEnc(), cryptSecret);
            }
        } catch (Exception e) {
            log.warn("[RealModeStartupGuard] 解密 {} key 失败，回落 yml: {}", channelKey, e.getMessage());
        }
        return ymlKey;
    }
}