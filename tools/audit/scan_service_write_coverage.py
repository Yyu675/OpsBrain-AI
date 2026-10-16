#!/usr/bin/env python3
"""
后端 Service/Manager 层**写方法**测试覆盖扫描。

━━ 与前端 scan_export_coverage.py 的关系 ━━━━━━━━━━━━━━━━━━━━━━━
同一思路的后端版：统计粒度落到**方法**而非文件。
「这个 Service 有测试」不等于「它的每个写方法都被测了」——
`TicketService` 有 21 个写方法、既有测试覆盖 13 个，
剩下 8 个里就藏着 Saga 补偿动作 `voidTicket` 与物理删除 `deleteTicket`。

━━ 为什么只看写方法 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
读方法算错了顶多显示不对，写方法算错了会改坏数据。
在覆盖资源有限时，写操作的优先级明确高于读操作。

━━ 2026-08-26 重写：原版约 40% 是误报 ━━━━━━━━━━━━━━━━━━━━━━━━
首次真实运行报出 36 个「未覆盖写方法」，逐个核实后发现大量噪音：

  · `ToolRuntimeManager` 的 riskLevel/timeoutMs/idempotent 等 6 个
    —— 是**匿名内部类**里实现的 `@ToolMeta` 注解属性，不是服务方法；
  · `KnowledgeDocService.IndexOutcome` —— 是 `public record`，不是方法；
  · `CostQuotaManager.exceeded` —— 是嵌套 record 的静态工厂；
  · `automationPolicyStats` / `accuracyStats` —— 只读聚合查询，
    命名不以 get/find 开头就被当成了写方法。

误报率高的直接后果是**清单失去可信度**：没人愿意逐条核对 36 项，
于是整个扫描被忽略。所以本次重写的重点不是找出更多，而是**少骗人**。

四条过滤规则（均由上述真实误报反推得出）：
  1. 跳过 record / enum / interface / @interface 声明；
  2. 跳过嵌套类型内部的方法（只看顶层类的直接成员）；
  3. 扩充只读前缀白名单，并识别 `*Stats` / `*Snapshot` 等只读后缀；
  4. 跳过 `@Override` 方法——它们实现的是外部契约，
     由契约持有方或集成测试覆盖，不该记在本类头上。

━━ 用法 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    python3 tools/audit/scan_service_write_coverage.py
    python3 tools/audit/scan_service_write_coverage.py --all   # 连已覆盖的一起列
"""
import argparse
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

_BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
_LINE_COMMENT = re.compile(r'//.*?$', re.M)
_STRING = re.compile(r'"(?:\\.|[^"\\])*"')

# 只读方法名前缀。命名不规范的只读方法靠下面的后缀规则兜底
_READ_PREFIX = re.compile(
    r'^(get|find|list|count|is|has|can|should|describe|all|query|load|read|'
    r'fetch|search|exists|lookup|resolve|to|as|of|from|build|create[A-Z]?\w*Request)'
)
# 只读后缀：xxxStats / xxxSnapshot / xxxSummary / xxxReport
_READ_SUFFIX = re.compile(r'(Stats|Snapshot|Summary|Report|Info|View|Dto)$')


def strip_noise(src: str) -> str:
    """去注释与字符串字面量，避免把示例代码/日志文本当成声明"""
    return _STRING.sub('""', _LINE_COMMENT.sub('', _BLOCK_COMMENT.sub('', src)))


def top_level_body(src: str, cls: str) -> str:
    """
    截取顶层类的直接成员区域，剔除嵌套类型。

    做法：从类声明处开始按花括号配平，遇到嵌套的
    class/record/enum/interface 声明就整段跳过。
    这一步专治「匿名内部类的注解实现被当成服务方法」那类误报。
    """
    m = re.search(r'\b(?:class|record|enum|interface)\s+' + re.escape(cls) + r'\b', src)
    if not m:
        return src
    i = src.find('{', m.end())
    if i < 0:
        return src

    out = []
    depth = 0
    nested_decl = re.compile(r'\b(class|record|enum|interface|new\s+\w+\s*\()\s')
    n = len(src)
    j = i
    skip_until_depth = None

    while j < n:
        ch = src[j]
        if ch == '{':
            depth += 1
            # 进入嵌套类型/匿名类体：记录深度，直到回到该深度前不采集
            if skip_until_depth is None:
                look = src[max(0, j - 200):j]
                if nested_decl.search(look) and depth > 1:
                    skip_until_depth = depth
        elif ch == '}':
            if skip_until_depth is not None and depth == skip_until_depth:
                skip_until_depth = None
            depth -= 1
            if depth == 0:
                break
        if skip_until_depth is None:
            out.append(ch)
        j += 1
    return ''.join(out)


