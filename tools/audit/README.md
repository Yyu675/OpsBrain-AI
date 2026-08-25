# 代码模式扫描（audit）

这些脚本查的是**编译期无信号、运行期无报错、只在特定路径才暴露**的缺陷。
人工通读几乎不可能发现，但它们都有可被脚本识别的固定模式。

本会话靠这类扫描查出过：
- `acknowledgeTicket` 自调用导致两个 `@Transactional` 完全失效
- 付费 LLM 探针的开关配了、文档写了，但 `@ConditionalOnProperty`
  标在 `@RequestMapping` 方法上根本不生效（匿名可刷的成本失控口子）

## 用法

在仓库根目录执行：

```bash
python3 tools/audit/scan_self_invocation.py          # 自调用导致事务失效
python3 tools/audit/scan_annotations_and_catch.py    # 注解位置错误 / 静默吞异常
python3 tools/audit/scan_unread_config.py            # 配置项无人读取
python3 tools/audit/scan_service_write_coverage.py   # Service 写方法测试覆盖
```

## 说明

- 输出是**线索不是判决**。例如「空 catch」有 6 处命中，
  逐个看过都带解释性注释、属有意为之，不必修。
- `scan_unread_config.py` 对应的缺陷类型（配置项存在、文档齐全、
  却没有任何代码读取它）在本项目出现过两次，值得定期跑。
