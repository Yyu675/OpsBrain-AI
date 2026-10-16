# 阿里云 SLB 负载均衡故障处理手册

## SLB 健康检查失败排查

### 问题描述
阿里云 SLB（Server Load Balancer）后端服务器健康检查失败，导致流量无法转发。

### 常见原因
1. **后端服务未启动**：应用进程未运行或监听端口错误
2. **健康检查配置错误**：检查路径、端口、协议不匹配
3. **网络安全组阻断**：ECS 安全组未放行 SLB 健康检查 IP
4. **应用响应超时**：健康检查超时时间设置过短
5. **健康检查阈值过严**：连续失败阈值设置为 1 次

### 排查步骤

#### 1. 查看 SLB 控制台健康检查状态
登录阿里云控制台 → 负载均衡 SLB → 实例管理 → 监听 → 后端服务器：
- 查看服务器状态（正常/异常）
- 查看异常原因提示

#### 2. 验证后端服务是否正常
SSH 登录 ECS 服务器：
```bash
# 检查应用进程
ps aux | grep java  # 或 nginx、node 等

# 检查监听端口
netstat -tuln | grep 8080

# 本地测试健康检查接口
curl -I http://127.0.0.1:8080/health
```

#### 3. 检查安全组规则
阿里云控制台 → ECS → 网络与安全 → 安全组：
- 确认入方向规则是否放行健康检查 IP 段
- SLB 健康检查源 IP 段：**100.64.0.0/10**（必须放行）

#### 4. 查看 SLB 健康检查配置
控制台 → 监听配置 → 健康检查设置：
```
健康检查端口：8080
健康检查路径：/health
响应超时时间：5 秒（建议 ≥5s）
检查间隔：2 秒
不健康阈值：3 次（建议 ≥3）
健康阈值：3 次
```

### 解决方案

#### 方案 1：放行健康检查 IP 段（最常见）
在 ECS 安全组中添加入方向规则：
```
协议类型：自定义 TCP
端口范围：8080/8080（应用端口）
授权对象：100.64.0.0/10
描述：SLB 健康检查
```

#### 方案 2：修正健康检查路径
确保应用有健康检查端点：
```java
// Spring Boot 示例
@RestController
public class HealthController {
    @GetMapping("/health")
    public String health() {
        return "OK";  // 返回 2xx 状态码即可
    }
}
```

SLB 配置：
```
健康检查方式：HTTP
健康检查路径：/health
正常状态码：http_2xx
```

#### 方案 3：延长超时时间和增加阈值
调整健康检查参数（应对应用启动慢）：
```
响应超时时间：10 秒（增加到 10s）
不健康阈值：5 次（增加容错）
检查间隔：3 秒
```

#### 方案 4：检查应用日志
如果应用偶尔超时，检查是否有性能问题：
```bash
# 查看应用日志
tail -f /var/log/app.log

# 检查系统资源
top
df -h
```

### 验证方法
```bash
# 在 ECS 上模拟 SLB 健康检查
curl -v http://127.0.0.1:8080/health

# 使用 telnet 测试端口连通性
telnet 127.0.0.1 8080
```

### 参考配置
#### 标准 HTTP 健康检查配置
```
健康检查方式：HTTP
健康检查端口：后端服务器端口（如 8080）
健康检查路径：/health
正常状态码：http_2xx,http_3xx
响应超时时间：5 秒
检查间隔：2 秒
不健康阈值：3 次
健康阈值：3 次
```

#### 标准 TCP 健康检查配置
```
健康检查方式：TCP
健康检查端口：后端服务器端口
响应超时时间：5 秒
检查间隔：2 秒
不健康阈值：3 次
健康阈值：3 次
```

---

## SLB 502/504 错误排查

### 问题描述
客户端访问 SLB 时返回 502 Bad Gateway 或 504 Gateway Timeout。

### 常见原因
1. **后端服务全部异常**：所有 ECS 健康检查失败
2. **后端服务响应超时**：处理时间超过 SLB 超时设置（默认 60s）
3. **连接数耗尽**：后端服务 max connections 达到上限
4. **后端服务返回异常**：应用代码抛出 500 错误
5. **SLB 到后端网络不通**：安全组、网络 ACL 阻断

### 排查步骤

#### 1. 检查后端服务健康状态
SLB 控制台 → 监听 → 后端服务器：
- 如果全部异常，按"健康检查失败"排查
- 如果部分异常，隔离故障服务器

#### 2. 查看 SLB 访问日志
控制台 → 日志管理 → 访问日志：
```
查看关键字段：
- status：502/504
- upstream_status：后端实际返回的状态码
- upstream_response_time：后端响应时间
```

#### 3. 检查后端服务日志
```bash
# 查看应用错误日志
tail -f /var/log/app/error.log

# 查看 Nginx 错误日志（如果后端是 Nginx）
tail -f /var/nginx/error.log
```

#### 4. 测试后端服务直连
```bash
# 绕过 SLB 直接访问 ECS
curl -v http://<ecs-ip>:8080/api/test

# 压测后端服务（模拟高并发）
ab -n 1000 -c 50 http://<ecs-ip>:8080/api/test
```

### 解决方案

#### 方案 1：增加 SLB 超时时间
控制台 → 监听配置 → 高级配置：
```
连接超时时间：60 秒（默认）
请求超时时间：60 秒（默认）

# 如果后端处理耗时长（如报表生成），增加到 180 秒
请求超时时间：180 秒
```

