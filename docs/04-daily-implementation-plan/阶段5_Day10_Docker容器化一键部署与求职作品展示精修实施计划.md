# 📅 阶段5_Day10_Docker容器化一键部署与求职作品展示精修实施计划

> **阶段所属**：阶段五：部署上线与求职精细化包装  
> **当日核心目标**：完成整个全栈工程（Spring Boot + Vue3 Nginx + PgVector/Redis）的 `Dockerfile` 与一键生产多容器编排文件 `docker-compose.prod.yml` 编写；把线上演示链接挂载成功，并将最硬核的 **STAR/PAR 法则项目亮点与“4.2%幻觉率 / 降本70%”实绩指标** 极其亮眼地打进求职简历中。  
> **预计耗时**：5 - 6 小时  
> **完成产出**：能够使用一条命令 `docker compose -f docker-compose.prod.yml up -d` 在阿里云/腾讯云 2核4G 服务器上一键拉起全部服务并完美访问；产出一份随时可直接向大厂投递高邀约率的极品简历。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：全栈 Docker 容器化打包文件编写
1. **构建后端 Spring Boot 镜像 (`Dockerfile.backend`)**：
   ```dockerfile
   FROM eclipse-temurin:17-jre-alpine
   WORKDIR /app
   COPY target/devops-ai-agent-1.0.0.jar app.jar
   EXPOSE 8080
   ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
   ```
2. **构建前端 Vue3 Nginx 静态服务镜像 (`Dockerfile.frontend`)**：
   ```dockerfile
   # 编译构建阶段
   FROM node:18-alpine AS build-stage
   WORKDIR /app
   COPY package*.json ./
   RUN npm install
   COPY . .
   RUN npm run build

   # 生产 Nginx 托管阶段
   FROM nginx:1.24-alpine
   COPY --from=build-stage /app/dist /usr/share/nginx/html
   COPY nginx.conf /etc/nginx/conf.d/default.conf
   EXPOSE 80
   CMD ["nginx", "-g", "daemon off;"]
   ```
3. **编写完整多容器一键生产编排 (`docker-compose.prod.yml`)**：
   ```yaml
   version: '3.8'
   services:
     pgvector-db:
       image: ankane/pgvector:v0.5.1
       container_name: prod-pgvector
       environment:
         POSTGRES_DB: devops_knowledge_db
         POSTGRES_USER: devops
         POSTGRES_PASSWORD: ${DB_PWD:devops_secure_pwd}
       volumes:
         - pgvector_data:/var/lib/postgresql/data
         - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql

     redis-cache:
       image: redis:7.0-alpine
       container_name: prod-redis
       command: redis-server --requirepass ${REDIS_PWD:redis_secure_pwd}

     backend-api:
       build:
         context: .
         dockerfile: Dockerfile.backend
       container_name: prod-backend
       ports:
         - "8080:8080"
       environment:
         SPRING_DATASOURCE_URL: jdbc:postgresql://pgvector-db:5432/devops_knowledge_db
         SPRING_DATASOURCE_PASSWORD: ${DB_PWD:devops_secure_pwd}
         SPRING_DATA_REDIS_HOST: redis-cache
         SPRING_DATA_REDIS_PASSWORD: ${REDIS_PWD:redis_secure_pwd}
         DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY}
       depends_on:
         - pgvector-db
         - redis-cache

     frontend-web:
       build:
         context: ./devops-agent-web
         dockerfile: Dockerfile.frontend
       container_name: prod-frontend
       ports:
         - "80:80"
       depends_on:
         - backend-api

   volumes:
     pgvector_data:
   ```

### ⏰ 13:00 - 15:30：README.md 文档与演示 GIF 录制包装
1. **在本地或者云服务器跑通整个系统**。使用屏幕录制软件（如 `Licecap` 或 `ScreenToGif`），录制一段 15 秒极具视觉震撼的 GIF 动图：
   * 画面 1：点击上方“K8s Pod 异常排查”卡片 -> 顺畅冒气泡和文字；
   * 画面 2：切换到“数据统计看板” -> ECharts 三个图表平滑弹窗出现。
2. **编写 GitHub 主页 README.md**：将你的系统架构图、GIF 演示动图、与 Day 9 计算出来的《全量评测表数据》放在项目根目录最显眼处。

