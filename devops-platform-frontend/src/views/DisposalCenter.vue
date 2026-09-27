<script setup lang="ts">
/**
 * 处置中心（/disposal）——「自动化走到需要人」的统一队列（2026-09-27 合并）。
 *
 * ── 为什么合并 ────────────────────────────────────────────────
 * 审批中心与自愈中心原是顶栏两个独立入口，但它们是同一件事的两面：
 * 自动化链路走到需要人来裁决/兜底的地方。值班 admin 的工作流是
 * 「处理待审批 → 看自愈执行得怎么样」，两个顶栏入口割裂了这个动线。
 * 现合并为标签页（复用 Settings 的标签模式），旧路由全部重定向保活：
 *   /approvals          → /disposal?tab=approvals
 *   /self-healing/tasks → /disposal?tab=healing
 *
 * ── 边界 ──────────────────────────────────────────────────────
 * 自愈执行详情（/self-healing/tasks/:id 一族）是独立详情页，不并入标签——
 * 它有面包屑与直达链接语义，从审批/告警页跳转落点不变。
 * 整页限 admin（路由 meta.roles 把守；后端 @SaCheckRole 是真正的安全边界）。
 */
import { computed, defineAsyncComponent, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ClipboardCheck, HeartPulse } from 'lucide-vue-next'
import { usePendingApprovalCountQuery } from '@/api/queries/approval.query'
import { useAppStore } from '@/stores/app'

defineOptions({ name: 'DisposalCenter' })

interface DisposalTab {
  key: string
  label: string
  icon: Component
  component: Component
}

/** 与 Settings 同一异步标签包装：命名包装器保证 DevTools 可读、测试可按名桩掉 */
const asyncTab = (loader: () => Promise<unknown>, name: string): Component => {
  const wrapper = defineAsyncComponent({
    loader: loader as () => Promise<Component>,
    loadingComponent: {
      name: 'DisposalTabLoading',
      render: () => h('div', { class: 'tab-loading' }, '加载中…'),
    },
    delay: 120,
  })
  return Object.assign(wrapper, { name })
}

const TABS: DisposalTab[] = [
  { key: 'approvals', label: '审批队列', icon: ClipboardCheck, component: asyncTab(() => import('@/views/ApprovalCenter.vue'), 'ApprovalCenter') },
  { key: 'healing', label: '自愈台账', icon: HeartPulse, component: asyncTab(() => import('@/views/HealingCenter.vue'), 'HealingCenter') },
]

const route = useRoute()
const router = useRouter()
const app = useAppStore()

// 待审角标（与 AppNavbar 同一查询缓存：审批决策后 invalidate 两边同步刷新）
const canSeeApprovals = computed(() => app.isAuthenticated && app.hasRole(['admin']))
const { count: approvalPending } = usePendingApprovalCountQuery(canSeeApprovals)

/** 当前标签：URL 是唯一事实来源（可直链、可分享、刷新不丢）；未知值回退审批队列 */
const activeKey = computed(() => {
  const raw = typeof route.query.tab === 'string' ? route.query.tab : ''
  return TABS.some((t) => t.key === raw) ? raw : 'approvals'
})

const activeComponent = computed(
  () => TABS.find((t) => t.key === activeKey.value)?.component ?? TABS[0].component
)

const selectTab = (key: string) => {
  if (key === activeKey.value) return
  // 默认标签不写进 URL，保持地址栏干净
  void router.replace({ query: key === 'approvals' ? {} : { tab: key } })
}
</script>

<template>
  <div class="disposal-page">
    <div class="disposal-layout">
      <aside class="disposal-rail" aria-label="处置中心导航">
        <div class="rail-title">处置中心</div>
        <button
          v-for="t in TABS"
          :key="t.key"
          type="button"
          class="rail-item"
          :class="{ active: t.key === activeKey }"
          :aria-current="t.key === activeKey ? 'page' : undefined"
          @click="selectTab(t.key)"
        >
          <component :is="t.icon" :size="15" />
          <span>{{ t.label }}</span>
          <span
            v-if="t.key === 'approvals' && approvalPending > 0"
            class="rail-badge"
            :title="`${approvalPending} 项待审批`"
          >{{ approvalPending > 99 ? '99+' : approvalPending }}</span>
        </button>
      </aside>

      <main class="disposal-content">
        <component :is="activeComponent" />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.disposal-page {
  min-height: 100vh;
  background: var(--surface-0);
}

.disposal-layout {
  display: flex;
  align-items: flex-start;
  max-width: 1520px;
  margin: 0 auto;
}

/* ===== 标签轨（与 Settings 同模式） ===== */
.disposal-rail {
  position: sticky;
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
  font-weight: 600;
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
  color: var(--text-2);
  cursor: pointer;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    color: var(--text-1);
  }

  &.active {
    background: var(--brand-subtle);
    color: var(--brand);
    font-weight: 600;
  }
}

.rail-badge {
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}

.disposal-content {
  flex: 1;
  min-width: 0;
}

:deep(.tab-loading) {
  padding: 48px 24px;
  font-size: var(--text-sm);
  color: var(--text-3);
}

/* ===== 窄屏：标签轨变顶部横向滚动条（同 Settings） ===== */
@media (max-width: 768px) {
  .disposal-layout {
    flex-direction: column;
  }

  .disposal-rail {
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

  .rail-badge {
    margin-left: 4px;
  }
}
</style>