#### 方案 2：优化后端应用性能
```java
// 异步处理耗时操作
@Async
public CompletableFuture<String> slowTask() {
    // 耗时操作
    return CompletableFuture.completedFuture("done");
}

// 增加数据库连接池
spring.datasource.hikari.maximum-pool-size=50
```

#### 方案 3：增加后端服务器数量
控制台 → 后端服务器 → 添加后端服务器：
- 水平扩展 ECS 实例，分担流量
- 配置权重分配策略

#### 方案 4：排查后端应用错误
```bash
# 查看 JVM 堆内存（Java 应用）
jstat -gcutil <pid> 1000

# 查看线程堆栈
jstack <pid> > thread_dump.txt

# 查看连接数
netstat -an | grep ESTABLISHED | wc -l
```

#### 方案 5：开启 SLB 会话保持
如果应用依赖 Session：
```
会话保持：开启
会话保持超时时间：3600 秒
会话保持方式：植入 Cookie
```

### 错误码对照表
| 错误码 | 原因 | 排查重点 |
|-------|------|---------|
| 502 | 后端服务返回无效响应 | 检查应用是否抛出异常 |
| 502 | 后端服务全部不可用 | 检查健康检查状态 |
| 504 | 后端服务响应超时 | 检查应用处理时间、数据库慢查询 |
| 504 | 后端服务无响应 | 检查应用是否假死、线程池耗尽 |

---

## SLB 跨域 CORS 配置

### 问题描述
前端通过 SLB 访问后端 API 时，浏览器报跨域错误（CORS policy blocked）。

### 解决方案

#### 方案 1：后端应用配置 CORS（推荐）
Spring Boot 全局配置：
```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("https://example.com")  // 前端域名
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

#### 方案 2：Nginx 反向代理配置 CORS
如果后端是 Nginx：
```nginx
location /api/ {
    add_header 'Access-Control-Allow-Origin' 'https://example.com' always;
    add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, DELETE, OPTIONS' always;
    add_header 'Access-Control-Allow-Headers' 'Authorization, Content-Type' always;
    add_header 'Access-Control-Allow-Credentials' 'true' always;
    
    if ($request_method = 'OPTIONS') {
        return 204;
    }
    
    proxy_pass http://backend;
}
```

#### 方案 3：SLB 转发规则（不推荐）
阿里云 SLB 7 层监听（HTTPS）支持自定义响应头，但配置复杂，优先在应用层处理。

### 验证方法
```bash
# 测试 CORS 预检请求
curl -X OPTIONS https://api.example.com/test \
  -H "Origin: https://example.com" \
  -H "Access-Control-Request-Method: POST" \
  -v
```

预期响应头：
```
Access-Control-Allow-Origin: https://example.com
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
```

---

## SLB HTTPS 证书配置

### 问题描述
配置 HTTPS 监听，需要上传 SSL 证书。

### 证书格式要求
- 证书格式：PEM
- 证书内容：包含完整证书链（服务器证书 + 中间证书）
- 私钥：RSA 私钥（需去除密码保护）

### 配置步骤

#### 1. 上传证书到 SSL 证书服务
阿里云控制台 → SSL 证书 → 上传证书：
```
证书名称：example.com
证书内容：粘贴 .crt 文件内容
私钥内容：粘贴 .key 文件内容
```

#### 2. 创建 HTTPS 监听
SLB 控制台 → 监听 → 添加监听：
```
前端协议/端口：HTTPS / 443
后端协议/端口：HTTP / 8080（后端不需要 HTTPS）
SSL 证书：选择已上传的证书
TLS 安全策略：tls_cipher_policy_1_2（推荐）
```

#### 3. 配置强制跳转 HTTPS（可选）
创建 HTTP 监听（端口 80）：
```
监听规则：添加转发策略
域名：example.com
URL：/*
动作：重定向到 HTTPS
```

### 证书续期注意事项
- 证书到期前 30 天续期
- 续期后重新上传到 SLB
- 支持无感知替换（不影响业务）

### 验证方法
```bash
# 测试 HTTPS 访问
curl -v https://example.com

# 检查证书有效期
openssl s_client -connect example.com:443 -servername example.com 2>/dev/null | openssl x509 -noout -dates
```

---

## SLB 流量分发策略

### 加权轮询（WRR）
默认算法，根据后端服务器权重分配流量：
```
ECS-1：权重 100 → 50% 流量
ECS-2：权重 100 → 50% 流量
```

适用场景：后端服务器配置相同。

### 加权最小连接数（WLC）
优先转发到连接数最少的服务器：
```
ECS-1：当前 50 个连接 → 不转发
ECS-2：当前 10 个连接 → 转发到此
```

适用场景：长连接场景（WebSocket、数据库连接）。

### 一致性哈希（CH）
根据源 IP 或 URI 哈希，同一客户端总是转发到同一服务器：
```
客户端 IP：1.2.3.4 → 哈希 → 固定转发到 ECS-1
```

适用场景：需要保持会话亲和性（但推荐用 Redis 共享 Session）。

### 配置方法
控制台 → 监听配置 → 调度算法：
- 选择算法类型
- 配置后端服务器权重（0-100）

---

📚 **参考来源**：阿里云 SLB 官方文档、运维实战经验总结
