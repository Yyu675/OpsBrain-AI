#!/usr/bin/env python3
"""
代码模式扫描统一入口。

查的是**编译期无信号、运行期无报错、只在特定路径才暴露**的缺陷。
人工通读几乎不可能发现，但它们都有可被脚本识别的固定模式。

本会话靠这类扫描查出过两个真实缺陷：
  - acknowledgeTicket 自调用，导致两个 @Transactional 完全失效
  - 付费 LLM 探针的开关配了、文档写了，但 @ConditionalOnProperty
    标在 @RequestMapping 方法上根本不生效（匿名可刷的成本失控口子）

━━ 为什么需要基线（baseline.json）━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
扫描输出是**线索不是判决**。比如「空 catch」当前有 6 处命中，
逐个看过都带解释性注释、属有意为之。

如果不区分「已审阅接受」与「新引入」，这个检查会从第一天起就是红的，
而**长期红着的检查等于没有检查**——大家会习惯性忽略它，
真正的新问题也就跟着被忽略了。

所以：已审阅的条目记进 baseline.json，扫描只对**新增条目**报错。
这和项目里 knip 的处理思路一致（见 ci.yml 里那段注释）。

━━ 用法 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    python3 tools/audit/run_audit.py            # 检查，有新增问题则退出码 1
    python3 tools/audit/run_audit.py --report   # 只输出报告，恒退出 0
    python3 tools/audit/run_audit.py --update   # 把当前结果写回基线（需人工复核后再提交）

退出码：0=无新增问题；1=发现新增问题。
"""
import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASELINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'baseline.json')


def java_files():
    return sorted(glob.glob(os.path.join(ROOT, 'src/main/java/**/*.java'), recursive=True))


_BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)


def strip_comments(src):
    """
    剥离注释，但**保留行号**（注释内容替换成等量空行/空格）。

    这一步是必需的：本项目的注释里大量讨论「不该怎么写」，
    例如 HealthCheckController 的 Javadoc 专门解释了
    「@ConditionalOnProperty 标在 @GetMapping 上不生效」。

    不剥离的话，**修复缺陷时写下的说明反而会触发对该缺陷的告警**——
    扫描器会把文档当代码，越是把教训写清楚的地方误报越多。
    """
    def _blank(m):
        return re.sub(r'[^\n]', ' ', m.group(0))

    src = _BLOCK_COMMENT.sub(_blank, src)
    out = []
    for line in src.split('\n'):
        idx = line.find('//')
        # 简单排除 http:// 这类误伤
        while idx > 0 and line[idx - 1] == ':':
            idx = line.find('//', idx + 2)
        out.append(line[:idx] if idx >= 0 else line)
    return '\n'.join(out)


def java_sources():
    """(路径, 已剥离注释的源码) —— 所有扫描统一走这里"""
    for f in java_files():
        yield f, strip_comments(open(f, encoding='utf-8').read())


def rel(path):
    return os.path.relpath(path, ROOT).replace('\\', '/')


# ══════════════════════════════════════════════════════════════════
# 扫描 1：非事务方法自调用 @Transactional 方法
#
# Spring 的事务由 AOP 代理织入。this.xxx() 不经过代理，
# 被调用方法上的 @Transactional 完全不生效——
# 多步写入退化成各自独立的自动提交，中途失败会留下半截状态。
# ══════════════════════════════════════════════════════════════════
def scan_self_invocation():
    found = []
    for f, src in java_sources():
        lines = src.split('\n')

        tx_methods = set()
        for i, l in enumerate(lines):
            if '@Transactional' not in l:
                continue
            for j in range(i + 1, min(i + 8, len(lines))):
                m = re.search(r'public\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', lines[j])
                if m:
                    tx_methods.add(m.group(1))
                    break
        if not tx_methods:
            continue

        cur, cur_is_tx = None, False
        for i, l in enumerate(lines):
            m = re.search(r'^\s{4}public\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', l)
            if m:
                cur = m.group(1)
                cur_is_tx = any('@Transactional' in lines[k]
                                for k in range(max(0, i - 8), i))
                continue
            if not cur or cur_is_tx:
                continue
            for t in tx_methods:
                if t == cur:
                    continue
                if re.search(r'(?<![\w.])' + re.escape(t) + r'\s*\(', l):
                    found.append({
                        'file': rel(f), 'caller': cur, 'callee': t,
                        'detail': f'非事务方法 {cur}() 自调用 @Transactional 的 {t}()',
                    })
    # 同一对 caller/callee 只报一次
    seen, uniq = set(), []
    for it in found:
        k = (it['file'], it['caller'], it['callee'])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(it)
    return uniq


