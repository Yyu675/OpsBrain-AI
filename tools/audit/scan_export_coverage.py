#!/usr/bin/env python3
"""
前端**导出覆盖**扫描：找出「有导出、但从未在任何测试里被引用」的函数。

━━ 为什么需要这个 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
上一轮修 AlertList 分页缺陷时暴露了一个统计口径问题：

    useServerPagination.test.ts 有 46 例测试，
    但它们**全部打在 useServerPagination 上**，
    同文件导出的 useServerPaginationFrom 一例都没有。

而那个变体只在两个页面被用到，那两个页面当时也都没测试——
缺陷正好落在这个交叉盲区里。

教训：**「这个文件有测试」不等于「它的每个导出都被测了」**。
按文件统计覆盖率会把这类缺口完全掩盖掉：
`useServerPagination.ts` 在任何口径下都是「已覆盖」的文件。

所以统计粒度必须落到**导出符号**级别。

━━ 为什么不用 istanbul 覆盖率 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
行覆盖率回答「这行代码跑过没有」，本扫描回答
「有没有人**针对这个导出**写过断言」。两者不等价：

  - `useServerPaginationFrom` 内部调用 `buildPagination`，
    而 `buildPagination` 被主函数的 46 例覆盖得很好——
    行覆盖率会显示这块代码红得不明显，掩盖「变体本身没人测」；
  - 反过来，一个导出被测试 import 了但只是顺带用到（比如构造夹具），
    行覆盖率会算它「覆盖了」，本扫描则如实反映它被引用过。

两者互补。本扫描的价值在于**便宜、直观、指向明确**：
它给出的是一份「该补哪个函数的测试」的清单，而不是一个百分比。

━━ 扫描范围 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
只扫**逻辑层**：composables / utils / stores / api 的纯函数与 api/utils。
刻意不含：
  - `.vue` 组件——它们的「被测」形态是 mount 而非 import 符号，
    另有渲染冒烟测试这条线在管；
  - `src/api/services/*` 与 `src/api/*.ts` 的网络封装——
    它们在测试里普遍被 `vi.mock` 掉，import 的是 mock 不是实现，
    统计它们只会产生大量假阴性；
  - 类型导出（`export type` / `export interface`）——类型没有运行时行为。

━━ 用法 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
本模块被 run_audit.py 调用，也可单独运行看完整清单：

    python3 tools/audit/scan_export_coverage.py          # 列出未覆盖导出
    python3 tools/audit/scan_export_coverage.py --all    # 连已覆盖的一起列
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FE = os.path.join(ROOT, 'devops-platform-frontend', 'src')

# 只扫逻辑层。理由见文件头
SCAN_DIRS = ['composables', 'utils', 'stores', os.path.join('api', 'utils')]

# ── 导出识别 ────────────────────────────────────────────────────
# 刻意只认「值导出」：函数、const、class。
# `export type` / `export interface` 没有运行时行为，测不测无意义
_EXPORT_PATTERNS = [
    re.compile(r'^export\s+(?:async\s+)?function\s+(\w+)', re.M),
    re.compile(r'^export\s+const\s+(\w+)\s*[=:]', re.M),
    re.compile(r'^export\s+class\s+(\w+)', re.M),
]

_LINE_COMMENT = re.compile(r'//.*?$', re.M)
_BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)


def strip_comments(src: str) -> str:
    """去注释，避免把示例代码里的 export 当成真导出"""
    return _LINE_COMMENT.sub('', _BLOCK_COMMENT.sub('', src))


def is_test_file(path: str) -> bool:
    return '__tests__' in path or path.endswith(('.test.ts', '.spec.ts'))


def walk_ts(base: str):
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if not name.endswith('.ts') or name.endswith('.d.ts'):
                continue
            yield os.path.join(dirpath, name)


def collect_exports():
    """返回 {(相对路径, 符号名)} —— 生产代码里的值导出"""
    out = []
    for rel_dir in SCAN_DIRS:
        base = os.path.join(FE, rel_dir)
        if not os.path.isdir(base):
            continue
        for path in walk_ts(base):
            if is_test_file(path):
                continue
            src = strip_comments(open(path, encoding='utf-8').read())
            names = set()
            for pat in _EXPORT_PATTERNS:
                names.update(pat.findall(src))
            for n in sorted(names):
                out.append((os.path.relpath(path, FE).replace(os.sep, '/'), n))
    return out


def collect_test_text():
    """
    把所有测试文件拼成一大段文本。

    用「符号名是否作为完整单词出现」这个宽松判据，而不是解析 import 语句：
    测试里存在 `import * as m from '...'` 与 `vi.importActual` 两种写法，
    严格解析 import 会漏掉它们，产生假阳性（说没测其实测了）。

    宁可宽松——本扫描的目的是**找出确定没人碰过的导出**，
    假阴性（漏报）可以接受，假阳性（误报）会让清单失去可信度。
    """
    chunks = []
    for dirpath, _dirnames, filenames in os.walk(FE):
        for name in filenames:
            path = os.path.join(dirpath, name)
            if not name.endswith('.ts') or not is_test_file(path):
                continue
            chunks.append(open(path, encoding='utf-8').read())
    return '\n'.join(chunks)


def scan():
    """供 run_audit.py 调用。返回未被任何测试引用的导出清单"""
    tests = collect_test_text()
    found = []
    for rel_path, name in collect_exports():
        if re.search(r'\b' + re.escape(name) + r'\b', tests):
            continue
        found.append({
            'file': f'devops-platform-frontend/src/{rel_path}',
            'symbol': name,
            'detail': f'导出 {name} 从未在任何测试中被引用',
        })
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='连已覆盖的导出一起列出')
    args = ap.parse_args()

    tests = collect_test_text()
    exports = collect_exports()
    uncovered = []

    by_file = {}
    for rel_path, name in exports:
        covered = bool(re.search(r'\b' + re.escape(name) + r'\b', tests))
        if not covered:
            uncovered.append((rel_path, name))
        if args.all or not covered:
            by_file.setdefault(rel_path, []).append((name, covered))

    for rel_path in sorted(by_file):
        print(f"\n▎{rel_path}")
        for name, covered in by_file[rel_path]:
            print(f"   {'✅' if covered else '🔴'} {name}")

    print(f"\n{'─' * 60}")
    print(f"值导出合计 {len(exports)} 个，其中 {len(uncovered)} 个未被任何测试引用")
    return 0


if __name__ == '__main__':
    sys.exit(main())
