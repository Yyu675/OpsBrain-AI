/**
 * 设置页（/settings）组件测试。
 *
 * ── 为什么测这个 ──────────────────────────────────────────────
 * 设置页是治理配置的**唯一入口**（原治理下拉已撤销，旧路由全部
 * 重定向到这里）。它出问题 = 管理员改不了模型渠道、看不了审计日志。
 *
 * ── 覆盖重点 ──────────────────────────────────────────────────
 * 1. **标签按角色过滤**。多数标签限 admin（后端另有 @SaCheckRole 兜底，
 *    这里是体验层）——普通值班人不该看到一排点进去全是 403 的标签。
 * 2. **URL 是唯一事实来源**。?tab= 可直链/可分享/刷新不丢；
 *    不认识或无权限的标签静默回退「常规」，不弹错、不白屏。
 * 3. **默认标签不污染 URL**。?tab=general 等价于不带参，地址栏保持干净。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

// 标签内容页全部按名桩掉（包装器与目标组件同名，VTU 按名匹配 stubs，
// 加载器根本不会触发）：本测试守的是「壳」（标签轨 + 路由同步 + 角色过滤），
// 各面板有自己的测试，不该在这里连带加载它们的 API 依赖。
// 此前试过 vi.mock 各视图模块——VTU 给异步组件建桩时会持续探测模块
// 命名空间上的 Vue 内部字段（__v_isVNode 等），桩不胜桩，故放弃该路线。
const TAB_STUBS = Object.fromEntries(
  ['ModelChannels', 'AutomationPolicies', 'ActionAllowlist', 'RiskLevels', 'Integrations', 'AuditLogs']
    .map((name) => [name, { template: `<div class="tab-stub">${name}</div>` }]),
)

const appStore = vi.hoisted(() => ({
  currentUser: { role: 'admin' as string },
  hasRole(roles?: string[]) {
    if (!roles || roles.length === 0) return true
    return roles.includes(this.currentUser.role)
  },
}))
vi.mock('@/stores/app', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/stores/app')
  return { ...actual, useAppStore: () => appStore }
})

import Settings from '../Settings.vue'

let router: Router

const mountPage = async (url = '/settings', role = 'admin') => {
  appStore.currentUser.role = role

  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings', component: Settings }],
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(Settings, {
    global: {
      plugins: [router],
      stubs: {
        GeneralSettingsPanel: { template: '<div class="tab-stub">常规</div>' },
        ...TAB_STUBS,
      },
    },
  })
  await flushPromises()
  return wrapper
}

const railLabels = (w: Awaited<ReturnType<typeof mountPage>>) =>
  w.findAll('.rail-item').map((el) => el.text())

beforeEach(() => {
  vi.clearAllMocks()
})

describe('Settings — 标签轨与角色过滤', () => {
  it('默认落在「常规」标签', async () => {
    const w = await mountPage()
    expect(w.find('.rail-item.active').text()).toContain('常规')
    expect(w.find('.tab-stub').text()).toBe('常规')
  })

  it('管理员看到全部七个标签', async () => {
    const w = await mountPage('/settings', 'admin')
    expect(railLabels(w)).toEqual([
      '常规', '模型渠道', '自动化策略', '动作白名单', '风险等级', '接入管理', '审计日志',
    ])
  })

  it('普通值班人只看到「常规」与「接入管理」——admin 标签整个不渲染', async () => {
    // 比「点进去 403」好：看不见进不去，而不是看得见吃闭门羹
    const w = await mountPage('/settings', 'operator')
    expect(railLabels(w)).toEqual(['常规', '接入管理'])
  })
})

describe('Settings — URL 同步', () => {
  it('?tab= 直链渲染对应面板（旧路由重定向的落点）', async () => {
    const w = await mountPage('/settings?tab=audit-logs')
    await vi.waitFor(() => expect(w.find('.tab-stub').text()).toBe('AuditLogs'))
    expect(w.find('.rail-item.active').text()).toContain('审计日志')
  })

  it('不认识的标签回退「常规」，不白屏不报错', async () => {
    const w = await mountPage('/settings?tab=nonexistent')
    expect(w.find('.rail-item.active').text()).toContain('常规')
  })

  it('直链无权限的标签回退「常规」', async () => {
    // URL 可被手改；回退而非 403——设置页不藏数据，藏的是入口
    const w = await mountPage('/settings?tab=audit-logs', 'operator')
    expect(w.find('.rail-item.active').text()).toContain('常规')
    expect(w.find('.tab-stub').text()).toBe('常规')
  })

  it('点击标签写回 URL；点回「常规」则清掉参数', async () => {
    const w = await mountPage()

    await w.findAll('.rail-item').find((el) => el.text().includes('接入管理'))!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('integrations')
    await vi.waitFor(() => expect(w.find('.tab-stub').text()).toBe('Integrations'))

    await w.findAll('.rail-item').find((el) => el.text().includes('常规'))!.trigger('click')
    await flushPromises()
    // 默认标签不写进 URL，保持地址栏干净
    expect(router.currentRoute.value.query.tab).toBeUndefined()
    expect(w.find('.rail-item.active').text()).toContain('常规')
  })

  it('重复点击当前标签不触发导航', async () => {
    const w = await mountPage()
    const replaceSpy = vi.spyOn(router, 'replace')
    replaceSpy.mockClear()

    await w.findAll('.rail-item').find((el) => el.text().includes('常规'))!.trigger('click')

    expect(replaceSpy).not.toHaveBeenCalled()
  })
})
