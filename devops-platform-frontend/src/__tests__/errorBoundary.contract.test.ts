import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

/**
 * 前端错误兜底机制的**契约测试**。
 *
 * ## 为什么前端不照搬后端那套「静默 catch」规则
 *
 * 后端刚做完 `catch (Exception)` 普查（166 处 → 修 2 处），本轮原计划把同一套
 * 规则搬到前端。实际扫描后发现**判据不适用**：前端 206 处 catch 里，
 * 大量「块内无日志」的写法是完全正确的，因为前端的错误处理是**分层**的：
 *
 * | 前端惯用法 | 为什么块内不需要日志 |
 * | --- | --- |
 * | `catch { /* store 已提示错误 *\/ }` | 错误已由 store / mutation 的 onError 统一 toast |
 * | `catch { loadError.value = e }` | 写进响应式状态由 UI 渲染错误态——比日志**更好**，用户直接可见 |
 * | `catch { /* 用户取消 *\/ }` | `ElMessageBox` 的 reject 就是「用户点了取消」，不是故障 |
 * | `try { localStorage... } catch {}` | 隐私模式下必然抛错，是预期分支 |
 *
 * 按后端标准硬套会产出几十条误报。**一个天天误报的规则，最终会被加白名单加到失效**，
 * 那还不如不加。
 *
 * ## 那前端真正该守什么
 *
 * 守**兜底机制本身不被拆掉**。这套机制的特点是：拆了之后
 * <b>不会有任何报错</b>，页面照常跑，只是错误不再提示给用户——
 * 一次 500 变成「点了没反应」，而开发者在控制台什么都看不到。
 * 这正是 CI 该拦住的那类改动。
 */

// 用 process.cwd()（vitest 的 root=前端目录）而非 import.meta.url：
// 后者在 vitest 下解出的是 /src 这种绝对路径，readdirSync 直接 ENOENT。
// 本地预跑时实测撞到
const SRC = join(process.cwd(), 'src')
const MAIN_TS = join(SRC, 'main.ts')
const HTTP_TS = join(SRC, 'utils/http.ts')
const NOTIFY_TS = join(SRC, 'utils/notify.ts')

/** 读文件并剔除注释行——注释里常写着反例字面量，会让断言恒假 */
function codeOf(path: string): string {
  return readFileSync(path, 'utf-8')
    .split('\n')
    .map(l => l.trim())
    .filter(l => !l.startsWith('//') && !l.startsWith('*') && !l.startsWith('/*'))
    .join('\n')
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue
      walk(full, acc)
    } else if ((name.endsWith('.ts') || name.endsWith('.vue')) && !name.endsWith('.test.ts')) {
      acc.push(full)
    }
  }
  return acc
}

describe('全局错误兜底不得被拆除', () => {
  it('Vue 渲染期异常有 errorHandler 兜底', () => {
    // 没有它时，任何组件渲染抛错会让那棵子树白屏，
    // 而控制台只有一行 Vue 内部堆栈，用户侧完全没有提示
    const code = codeOf(MAIN_TS)
    expect(code).toContain('app.config.errorHandler')
    expect(code, 'errorHandler 里必须给用户可见的提示，只 console 等于没提示')
      .toMatch(/errorHandler[\s\S]{0,600}notify\.error/)
  })

  it('未捕获的 Promise rejection 有 unhandledrejection 兜底', () => {
    // 这是前端最容易漏的一类：async 函数里抛错而调用方没 await/catch，
    // 浏览器只在控制台打一条 warning，用户完全无感——
    // 表现为「点了没反应」，是最难收集到反馈的故障形态
    const code = codeOf(MAIN_TS)
    expect(code).toContain("addEventListener('unhandledrejection'")
    expect(code, 'unhandledrejection 里必须给用户可见的提示')
      .toMatch(/unhandledrejection[\s\S]{0,1200}notify\.error/)
  })

  it('两条兜底路径都做了去重，避免同一错误弹两次', () => {
    // errorHandler 与 unhandledrejection 可能同时触发。
    // 不去重会让用户看到两个内容相同的 toast，
    // 而重复的提示会训练用户忽略所有提示
    const code = codeOf(MAIN_TS)
    expect(code).toMatch(/shouldShowError|DEDUP_WINDOW|lastErrorKey/)
  })

  it('动态导入失败被单独识别，不与业务错误混为一谈', () => {
    // 发版后旧页面加载新 chunk 会失败（文件名带 hash 已变）。
    // 这类错误的正确处置是提示刷新，而不是报「发生意外错误」——
    // 后者会让用户以为是数据问题而反复重试
    const code = codeOf(MAIN_TS)
    expect(code).toMatch(/Failed to fetch dynamically imported module|Loading chunk/)
  })
})

