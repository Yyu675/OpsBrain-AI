/**
 * 文档属性面板测试。
 *
 * ── 这个组件的风险点不在「能不能显示」，在「状态归属」──────────
 * 它是受控组件：分类 / 标签 / 摘要 / 发布开关的真实状态都在父组件的
 * `formData` 上，面板只通过 v-model 读写。
 *
 * 这是刻意的设计——草稿自动暂存、离开确认、保存校验全都读那一份
 * `formData`。面板自己持有状态会立刻产生两份真相：
 * **用户改了分类却没进草稿**，刷新后改动消失，而且不会有任何报错。
 * 这是拆分子组件最典型的回归，所以本类第一组就测它。
 *
 * ── 第二个风险点：新建与编辑是两套完全不同的展示 ──────────────
 * 新建时最后一组是「保存后立即发布」开关；
 * 编辑时是「当前版本 vN + 变更说明输入框」。
 * 这两块用 v-if/v-else 切换，写反了的后果是：
 * 编辑已有文档时冒出一个发布开关，用户以为能一键发布，点了却没反应。
 */
import { describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

import type { KnowledgeCategoryEntity, KnowledgeTag } from '@/api/types'
import { MAX_TAGS } from '@/utils/editorContent'
import DocPropertiesPanel from '../DocPropertiesPanel.vue'

const categories: KnowledgeCategoryEntity[] = [
  { id: 1, parentId: null, name: '运维', sortOrder: 0, docCount: 0 },
  { id: 2, parentId: 1, name: '容器', sortOrder: 0, docCount: 0 },
]

const managedTags: KnowledgeTag[] = [
  { id: 1, name: 'k8s', description: '', color: '', usageCount: 3 },
]

function mountPanel(overrides: Record<string, unknown> = {}): VueWrapper {
  return mount(DocPropertiesPanel, {
    props: {
      category: '',
      tags: [],
      summary: '',
      publishOnCreate: false,
      changeReason: '',
      categories,
      managedTags,
      hotTags: [{ tag: 'mysql' }, { tag: 'redis' }],
      isNew: true,
      currentVersion: 1,
      isDraft: false,
      hasContent: true,
      ...overrides,
    },
    global: {
      stubs: {
        // Element Plus 的下拉在 jsdom 里依赖 popper，stub 掉让失败信号
        // 指向面板自己的逻辑，而不是第三方组件的渲染细节
        'el-select': { template: '<div class="el-select"><slot /></div>' },
        'el-option': { props: ['label', 'value'], template: '<div class="el-option" :label="label" />' },
        'el-switch': { template: '<div class="el-switch" />' },
        'el-input': { template: '<div class="el-input" />' },
      },
    },
  })
}

describe('DocPropertiesPanel', () => {
  describe('状态归属：改动必须通过 v-model 回传父组件', () => {
    it('摘要输入触发 update:summary —— 不回传就不会进草稿', () => {
      const wrapper = mountPanel()

      wrapper.find('textarea').setValue('这是摘要')

      // 面板自己存状态的话，用户改了摘要、刷新后消失，且没有任何报错
      expect(wrapper.emitted('update:summary')?.at(-1)).toEqual(['这是摘要'])
    })

    it('点热门标签发出 addTag 事件，而不是自己往 tags 里塞', () => {
      const wrapper = mountPanel()

      wrapper.findAll('.ce-hot-tag')[0].trigger('click')

      // 交给父组件处理：那里有重复校验与上限校验，
      // 面板自己 push 会绕过它们
      expect(wrapper.emitted('addTag')?.[0]).toEqual(['mysql'])
    })

    it('新建分类按钮只发事件，不自己弹窗', () => {
      const wrapper = mountPanel()

      wrapper.find('.ce-category-control button').trigger('click')

      expect(wrapper.emitted('createCategory')).toHaveLength(1)
    })

    it('自动生成摘要只发事件', () => {
      const wrapper = mountPanel()

      wrapper.find('.ce-auto-excerpt').trigger('click')

      expect(wrapper.emitted('generateSummary')).toHaveLength(1)
    })
  })

  describe('热门标签的禁用条件', () => {
    it('已添加过的标签被置灰', () => {
      const wrapper = mountPanel({ tags: ['mysql'] })

      const buttons = wrapper.findAll('.ce-hot-tag')
      expect((buttons[0].element as HTMLButtonElement).disabled).toBe(true)
      expect((buttons[1].element as HTMLButtonElement).disabled).toBe(false)
    })

    it('达到标签上限后全部置灰 —— 不置灰会让用户点了没反应还不知道为什么', () => {
      const full = Array.from({ length: MAX_TAGS }, (_, i) => `t${i}`)
      const wrapper = mountPanel({ tags: full })

      const buttons = wrapper.findAll('.ce-hot-tag')
      expect(buttons.every(b => (b.element as HTMLButtonElement).disabled)).toBe(true)
    })

    it('最多展示 8 个热门标签，多了会把面板撑爆', () => {
      const many = Array.from({ length: 20 }, (_, i) => ({ tag: `tag${i}` }))
      const wrapper = mountPanel({ hotTags: many })

      expect(wrapper.findAll('.ce-hot-tag')).toHaveLength(8)
    })

    it('无热门标签时整块不渲染，不留一个空的「热门：」标签', () => {
      const wrapper = mountPanel({ hotTags: [] })

      expect(wrapper.find('.ce-hot-tags').exists()).toBe(false)
    })
  })

  describe('新建与编辑是两套展示', () => {
    it('新建时显示发布开关，不显示版本信息', () => {
      const wrapper = mountPanel({ isNew: true })

      expect(wrapper.text()).toContain('保存后立即发布')
      expect(wrapper.text()).not.toContain('当前版本')
    })

    it('编辑时显示版本号与变更说明，不显示发布开关', () => {
      const wrapper = mountPanel({ isNew: false, currentVersion: 7 })

      // 写反的后果：编辑已有文档时冒出发布开关，
      // 用户以为能一键发布，点了却没反应
      expect(wrapper.text()).toContain('当前版本 v7')
      expect(wrapper.text()).not.toContain('保存后立即发布')
      expect(wrapper.find('.el-input').exists()).toBe(true)
    })

    it('编辑草稿时带「草稿」标记 —— 用户据此知道还没发布', () => {
      const wrapper = mountPanel({ isNew: false, isDraft: true })

      expect(wrapper.find('.ce-draft-tag').exists()).toBe(true)
    })

    it('已发布文档不显示草稿标记', () => {
      const wrapper = mountPanel({ isNew: false, isDraft: false })

      expect(wrapper.find('.ce-draft-tag').exists()).toBe(false)
    })
  })

  describe('摘要区', () => {
    it('实时显示字数，让用户知道离 200 上限还有多远', () => {
      const wrapper = mountPanel({ summary: '12345' })

      expect(wrapper.find('.ce-excerpt-count').text()).toContain('5/200')
    })

    it('说明留空会自动提取 —— 否则用户不知道摘要是可选的', () => {
      const wrapper = mountPanel()

      expect(wrapper.find('.ce-excerpt-count').text()).toContain('自动提取')
    })

    it('正文为空时不显示「自动生成」按钮', () => {
      const wrapper = mountPanel({ hasContent: false })

      // 显示了也点不出东西——没有正文就没法生成摘要，
      // 给一个必然失败的按钮不如不给
      expect(wrapper.find('.ce-auto-excerpt').exists()).toBe(false)
    })
  })

  describe('分类下拉', () => {
    it('展示完整路径而非仅分类名 —— 同名子分类否则无法区分', () => {
      const wrapper = mountPanel()

      // 直接取分类下拉里的 option label（标签下拉也用 el-option，
      // 所以按容器 .ce-category-control 限定范围）
      const labels = wrapper
        .find('.ce-category-control')
        .findAll('.el-option')
        .map(o => o.attributes('label'))

      expect(labels).toEqual(['运维', '运维 / 容器'])
    })

    it('标签上限展示在 label 上，用户提前知道限制', () => {
      const wrapper = mountPanel()

      expect(wrapper.text()).toContain(`最多 ${MAX_TAGS} 个`)
    })
  })
})
