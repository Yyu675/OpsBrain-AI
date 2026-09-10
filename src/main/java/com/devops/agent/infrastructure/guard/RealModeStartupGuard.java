package com.devops.agent.infrastructure.guard;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * REAL 模式启动期 AI 渠道契约自检（方案 C 第一道防线，批 74）。
 *
 * <h3>防什么</h3>
 * <p>
 * 批 73 实测暴露的真实故障形态：配置的 embedding 模型维度与铁律不符
 * （qwen3-embedding-8b 在网关上仅支持 ≤1024，配置 1536 三连败）。
 * 此类错误此前要等到<b>第一次写库或检索</b>才以
 * {@code expected 1536 dimensions, not 1024} 暴露——运行期才炸，
 * 排障面在业务日志里，不如启动期 fail-fast 直接。
 * </p>
 *
 * <h3>做什么</h3>
 * <ol>
 *   <li><b>维度契约自检</b>：真实调一次 embed("维度自检")，实测维度 ≠
 *       {@code devops.ai.vector.dimension} → 抛异常拒绝启动；</li>
 *   <li><b>指纹锁校验</b>：调 {@link ModelFingerprintGuard#verifyOrRecord()}——
 *       embedding 模型与上次入库记录不一致 → 抛异常并给出重算指引，
 *       拒绝「新旧向量静默混用」。</li>
 * </ol>
 *
 * <h3>为什么用 ApplicationReadyEvent 而不是 Bean 初始化期</h3>
 * <p>
 * embed 实调需要完整的 Bean 依赖链（OkHttp/限流装饰器）就位；
 * Ready 事件时全链已备，且此时拒绝启动对外表现一致（进程退出、
 * 编排器重启退避），无需在 Bean 依赖序上做精细手术。
 * </p>
 *
 * <p>MOCK 模式不装配本组件——假向量确定性生成，无契约可检。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-10（批 74，方案 C）
 */
@Component
@ConditionalOnProperty(name = "devops.ai.mode", havingValue = "REAL")
public class RealModeStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(RealModeStartupGuard.class);

    private final EmbeddingModel embeddingModel;
    private final ModelFingerprintGuard fingerprintGuard;
    private final SemanticCacheService semanticCacheService;
    @org.springframework.beans.factory.annotation.Value("${devops.ai.vector.dimension:1536}")
    private int vectorDimension;

    public RealModeStartupGuard(@Qualifier("embeddingModel") EmbeddingModel embeddingModel,
                                 ModelFingerprintGuard fingerprintGuard,
                                 SemanticCacheService semanticCacheService) {
        this.embeddingModel = embeddingModel;
        this.fingerprintGuard = fingerprintGuard;
        this.semanticCacheService = semanticCacheService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        // ---- 1) 维度契约：实测一次 embed ----
        Embedding probe;
        try {
            probe = embeddingModel.embed("维度契约自检：opsbrain startup guard").content();
        } catch (Exception e) {
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
                    + "② 全链路改维（V1 基线 VECTOR(n) + vector.dimension + 全库向量重建 + 索引重建）。"
                    + "当前错误源于「配置了模型不支持的维度」，见 AGENTS §3.3 三处联动铁律。",
                    actual, vectorDimension, vectorDimension));
        }
        log.info("✅ [RealModeStartupGuard] 维度契约自检通过：实测 {} 维 = 配置 {} 维", actual, vectorDimension);

        // ---- 2) 指纹锁：模型变更检测 ----
        String stale = fingerprintGuard.verifyOrRecord();
        if (stale != null) {
            // 语义缓存同步作废：缓存里的查询向量是旧模型算的，新模型下相似度比对全错。
            // 在拒绝启动前先清——运维重算向量+解锁后直接可跑，不留脏缓存。
            try {
                semanticCacheService.clearAllCache();
                log.warn("🗑️ [RealModeStartupGuard] 检测到模型变更，语义缓存已同步清空");
            } catch (Exception cacheEx) {
                log.error("⛔ [RealModeStartupGuard] 语义缓存清空失败——需手工清 devops:cache:ans:*", cacheEx);
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
}
