<script setup lang="ts">
import { Clock3, Layers3, ShieldCheck } from 'lucide-vue-next'

withDefaults(defineProps<{
  stage: string
  title: string
  description: string
  status?: string
  capabilities?: string[]
}>(), {
  status: '规划中',
  capabilities: () => []
})
</script>

<template>
  <section class="coming-soon" aria-labelledby="coming-soon-title">
    <div class="stage-badge">{{ stage }}</div>
    <div class="icon-wrap"><Layers3 :size="30" /></div>
    <p class="eyebrow">能力建设路线图</p>
    <h1 id="coming-soon-title">{{ title }}</h1>
    <p class="description">{{ description }}</p>

    <div v-if="capabilities.length" class="capabilities" aria-label="规划能力">
      <div v-for="item in capabilities" :key="item" class="capability-item">
        <ShieldCheck :size="17" />
        <span>{{ item }}</span>
      </div>
    </div>

    <div class="status-line">
      <Clock3 :size="16" />
      <span>{{ status }}，当前页面仅用于路由与信息架构占位</span>
    </div>
  </section>
</template>

<style scoped>
.coming-soon {
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  padding: 64px 56px;
  text-align: center;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-card, 14px);
  box-shadow: var(--shadow-card, var(--shadow-sm));
  overflow: hidden;
}
.coming-soon::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 4px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-primary-light));
}
.stage-badge {
  position: absolute;
  top: 20px;
  right: 20px;
  padding: 5px 10px;
  border-radius: var(--radius-full);
  color: var(--color-primary);
  background: var(--color-primary-lighter);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
}
.icon-wrap {
  width: 64px;
  height: 64px;
  margin: 0 auto 20px;
  display: grid;
  place-items: center;
  color: var(--color-primary);
  background: var(--color-primary-lighter);
  border-radius: 18px;
}
.eyebrow { margin: 0 0 8px; color: var(--color-primary); font-size: var(--text-sm); font-weight: var(--weight-semibold); }
h1 { margin: 0; color: var(--color-text-primary); font-size: var(--text-3xl); line-height: var(--leading-tight); }
.description { max-width: 590px; margin: 18px auto 0; color: var(--color-text-secondary); line-height: var(--leading-relaxed); }
.capabilities { margin: 32px 0 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; text-align: left; }
.capability-item { display: flex; gap: 9px; align-items: center; padding: 13px 14px; color: var(--color-text-secondary); background: var(--color-bg); border-radius: var(--radius-md); font-size: var(--text-sm); }
.capability-item svg { flex: none; color: var(--state-success); }
.status-line { margin-top: 32px; padding-top: 22px; border-top: 1px solid var(--color-border-light); display: flex; justify-content: center; align-items: center; gap: 8px; color: var(--color-text-tertiary); font-size: var(--text-sm); }
@media (max-width: 767px) {
  .coming-soon { padding: 52px 22px 36px; text-align: left; }
  .icon-wrap { margin-left: 0; }
  h1 { font-size: var(--text-2xl); }
  .description { margin-left: 0; }
  .capabilities { grid-template-columns: 1fr; }
  .status-line { justify-content: flex-start; align-items: flex-start; }
}
</style>