def write_methods(path: str):
    """返回 (类名, [写方法名])。非类文件或无写方法时返回 (cls, [])"""
    cls = os.path.basename(path).replace('.java', '')
    raw = open(path, encoding='utf-8').read()

    # 接口/记录/枚举本身不算实现类
    if re.search(r'\b(interface|@interface|enum)\s+' + re.escape(cls) + r'\b', raw):
        return cls, []
    if re.search(r'\brecord\s+' + re.escape(cls) + r'\s*\(', raw):
        return cls, []

    src = top_level_body(strip_noise(raw), cls)

    names = []
    for m in re.finditer(r'(?:^|\n)\s*((?:@\w+(?:\([^)]*\))?\s*)*)'
                         r'public\s+(?!record\b|class\b|enum\b|interface\b)'
                         r'(?:static\s+|final\s+|synchronized\s+)*'
                         r'[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', src):
        annos, name = m.group(1), m.group(2)
        if name == cls:                       # 构造器
            continue
        if '@Override' in annos:              # 实现外部契约，不记在本类头上
            continue
        if _READ_PREFIX.match(name):
            continue
        if _READ_SUFFIX.search(name):
            continue
        names.append(name)
    return cls, sorted(set(names))


def scan():
    """供 run_audit.py 调用：返回未被任何测试引用的写方法清单"""
    tests = []
    for f in glob.glob(os.path.join(ROOT, 'src/test/**/*.java'), recursive=True):
        tests.append(open(f, encoding='utf-8').read())
    blob = '\n'.join(tests)

    found = []
    targets = (glob.glob(os.path.join(ROOT, 'src/main/java/**/*Service*.java'), recursive=True)
               + glob.glob(os.path.join(ROOT, 'src/main/java/**/*Manager.java'), recursive=True))
    for path in sorted(set(targets)):
        cls, methods = write_methods(path)
        for name in methods:
            # 宽松判据：只要测试里出现 `.name(` 就算被碰过。
            # 宁可漏报也不误报——清单的价值在于可信
            if re.search(r'\.' + re.escape(name) + r'\s*\(', blob):
                continue
            found.append({
                'file': os.path.relpath(path, ROOT).replace(os.sep, '/'),
                'method': f'{cls}.{name}',
                'detail': f'写方法 {cls}.{name} 从未在任何测试中被调用',
            })
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='连已覆盖的写方法一起列出')
    args = ap.parse_args()

    tests = []
    for f in glob.glob(os.path.join(ROOT, 'src/test/**/*.java'), recursive=True):
        tests.append(open(f, encoding='utf-8').read())
    blob = '\n'.join(tests)

    targets = (glob.glob(os.path.join(ROOT, 'src/main/java/**/*Service*.java'), recursive=True)
               + glob.glob(os.path.join(ROOT, 'src/main/java/**/*Manager.java'), recursive=True))

    total = uncovered = 0
    for path in sorted(set(targets)):
        cls, methods = write_methods(path)
        if not methods:
            continue
        miss = [m for m in methods
                if not re.search(r'\.' + re.escape(m) + r'\s*\(', blob)]
        total += len(methods)
        uncovered += len(miss)
        if miss or args.all:
            print(f"\n▎{cls}  （写方法 {len(methods)} 个，未覆盖 {len(miss)} 个）")
            for m in methods:
                covered = m not in miss
                if args.all or not covered:
                    print(f"   {'✅' if covered else '🔴'} {m}")

    print(f"\n{'─' * 60}")
    print(f"Service/Manager 写方法合计 {total} 个，其中 {uncovered} 个未被任何测试调用")
    return 0


if __name__ == '__main__':
    sys.exit(main())
