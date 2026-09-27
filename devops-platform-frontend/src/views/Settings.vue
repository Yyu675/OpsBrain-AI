<script setup lang="ts">
/**
 * 设置页（/settings）——个人偏好与平台治理配置的统一入口。
 *
 * ── 为什么治理配置搬进这里 ────────────────────────────────────
 * 原「治理」下拉把配置类入口（模型渠道/自动化策略/白名单/风险等级/
 * 接入管理/审计日志）散落在导航层，与审批/自愈这类**日常处置队列**混在一起。
 * 现按职责分流：审批中心、自愈中心合并为「处置中心」顶栏入口
 * （值班要用、带待审角标，2026-09-27 并入 /disposal）；
 * 配置与审计类收进本页，标签互相隔离、可直链（?tab=xxx）。
 * 六个旧路由（/model-channels、/automation/*、/governance/audit-logs、
 * /integrations）全部重定向到对应标签，旧链接不死。
 *
 * ── 角色过滤 ──────────────────────────────────────────────────
 * 多数标签限 admin（后端另有 @SaCheckRole 兜底，这里只是体验层）。
 * 直链未授权标签静默回退「常规」，不弹 403——设置页本身不藏数据，
 * 藏的是「你没有权限修改平台配置」这个事实的入口。
 *
 * ── 为什么不用 keep-alive ─────────────────────────────────────
 * 接入管理等页挂了 30s 轮询。keep-alive 会让隐藏标签在后台继续打接口，
 * 离开标签即卸载，再进来重新拉——设置页是低频入口，重新拉取的成本
 * 远低于后台空转。
 */
import { computed, defineAsyncComponent, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Cpu, Gauge, History, Plug, Settings as SettingsIcon, ShieldCheck, Zap,
} from 'lucide-vue-next'
import { useAppStore } from '@/stores/app'
import GeneralSettingsPanel from '@/components/settings/GeneralSettingsPanel.vue'

defineOptions({ name: 'Settings' })

type Role = 'admin' | 'operator' | 'viewer' | 'guest'

interface SettingsTab {
  key: string
  label: string
  icon: Component
  /** 缺省=所有登录用户可见；标注则仅这些角色可见（路由层不再单独把守，这里兜底体验） */
  roles?: Role[]
  component: Component
}

/**
 * 标签内容统一异步加载：设置页七个面板只会有一个在屏，没必要全打进首屏包。
 *
 * 包装器**必须命名**（与目标组件同名）：一是 DevTools 里可读；
 * 二是测试能按名桩掉（VTU 按组件名匹配 stubs），否则桩会打在
 * 匿名包装器上、真实面板连带它的 API 依赖一起被加载。
 */
const asyncTab = (loader: () => Promise<unknown>, name: string): Component => {
  const wrapper = defineAsyncComponent({
    loader: loader as () => Promise<Component>,
    loadingComponent: {
      name: 'SettingsTabLoading',
      render: () => h('div', { class: 'tab-loading' }, '加载中…'),
    },
    delay: 120,
  })
  return Object.assign(wrapper, { name })
}

const ADMIN: Role[] = ['admin']

/**
 * 标签注册表。顺序即展示顺序：常规在前（人人可用），其后按 L1→L4 阶段排。
 * 新增配置能力在此登记一行即可，无需动模板。
 */