### ⏰ 16:00 - 18:00：求职简历最强黄金位优化精修
打开你的简历，将本项目放置在【项目经历】的**正第一位置**，严格使用以下精心雕琢的实战话术进行包装：

```markdown
### 企业内部知识库智能运维助手（全栈 AI Agent 系统自研落地）
**主要技术栈**：Java 17、Spring Boot 3.5、LangChain4j、Vue3、Element Plus、PgVector、Redis 7、SSE 打字机流式交互
**项目演示地址**：http://your-server-ip  |  **开源仓库地址**：https://github.com/yourname/devops-ai-agent

**项目简述**：针对企业 IT/DevOps 运维团队每天需查阅海量分散手册、故障排查繁琐、且通用大模型极易答非所问与瞎编危险系统命令等痛点，自研了融合企业私有知识库检索与自动建单流转的 AI Agent 智能助手。

**核心亮点与难点突破**：
- **自研微内核 Agent 多级调度与隔离引擎**：基于 LangChain4j 整合 `PgVector` 构建父子切片（Parent-Child Retriever）与混合检索体系（向量+BM25）。严格落地 **“工具层白名单校验隔离 (Tool Whitelist Guard)”**，仅开放文献查阅和工单提交接口，从物理底层切断 LLM 越权调起宿主机 Shell 指令的隐患。
- **首创四层幻觉治理与自愈体系**：针对大模型事实捏造红线，落地了 **「SystemPrompt思维链约束 + JSON Schema参数强校验与自愈重试 + 低相似度 Score<0.73 阈值熔断兜底 + 文末强制出处溯源」** 四重严谨防护机制。构造 50 个正负复杂场景测试集进行自动化对撞压测，将**大模型事实幻觉率由 28% 猛烈压降至 4.2%以内**。
- **智能意图分级与大小模型双层调度 (降本70%)**：根据提问的复杂度极速智能分流，常识 Q&A 走高速主模型 (`Qwen-Turbo/DeepSeek-V3`)，长篇 K8s Stack Trace 报错归因才调起重度推理引擎 (`DeepSeek-R1`)。配合最前方的 **Redis 语义缓存 (Similarity > 0.95 极速 0 费复用)**，使系统平均响应压缩至 **1.8秒内，单用户日均大模型调用花费压降超 70% (控制在 0.05元以内)**。
- **全栈架构体验与 B 端看板开发**：后端采用 Spring Boot `SseEmitter` 封装流式数据流与中间状态气泡推送；前端通过 Vue3 + Element Plus 构建防抖渲染打字机窗口与快捷提问卡片，并通过 Apache ECharts 实现成本、命中率与路由调度大数据的动态监测可视化。
```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：云服务器内存不足导致前端编译报错 (OOM) 怎么办？**  
   在 2核4G 的便宜云服务器上，如果让 `docker compose build` 执行 `npm run build`，经常会出现 `JavaScript heap out of memory`。推荐在本地笔记本电脑先执行完 `npm run build` 产出 `dist/` 文件夹，然后再打包或者通过本地推送镜像，避免小内存云服务器直接挂掉。
2. **💡 建议二：面试时如果被问到“没上线怎么证明可用”的兜底**  
   就算你没有公网云服务器，也绝不要慌！你的笔记本就是你的服务器！你在面试时直接掏出笔记本或者展示屏幕，当着面试官的面敲一条 `docker compose -f docker-compose.prod.yml up -d`，30秒内所有容器完美就位，一秒打开浏览器秒开体验，这种对工程熟练度的掌控力比单放一个网页链接更令 HR 与技术主管折服！

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 执行 `docker compose -f docker-compose.prod.yml up -d -d`，4 个容器（PG、Redis、Backend、Frontend）全部显示绿色健康状态
- [ ] 浏览器直接访问 `http://localhost` 或云端 IP，顺利出现网页，所有接口、缓存、流式与图表 100% 丝滑无死角运行
- [ ] 简历已完成 PDF 导出，关键词如 `四层幻觉防护`、`降本70%`、`语义切片与混合检索` 均排布在最醒目的黄金位置，投递简历邀约率大幅暴增！
