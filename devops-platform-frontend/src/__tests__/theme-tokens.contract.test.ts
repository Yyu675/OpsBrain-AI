/**
 * 主题令牌契约测试 —— **硬编码白色背景不得再出现**。
 *
 * ── 背景：一个真实存在过的视觉缺陷 ────────────────────────────
 * 项目有一套完整的暗色主题（`theme.css` 的 `html.dark` 下重定义了
 * surface / text / border / brand 全套令牌），而且 `useTheme` 的默认
 * 模式是 **`'system'`**——也就是说，**任何系统设置为深色的用户，
 * 一打开就是暗色界面**，不需要做任何操作。
 *
 * 但代码里散落着 28 处 `background: white` / `background: #fff`，
 * 横跨 Dashboard、Home、TicketDetail、Login 等核心页面。
 * 这些硬编码不跟随主题，于是暗色下它们是一块块**刺眼的纯白卡片**
 * 压在深灰背景上，白底上又印着为暗色准备的浅色文字（`--text-1` 在暗色下
 * 是接近白的 `oklch(0.95 …)`）——**白底白字，直接读不了**。
 *
 * 最能说明这是「疏漏而非设计」的证据：`CollapsibleCard.vue` 里
 * 第 74 行写死 `background: white`，而第 90 行的 hover 态用的是
 * `var(--color-surface-hover, var(--surface-2))`——同一个文件、
 * 相隔 16 行，一个跟随主题一个不跟随。
 *
 * ── 这个测试守什么 ────────────────────────────────────────────
 * 逐个扫描 `.vue` 的样式块，任何 `background: white|#fff` 都会让它失败。
 * 这类问题**类型检查看不见、单元测试看不见、构建也不报错**，
 * 只有在暗色模式下用肉眼才发现——正因如此它才需要一道自动化防线。
 *
 * ── 唯一的例外 ────────────────────────────────────────────────
 * `SettingsDialog` 的开关滑块。它始终压在有色轨道上，需要固定对比，
 * 跟随主题反而会让暗色下的开关「消失」。例外在下方显式列出，
 * 新增例外必须写清理由——这比放宽正则要好，
 * 因为它强迫每一次豁免都被人看见。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

// 用 process.cwd() 而非 import.meta.url：jsdom 环境下 import.meta.url
// 不是 file: 协议，fileURLToPath 会抛 "The URL must be of scheme file"，
// 且报错发生在模块顶层——整个文件 0 个用例被收集，看起来像「测试不存在」
const SRC = join(process.cwd(), 'src')

/**
 * 已知且**刻意**保留的硬编码白色。
 * key 是相对 src 的路径，value 是理由——理由是必填的，
 * 不写理由的豁免下一个人无法判断能不能动。
 */
const ALLOWED: Record<string, string> = {
  'components/common/SettingsDialog.vue':
    '开关滑块：始终压在有色轨道上需固定对比，跟随主题会让暗色下的开关看起来消失',
}

const collectVueFiles = (dir: string, out: string[] = []): string[] => {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue
      collectVueFiles(full, out)
    } else if (name.endsWith('.vue')) {
      out.push(full)
    }
  }
  return out
}

/** 只匹配纯白背景。`rgba(255,255,255,0.06)` 这类半透明叠加是合法手法，不在范围内 */
const HARDCODED_WHITE = /background(-color)?:\s*(white|#fff|#ffffff)\s*[;}]/gi

describe('主题令牌契约', () => {
  it('样式里不得出现硬编码白色背景——暗色模式下会变成白底白字', () => {
    const offenders: string[] = []

    for (const file of collectVueFiles(SRC)) {
      const rel = relative(SRC, file).replace(/\\/g, '/')
      const content = readFileSync(file, 'utf8')

      // 只看样式块，避免误伤模板里的示例文本或注释中的说明
      const styleStart = content.indexOf('<style')
      if (styleStart === -1) continue
      const styles = content.slice(styleStart)

      const hits = styles.match(HARDCODED_WHITE)
      if (!hits) continue
      if (rel in ALLOWED) continue

      offenders.push(`${rel} —— ${hits.length} 处：${hits.join(' / ')}`)
    }

    expect(
      offenders,
      '这些文件用了硬编码白色背景。请改用 var(--color-surface, var(--surface-1))；\n'
        + '确有必要保留的，加进本文件的 ALLOWED 并写明理由：\n'
        + offenders.join('\n'),
    ).toEqual([])
  })

  it('豁免清单本身必须有效——列了却已经不存在的条目要及时清掉', () => {
    // 防止豁免清单变成一份越积越长、没人敢删的历史遗留。
    // 文件改好了却忘了从清单里移除，下一个人会以为那里还有坑
    for (const [rel, reason] of Object.entries(ALLOWED)) {
      const content = readFileSync(join(SRC, rel), 'utf8')
      const styles = content.slice(content.indexOf('<style'))

      expect(reason.length, `${rel} 的豁免理由不能为空`).toBeGreaterThan(10)
      expect(
        styles.match(HARDCODED_WHITE),
        `${rel} 已经不含硬编码白色了，请把它从 ALLOWED 里删掉`,
      ).not.toBeNull()
    }
  })

  it('暗色主题确实重定义了这些令牌——否则替换成令牌也没意义', () => {
    // 反向验证：如果 --surface-1 在暗色下没被重定义，
    // 那么「改用令牌」这件事本身就是空操作，上面两条守着的东西也就没有价值
    const theme = readFileSync(join(SRC, 'assets/styles/theme.css'), 'utf8')
    const darkBlock = theme.slice(theme.indexOf('html.dark'))

    for (const token of ['--surface-1', '--text-1', '--border-1']) {
      expect(darkBlock, `暗色块里应重定义 ${token}`).toContain(`${token}:`)
    }
  })
})
