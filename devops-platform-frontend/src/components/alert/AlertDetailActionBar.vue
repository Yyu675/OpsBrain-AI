<script setup lang="ts">
/**
 * 告警详情页头部动作条（从 AlertDetail.vue 拆出，行数红线纪律）。
 *
 * 汇集处置动作：刷新 / 误报标注（V17，再点即撤）/ 处置入口直链 / 确认 / 标记恢复。
 * 组件只做展示与事件上抛，业务动作留在父页（mutation/提示统一在父层）。
 *
 * 处置入口：注解里的 runbook_url/dashboard_url 提为直达按钮——此前 runbook
 * 躺在注解区只是不可点的文本，而「照着手册做」恰恰是处置时最需要的动作。
 */
import { computed } from 'vue'
import { Bell, CheckCircle, RefreshCw, ShieldAlert, BookOpen, LayoutDashboard } from 'lucide-vue-next'
import type { Alert } from '@/api/types'

const props = defineProps<{
  alert: Alert
  loading: boolean
  /** 任一处置 mutation 在途（父层合并三个 isPending） */
  acting: boolean
  canAcknowledge: boolean
  canResolve: boolean
}>()

const emit = defineEmits<{
  refresh: []
  acknowledge: []
  resolve: []
  toggleFalsePositive: []
}>()

/** 可点链接键（告警接入命名规范 9 键里的 URL 族）；值需 http(s) 前缀才认定为链接 */
const LINK_KEYS: { key: string; label: string; short: string }[] = [
  { key: 'runbook_url', label: 'Runbook 处置手册', short: 'Runbook' },
  { key: 'dashboard_url', label: '监控大盘', short: '大盘' },
]

/** 从 annotationsJson 提取 URL 族直链（防御性解析，非法 JSON 降级空数组） */
const annotationLinks = computed(() => {
  let entries: Record<string, unknown> = {}
  try {
    const obj = JSON.parse(props.alert.annotationsJson ?? '')
    if (obj && typeof obj === 'object' && !Array.isArray(obj)) entries = obj as Record<string, unknown>
  } catch { /* 非 JSON 视为无链接 */ }
  return LINK_KEYS
    .map(({ key, label, short }) => ({
      key, label, short, value: String(entries[key] ?? ''),
    }))
    .filter(e => /^https?:\/\//i.test(e.value))
})
</script>

<template>
  <div class="header-actions">
    <button class="btn-outline" type="button" :disabled="loading" @click="emit('refresh')">
      <RefreshCw :size="15" :class="{ spin: loading }" />
      刷新
    </button>
    <button
      class="btn-outline" type="button" v-permission.disable="{ codes: ['alert:write'] }"
      :class="{ 'btn-flagged': alert.falsePositive }"
      :disabled="acting"
      :title="alert.falsePositive ? '取消误报标记' : '标记为误报（反哺阈值优化）'"
      @click="emit('toggleFalsePositive')"
    >
      <ShieldAlert :size="15" />
      {{ alert.falsePositive ? '已标误报' : '误报' }}
    </button>
    <a
      v-for="l in annotationLinks" :key="l.key" :href="l.value"
      target="_blank" rel="noopener noreferrer" class="btn-outline action-link"
      :title="l.label"
    >
      <BookOpen v-if="l.key === 'runbook_url'" :size="15" />
      <LayoutDashboard v-else :size="15" />
      {{ l.short }}
    </a>
    <button
      class="btn-outline" type="button" v-permission.disable="{ codes: ['alert:write'] }"
      :disabled="!canAcknowledge || acting"
      @click="emit('acknowledge')"
    >
      <CheckCircle :size="15" />
      {{ alert.status === 'ACKNOWLEDGED' ? '已确认' : '确认告警' }}
    </button>
    <button
      class="btn-primary" type="button" v-permission.disable="{ codes: ['alert:write'] }"
      :disabled="!canResolve || acting"
      @click="emit('resolve')"
    >
      <Bell :size="15" />
      {{ alert.status === 'RESOLVED' ? '已恢复' : '标记恢复' }}
    </button>
  </div>
</template>

<style scoped lang="scss">
/* 与 AlertDetail 页内按钮同一视觉（scoped 不继承，样式随组件走） */
.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-outline,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--radius);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--duration-fast, 0.15s) ease;
  white-space: nowrap;

  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

.btn-outline {
  border: 1px solid var(--border-1);
  background: var(--surface-1);
  color: var(--text-1);

  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}

.btn-primary {
  border: 1px solid var(--brand);
  background: var(--brand);
  color: var(--text-inverse);

  &:hover:not(:disabled) { filter: brightness(1.06); }
}

/* 误报按钮的报警态（V17）：warning 语义——不是危险，是「规则在被投票」 */
.btn-flagged {
  border-color: var(--warning);
  color: var(--warning);
}

/* 处置入口链接：与 .btn-outline 同视觉（锚元素补齐内衬） */
.action-link { text-decoration: none; }

.spin { animation: spin 1s linear infinite; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
