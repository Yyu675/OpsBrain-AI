package com.devops.agent.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基座（S0-2，路线图 §4.2 任务 0-2.2/0-2.3）。
 *
 * <h3>这个类解决什么问题</h3>
 * 在 Testcontainers 之前，集成测试连的是「环境里恰好存在的那台 PG」
 * （dev profile 写死的 25432）。这带来三个都真实发生过的痛：
 * <ul>
 *   <li><b>本地起不来</b>：新机器只装 Docker 跑 {@code ./mvnw verify} 必挂；</li>
 *   <li><b>与共享库互相污染</b>：测试写入/清表会踩别人库里的数据；</li>
 *   <li><b>空库迁移链不可证</b>：CI 里 psql 预建后，「Flyway 对空库建全表」
 *       只能间接推断。容器每轮都是真空库，启动即自动执行全量迁移——
 *       这是 S0-1 验收 #1 的第二重证据（第一重是 CI 全迁移重放）。</li>
 * </ul>
 *
 * <h3>为什么是 singleton 容器而不是 @Testcontainers + @Container</h3>
 * {@code @Testcontainers} 对 <b>每个测试类</b>起一个容器，十几秒一个，
 * 类一多 CI 直接拖垮。此处用 <b>JVM 级静态单例</b>（static 块启动）：
 * 同 JVM 内所有子类<b>共享同一个容器</b>，只付一次启动成本；
 * 容器销毁交给 Testcontainers 的 Ryuk sidecar（JVM 退出即回收，无残留）。
 *
 * <h3>为什么是 pgvector/pgvector:pg16 而不是 postgres:16</h3>
 * 检索链路依赖 {@code VECTOR(1536)} 列类型与 {@code vector_dims()} 函数——
 * 官方 postgres 镜像<b>不带 pgvector 扩展</b>，建表语句直接报
 * 「type vector does not exist」。pgvector 官方镜像预装了扩展。
 * {@code asCompatibleSubstituteFor(PostgreSQLContainer.IMAGE)} 是
 * Testcontainers 的显式声明：「该镜像与官方 postgres 镜像 API 兼容」，
 * 不做这个声明它会拒绝启动非官方命名的镜像。
 *
 * <h3>为什么用 @DynamicPropertySource 而不是 @ServiceConnection</h3>
 * @ServiceConnection（Boot 3.1+）需要 spring-boot-testcontainers 依赖，
 * 且自动装配的点过于隐式——排查「测试到底连了哪个库」时要在框架内部
 * 翻连接细节。这里三行显式注册，把容器 URL/账号/密码钉在明处，
 * dev profile 里写死的 25432 连接只对本类的 JVM 失效。
 *
 * <h3>使用约束</h3>
 * <ul>
 *   <li>子类继承本类，并自行加 {@code @SpringBootTest} / {@code @ActiveProfiles("dev")}
 *       / {@code @TestPropertySource}（AI_MODE=MOCK 之类属测试关注点，不没收子类选择权）；</li>
 *   <li>共享容器意味着<b>测试间数据可见</b>：写入型测试必须自带幂等清理
 *       （如 {@code ingestAllLocalDocuments(true)} 的重建语义），
 *       或断言只依赖「建表存在」这类不可变事实；</li>
 *   <li>禁止在本基座里塞业务前置数据——基座只提供「一台真空 pgvector」，
 *       数据是每个测试自己的职责。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-2）
 */
public abstract class AbstractIntegrationTest {

    /**
     * 共享的 PG 容器（pgvector/pgvector:pg16 镜像，预装向量扩展）。
     * 线程安全：{@link PostgreSQLContainer} 的 {@code start()} 内部有双重检查锁，
     * 且各子类启动时序由 JUnit 类加载顺序决定，此处 static 块天然单例。
     */
    @SuppressWarnings("resource") // 容器不 close——Ryuk sidecar 在 JVM 退出时统一回收
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor(PostgreSQLContainer.IMAGE))
            .withDatabaseName("devops_knowledge_db")
            .withUsername("devops")
            .withPassword("devops_password");

    /**
     * 在 Spring 上下文装配前（此时 DataSource 尚未创建）把容器连接信息
     * 注入为最高优先级属性，覆盖 application-dev.yml 里写死的 25432。
     * Flyway 在同一上下文启动期将对这台真空库执行全量迁移。
     */
    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static {
        // 显式启动（而非依赖首次 getJdbcUrl 触发）：若容器拉不起来，
        // 错误抛出点定位在本类，而不是包装成「属性注册失败」。
        POSTGRES.start();
    }
}
