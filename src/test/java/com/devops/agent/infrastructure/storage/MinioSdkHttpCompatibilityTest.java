package com.devops.agent.infrastructure.storage;

import com.sun.net.httpserver.HttpServer;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 SDK + 本地 HTTP 协议夹具验证 Boot BOM 管理下的运行时依赖兼容性。
 * 不 mock MinioClient，不依赖外网/云凭据；不是完整 MinIO 服务端端到端测试。
 */
class MinioSdkHttpCompatibilityTest {
    @Test
    @Timeout(30)
    void bucketUploadPresignAndDeleteUseCompatibleRuntime() throws Exception {
        var requests = new ConcurrentLinkedQueue<String>();
        var uploaded = new AtomicReference<byte[]>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                // 夹具也验证签名头存在；所有凭据都是固定假值。
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256")) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                if ("PUT".equals(exchange.getRequestMethod())) {
                    uploaded.set(exchange.getRequestBody().readAllBytes());
                    exchange.getResponseHeaders().set("ETag", "\"sdk-probe-etag\"");
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders("DELETE".equals(exchange.getRequestMethod()) ? 204 : 200, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                    .credentials("sdk-probe", "sdk-probe-not-a-real-secret")
                    .region("us-east-1")
                    .build();
            assertThat(client.bucketExists(BucketExistsArgs.builder().bucket("opsbrain-sdk-test").build())).isTrue();
            byte[] payload = "opsbrain sdk compatibility probe".getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectArgs.builder().bucket("opsbrain-sdk-test").object("probe.txt")
                    .stream(new ByteArrayInputStream(payload), payload.length, -1).contentType("text/plain").build());
            assertThat(uploaded.get()).isEqualTo(payload);
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket("opsbrain-sdk-test").object("probe.txt").expiry(60).build());
            assertThat(url).contains("/opsbrain-sdk-test/probe.txt?")
                    .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256").contains("X-Amz-Signature=");
            client.removeObject(RemoveObjectArgs.builder().bucket("opsbrain-sdk-test").object("probe.txt").build());
            assertThat(requests).containsExactly("HEAD /opsbrain-sdk-test", "PUT /opsbrain-sdk-test/probe.txt",
                    "DELETE /opsbrain-sdk-test/probe.txt");
        } finally {
            server.stop(0);
        }
    }
}
