/**
 * 文章大纲面板测试。
 *
 * ── 这个面板的定位：只展示与派发，不碰正文 ────────────────────
 * 标题的增 / 改 / 删都通过事件交回父组件，由父组件调用
 * `utils/editorContent` 里那三个函数（21 例单测，含索引越界保护）。
 *
 * 面板自己改正文会绕过那层保护，而**改错索引的表现是
 * 「我改了 A，B 却变了」**——不报错，用户只会觉得系统有鬼。
 * 所以本类第一组就验证「事件里回传的是原始 item」，
 * 而不是面板自己算出来的什么东西。
 */
import { describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

import type { TocItem } from '@/utils/editorContent'
import DocOutlinePanel from '../DocOutlinePanel.vue'

const items: TocItem[] = [
  { id: 'h-0', text: '现象与影响', level: 2, elementIndex: 0 },
  { id: 'h-1', text: '排查细节', level: 3, elementIndex: 1 },
  { id: 'h-2', text: '根因', level: 2, elementIndex: 2 },
]

const mountPanel = (list: TocItem[] = items): VueWrapper =>
  mount(DocOutlinePanel, { props: { items: list } })

describe('DocOutlinePanel', () => {
  describe('列表渲染', () => {
    it('每个标题一行，文本原样展示', () => {
      const wrapper = mountPanel()

      const rows = wrapper.findAll('.ce-toc-row')
      expect(rows).toHaveLength(3)
      expect(rows[0].find('.ce-toc-item').text()).toBe('现象与影响')
    })

    it('三级标题带缩进类 —— 没有层级区分的大纲等于一堆平铺文字', () => {
      const wrapper = mountPanel()

      const buttons = wrapper.findAll('.ce-toc-item')
      expect(buttons[0].classes()).not.toContain('level-three')
      expect(buttons[1].classes()).toContain('level-three')
    })

    it('用 item.id 作 key —— 同名标题不会互相顶替', () => {
      // 两个同名标题在文档里很常见（如两处「验证」），
      // 若用 text 作 key，Vue 会复用同一个 DOM 节点，
      // 点第二个会跳到第一个的位置
      const dup: TocItem[] = [
        { id: 'h-0', text: '验证', level: 2, elementIndex: 0 },
        { id: 'h-1', text: '验证', level: 2, elementIndex: 1 },
      ]
      const wrapper = mountPanel(dup)

      expect(wrapper.findAll('.ce-toc-row')).toHaveLength(2)
    })
  })

  describe('事件派发：回传原始 item，不自己算索引', () => {
    it('点标题文本发 scrollTo，携带完整 item', () => {
      const wrapper = mountPanel()

      wrapper.findAll('.ce-toc-item')[1].trigger('click')

      // 必须是原始对象：父组件靠 elementIndex / lineIndex 定位，
      // 面板重新拼一个对象很容易漏字段
      expect(wrapper.emitted('scrollTo')?.[0]).toEqual([items[1]])
    })

    it('点铅笔发 rename，携带对应 item', () => {
      const wrapper = mountPanel()

      wrapper.findAll('.ce-toc-row')[2].findAll('button')[1].trigger('click')

      expect(wrapper.emitted('rename')?.[0]).toEqual([items[2]])
    })

    it('点垃圾桶发 remove，携带对应 item', () => {
      const wrapper = mountPanel()

      wrapper.findAll('.ce-toc-row')[0].findAll('button')[2].trigger('click')

      expect(wrapper.emitted('remove')?.[0]).toEqual([items[0]])
    })

    it('面板不直接改正文 —— 三个写事件都只是派发', () => {
      const wrapper = mountPanel()

      wrapper.findAll('.ce-toc-row')[0].findAll('button')[1].trigger('click')
      wrapper.findAll('.ce-toc-row')[0].findAll('button')[2].trigger('click')

      // 没有任何 update:xxx 事件——正文的所有权在父组件，
      // 面板越权修改会绕过 removeHeadingIn 的越界保护
      const emitted = Object.keys(wrapper.emitted())
      expect(emitted.some(e => e.startsWith('update:'))).toBe(false)
    })

    it('标题头部的加号发 insert', () => {
      const wrapper = mountPanel()

      wrapper.find('.ce-side-head-action').trigger('click')

      expect(wrapper.emitted('insert')).toHaveLength(1)
    })
  })

  describe('空态', () => {
    it('无标题时展示可点的「添加第一个二级标题」', () => {
      const wrapper = mountPanel([])

      // 纯文字提示会让用户知道该做什么却不知道从哪做——
      // 大纲面板里并没有别的入口
      const empty = wrapper.find('.ce-toc-empty-action')
      expect(empty.exists()).toBe(true)
      expect(empty.text()).toContain('添加第一个二级标题')
    })

    it('空态点击同样发 insert 事件', () => {
      const wrapper = mountPanel([])

      wrapper.find('.ce-toc-empty-action').trigger('click')

      expect(wrapper.emitted('insert')).toHaveLength(1)
    })

    it('空态时不渲染列表容器', () => {
      const wrapper = mountPanel([])

      expect(wrapper.find('.ce-toc-list').exists()).toBe(false)
    })

    it('有标题时不渲染空态按钮', () => {
      const wrapper = mountPanel()

      expect(wrapper.find('.ce-toc-empty-action').exists()).toBe(false)
    })
  })
})
