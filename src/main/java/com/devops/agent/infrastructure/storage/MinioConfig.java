package com.devops.agent.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置
 * <p>
 * 选用 MinIO 而非本地文件系统的理由：
 * <ul>
 *   <li>S3 兼容协议，生产可平滑切换到阿里云 OSS / AWS S3，无需改代码</li>
 *   <li>多实例部署时文件共享，本地目录做不到</li>
 *   <li>原生支持预签名 URL——文件不经后端流转，不占应用带宽</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Configuration
public class MinioConfig {

    @Value("${devops.storage.minio.endpoint}")
    private String endpoint;

    @Value("${devops.storage.minio.access-key}")
    private String accessKey;

    @Value("${devops.storage.minio.secret-key}")
    private String secretKey;

    @Value("${devops.storage.minio.bucket}")
    private String bucket;

    /**
     * MinIO 客户端
     * <p>
     * 启动时确保桶存在。桶已由 docker-compose 的 minio-init 建好，
     * 此处兜底覆盖「手工起 MinIO 未跑 init」的场景。
     * </p>
     * <p>
     * 连接失败不阻塞启动——附件是增强功能，
     * 对象存储不可用时其余能力应照常工作（Fail-Safe 降级）。
     * </p>
     */
    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("🪣 [MinIO] 桶不存在，已创建: {}", bucket);
            }
            log.info("✅ [MinIO] 客户端就绪 | endpoint={} | bucket={}", endpoint, bucket);
        } catch (Exception e) {
            // 仅告警：附件功能会在调用时返回明确错误，
            // 不应因对象存储不可用导致整个应用起不来
            log.warn("⚠️ [MinIO] 连通性检查失败，附件功能将不可用 | endpoint={} | {}",
                    endpoint, e.getMessage());
        }

        return client;
    }
}