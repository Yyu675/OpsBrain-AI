import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clearDraft, loadDraft, saveDraft } from '../draftStorage'

/**
 * 草稿暂存测试。
 *
 * ── 为什么这 59 行值得单独测 ──────────────────────────────────
 * 它是「用户写的东西会不会丢」的唯一保障。
 * `KnowledgeEditor.vue` 有 2013 行，用户可能在里面写半小时的排障文档；
 * 中途刷新、误点返回、浏览器崩溃时，能不能恢复全看这个模块。
 *
 * 而它的失败方式全是**静默**的：
 *   - 存不进去（隐私模式、配额满）→ 用户毫不知情，直到刷新后发现全没了
 *   - 读出脏数据 → 抛异常打断整个编辑器加载，比没草稿更糟
 *   - 过期判断写错 → 要么永远恢复三个月前的旧稿，要么刚写完就被清掉
 *
 * 这些都不会有报错，所以只能靠测试钉住。
 *
 * ── 关于 sessionStorage 而非 localStorage ─────────────────────
 * 选 session 是对的：草稿是「这次编辑」的中间态，不该跨标签页串味，
 * 也不该在关掉浏览器几天后还冒出来。测试里一并钉住它用的是哪个。
 */
describe('draftStorage', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  describe('存取往返', () => {
    it('存进去能原样读出来（对象结构不被压平）', () => {
      const draft = {
        form: { title: 'MySQL 连接池排查', tags: ['mysql', 'p1'], content: '## 现象\n连接数打满' },
        baseVersion: 3,
        editorMode: 'markdown' as const,
      }

      expect(saveDraft('doc-1', draft)).toBe(true)
      expect(loadDraft('doc-1')).toEqual(draft)
    })

    it('用 sessionStorage 且带命名前缀 —— 不与其他模块的键冲突', () => {
      saveDraft('doc-1', { a: 1 })

      // 前缀让草稿键在 DevTools 里一眼可辨，也避免与业务键撞名
      const keys = Object.keys(sessionStorage)
      expect(keys.some((k) => k.includes('doc-1'))).toBe(true)
      expect(keys.every((k) => k.startsWith('__draft__:'))).toBe(true)
      // 不该写进 localStorage：草稿是「这次编辑」的中间态，
      // 跨标签页串味或几天后突然冒出来都是 bug
      expect(localStorage.getItem('__draft__:doc-1')).toBeNull()
    })

    it('不同 key 互不干扰 —— 同时编辑两篇文档不能串稿', () => {
      saveDraft('doc-1', { title: '甲' })
      saveDraft('doc-2', { title: '乙' })

      expect(loadDraft<{ title: string }>('doc-1')?.title).toBe('甲')
      expect(loadDraft<{ title: string }>('doc-2')?.title).toBe('乙')
    })

    it('重复保存覆盖旧值，不累积', () => {
      saveDraft('doc-1', { title: '第一版' })
      saveDraft('doc-1', { title: '第二版' })

      expect(loadDraft<{ title: string }>('doc-1')?.title).toBe('第二版')
    })

    it('key 不存在时返回 null，而不是抛错', () => {
      expect(loadDraft('never-written')).toBeNull()
    })

    it('clearDraft 后读不到', () => {
      saveDraft('doc-1', { title: 'x' })
      clearDraft('doc-1')

      expect(loadDraft('doc-1')).toBeNull()
    })

    it('clearDraft 不存在的 key 不抛错（重复清理是常态）', () => {
      expect(() => clearDraft('never-written')).not.toThrow()
    })
  })

  describe('过期', () => {
    it('超过 maxAge 的草稿返回 null 并顺手清掉', () => {
      saveDraft('doc-1', { title: '旧稿' })

      // 25 小时后（默认上限 24 小时）
      vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 25 * 60 * 60 * 1000)

      expect(loadDraft('doc-1')).toBeNull()
      // 过期还留着的话，sessionStorage 会被永远读不到的死数据慢慢占满
      expect(sessionStorage.getItem('__draft__:doc-1')).toBeNull()
    })

    it('未超过 maxAge 的草稿正常返回', () => {
      saveDraft('doc-1', { title: '新稿' })

      vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 23 * 60 * 60 * 1000)

      expect(loadDraft<{ title: string }>('doc-1')?.title).toBe('新稿')
    })

    it('maxAge 可按调用方需要收紧', () => {
      saveDraft('doc-1', { title: 'x' })

      vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 2000)

      expect(loadDraft('doc-1', 1000)).toBeNull()
      expect(loadDraft('doc-1', 60_000)).toBeNull() // 上一次调用已把它清掉了
    })
  })

  describe('脏数据与降级 —— 失败必须是静默降级，不能打断编辑器', () => {
    it('存的不是合法 JSON 时返回 null 并清掉，不抛异常', () => {
      sessionStorage.setItem('__draft__:doc-1', '{ 这不是 JSON')

      // 抛异常会打断整个编辑器加载流程，用户连页面都打不开——
      // 比「没有草稿」糟糕得多
      expect(() => loadDraft('doc-1')).not.toThrow()
      expect(loadDraft('doc-1')).toBeNull()
    })

    it('JSON 合法但结构不符（缺 value 字段）同样返回 null', () => {
      sessionStorage.setItem('__draft__:doc-1', JSON.stringify({ savedAt: Date.now() }))

      expect(loadDraft('doc-1')).toBeNull()
    })

    it('value 为 null 时如实返回 null，不误判为「无草稿」而报错', () => {
      sessionStorage.setItem('__draft__:doc-1',
        JSON.stringify({ value: null, savedAt: Date.now() }))

      expect(loadDraft('doc-1')).toBeNull()
    })

    it('缺 savedAt 时不做过期判断，草稿仍可恢复', () => {
      // 老版本写入的数据可能没有 savedAt。
      // 若因此判定「过期」而丢弃，升级发版会让所有人的在编草稿凭空消失
      sessionStorage.setItem('__draft__:doc-1',
        JSON.stringify({ value: { title: '老格式' } }))

      expect(loadDraft<{ title: string }>('doc-1')?.title).toBe('老格式')
    })

    it('sessionStorage 不可用时 loadDraft 返回 null 不抛错（隐私模式）', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new DOMException('SecurityError')
      })

      expect(() => loadDraft('doc-1')).not.toThrow()
      expect(loadDraft('doc-1')).toBeNull()
    })

    it('写入失败时 saveDraft 返回 false —— 调用方据此可提示用户', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('QuotaExceededError')
      })

      // 返回 false 而不是抛错：写不进去不该让用户的编辑操作中断，
      // 但调用方需要知道「这次没存上」，否则用户会以为草稿一直在
      expect(saveDraft('doc-1', { title: 'x' })).toBe(false)
    })

    it('删除失败时不抛错（清理是尽力而为）', () => {
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new DOMException('SecurityError')
      })

      expect(() => clearDraft('doc-1')).not.toThrow()
    })

    it('含循环引用的对象保存失败但不抛错', () => {
      const circular: Record<string, unknown> = { title: 'x' }
      circular.self = circular

      expect(saveDraft('doc-1', circular)).toBe(false)
      expect(loadDraft('doc-1')).toBeNull()
    })
  })

  describe('内容保真 —— 排障文档里什么字符都可能有', () => {
    it('保留换行、缩进与 Markdown 结构', () => {
      const content = '## 步骤\n\n1. 查看日志\n   ```bash\n   kubectl logs -f pod\n   ```\n2. 重启'

      saveDraft('doc-1', { content })

      expect(loadDraft<{ content: string }>('doc-1')?.content).toBe(content)
    })

    it('保留中文、emoji 与特殊字符', () => {
      const content = '连接池「爆了」🔥 —— 错误码 <500> & "quoted" \\backslash'

      saveDraft('doc-1', { content })

      expect(loadDraft<{ content: string }>('doc-1')?.content).toBe(content)
    })

    it('空字符串是有效内容，不能被当成「无草稿」', () => {
      // 用户把内容全删光也是一种编辑状态，
      // 恢复时应该还原成空，而不是把之前的内容又找回来
      saveDraft('doc-1', { title: '标题还在', content: '' })

      const loaded = loadDraft<{ title: string; content: string }>('doc-1')
      expect(loaded).not.toBeNull()
      expect(loaded?.content).toBe('')
      expect(loaded?.title).toBe('标题还在')
    })

    it('保留 baseVersion=0 与 null 的区别', () => {
      // KnowledgeEditor 靠 baseVersion 判断草稿是否与服务器版本冲突：
      // null = 新文档草稿，0 = 基于 v0。用 falsy 判断会把两者混为一谈
      saveDraft('a', { baseVersion: 0 })
      saveDraft('b', { baseVersion: null })

      expect(loadDraft<{ baseVersion: number | null }>('a')?.baseVersion).toBe(0)
      expect(loadDraft<{ baseVersion: number | null }>('b')?.baseVersion).toBeNull()
    })
  })
})
