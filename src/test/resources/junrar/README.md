# Junrar健康测试夹具（不含恶意死循环归档）

来自官方仓库`junrar/junrar`，固定提交`9fd65e3449274100f23dd6d9c592e7a12b788d36`（v7.6.1），目录`src/test/resources/com/github/junrar/`。经GitHub contents API取实际字节并复核Git blob SHA；只供测试，应用资源不包含这些文件。保留该提交的`LICENSE`于`LICENSE-upstream.txt`（UnRAR许可；不得用于重建RAR压缩算法）。本项目不实现RAR压缩、不自建/伪装Junrar生产构件。

| 文件 | 大小 | SHA256 | 用途 |
|---|---:|---|---|
| test.rar | 120 | 65f8921cab788afaa177f7c080c29a364a77eea91ee57c4390f5d4d14527da60 | 正常RAR4；foo/bar.txt精确字节`baz\n`，真实SDK抽取与项目配置的Tika解析 |
| unicode.rar | 236 | b35d9c57cab65a2f15827cb072aed971a99902d332f8c0ccf1ebcac69ab951e2 | 正常RAR4 Unicode文件名：新建文本文档.txt、ウニコド.txt |

源码/夹具出处：<https://github.com/junrar/junrar/tree/9fd65e3449274100f23dd6d9c592e7a12b788d36/src/test/resources/com/github/junrar>。

刻意不复制/运行上游`as-vm-1-trigger.rar`。安全回归通过真实SDK的setIP边界及JMP→RET程序验证；仅给输入指令List加四次读取上限，第五次立即抛出异常，将旧版错误变成确定性JUnit断言失败，而不是让工作线程永久旋转。没有修改/桩替SDK解释器，不用耗时比较或超时作为负对照依据。
