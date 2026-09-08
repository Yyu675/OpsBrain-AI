# 30 · SSE 集成测试受阻（如实记录）+ KnowledgeEditor 拆分启动

> 本轮两件事：一件**没做成**并说明为什么停，一件做成了。
> 先说没做成的那件。

---

## 一、SSE 事件序列集成测试：写完了，但跑不通，已标 `@Disabled`

### 目标与思路

这是前几轮明确留下的唯一缺口。`DevOpsChatControllerSseTest` 用
`@WebMvcTest` 覆盖了同步拒绝路径，但它 mock 掉了 `DevOpsAgentService`，
**验证不了事件序列**——而 SSE 的价值恰恰在序列。

思路是可行的：`AI_MODE=MOCK` 时 `MockStreamingChatModel` 逐字回调，
不调真实 API、输出可预期，整条链路能在 `@SpringBootTest` 里跑通。

写了 9 例，覆盖 `start → token* → complete` 的顺序、
start/complete 的 traceId 一致性、`citations` 必须是数组而非 null 等。

### 卡在哪

9 例**全部失败，症状统一**：`MockMvc` 拿到的响应体是空的。
「全军覆没 + 症状一致」基本可以断定是机制问题而非断言错。

排查了两轮：

| 假设 | 处置 | 结果 |
|---|---|---|
| `getAsyncResult()` 不等流写完 | 改为轮询 `isAsyncStarted()` | ❌ 无效，耗时仍是个位数毫秒 |
| surefire 详情只进文件 | 加 `useFile=false` | ❌ annotations API 仍只回摘要行 |

### 为什么停下来

**没有任何通道能拿到这 9 条断言的实际值**：

- CI 原始日志走 Azure blob —— 沙箱防火墙挡住
- artifact 下载 —— 返回 0 字节
- annotations API —— 只回传 `<<< FAILURE!` 摘要行
- 本地跑 Maven —— 所有镜像不可达

也就是说，继续推进只能**靠盲猜改代码**。而盲猜的风险不是浪费时间，
是**把本来正确的产品实现改坏**——本会话已多次遇到「测试报错但错在测试」
（`anyString()` 不匹配 null、`@TestPropertySource` 在 `@Nested` 上不生效、
`overdue=yes` 是合法布尔值）。如果这次顺着「产品没发事件」的表象去改
`DevOpsChatController`，破坏的是一个已经在跑的正确实现。

已为此消耗 5 轮 CI。**停在这里是更负责任的选择。**

### 为什么是 `@Disabled` 而不是删除

那 9 条断言本身是对的。删掉等于把已经想清楚的契约又丢了。
留着并在类注释写明**重启条件**：

1. 能在本地跑通 Maven —— 一次运行就能看到完整堆栈
2. CI 能取到 `target/surefire-reports/*.txt`
3. **改用 `@SpringBootTest(RANDOM_PORT)` + `WebTestClient` 真正走网络消费流** ——
   绕开 `MockMvc` 对 `SseEmitter` 的模拟差异，这是最可能直接解决的路径

> 判断依据与 audit 基线机制是同一条：
> **长期红着的 CI 等于没有 CI。**

### 顺带留下的两个改进（与本次成败无关）

- `pom.xml` 加 surefire `useFile=false` + `trimStackTrace=false`
- `mvnw` 增加 surefire 报告重放

它们对**将来任何一次测试失败**的排查都有用。这次没能解决问题，
但下次在正常网络下会直接生效。

---

## 二、KnowledgeEditor.vue 拆分启动（做成了）

按第 22 轮教训：**先补测试再拆**。

抽出 `utils/editorContent.ts`（177 行，6 个纯函数 + 2 个类型），
配 **32 例单测**。组件从 2013 行降到 **1962 行**。

### 为什么抽这几个

不只是「行数多」，而是**它们决定内容会不会丢**，且在组件内无法单测
（挂载编辑器需要 Quill、Turndown、异步组件与路由，跟这几十行逻辑毫无关系）。

| 函数 | 写错的后果 |
|---|---|
| `hasMeaningfulContent` | 用户写了一屏表格，却被告知「请先输入文档内容」 |
| `toVisualContent` | 白名单漏一类标签 → 粘贴来的表格塌成一行纯文本 |
| `normalizeDraftState` | 不兼容旧格式 → 升级发版让所有人的在编草稿变 undefined |
| `toPlainText` | 用正则剥标签 → 属性里的 `>` 剥错，摘要混进半截标签 |
| `extractToc` | HTML 用 elementIndex、Markdown 用 lineIndex，混用会跳错位置 |

几条值得单独说的测试：

- **纯表格 / 纯图片 / 纯代码块 / 纯分隔线都算「有内容」**——
  一篇全是架构图的文档没有文本，只看 `textContent` 会判成空
- **`<p><br></p>`、零宽字符不算内容**——富文本编辑器在看起来空白时
  仍会留下这些，直接 `trim()` 判空会让用户提交空文档
- **净化要剥掉 script/onclick/javascript:/iframe，但保留 table/th/td 与
  `data-language`**——编辑者往往是管理员，一次 XSS 拿到的是最高权限会话；
  而表格和代码块是知识库文档的主力结构
- **`baseVersion=0` 的新格式不能被误判为旧格式**——靠 `'form' in raw`
  判断而非真值，否则 `baseVersion=0` 的草稿会被重置

### 结果

```
KnowledgeEditor.vue   2013 → 1962 行
utils/editorContent.ts       +177 行（含大量决策说明）
前端测试             1109 → 1141 例
vue-tsc / eslint     通过
前端 CI              绿
```

拆分只走了第一步。但**核心逻辑现在有 32 例测试托底**，
后续动组件结构（B2/B3 表单、右侧面板）时才有安全网——
这正是第 22 轮拆 TicketDetail 时被 74 例测试救回来的那个经验。

---

## 三、当前状态

```
后端   58 个测试文件 / 723 例（其中 SSE 9 例 @Disabled）
  ├─ Controller 契约   18/18
  └─ E2E 集成          3 个文件（告警链路、鉴权链路、SSE-暂缓）
前端   57 个测试文件 / 1141 例
门禁   tools/audit 四类扫描，0 命中
CI     绿
```

## 四、下一步

| 优先级 | 事项 |
|---|---|
| 高 | **SSE 换 `WebTestClient` 方案重试** —— 前提是能拿到失败详情，否则同样是盲猜 |
| 中 | 继续拆 `KnowledgeEditor.vue`：右侧设置面板与模板选择器是下两个有明确边界的单元 |
| 中 | `AgentStateManager`（5/6 写方法未覆盖） |
| 低 | `TicketList.vue` 2679 行，同样需先补测试 |

> 另：`tools/audit/run_audit.py` 接入 CI 仍需你操作（我无 workflows 写权限），
> 片段见 `tools/audit/README.md`。
