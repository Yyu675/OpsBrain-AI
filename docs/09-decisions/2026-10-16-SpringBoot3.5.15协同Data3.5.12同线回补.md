# 决策记录：Spring Boot 3.5.15 协同 Spring Data 3.5.12 同线回补与漏洞减量（批121）

- **日期**：2026-10-16
- **状态**：已实施并受验
- **活动分支**：`arena/5a93fadf-opsbrain-ai`
- **对应 PR**：[PR #24](https://github.com/Yyu675/OpsBrain-AI/pull/24)

---

## 一、背景与问题

在批 120 完成 Spring Framework 6.2.19 回补后，基线原生漏洞降至 16 条（11 HIGH + 5 CRITICAL）。经审计，其中 3 条 HIGH 集中在 Spring 运行时基础设施：
1. **`CVE-2026-40973`（GHSA-wwpq-f5c3-7hvx）**：Spring Boot `ApplicationTemp` 临时目录预测与权限边界缺陷，修复版本为 `3.5.14`（或 4.0.6）；
2. **`CVE-2026-41695`（GHSA-88fw-v6x4-3f58）**：Spring Data Commons `PersistentPropertyPathFactory` 未受限属性路径 DoS 缓存，修复版本为 `3.5.12`（或 4.0.6）；
3. **`CVE-2026-41716`（GHSA-9fw2-h3hf-293r）**：Spring Data 属性查询缓存堆耗尽，修复版本为 `3.5.12`。

同时，Jackson（8 条）处于 `2.19.4`，其维护修复线位于 `2.21.7/2.22.3` 或倒退 `2.18.11`，若此时 Boot 带来微小升级，需严格评估 Jackson 的版本对齐情况；Tomcat（3 条 CRITICAL）和 WebMVC（2 条 CRITICAL）则维持阻断并严格保留。

---

## 二、方案对比与决策

### 方案 A：Boot 维持 3.5.12，单点覆写 `spring-data-commons: 3.5.12`
- **利**：Boot 核心启动类不变；
- **弊**：破坏 Spring Data 全家族协同（jpa, jdbc, redis 仍为 3.5.10），且无法修复 Boot 本身的 `CVE-2026-40973`。

### 方案 B：升至 Boot 3.5.14，显式覆写 `spring-data-bom.version: 2025.0.12`
- **利**：停留在 3.5.14 最小修复点；
- **弊**：Boot 3.5.14 原生托管的是 `spring-data-bom: 2025.0.11`（Data 3.5.11，未修 Data 漏洞），人为拼接未经验证的 POM 依赖矩阵。

### 方案 C（采纳）：官方协同维护线对齐 —— 推进至 Boot 3.5.15
- **原因**：
  1. Boot 3.5.15 仍严格处于 3.5 维护线，**完全不跨 Boot 4.x / Spring 7**；
  2. 官方直接托管 `spring-data-bom: 2025.0.12`（Data 全族对齐 3.5.12），且天然原生适配 `spring-framework: 6.2.19`；
  3. 天然托管 Jackson BOM 2.21.4，顺带消解了 3 条已被 2.21.4 修复的 Jackson 旧 HIGH；
  4. 保证 Data 全模块（`commons`, `jpa`, `jdbc`, `redis`, `relational`, `keyvalue`）原子对齐，不拆散族群。

---

## 三、真实安全回归与工程验证

1. **真实构件官方 Central 供应**：
   - 26 个候选模块（Boot 19 个 + Data 7 个）全部通过 Central HTTP 200 验证，且合法包含 Starter 专用元数据。
2. **安全行为回归测试 (`SpringBootDataSecurityCompatibilityTest`)**：
   - `applicationTempCreatesSecureOwnedDirectory`：断言新建临时目录具备仅属主读写执行权限（0700/Posix）；
   - `applicationTempRejectsExistingDirectoryWithPermissivePermissions`：断言已存在全权限目录抛出 `IllegalStateException`；
   - `applicationTempRejectsSymlinkedDirectoryLocation`：断言软链接目录抛出明确非目录异常；
   - `persistentPropertyPathFactoryUsesConcurrentLruCache`：反射断言 `propertyPaths` 缓存使用 `ConcurrentLruCache` 且 `capacity=512`，彻底杜绝无界堆耗尽；
   - 包含健康映射对照与泛型类型推断保护。
3. **测试套件运行实测**：
   - 后端测试用例数提升至 **2087（0 FAIL, 0 ERROR, 2 既有 SKIP）**；
   - 前端 1863 测试、浏览器 60 测试、真 SQL/HTTP/CLI 取证通过；
   - 运行时补丁验证通过：`verified_variants_count` 提升至 92（包含 Boot 4 个生产 JAR 与 Data 6 个生产 JAR），Boot/Data 正式纳入 `zero_hc_groups`；
   - 原生漏洞从 **16 条显著降至 10 条（5 HIGH + 5 CRITICAL）**：
     - 正向关闭 Boot 1 条 + Data 2 条 + Jackson 3 条旧漏洞；
     - 严格保留 Tomcat 3 条 CRITICAL、WebMVC 2 条 CRITICAL 及 Jackson 剩余 5 条 HIGH；
     - 门禁退出码维持 1，`full_security_passed=false`，灰度不扩大。
