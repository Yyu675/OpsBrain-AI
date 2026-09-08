# =============================================================================
# OpsBrain AI 生产镜像（多阶段构建）
#
# 前端产物打进后端 static 目录，单容器交付：
#   - 少一个 Nginx 容器与一层反向代理配置
#   - 前端用相对路径调 /ai/api/**，同源，天然无跨域问题
#
# 构建：docker build -t opsbrain-ai:latest .
# 运行：见 docker-compose.yml
# =============================================================================

# ---------- 阶段 1：构建前端 ----------
FROM node:22-alpine AS web
WORKDIR /build/web

# 先只拷贝清单再 npm ci：依赖未变时这一层命中缓存，
# 改业务代码不会触发重新装包（几分钟 → 几秒）。
COPY devops-platform-frontend/package.json devops-platform-frontend/package-lock.json ./
RUN npm ci

COPY devops-platform-frontend/ ./
RUN npm run build


# ---------- 阶段 2：构建后端 ----------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /build

# 同理：先拉依赖，后拷源码
COPY pom.xml ./
COPY .mvn/ ./.mvn/
RUN mvn -B -ntp dependency:go-offline

COPY src/ ./src/
# 前端产物放进 static，由 Spring Boot 直接托管
COPY --from=web /build/web/dist/ ./src/main/resources/static/

# 跳过测试：测试已在 CI 中执行过（且需要 DB/Redis），
# 镜像构建阶段不应依赖外部中间件。
RUN mvn -B -ntp clean package -DskipTests


# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:21-jre-alpine

# curl 供 HEALTHCHECK 使用；tzdata 保证日志时间是本地时区
RUN apk add --no-cache curl tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 非 root 运行：容器逃逸时限制影响面。
# 这是容器安全的基本要求，不是可选项。
RUN addgroup -S opsbrain && adduser -S opsbrain -G opsbrain
USER opsbrain

WORKDIR /app
COPY --from=api --chown=opsbrain:opsbrain /build/target/*.jar app.jar

EXPOSE 8088

# 健康检查路径必须带 context-path（server.servlet.context-path=/ai）。
# 用 /ping（HealthCheckController 的 @GetMapping({"", "/ping"})）——
# 它只回进程存活，不探 DB/模型；存活探针不应因下游依赖抖动而判定容器该重启。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8088/ai/api/v1/health/ping || exit 1

# -XX:MaxRAMPercentage：让 JVM 按容器内存限制自适应堆大小。
#   不设的话 JVM 只看到宿主机内存，容易被 cgroup OOMKill。
# -XX:+ExitOnOutOfMemoryError：OOM 后直接退出让编排层重启，
#   而不是留一个半死不活、请求全失败却仍通过存活探针的实例。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
