<script setup lang="ts">
/**
 * 首页（公开路由）——值班工作台的门面。
 *
 * ── 为什么是分发器而不是独立页面 ─────────────────────────────
 * 2026-09-27 去营销化：原首页是营销落地页（hero 图、「免费试用」、
 * 「加入数百家企业的选择」），与对内运维平台的定位不符。
 * 现改为：
 * - 登录用户：直接渲染值班工作台（Dashboard 组件：KPI / SLA 风险 /
 *   实时告警流 / 趋势）。同一组件单一事实源，不复制、不另起看板；
 * - 访客：只给一句话说明 + 登录入口 + 能力清单。不发任何受保护请求
 *   （/dashboard/overview 受 SaInterceptor 保护，访客调用必 401 →
 *   派发 auth:unauthorized → 访客被踢去登录页，公开首页形同虚设）。
 */
import { computed } from 'vue'
import { LogIn } from 'lucide-vue-next'
import Dashboard from '@/views/Dashboard.vue'
import { useAppStore } from '@/stores/app'

// keep-alive 按组件名匹配（App.vue include="Home"）——工作台状态随首页缓存
defineOptions({ name: 'Home' })

const app = useAppStore()
const isGuest = computed(() => !app.isAuthenticated)

/**
 * 访客态能力清单。点进去会触发登录引导弹窗（路由守卫的既定流程）——
 * 链接是诚实预告，不是死按钮。
 */
const capabilities = [
  { name: '智能工单', desc: '告警自动建单、SLA 跟踪、闭环处置与复盘沉淀', path: '/tickets' },
  { name: '告警事件', desc: 'Prometheus 告警接入、指纹去重、聚合降噪、自愈观察', path: '/alerts' },
  { name: '知识库', desc: '结构化复盘沉淀、语义检索、处置方案复用', path: '/knowledge' },
  { name: '监控中心', desc: '主机资源与抓取目标的实时态势与历史趋势', path: '/monitoring' },
]
</script>

<template>
  <!-- 登录用户：值班工作台（原数据概览内容，2026-09-27 升格为首页） -->
  <Dashboard v-if="!isGuest" />

  <!-- 访客：一句话说明 + 登录入口 + 能力预告，不做营销话术 -->
  <div v-else class="guest-home">
    <section class="guest-hero">
      <h1 class="guest-title">OpsBrain 智能运维平台</h1>
      <p class="guest-sub">告警 → 工单 → 知识沉淀的一体化处置闭环</p>
      <RouterLink to="/login" class="guest-login">
        <LogIn :size="16" />
        登录进入工作台
      </RouterLink>
    </section>

    <section class="guest-caps" aria-label="平台能力">
      <RouterLink
        v-for="c in capabilities"
        :key="c.name"
        :to="c.path"
        class="guest-cap"
      >
        <h2 class="guest-cap-name">{{ c.name }}</h2>
        <p class="guest-cap-desc">{{ c.desc }}</p>
      </RouterLink>
    </section>
  </div>
</template>

<style scoped lang="scss">
.guest-home {
  min-height: calc(100vh - 56px);
  background: var(--surface-0);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-12);
  padding: var(--space-12) var(--space-6);
}

.guest-hero {
  text-align: center;
}

.guest-title {
  margin: 0 0 var(--space-3);
  font-size: var(--text-3xl);
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: -0.02em;
}

.guest-sub {
  margin: 0 0 var(--space-8);
  font-size: var(--text-lg);
  color: var(--text-2);
}

.guest-login {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 40px;
  padding: 0 var(--space-6);
  border-radius: var(--radius);
  background: var(--brand);
  color: var(--brand-fg);
  font-size: var(--text-sm);
  font-weight: 600;
  text-decoration: none;
  transition: background var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--brand-hover);
  }
}

.guest-caps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-4);
  width: 100%;
  max-width: 960px;
}

.guest-cap {
  padding: var(--space-5);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  background: var(--surface-1);
  text-decoration: none;
  transition: border-color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);

  &:hover {
    border-color: var(--brand);
    transform: translateY(-2px);
  }
}

.guest-cap-name {
  margin: 0 0 var(--space-2);
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-1);
}

.guest-cap-desc {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--text-2);
}
</style>
