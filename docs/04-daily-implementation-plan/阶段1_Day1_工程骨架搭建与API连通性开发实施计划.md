# 📅 阶段1_Day1_工程骨架搭建与API连通性开发实施计划

> **阶段所属**：阶段一：基础环境与数据准备  
> **当日核心目标**：从零搭建 Spring Boot 3.5 + JDK 17 全栈后端工程骨架，引入 LangChain4j 依赖，配置并一键启动 Docker 本地容器化基础服务（PostgreSQL/PgVector + Redis 7.0），成功打通 DeepSeek/阿里云百炼 API。  
> **预计耗时**：5 - 6 小时  
> **完成产出**：能够通过 Postman 或 `curl` 发送一个 JSON 请求，调用底层 LangChain4j API 成功拿到大模型的标准问答回复。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:00：Spring Boot 3 + JDK 17 初始化与 Pom 依赖配置
1. **创建 Maven 工程**：使用 Spring Initializr（直接用 IntelliJ IDEA 或 start.spring.io），选择 Java 17，Spring Boot 3.5.6。
2. **精简并锁定关键 `pom.xml` 依赖**：
   ```xml
   <!-- 统一由 langchain4j-bom 管理 LangChain4j 各依赖版本 (BOM 版本 1.1.0-beta7，核心逻辑为 1.1.0 GA) -->
   <dependencyManagement>
       <dependencies>
           <dependency>
               <groupId>dev.langchain4j</groupId>
               <artifactId>langchain4j-bom</artifactId>
               <version>1.1.0-beta7</version>
               <type>pom</type>
               <scope>import</scope>
           </dependency>
       </dependencies>
   </dependencyManagement>

   <dependencies>
       <!-- Spring Boot Web & Validation -->
       <dependency>
           <groupId>org.springframework.boot</groupId>
           <artifactId>spring-boot-starter-web</artifactId>
       </dependency>
       <dependency>
           <groupId>org.springframework.boot</groupId>
           <artifactId>spring-boot-starter-validation</artifactId>
       </dependency>

       <!-- LangChain4j 核心依赖与 OpenAI 兼容客户端 (对接 DeepSeek/百炼)，版本由 langchain4j-bom 统一管理 -->
       <dependency>
           <groupId>dev.langchain4j</groupId>
           <artifactId>langchain4j-spring-boot-starter</artifactId>
       </dependency>
       <dependency>
           <groupId>dev.langchain4j</groupId>
           <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
       </dependency>
       
       <!-- LangChain4j PgVector 向量数据库插件，版本由 langchain4j-bom 统一管理 -->
       <dependency>
           <groupId>dev.langchain4j</groupId>
           <artifactId>langchain4j-pgvector</artifactId>
       </dependency>

       <!-- Redis 缓存支持 -->
       <dependency>
           <groupId>org.springframework.boot</groupId>
           <artifactId>spring-boot-starter-data-redis</artifactId>
       </dependency>

       <dependency>
           <groupId>org.projectlombok</groupId>
           <artifactId>lombok</artifactId>
           <optional>true</optional>
       </dependency>
   </dependencies>
   ```

### ⏰ 11:00 - 12:30：Docker 一键本地研发容器拉起（防本地 DLL 踩坑）
1. 在工程根目录下创建 `docker-compose.dev.yml`（直接复用白皮书中的配置）：
   * 启动 `ankane/pgvector:v0.5.1` (端口 5432)
   * 启动 `redis:7.0-alpine` (端口 6379)
2. 在 terminal 中执行：`docker compose -f docker-compose.dev.yml up -d`
3. 检查容器状态：`docker ps` 确保 `devops-pgvector` 和 `devops-redis` 均为 `Up` 状态。

### ⏰ 14:00 - 16:30：大模型 API 连接配置与 Service 测试编写
1. **配置 `application.yml`**：
   ```yaml
   spring:
     application:
       name: devops-ai-agent
     datasource:
       url: jdbc:postgresql://localhost:5432/devops_knowledge_db
       username: devops
       password: devops_password
     data:
       redis:
         host: localhost
         port: 6379
         password: devops_redis_pwd

   # LangChain4j 自定义大模型连接参数配置
   ai:
     open-ai:
       # 如果使用 DeepSeek API (兼容 OpenAI 规范)
       base-url: https://api.deepseek.com/v1
       api-key: ${DEEPSEEK_API_KEY:your-key-here}
       model-name: deepseek-chat
       temperature: 0.1 # 运维助手尽量保持客观严谨，将温度调低
   ```
2. **编写测试大模型连接的 `AiModelConfig.java` 与简单 Controller**：
   ```java
   package com.devops.agent.config;

   import dev.langchain4j.model.chat.ChatModel;
   import dev.langchain4j.model.openai.OpenAiChatModel;
   import org.springframework.beans.factory.annotation.Value;
   import org.springframework.context.annotation.Bean;
   import org.springframework.context.annotation.Configuration;

   @Configuration
   public class AiModelConfig {
       @Value("${ai.open-ai.base-url}")
       private String baseUrl;
       @Value("${ai.open-ai.api-key}")
       private String apiKey;
       @Value("${ai.open-ai.model-name}")
       private String modelName;

       @Bean
       public ChatModel chatLanguageModel() {
           return OpenAiChatModel.builder()
                   .baseUrl(baseUrl)
                   .apiKey(apiKey)
                   .modelName(modelName)
                   .temperature(0.1)
                   .logRequests(true)
                   .logResponses(true)
                   .build();
       }
   }
   ```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：绝对不要试图在 Windows/Mac 上本地原生编译安装 FAISS**  
   根据前置白皮书铁律，Java 生态下调用底层 C++ 向量库易引发 JNI 动态库找不到异常。Day 1 必须坚定使用 `Docker + pgvector`，把关系数据表和切片向量统一装在 PostgreSQL 内。
2. **💡 建议二：API Key 环境变量隔离防泄漏**  
   千万别把 API Key 真实字符串死硬编码提交到 Git 仓库，很容易被封号。使用 `${DEEPSEEK_API_KEY}` 环境变量注入，本地在 IntelliJ IDEA 的 `Run -> Edit Configurations -> Environment variables` 中配置即可。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 工程能够使用 `mvn clean compile` 0 警告成功编译
- [ ] Docker 容器 `devops-pgvector` 与 `devops-redis` 稳定运行中
- [ ] 编写并执行一个 JUnit 测试单元或 Postman 请求 `chatLanguageModel.generate("你好，请回答1+1等于几")`，控制台和接口都能于 `1.5秒` 内收到大模型返回的内容 `2`
