/**
 * ModelChannels —— 渲染冒烟测试（阶段A-P0 只读展示页）。
 *
 * ── 为什么需要这一组 ──────────────────────────────────────────
 * `ModelChannels.vue` 是本仓最「新」的页面（阶段A 渠道可视化），
 * 后端契约已由 ModelChannelControllerWebTest（3 例）锁住——但那只证了
 * 「接口返回对」，不证「页面画对」。按批 28 教训：vm 层断言与渲染断言
 * 是两层皮，各自只证一半。
 *
 * ── 本页画错的代价 ────────────────────────────────────────────
 * 它是**渠道配置的控制面**。最需要锁住的三个点：
 *
 * <ol>
 *   <li><b>脱敏 key 展示</b>——后端只给 maskedKey 不给明文，页面绝不能
 *       自行还原/拼接（前端本就没有解密能力）；这里锁「展示的是脱敏串」。</li>
 *   <li><b>向量维度徽章</b>——embedding 的 dimension 是 RAG 链路健康的关键
 *       读数，展示错会让运维误判「检索维度不对」排查方向。</li>
 *   <li><b>四态齐全</b>——加载/空/错误/有数据，缺哪一态用户都得不到
 *       准确反馈。尤其空态不能把「没镜像过」说成「渠道崩了」。</li>
 * </ol>
 *
 * ── 分工 ──────────────────────────────────────────────────────
 * 后端契约测试证「接口给对」，本文件证「页面把渠道状态画对了」。
 * 这里不 stub DataStateBoundary——需要它真实执行四态逻辑，
 * 才能断言空态/错误态文案（stub 成只透 slot 会把这些文案吞掉）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  fetchModelChannels: vi.fn(),
  fetchChannelHistory: vi.fn(),
  rollbackModelChannel: vi.fn(),
  probeChannelCapabilities: vi.fn(),
  fetchChannelCapabilities: vi.fn(),
}))
vi.mock('@/api/modelChannels', () => api)

import ModelChannels from '../ModelChannels.vue'

/** 页面默认加载三个渠道的夹具。 */
function fixtureChannels() {
  return {
    channels: [
      {
        channelKey: 'chat',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        maskedKey: 'sk-ws-****7890',
        turboModel: 'qwen-turbo',
        reasonerModel: 'deepseek-v4-flash-0731',
        model: null,
        dimension: null,
        status: 'ACTIVE',
        updatedAt: '2026-09-20T10:00:00',
      },
      {
        channelKey: 'embedding',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        maskedKey: 'sk-ws-****1234',
        turboModel: null,
        reasonerModel: null,
        model: 'qwen3.7-text-embedding',
        dimension: 1536,
        status: 'ACTIVE',
        updatedAt: '2026-09-20T10:00:00',
      },
      {
        channelKey: 'reranker',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        maskedKey: null,
        turboModel: null,
        reasonerModel: null,
        model: 'qwen3-reranker-8b',
        dimension: null,
        status: 'ACTIVE',
        updatedAt: '2026-09-20T10:00:00',
      },
    ],
  }
}

function mountPage() {
  return mount(ModelChannels, {
    global: {
      stubs: {
        // el-tag 未注册时按未知元素渲染会丢 slot；stub 成 span 保住状态文本
        'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
        // el-dialog 同理：未知元素只渲染默认 slot，#footer 命名 slot 会被丢弃，
        // 导致「重新实测/保存」等按钮在断言里不可见——stub 把两个 slot 都铺平
        'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
      },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('有数据：三渠道卡片渲染', () => {
  it('渲染三张卡片，脱敏 key / 模型 / 维度徽章各就各位', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    const wrapper = mountPage()
    await flushPromises()

    const cards = wrapper.findAll('.channel-card')
    expect(cards).toHaveLength(3)

    // chat 卡片：turbo + reasoner 模型、脱敏 key
    const chat = wrapper.findAll('.channel-card')[0]
    expect(chat.text()).toContain('chat')
    expect(chat.text()).toContain('qwen-turbo')
    expect(chat.text()).toContain('deepseek-v4-flash-0731')
    // 脱敏 key：只出现 mask 后的串，绝不出现明文形态
    expect(chat.text()).toContain('sk-ws-****7890')
    expect(chat.text()).not.toContain('H.aaaabbbbcccc')
    expect(wrapper.text()).not.toMatch(/H\.[A-Za-z]+/)

    // embedding 卡片：model + 维度徽章（1536 是铁律关键读数）
    const emb = wrapper.findAll('.channel-card')[1]
    expect(emb.text()).toContain('embedding')
    expect(emb.text()).toContain('qwen3.7-text-embedding')
    const dimBadge = emb.find('.dimension-badge')
    expect(dimBadge.exists()).toBe(true)
    expect(dimBadge.text()).toBe('1536')

    // reranker：model 在、无 dimension
    const rerank = wrapper.findAll('.channel-card')[2]
    expect(rerank.text()).toContain('qwen3-reranker-8b')
    expect(rerank.find('.dimension-badge').exists()).toBe(false)

    // 状态标签：三渠道都是 ACTIVE
    expect(wrapper.text()).toContain('ACTIVE')
  })

  it('reranker 未配置 key 时展示 — 而非 null 字符', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    const wrapper = mountPage()
    await flushPromises()

    const rerank = wrapper.findAll('.channel-card')[2]
    // 脱敏 Key 行为空 → 页面用 dash() 显示为 —
    expect(rerank.text()).toContain('—')
    expect(rerank.text()).not.toContain('null')
  })

  it('chat 未配置备用模型时显示「未配置」提示（方案 A）', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    expect(chat.text()).toContain('备用模型')
    expect(chat.text()).toContain('未配置')
  })

  it('chat 已配置备用模型时显示备用模型名 tag（方案 A）', async () => {
    const data = fixtureChannels()
    data.channels[0] = {
      ...data.channels[0],
      fallbackBaseUrl: 'https://api.deepseek.com/v1',
      fallbackModel: 'deepseek-chat',
      fallbackMaskedKey: 'sk-fb-****5678',
    }
    api.fetchModelChannels.mockResolvedValue(data)
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    expect(chat.text()).toContain('deepseek-chat')
    // 备用密文/明文绝不展示，只有脱敏串
    expect(chat.text()).not.toContain('sk-fb-RealKey')
  })
})

describe('空态', () => {
  it('无渠道数据时显示「暂无渠道配置」而非空白', async () => {
    api.fetchModelChannels.mockResolvedValue({ channels: [] })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无渠道配置')
    expect(wrapper.findAll('.channel-card')).toHaveLength(0)
  })
})

describe('错误态', () => {
  it('接口失败显示错误并可重试', async () => {
    api.fetchModelChannels.mockRejectedValue(new Error('503 backend down'))
    const wrapper = mountPage()
    await flushPromises()

    // DataStateBoundary 错误态渲染（含"重试"标签），同时没有卡片
    expect(wrapper.text()).toContain('重试')
    expect(wrapper.findAll('.channel-card')).toHaveLength(0)

    // 重试：页面刷新按钮（@click=loadChannels）→ 重新拉取成功 → 渲染三渠道
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    wrapper.find('.btn-retry').trigger('click')
    await flushPromises()
    expect(api.fetchModelChannels).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.channel-card')).toHaveLength(3)
  })

  it('刷新按钮（页面级）重新拉取', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    const wrapper = mountPage()
    await flushPromises()
    expect(api.fetchModelChannels).toHaveBeenCalledTimes(1)

    wrapper.find('.btn-retry').trigger('click')
    await flushPromises()
    expect(api.fetchModelChannels).toHaveBeenCalledTimes(2)
  })
})

