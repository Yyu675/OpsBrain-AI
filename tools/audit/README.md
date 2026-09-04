# 代码模式扫描（audit）

查的是**编译期无信号、运行期无报错、只在特定路径才暴露**的缺陷。
人工通读几乎不可能发现，但它们都有可被脚本识别的固定模式。

本会话靠这类扫描查出过两个真实缺陷：

- `acknowledgeTicket` 自调用，导致两个 `@Transactional` 完全失效
  （多步写入退化成各自独立的自动提交，中途失败留下半截状态）
- 付费 LLM 探针的开关配了、文档也写了，但 `@ConditionalOnProperty`
  标在 `@RequestMapping` 方法上根本不生效——端点一直开放，
  且它在鉴权白名单内，构成匿名可刷的成本失控口子

## 用法

```bash
python3 tools/audit/run_audit.py            # 检查，有新增问题则退出码 1
python3 tools/audit/run_audit.py --report   # 只输出报告，恒退出 0
python3 tools/audit/run_audit.py --update   # 把当前结果写回基线（需人工复核理由后再提交）
```

零依赖，只用标准库，不需要 JDK 或 Maven。

## 四类扫描

| 扫描 | 查什么 | 为什么会漏 |
|---|---|---|
| 自调用事务失效 | 非事务方法内 `this.xxx()` 调用 `@Transactional` 方法 | Spring 事务靠 AOP 代理，自调用不经过代理 |
| 条件注解位置错误 | `@ConditionalOnXxx` 标在请求映射方法上 | 它是 Bean 注册阶段的条件，对方法无效 |
| `@Transactional` 可见性 | 标在 private/protected 方法上 | 同样因代理机制不生效 |
| 配置项无人读取 | `application.yml` 里的 `devops.*` 无代码引用 | 配置存在 + 文档齐全，唯独没人读 |

## 基线机制

`baseline.json` 记录**已人工审阅并接受**的命中，扫描只对新增条目报错。

这一步不是为了放水。扫描输出是**线索不是判决**——
比如「空 catch」在本项目有 6 处，逐个看过都带解释性注释、属有意为之。
若不区分「已接受」与「新引入」，这个检查会从第一天起就是红的，
而**长期红着的检查等于没有检查**：大家会习惯性忽略它，
真正的新问题也就跟着被忽略。

（同样的取舍见 `.github/workflows/ci.yml` 里 knip 那段注释。）

## 两个实现细节，都是踩过坑才加的

**1. 必须剥离注释再扫描**

本项目的注释里大量讨论「不该怎么写」。例如 `HealthCheckController`
的 Javadoc 专门解释了「`@ConditionalOnProperty` 标在 `@GetMapping` 上不生效」。

不剥离的话，**修复缺陷时写下的说明反而会触发对该缺陷的告警**——
越是把教训写清楚的地方误报越多。首版就是这么误报了 2 处。

**2. 注解匹配必须用正则，不能用字面量**

注解可能写成全限定名
（`@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`）。
首版用字面量 `'@ConditionalOnProperty'`，做回归验证时注入全限定名版本
**直接漏报**。而漏报比误报危险得多：它让人误以为这一类问题已经不存在。

## 回归验证

改动扫描逻辑后，应当验证它**真的能抓到缺陷**，而不只是「什么都查不出」。
做法是把已修复的两个缺陷人为注入回去，确认扫描报红，再还原：

```bash
# 1. 去掉 acknowledgeTicket 的 @Transactional
# 2. 给 checkAiModel 加回 @ConditionalOnProperty
python3 tools/audit/run_audit.py    # 应报 3 处新增、退出码 1
# 3. 还原代码，再跑一次确认回到 0
```

## 接入 CI

在 `.github/workflows/ci.yml` 的 backend job 里加一步即可（无需额外依赖）：

```yaml
      - name: 代码模式扫描
        run: python3 tools/audit/run_audit.py
```

建议**直接设为阻断**：当前基线为空、四类扫描全部无命中，
从干净状态开始，任何新增都是真的新问题。