describe('HTTP 层错误契约', () => {
  it('http.ts 把失败统一包装为 HttpError 抛出，而不是返回 null', () => {
    // 返回 null 会让调用方拿着 null 继续往下走，
    // 错误在几层之后才以「读取 undefined 属性」的形式炸出来——
    // 那时堆栈已经完全指不到真正的失败点
    const code = codeOf(HTTP_TS)
    expect(code).toMatch(/throw new HttpError/)
  })

  it('存在 toFriendlyError 把技术错误转成用户能读懂的文案', () => {
    // 直接把 "Network request failed" 或后端堆栈弹给用户毫无意义，
    // 且可能泄露内部实现
    expect(codeOf(HTTP_TS)).toMatch(/export function toFriendlyError/)
  })

  it('主动取消不被当作错误提示', () => {
    // 用户关弹窗、切页面、点「停止生成」都会触发 abort。
    // 弹一句「操作失败」反而让用户以为自己的取消动作出了问题
    const code = codeOf(NOTIFY_TS)
    expect(code, 'handleServerError 需要识别 abort 并静默返回')
      .toMatch(/isAbortLike|AbortError/)
  })
})

describe('Markdown 渲染单一入口', () => {
  it('除 safeMarkdown.ts 外不得自行调用 marked + DOMPurify 组合', () => {
    // safeMarkdown.ts 的注释写明「所有 v-html 渲染 Markdown 的场景都必须走此函数，
    // 禁止自行调用 marked + DOMPurify」。
    //
    // 理由不是洁癖：净化配置（允许哪些标签/属性、a[target=_blank] 补 rel）
    // 集中在一处才能保证一致。自行组合的地方一旦漏配某项，
    // 就成了一个绕过全局策略的 XSS 缺口，而它看起来"也净化了"。
    // 允许的例外，每条都要写明理由：
    //   safeMarkdown.ts —— 统一入口本身；
    //   editorContent.ts —— 富文本**编辑器**场景，需保留展示端不允许的标签
    //     （表格、样式类等），净化策略天然不同；但它复用了同一份
    //     htmlSanitizePolicy 常量，没有另起一套白名单，故不算绕过全局策略。
    const ALLOWED_OWN_SANITIZE = ['utils/safeMarkdown.ts', 'utils/editorContent.ts']

    const offenders: string[] = []
    for (const file of walk(SRC)) {
      if (ALLOWED_OWN_SANITIZE.some(a => file.endsWith(a))) continue
      const code = codeOf(file)
      if (/DOMPurify\.sanitize/.test(code) && /marked\.parse|marked\(/.test(code)) {
        offenders.push(relative(SRC, file))
      }
    }
    expect(
      offenders,
      '这些文件自行组合 marked + DOMPurify，应改为调用 safeMarkdown（净化配置需集中一处）'
    ).toEqual([])
  })

  it('自行净化的例外文件必须复用同一份 htmlSanitizePolicy，不得另起白名单', () => {
    // 允许 editorContent.ts 自行 sanitize，前提是它用的是同一份标签/属性常量。
    // 若它另写一套字面量白名单，展示端与编辑端的策略就会各自漂移——
    // 而漂移的方向通常是编辑端越放越宽（为了让粘贴的内容不丢格式），
    // 最终成为绕过全局策略的入口。
    const code = codeOf(join(SRC, 'utils/editorContent.ts'))
    expect(code, 'editorContent.ts 应从 htmlSanitizePolicy 引入白名单常量')
      .toMatch(/from '\.\/htmlSanitizePolicy'|from '@\/utils\/htmlSanitizePolicy'/)
  })
})

describe('危险 API 使用约束', () => {
  it('不得使用裸 alert / confirm —— 应走统一的 notify / ElMessageBox', () => {
    // 裸 alert 会阻塞主线程且样式不可控，在移动端尤其糟糕；
    // 更重要的是它绕过了统一的错误去重与分级
    const offenders: string[] = []
    for (const file of walk(SRC)) {
      const code = codeOf(file)
      // window.alert / 行首 alert(，排除 ElMessageBox.confirm 等
      if (/(^|[^.\w])(window\.)?alert\s*\(/m.test(code)) {
        offenders.push(relative(SRC, file))
      }
    }
    expect(offenders, '这些文件使用了裸 alert，应改用 notify').toEqual([])
  })

  it('不得直接使用 v-html 绑定未经净化的内容', () => {
    // XSS 主入口。项目已有 safeMarkdown 做净化，
    // 绕过它直接 v-html 等于把知识库正文变成脚本注入点
    const offenders: string[] = []
    for (const file of walk(SRC)) {
      if (!file.endsWith('.vue')) continue
      const raw = readFileSync(file, 'utf-8')
      for (const m of raw.matchAll(/v-html\s*=\s*"([^"]+)"/g)) {
        const expr = m[1]
        // 允许绑定到已净化的表达式。
        // renderMarkdown 是各组件对 safeMarkdown 的本地包装（已实测确认
        // 三处实现都最终走 DOMPurify），故一并列入白名单——
        // 本地预跑时正是它触发了误报。
        if (!/safe|sanitiz|rendered|purif|renderMarkdown/i.test(expr)) {
          offenders.push(`${relative(SRC, file)} → v-html="${expr}"`)
        }
      }
    }
    expect(offenders, 'v-html 必须绑定经 safeMarkdown 净化后的内容').toEqual([])
  })
})
