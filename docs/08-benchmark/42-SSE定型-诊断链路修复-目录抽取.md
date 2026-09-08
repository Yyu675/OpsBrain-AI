# 42 · SSE 集成测试定型、诊断链路修复、目录逻辑抽取

> 本轮范围：`ChatStreamSseIntegrationTest` 9 例转绿、`mvnw`/`pom.xml` 诊断链路、`useDocOutline`
> **SSE 9 例全部通过**（后端 843 例全绿），前端 1349 → **1364**（69 文件）
> `KnowledgeDetail.vue` 1730 → **1689** 行

---

## 〇、工作区第 8 次被重置

本轮开始时本地退回基线，且远程已有两个我未参与的提交（`2b2b44a`、`64daa40`）。
按流程先确认差异方向：唯一含新增行的文件是 `TicketList.vue`，
但比对后确认那是**抽出 `useTicketBulkActions` 之前的旧版本**——
本地是陈旧回退而非未提交的新工作，`reset --hard` 安全。

---

## 一、建议 1：SSE 集成测试终于定型

这是挂了 **5 轮**的问题。本轮的价值不只在修好，更在于查清了**为什么前几轮都查不动**。

### 真正的根因（一行异常）

```
java.io.IOException: chunked transfer encoding, state: READING_LENGTH
```

SSE 走 `Transfer-Encoding: chunked`，规范要求以长度 0 的结束块收尾。
但 `SseEmitter.complete()` 之后容器直接关连接，现实中常常**收不到那个结束块**。
而 `BodyHandlers.ofString()` 是**严格实现**——在解析分块长度的状态下遇到 EOF
就抛异常，**连同已经收到的全部事件一起丢弃**。

这解释了此前所有令人困惑的现象：9 例全 ERROR、耗时都只有零点几秒、
看起来像 `@BeforeEach` 初始化失败。**实际上流已经跑完了、事件也都到了**，
只是在最后一个协议层动作上翻车。上一轮据「耗时特征」推断是建号/登录失败，方向完全错了。

### 改法

改用 `BodyHandlers.ofInputStream()` 手工读到 EOF，捕获 `IOException` 后
把**已读到的字节当作有效内容**返回。对 SSE 这是正确取舍：
事件是以 `\n\n` 分隔的自描述记录，少一个协议层结束块不影响任何一条已完整到达的事件；
为一个形式上的收尾块丢掉整条流才是真正的信息损失。

异常不被无声吞掉：超时与状态码校验保留，一个字节都没读到则由
`parseOrExplain` 给出带上下文的断言失败。

用 `record SimpleStringResponse` 包回 `HttpResponse<String>`，
**下游 9 个用例的断言代码一行未改**——它们直接转绿，这就是行为确实恢复的证据。

> 登录用的普通 JSON POST 保留 `ofString()`：它有正常的结束块，严格读取是对的。

---

## 二、诊断链路：三处静默失败叠加

这个 bug 之所以耗掉 5 轮，是因为**诊断链路本身坏了**，而且坏在三个各自独立的地方。
每一处都不报错，症状与根因隔得极远。

| # | 我的归因 | 实际 | 判定方式 |
|---|---|---|---|
| 1 | 受限网络取不到日志 | ❌ 错 | artifact/log 确实被墙，但不是详情缺失的原因 |
| 2 | GitHub 注解配额被概览行占满 | ⚠️ 部分对 | 上限 10 条属实（API 实测 `length=10`），但改完仍无详情 |
| 3 | `set -f` 关掉了通配符展开 | ⚠️ 属实但非致命 | `bash -c 'set -euf; grep -l x ./*.txt'` 无输出，去掉 `-f` 才有 |
| 4 | **`pom.xml` 的 `useFile=false`** | ✅ **真凶** | 探针读出「Tests行=183 但 surefire 报告 0 个」 |

第 4 条是**我自己两个修复互相打架**：

- `pom.xml` 配 `useFile=false` —— 让详情进控制台（为绕开 artifact 下不动）
- `mvnw` 加 surefire 重放 —— 去读 `target/surefire-reports/*.txt`

**后者依赖前者关掉的东西。** 合在一起就是死局，而且双方都「正常执行」：
Maven 跑完了，`mvnw` 的循环也执行了，只是 `find` 到 0 个文件。

### 破局靠的是探针，不是推断

前三轮都在推理，每次都言之成理却都错。真正定位是靠往 CI 里打**探针注解**，
把事实直接读出来：

```
[诊断] surefire 报告 0 个，其中含失败 0 个     ← 文件真的不存在
[诊断] Tests行=183 | 反应堆=1                  ← 但测试确实跑了 183 行
```

两个事实并排一放，矛盾立刻指向「有人让 surefire 不写文件」。

> **方法论**：诊断链路上每一环都要能独立回答「它拿到东西了吗」。
> 否则多个环节静默失败时，症状与根因可以隔得非常远。探针注解已在定型后移除。

另记两条实测现状：`PWD` 会被 GitHub 打成 `***`（误判为 secret），
路径类探针不可用；artifact 与 `gh run view --log` 均返回 EOF（防火墙）。

---

## 三、建议 3：抽出 `useDocOutline`

`buildToc` / `updateActiveToc` / `scrollToToc` / `decorateArticleContent`
是一组内聚单元——都必须在 `v-html` 渲染出真实 DOM **之后**执行，
且共享同一个正文容器 ref。`KnowledgeDetail.vue` 1730 → 1689 行。

**行为未变的证据**：既有 33 例测试（写操作 18 + 目录 15）**一行未改，直接全过**。

另外把「代码块语言标注 + 构建目录」合成 `refreshAfterRender` 一个入口——
只做其中一步的话，要么代码块没有语言角标，要么目录是空的，而两者都不报错。

---

## 四、建议 2：`TicketList.vue` 暂不拆

上一轮建议里第 2 条是「按视图拆子组件」。核查后**决定不做**：
剩余 2283 行里约 1500 行是模板，拆它需要动 DOM 结构与样式作用域，
而当前只有逻辑层测试、没有渲染快照——**在没有渲染测试兜底时动模板，
风险高于收益**。这与「先补测试再拆分」是同一条原则。

本轮把精力放在 SSE（挂了 5 轮的真问题）与 `useDocOutline`（有 33 例兜底）上。

---

## 五、本地验证

| 项 | 结果 |
|---|---|
| **CI 后端** | ✅ **843 例全绿（SSE 9 例首次全部通过）** |
| CI 前端 | ✅ |
| 前端 `vitest run` 全量 | ✅ **69 文件 / 1364 例** |
| 前端 `eslint` | ✅ 0 error |
| `tools/audit/run_audit.py` | ✅ 0 命中 |
| `bash -n mvnw` / `pom.xml` XML 校验 | ✅ |

---

## 六、下一轮建议

1. **`ChatStreamSseIntegrationTest` 现在是绿的**，可以考虑补充异常路径：
   客户端中途断开（`emitter.completeWithError`）、超长响应截断、
   以及并发多路流互不串号（`traceId` 隔离已有单例覆盖，但没有并发场景）。
2. **`TicketList.vue` 按视图拆分前，先补渲染冒烟测试**（列表态/卡片态各一），
   有了它再动模板才安全。
3. **`useDocOutline` 目前无独立单测**——它的行为由 `KnowledgeDetail.toc.test.ts`
   间接覆盖。若后续被别的页面复用，应补一组直接针对 composable 的用例。
4. **仍需用户操作**：`tools/audit/run_audit.py` 接入 CI（我无 workflows 权限）：
   ```yaml
         - name: 代码模式扫描
           run: python3 tools/audit/run_audit.py
   ```