const TABS: SettingsTab[] = [
  { key: 'general', label: '常规', icon: SettingsIcon, component: GeneralSettingsPanel },
  { key: 'model-channels', label: '模型渠道', icon: Cpu, roles: ADMIN, component: asyncTab(() => import('@/views/ModelChannels.vue'), 'ModelChannels') },
  { key: 'automation-policies', label: '自动化策略', icon: Zap, roles: ADMIN, component: asyncTab(() => import('@/views/AutomationPolicies.vue'), 'AutomationPolicies') },
  { key: 'action-allowlist', label: '动作白名单', icon: ShieldCheck, roles: ADMIN, component: asyncTab(() => import('@/views/ActionAllowlist.vue'), 'ActionAllowlist') },
  { key: 'risk-levels', label: '风险等级', icon: Gauge, roles: ADMIN, component: asyncTab(() => import('@/views/RiskLevels.vue'), 'RiskLevels') },
  { key: 'integrations', label: '接入管理', icon: Plug, component: asyncTab(() => import('@/views/Integrations.vue'), 'Integrations') },
  { key: 'audit-logs', label: '审计日志', icon: History, roles: ADMIN, component: asyncTab(() => import('@/views/AuditLogs.vue'), 'AuditLogs') },
]

const route = useRoute()
const router = useRouter()
const app = useAppStore()

const visibleTabs = computed(() =>
  TABS.filter((t) => !t.roles || app.hasRole(t.roles))
)

/**
 * 当前标签：URL 是唯一事实来源（可直链、可分享、刷新不丢）。
 * 未给出/不认识/无权限的标签静默回退「常规」。
 */
const activeKey = computed(() => {
  const raw = typeof route.query.tab === 'string' ? route.query.tab : ''
  return visibleTabs.value.some((t) => t.key === raw) ? raw : 'general'
})

const activeComponent = computed(
  () => visibleTabs.value.find((t) => t.key === activeKey.value)?.component ?? GeneralSettingsPanel
)

const selectTab = (key: string) => {
  if (key === activeKey.value) return
  // 默认标签不写进 URL，保持地址栏干净（与 useUrlFilters 同一条纪律）
  void router.replace({ query: key === 'general' ? {} : { tab: key } })
}
</script>

<template>
  <div class="settings-page">
    <div class="settings-layout">
      <!-- 标签轨：窄屏时经 CSS 变为顶部横向滚动条，不换实现 -->
      <aside class="settings-rail" aria-label="设置导航">
        <div class="rail-title">设置</div>
        <button
          v-for="t in visibleTabs"
          :key="t.key"
          type="button"
          class="rail-item"
          :class="{ active: t.key === activeKey }"
          :aria-current="t.key === activeKey ? 'page' : undefined"
          @click="selectTab(t.key)"
        >
          <component :is="t.icon" :size="15" />
          <span>{{ t.label }}</span>
        </button>
      </aside>

      <!-- 内容区：各标签页自带页头与背景，这里只提供留白与滚动容器 -->
      <main class="settings-content">
        <component :is="activeComponent" />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.settings-page {
  min-height: 100vh;
  background: var(--surface-0);
}

.settings-layout {
  display: flex;
  align-items: flex-start;
  max-width: 1520px;
  margin: 0 auto;
}

/* ===== 标签轨 ===== */
.settings-rail {
  position: sticky;
  /* 顶栏 56px + 留白 */
  top: 72px;
  flex-shrink: 0;
  width: 188px;
  padding: 20px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rail-title {
  padding: 0 10px 10px;
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.rail-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: var(--radius);
  background: transparent;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  color: var(--text-2);
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease, color 0.15s ease;

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }

  &.active {
    background: var(--brand-subtle);
    color: var(--brand);
    font-weight: var(--weight-semibold);
  }
}

/* ===== 内容区 ===== */
.settings-content {
  flex: 1;
  min-width: 0;
}

/* 异步标签的加载占位 */
:deep(.tab-loading) {
  padding: 48px 24px;
  font-size: var(--text-sm);
  color: var(--text-3);
}

/* ===== 窄屏：标签轨变顶部横向滚动条 ===== */
@media (max-width: 768px) {
  .settings-layout {
    flex-direction: column;
  }

  .settings-rail {
    position: static;
    width: 100%;
    flex-direction: row;
    overflow-x: auto;
    padding: 12px;
    gap: 4px;
  }

  .rail-title {
    display: none;
  }

  .rail-item {
    width: auto;
    white-space: nowrap;
    flex-shrink: 0;
  }
}
</style>
