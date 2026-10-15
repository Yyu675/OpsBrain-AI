import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * e2e 套件的**写法守卫**（不是功能测试）。
 *
 * 为什么要用测试来管写法：下面三条都是从 main 上真实的偶发红案里挖出来的机制，
 * 而它们**只有在别人写新用例时才会复发**——功能测试红不了，评审也容易看漏。
 * 与其写在文档里靠自觉，不如做成会红的闸。
 *
 *   ① 裸 `page.goto()`：本应用挂载即建 SSE/WebSocket 长连接，`'load'` 可能永不触发
 *      （goto 的默认 waitUntil）；且客户端跳转与进行中的 goto 相撞会报
 *      `net::ERR_ABORTED`。→ 一律走 `gotoApp()`。
 *   ② 裸点登录按钮：boot 期按钮重渲染会吞掉点击，Playwright 不会自动重试。
 *      → 一律走 `submitLoginForm()`。
 *   ③ 裸 `browser.newContext()`：不继承 config 里 chromium 项目的 storageState，
 *      得到的是**未登录**上下文，用例可能长期空过（只断言 body 可见）。
 *      → 必须显式传 storageState。
 */
const E2E_DIR = join(process.cwd(), 'e2e')

const walk = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })

/** 注释行不算违规——恰恰是那些「解释为什么不能这么写」的注释里会出现关键词 */
const isCodeLine = (line: string) => {
  const s = line.trim()
  return s.length > 0 && !s.startsWith('//') && !s.startsWith('*') && !s.startsWith('/*')
}

/** 只看真正会跑的用例文件；helpers 自身与 .auth 产物不在约束范围内 */
const specFiles = () =>
  walk(E2E_DIR)
    .filter((f) => f.endsWith('.spec.ts'))
    .map((f) => ({ file: relative(process.cwd(), f), text: readFileSync(f, 'utf-8') }))

describe('e2e 写法守卫', () => {
  it('① 不得出现不带 domcontentloaded 的裸 page.goto（一律走 gotoApp）', () => {
    const offenders: string[] = []
    for (const { file, text } of specFiles()) {
      text.split('\n').forEach((line, i) => {
        if (!isCodeLine(line)) return
        if (!/\.goto\(/.test(line)) return
        if (/gotoApp\(/.test(line)) return
        if (/domcontentloaded/.test(line)) return
        offenders.push(`${file}:${i + 1}: ${line.trim()}`)
      })
    }
    expect(offenders, `发现裸 goto（改用 helpers/nav 的 gotoApp）:\n${offenders.join('\n')}`).toEqual([])
  })

  it('② 不得绕过 submitLoginForm 直接点登录按钮（否则丢失点击重试）', () => {
    const offenders: string[] = []
    for (const { file, text } of specFiles()) {
      text.split('\n').forEach((line, i) => {
        if (!isCodeLine(line)) return
        if (/login-btn/.test(line) && /\.click\(/.test(line)) {
          offenders.push(`${file}:${i + 1}: ${line.trim()}`)
        }
      })
    }
    expect(offenders, `发现直接点登录按钮（改用 helpers/login 的 submitLoginForm）:\n${offenders.join('\n')}`).toEqual([])
  })

  it('③ newContext() 必须显式带 storageState（否则上下文未登录、用例会空过）', () => {
    const offenders: string[] = []
    for (const { file, text } of specFiles()) {
      text.split('\n').forEach((line, i) => {
        if (!isCodeLine(line)) return
        // 只管 browser.newContext：request.newContext() 是 APIRequestContext（自己 POST 登录
        // 拿 token 用），与浏览器 storageState 无关，不该被这条规则拦。
        if (!/\bbrowser\.newContext\(/.test(line)) return
        if (/storageState/.test(line)) return
        offenders.push(`${file}:${i + 1}: ${line.trim()}`)
      })
    }
    expect(offenders, `发现裸 newContext（必须传 storageState）:\n${offenders.join('\n')}`).toEqual([])
  })

  it('④ gotoApp 自身不得退化：实现里必须仍是 domcontentloaded', () => {
    // 前三条只扫 .spec.ts——若有人把 helpers/nav.ts 的实现改回裸 page.goto(path)，
    // 全套件会一起退回「等 load / 与客户端跳转抢跑」的老毛病，而前三条一条都不会红
    // （这个缺口是变异测试当场暴露的）。故把实现本身也钉住。
    const nav = readFileSync(join(E2E_DIR, 'helpers/nav.ts'), 'utf-8')
    expect(nav).toMatch(/waitUntil:\s*'domcontentloaded'/)
    expect(nav).not.toMatch(/^\s*page\.goto\(path\)\s*$/m)
  })

  it('守卫自身有效：确实扫到了用例文件（不是空转恒绿）', () => {
    const files = specFiles()
    expect(files.length).toBeGreaterThan(5)
    // 抽查：已知合规的文件必须在扫描范围内
    expect(files.some((f) => f.file.includes('navigation.spec.ts'))).toBe(true)
    expect(files.some((f) => f.file.includes('boundary-edge-cases.spec.ts'))).toBe(true)
  })
})
