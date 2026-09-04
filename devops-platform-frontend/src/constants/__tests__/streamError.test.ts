import { describe, it, expect } from 'vitest'

import { toStreamError, BIZ_ERRORS } from '../bizCode'

/**
 * SSE 错误事件的展示解析。
 *
 * 要防住的缺陷：**丢弃 `data.code`，只回显 `data.message`**。
 *
 * 修复前 ChatMode.vue 与 KnowledgeSinkDrawer.vue 的 onError 都是
 * `${data.message || '请求失败'}` —— code 整个不用。后果是同一个错误
 * 在 REST 与 SSE 两条路上呈现完全不同：REST 侧走 getBizError() 查表，
 * 用户能看到「下一步做什么」；SSE 侧只有后端那句 message。
 *
 * 而 SSE 是本产品的主链路（AI 问答全走它），且后端 message 常常很短——
 * `DevOpsChatController` 下发 SSE_CONNECTION_ERROR 时 message 只有
 * 「连接超时」四个字，它的 Retry 是 SAFE（重试就能好），
 * 用户却完全看不出，多半以为服务坏了就走了。
 *
 * 断言落点：**必须落在「换个 code 会不会得到不同结果」上**。
 * 只断言「返回的 text 包含 message」是抓不到缺陷的——
 * 丢弃 code 的实现照样满足它。
 */
describe('SSE 错误展示解析', () => {
  describe('retryable 必须来自码表，不能恒定', () => {
    it('50002 连接异常（Retry=SAFE）→ retryable', () => {
      expect(toStreamError(50002, '连接超时').retryable).toBe(true)
    })

    it('42901 请求过于频繁（Retry=BACKOFF）→ retryable', () => {
      expect(toStreamError(42901, '请求过于频繁').retryable).toBe(true)
    })

    it('40003 提示词注入（Retry=NEVER）→ 不可重试', () => {
      // 与上面两条构成分叉：恒 true 或恒 false 的实现必然挂掉一边
      expect(toStreamError(40003, '检测到注入').retryable).toBe(false)
    })

    it('40005 配额超限（Retry=NEVER）→ 不可重试', () => {
      // 这条尤其重要：配额用完时提示「可重试」是有害的，
      // 用户会反复点击，每次都失败
      expect(toStreamError(40005, '配额已用完').retryable).toBe(false)
    })

    it('未知码 → 不可重试（不瞎猜）', () => {
      expect(toStreamError(40999, '某种错误').retryable).toBe(false)
    })
  })

  describe('hint 来自码表，且必须进正文', () => {
    it('已知码把 hint 拼进 text —— 对话气泡会留存，toast 几秒就没了', () => {
      const view = toStreamError(50002, '连接超时')
      const hint = BIZ_ERRORS[50002].hint!

      // 断言 text 同时含 message 与 hint：
      // 丢弃 code 的实现只有 message，缺 hint，这里会失败
      expect(view.text).toContain('连接超时')
      expect(view.text).toContain(hint)
      expect(view.hint).toBe(hint)
    })

    it('码表里没有 hint 的码，text 不追加空括号', () => {
      // 找一个有 title 无 hint 的码；没有就跳过（避免为凑用例改产品数据）
      const codeWithoutHint = Object.keys(BIZ_ERRORS)
        .map(Number)
        .find((c) => !BIZ_ERRORS[c].hint)
      if (codeWithoutHint === undefined) return

      const view = toStreamError(codeWithoutHint, '出错了')
      expect(view.text).toBe('出错了')
      expect(view.text).not.toContain('（）')
    })

    it('未知码不编造 hint', () => {
      const view = toStreamError(40999, '某种错误')
      expect(view.hint).toBeUndefined()
      expect(view.text).toBe('某种错误')
    })
  })

  describe('文案优先级：后端 message 优先，码表兜底', () => {
    it('后端 message 存在时优先用它 —— 它常带上下文', () => {
      // 如「请求超出配额限制: 本月 token 已用完，请稍后重试或联系管理员」
      // 比码表里干巴巴的「请求超出配额限制」信息量大
      const view = toStreamError(40005, '请求超出配额限制: 本月 token 已用完')
      expect(view.text).toContain('本月 token 已用完')
    })

    it('message 为空时回落到码表 title，而不是显示空白', () => {
      const view = toStreamError(50002, '')
      expect(view.text).toContain(BIZ_ERRORS[50002].title)
      expect(view.title).toBe(BIZ_ERRORS[50002].title)
    })

    it('message 只有空白字符时同样回落 —— trim 后判空', () => {
      // 后端偶尔下发 "  "，直接 || 判断不会回落，用户看到一片空白
      const view = toStreamError(50002, '   ')
      expect(view.text).toContain(BIZ_ERRORS[50002].title)
    })

    it('码与 message 都缺失时用调用方给的兜底文案', () => {
      const view = toStreamError(undefined, undefined, 'AI 整理失败，请手动编写')
      expect(view.text).toBe('AI 整理失败，请手动编写')
      expect(view.title).toBe('AI 整理失败，请手动编写')
      expect(view.retryable).toBe(false)
    })
  })

  describe('title 用于 toast，与 text 分工不同', () => {
    it('已知码的 title 取码表的短标题，不带 hint', () => {
      const view = toStreamError(42901, '请求过于频繁，请 30 秒后重试')
      // toast 空间小，用短标题；详细信息留在对话气泡的 text 里
      expect(view.title).toBe(BIZ_ERRORS[42901].title)
      expect(view.title).not.toContain(BIZ_ERRORS[42901].hint!)
    })

    it('未知码的 title 回落到 message，不显示「未知错误」这类无用文案', () => {
      const view = toStreamError(40999, '后端说了句具体的话')
      expect(view.title).toBe('后端说了句具体的话')
    })
  })

  describe('SSE 侧实际会下发的码都能被识别', () => {
    // 后端 sendErrorEvent 调用点用到的全部码。
    // 少了任何一个，用户在该场景下就只能看到裸 message。
    // 这个清单与后端调用点对应，后端新增码时这里会失败——那正是提醒
    const SSE_CODES = [40001, 40003, 40005, 40006, 42901, 50001, 50002, 40301]

    it.each(SSE_CODES)('码 %i 在 BIZ_ERRORS 里有文案', (code) => {
      expect(
        BIZ_ERRORS[code],
        `码 ${code} 会由 SSE 下发但前端词表里没有，用户只能看到裸 message`
      ).toBeDefined()
    })
  })
})