# ══════════════════════════════════════════════════════════════════
# 扫描 2：条件注解标在请求映射方法上（不生效）
#
# @ConditionalOnProperty / @ConditionalOnBean 等是 **Bean 注册阶段**
# 的条件。Controller 这个 Bean 一旦注册，它的全部 @RequestMapping
# 方法都会被扫描注册成路由，没有任何一步会看方法上的这个注解。
#
# 本项目真实踩过：/health/ai-model 的付费探针开关因此完全失效，
# 端点一直开放且在鉴权白名单内。
# ══════════════════════════════════════════════════════════════════
# 用正则而非字面量匹配：注解可能写成全限定名
# （@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty）。
# 首版用字面量 '@ConditionalOnProperty'，回归验证时注入全限定名版本直接漏报了——
# 而漏报比误报危险得多：它让人误以为这一类问题已经不存在。
COND = re.compile(r'@(?:[\w.]+\.)?Conditional(?:OnProperty|OnBean|OnMissingBean'
                  r'|OnExpression|OnClass)\b')
MAPPING = re.compile(r'@(?:[\w.]+\.)?(?:Get|Post|Put|Delete|Patch|Request)Mapping\b')


def scan_conditional_on_mapping():
    found = []
    for f, src in java_sources():
        lines = src.split('\n')
        for i, l in enumerate(lines):
            if not COND.search(l):
                continue
            window = lines[max(0, i - 4): i + 5]
            if not any(MAPPING.search(w) for w in window):
                continue
            name = None
            for j in range(i + 1, min(i + 8, len(lines))):
                m = re.search(r'(?:public|protected|private)\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(',
                              lines[j])
                if m:
                    name = m.group(1)
                    break
            found.append({
                'file': rel(f), 'line': i + 1, 'method': name,
                'detail': f'条件注解标在请求映射方法 {name}() 上——Bean 注册阶段的条件对方法不生效',
            })
    return found


# ══════════════════════════════════════════════════════════════════
# 扫描 3：@Transactional 标在 private/protected 方法上
# 同样因为代理机制而完全不生效。
# ══════════════════════════════════════════════════════════════════
def scan_transactional_visibility():
    found = []
    for f, src in java_sources():
        lines = src.split('\n')
        for i, l in enumerate(lines):
            if '@Transactional' not in l:
                continue
            for j in range(i + 1, min(i + 8, len(lines))):
                s = lines[j].strip()
                if re.match(r'(private|protected)\s', s):
                    found.append({
                        'file': rel(f), 'line': j + 1,
                        'detail': f'@Transactional 标在非 public 方法上：{s[:60]}',
                    })
                    break
                if re.match(r'(public|@)', s):
                    break
    return found


# ══════════════════════════════════════════════════════════════════
# 扫描 4：配置项定义了但无代码读取
#
# 「配置项存在、文档齐全、代码看着也对，唯独没有任何东西真正读取它」——
# 这个模式在本项目出现过两次（付费探针开关、业务码词表）。
# 它不会有任何报错，只能靠扫描发现。
# ══════════════════════════════════════════════════════════════════
def scan_unread_config():
    yml = os.path.join(ROOT, 'src/main/resources/application.yml')
    if not os.path.exists(yml):
        return []

    keys, stack = [], []
    for line in open(yml, encoding='utf-8'):
        if not line.strip() or line.strip().startswith('#'):
            continue
        indent = len(line) - len(line.lstrip())
        m = re.match(r'\s*([a-zA-Z0-9\-_]+):\s*(.*)$', line)
        if not m:
            continue
        k, v = m.group(1), m.group(2).strip()
        stack = [(i, kk) for i, kk in stack if i < indent]
        stack.append((indent, k))
        if v and not v.startswith('#'):
            keys.append('.'.join(kk for _, kk in stack))

    src_all = ''.join(src for _, src in java_sources())
    found = []
    for k in keys:
        if not k.startswith('devops.'):
            continue
        if k in src_all or re.search(re.escape(k) + r'[:\}]', src_all):
            continue
        found.append({'file': 'application.yml', 'key': k,
                      'detail': f'配置项 {k} 无任何代码读取'})
    return found


