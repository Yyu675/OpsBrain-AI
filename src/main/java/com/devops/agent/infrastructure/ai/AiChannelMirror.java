package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * AI 渠道配置启动镜像（阶段A-P0）。
 *
 * <h3>职责</h3>
 * 启动时把当前 {@code application.yml}/{@code .env} 生效的模型渠道配置
 * （base-url/model/dimension/key）<b>镜像</b> 进 {@code sys_ai_channel} 表，
 * 供只读展示与后续（P1/P2）可视化编辑的底座。
 *
 * <h3>P1 起 DB 是权威源</h3>
 * <ul>
 *   <li>本组件只在<b>渠道不存在</b>时种下 yml 当前值（空库种子）；</li>
 *   <li>渠道已存在（含 UI 编辑过）→ <b>不覆盖</b>，尊重 DB 现状。
 *       否则每次启动把运维在 UI 的修改覆盖回 yml，编辑功能形同虚设。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiChannelMirror implements ApplicationRunner {

    private final AiChannelRepository repo;
    private final String mode;

    @Value("${devops.ai.channels.chat.base-url:}")
    private String chatBaseUrl;
    @Value("${devops.ai.channels.chat.api-key:}")
    private String chatKey;
    @Value("${devops.ai.channels.chat.turbo-model:}")
    private String turboModel;
    @Value("${devops.ai.channels.chat.reasoner-model:}")
    private String reasonerModel;

    @Value("${devops.ai.channels.embedding.base-url:}")
    private String embeddingBaseUrl;
    @Value("${devops.ai.channels.embedding.api-key:}")
    private String embeddingKey;
    @Value("${devops.ai.channels.embedding.model:}")
    private String embeddingModel;

    @Value("${devops.ai.channels.reranker.base-url:}")
    private String rerankerBaseUrl;
    @Value("${devops.ai.channels.reranker.api-key:}")
    private String rerankerKey;
    @Value("${devops.ai.channels.reranker.model:}")
    private String rerankerModel;

    @Value("${devops.ai.vector.dimension:1536}")
    private int dimension;

    /** Key 加密密钥（环境变量）。未配置时降级明文（打一次告警）。 */
    @Value("${MODEL_KEY_CRYPT_SECRET:}")
    private String cryptSecret;

    public AiChannelMirror(AiChannelRepository repo,
                           @Value("${devops.ai.mode:MOCK}") String mode) {
        this.repo = repo;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedIfAbsent(AiChannel.KEY_CHAT,
                    chatBaseUrl, chatKey, turboModel, reasonerModel, null);
            seedIfAbsent(AiChannel.KEY_EMBEDDING,
                    embeddingBaseUrl, embeddingKey, embeddingModel, null, dimension);
            seedIfAbsent(AiChannel.KEY_RERANKER,
                    rerankerBaseUrl, rerankerKey, rerankerModel, null, null);
            log.info("🛰 [AiChannelMirror] AI 渠道配置就绪 | mode={} | DB 权威源主持 | chat/embedding/reranker",
                    mode);
        } catch (Exception e) {
            log.error("🚨 [AiChannelMirror] AI 渠道配置镜像失败（不阻断启动）| {}", e.toString());
        }
    }

    /** 仅渠道不存在时种下启动配置；已存在（含 UI 编辑）不覆盖。 */
    private void seedIfAbsent(String key, String baseUrl, String rawKey,
                              String model1, String model2, Integer dim) {
        repo.findByKey(key).ifPresentOrElse(
                existing -> log.debug("[AiChannelMirror] 渠道 {} 已存在（保留 DB 现状，不覆盖）", key),
                () -> repo.upsert(channel(key, baseUrl, rawKey, model1, model2, dim)));
    }

    private AiChannel channel(String key, String baseUrl, String rawKey,
                              String model1, String model2, Integer dim) {
        // 尊重既有状态：渠道已存在（运维停用等）则沿用其 status，首次落库才初始化 ACTIVE。
        // 否则每次启动镜像把 status 强制写回 ACTIVE，会让 AiChannelRepository.updateStatus
        // 形同虚设（运维停用后一经重启即被覆盖）。
        String status = repo.findByKey(key).map(AiChannel::status)
                .orElse("ACTIVE");
        return new AiChannel(
                key,
                nz(baseUrl),
                ApiKeyCrypt.encrypt(rawKey, cryptSecret),
                ApiKeyCrypt.mask(rawKey),
                key.equals(AiChannel.KEY_CHAT) ? nz(model1) : null,
                key.equals(AiChannel.KEY_CHAT) ? nz(model2) : null,
                key.equals(AiChannel.KEY_CHAT) ? null : nz(model1),
                dim,
                status,
                null
        );
    }

    /** null → ""（避免表内 null 抖动）。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
