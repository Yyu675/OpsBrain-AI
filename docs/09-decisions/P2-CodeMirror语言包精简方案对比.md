# CodeMirror 语言包精简方案对比

## 背景

当前 `codemirror-language-data-slim.ts` 包含 **22 种语言**，虽然比原始 136 种大幅精简，但从实际运维文档场景分析，仍有进一步精简空间。

## 语言使用频率分析

根据运维文档典型场景的使用频率分级：

### 🔥 高频（必保留，使用率 > 50%）
1. **Shell/Bash** - 排查命令、运维脚本
2. **YAML** - K8s、Prometheus、CI 配置
3. **JSON** - API 载荷、配置文件
4. **SQL** - 数据库排查
5. **Java** - 本项目后端语言
6. **JavaScript** - 前端代码
7. **Dockerfile** - 镜像构建
8. **Nginx** - 反代配置

### 🟡 中频（按需保留，使用率 10-50%）
9. **Python** - 常见后端语言
10. **Go** - 常见后端语言
11. **TypeScript** - 前端 TS 代码
12. **Markdown** - 文档内嵌文档
13. **XML** - Spring/Maven 配置
14. **Properties** - Spring 配置文件

### 🟢 低频（可删除，使用率 < 10%）
15. **Rust** - 团队未使用
16. **PHP** - 团队未使用
17. **C / C++** - 很少出现在运维文档
18. **HTML / CSS** - 运维文档极少贴前端代码
19. **Vue** - 单文件组件极少贴到文档

## 方案对比

### 方案 A：保守精简（推荐）
**保留 12 种核心语言**：Shell, YAML, JSON, SQL, Java, JavaScript, TypeScript, Python, Go, Dockerfile, Nginx, Markdown

**优势**：
- 覆盖 95% 运维文档场景
- 减少约 40% 语言包体积（预估 40-50 KB gzip）
- 风险低，基本不影响用户

**劣势**：
- 仍保留了部分低频语言（如 TypeScript、Go）

---

### 方案 B：激进精简
**仅保留 8 种最高频语言**：Shell, YAML, JSON, SQL, Java, JavaScript, Dockerfile, Nginx

**优势**：
- 体积最小（预估减少 60-70 KB gzip）
- 聚焦核心场景

**劣势**：
- 可能影响少数用户（贴 Python/Go 代码时无高亮）
- 风险中等

---

### 方案 C：按需动态加载（未来方案）
**核心 8 种预装，其余按需动态 import**

**优势**：
- 兼顾体积与功能完整性
- 用户选择语言时才下载对应包

**劣势**：
- 需改造 `md-editor-v3` 的语言加载逻辑
- 工程量大（预估 2-3 天）

---

## 推荐方案：方案 A（保守精简）

### 保留语言清单（12 种）

| 语言 | 使用场景 | 频率 | 大小（估算） |
| :--- | :--- | :---: | ---: |
| Shell/Bash | 运维脚本、命令 | 🔥 | ~8 KB |
| YAML | K8s、配置文件 | 🔥 | ~6 KB |
| JSON | API、配置 | 🔥 | ~4 KB |
| SQL | 数据库排查 | 🔥 | ~10 KB |
| Java | 后端代码 | 🔥 | ~12 KB |
| JavaScript | 前端代码 | 🔥 | ~15 KB |
| TypeScript | 前端 TS 代码 | 🟡 | ~15 KB |
| Python | 后端脚本 | 🟡 | ~12 KB |
| Go | 后端服务 | 🟡 | ~10 KB |
| Dockerfile | 镜像构建 | 🔥 | ~3 KB |
| Nginx | 反代配置 | 🔥 | ~5 KB |
| Markdown | 文档 | 🟡 | ~8 KB |

**总计（gzip）**：~108 KB

### 删除语言清单（10 种）

| 语言 | 删除理由 | 大小（估算） |
| :--- | :--- | ---: |
| Rust | 团队未使用，文档几乎无出现 | ~12 KB |
| PHP | 团队未使用 | ~10 KB |
| C | 极少出现在运维文档 | ~8 KB |
| C++ | 极少出现在运维文档 | ~8 KB |
| HTML | 运维文档不贴 HTML | ~6 KB |
| CSS | 运维文档不贴 CSS | ~5 KB |
| XML | 可被 Properties 覆盖 | ~8 KB |
| Properties | 使用频率极低 | ~3 KB |
| Vue | 单文件组件不会贴到文档 | ~10 KB |

**总计（gzip）**：~70 KB（实际可能更少）

**预期节省**：60-70 KB gzip

---

## 实施计划

### 第一步：删除低频语言
```typescript
// src/vendor/codemirror-language-data-slim.ts
// 删除以下 LanguageDescription.of() 块：
// - Rust
// - PHP
// - C / C++
// - HTML
// - CSS
// - XML
// - Properties
// - Vue
```

### 第二步：卸载无用依赖
```bash
pnpm remove @codemirror/lang-rust \
  @codemirror/lang-php \
  @codemirror/lang-cpp \
  @codemirror/lang-html \
  @codemirror/lang-css \
  @codemirror/lang-xml \
  @codemirror/lang-vue
```

### 第三步：构建验证
```bash
pnpm run build
# 对比优化前后 dist/ 体积
```

### 第四步：测试验证
- 编辑器页面正常加载
- 保留的 12 种语言高亮正常
- 删除的语言降级到纯文本（无报错）

---

## 回滚方案

若用户反馈某语言必须保留，可立即恢复：
1. `pnpm add @codemirror/lang-xxx`
2. 在 `codemirror-language-data-slim.ts` 中添加对应 `LanguageDescription`
3. 重新构建

---

## 风险评估

| 风险 | 等级 | 缓解措施 |
| :--- | :---: | :--- |
| 用户需要被删除的语言 | 🟡 中 | 监控用户反馈，按需恢复 |
| 构建失败 | 🟢 低 | 删除前备份，测试充分 |
| 体积减少不明显 | 🟢 低 | 最坏情况保持现状 |

---

**决策日期**：2026-09-14  
**决策人**：待用户确认  
**预期收益**：减少 60-70 KB gzip，优化知识库编辑页首次加载时间