# 前端导出覆盖扫描独立成文件（逻辑较长，且可单独运行看完整清单）
from scan_export_coverage import scan as scan_export_coverage  # noqa: E402


SCANS = [
    ('self_invocation', '非事务方法自调用 @Transactional 方法', scan_self_invocation),
    ('conditional_on_mapping', '条件注解标在请求映射方法上', scan_conditional_on_mapping),
    ('transactional_visibility', '@Transactional 标在非 public 方法上', scan_transactional_visibility),
    ('unread_config', '配置项无代码读取', scan_unread_config),
    ('export_coverage', '前端逻辑层导出从未被测试引用', scan_export_coverage),
]


def fingerprint(scan_id, item):
    """稳定指纹：刻意不含行号——加几行代码不该让基线全部失效"""
    if scan_id == 'self_invocation':
        return f"{scan_id}|{item['file']}|{item['caller']}|{item['callee']}"
    if scan_id == 'unread_config':
        return f"{scan_id}|{item['key']}"
    if scan_id == 'export_coverage':
        return f"{scan_id}|{item['file']}|{item['symbol']}"
    return f"{scan_id}|{item['file']}|{item.get('method') or item['detail'][:40]}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--report', action='store_true', help='只输出报告，恒退出 0')
    ap.add_argument('--update', action='store_true', help='把当前结果写回基线')
    args = ap.parse_args()

    baseline = {}
    if os.path.exists(BASELINE):
        baseline = json.load(open(BASELINE, encoding='utf-8')).get('accepted', {})

    all_items, new_items = {}, []
    for scan_id, title, fn in SCANS:
        items = fn()
        all_items[scan_id] = items
        print(f"\n▎{title}")
        if not items:
            print("   ✅ 无命中")
            continue
        for it in items:
            fp = fingerprint(scan_id, it)
            known = fp in baseline
            mark = '　已接受' if known else '🔴 新增'
            loc = it.get('line')
            print(f"   {mark}  {it['file']}{':' + str(loc) if loc else ''}")
            print(f"           {it['detail']}")
            if known and baseline[fp]:
                print(f"           理由：{baseline[fp]}")
            if not known:
                new_items.append((scan_id, fp, it))

    if args.update:
        acc = dict(baseline)
        for scan_id, _, fn in SCANS:
            for it in all_items[scan_id]:
                fp = fingerprint(scan_id, it)
                acc.setdefault(fp, '（待补充审阅理由）')
        json.dump({'_comment': '已人工审阅并接受的扫描命中。'
                               '新增条目会让 run_audit.py 退出码为 1。',
                   'accepted': acc},
                  open(BASELINE, 'w', encoding='utf-8'),
                  ensure_ascii=False, indent=2)
        print(f"\n已写入基线：{rel(BASELINE)}（请人工复核每条的理由后再提交）")
        return 0

    total = sum(len(v) for v in all_items.values())
    print(f"\n{'─' * 60}")
    print(f"合计命中 {total} 处，其中新增 {len(new_items)} 处")

    if new_items and not args.report:
        print("\n❌ 发现新增的可疑模式。请逐条确认：")
        print("   · 确属缺陷 → 修复它")
        print("   · 有意为之 → 运行 --update 并在 baseline.json 里补上理由")
        return 1

    print("✅ 无新增问题")
    return 0


if __name__ == '__main__':
    sys.exit(main())