// ==================================================================

describe('V5 能力探测三态展示', () => {
  it('已探测渠道显示三态摘要徽标；UNKNOWN 不画成「不支持」', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    api.fetchChannelCapabilities.mockImplementation((key: string) => {
      if (key === 'chat') return Promise.resolve({
        channelKey: 'chat',
        capabilities: {
          chat: { state: 'SUPPORTED', detail: '基础对话正常' },
          streaming: { state: 'UNKNOWN', detail: '超时' },
        },
        probedAt: '2026-09-23T10:00:00',
      })
      return Promise.resolve(null)
    })
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    // 有 UNKNOWN → warning 态「1/2 项支持」，绝不能是「不支持」
    expect(chat.text()).toContain('1/2 项支持')
    const emb = wrapper.findAll('.channel-card')[1]
    expect(emb.text()).toContain('未实测')
  })

  it('打开能力探测 Dialog 显示三态明细（UNKNOWN 灰非红语义在场）', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    api.fetchChannelCapabilities.mockImplementation((key: string) => {
      if (key === 'chat') return Promise.resolve({
        channelKey: 'chat',
        capabilities: {
          function_calling: { state: 'UNSUPPORTED', detail: '上游明确不支持' },
          json_mode: { state: 'UNKNOWN', detail: '探测超时' },
        },
        probedAt: '2026-09-23T10:00:00',
      })
      return Promise.resolve(null)
    })
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    await chat.findAll('.tool-btn')[1].trigger('click')  // 能力探测按钮
    await flushPromises()

    const dialogText = wrapper.text()
    expect(dialogText).toContain('function_calling')
    expect(dialogText).toContain('不支持')
    expect(dialogText).toContain('未测成')
    expect(dialogText).toContain('2026-09-23 10:00')
    // 重测按钮在场（真实 API 调用，故为用户手动触发）
    expect(dialogText).toContain('重新实测')
  })
})

// ==================================================================

describe('V5 变更历史与回滚', () => {
  it('打开历史 Dialog 展示快照列表（id/说明/时间/操作人）', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    api.fetchChannelHistory.mockResolvedValue({
      history: [{
        id: 5, channelKey: 'chat', baseUrl: 'https://api.deepseek.com/v1',
        maskedKey: 'sk-ws-****7890', turboModel: 'qwen-turbo', reasonerModel: 'deepseek-v4-flash-0731',
        model: null, dimension: null, status: 'ACTIVE',
        fallbackBaseUrl: null, fallbackModel: null, fallbackMaskedKey: null,
        changedAt: '2026-09-22T10:00:00', changedBy: 'admin', changeNote: '编辑',
      }],
    })
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    await chat.findAll('.tool-btn')[0].trigger('click')  // 变更历史按钮
    await flushPromises()

    expect(api.fetchChannelHistory).toHaveBeenCalledWith('chat')
    const dialogText = wrapper.text()
    expect(dialogText).toContain('#5')
    expect(dialogText).toContain('编辑')
    expect(dialogText).toContain('admin')
    expect(dialogText).toContain('qwen-turbo / deepseek-v4-flash-0731')
    expect(dialogText).toContain('回滚到此版本')
  })

  it('空历史显示「暂无变更记录」而非空白', async () => {
    api.fetchModelChannels.mockResolvedValue(fixtureChannels())
    api.fetchChannelHistory.mockResolvedValue({ history: [] })
    const wrapper = mountPage()
    await flushPromises()

    const chat = wrapper.findAll('.channel-card')[0]
    await chat.findAll('.tool-btn')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无变更记录')
  })
})
