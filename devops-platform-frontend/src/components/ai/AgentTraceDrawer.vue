<script setup lang="ts">
/**
 * Agent 执行轨迹抽屉。
 *
 * 回答排障时的第一个问题：**这次对话到底走到哪一步、慢在哪、为什么没走完。**
 *
 * 与「AI 调用日志」的分工：那边看的是落库的成本与问答原文，
 * 这边看的是内存里的状态机轨迹（30 分钟空闲即清理）。
 *
 * <h3>为什么「查不到」要分两种说法</h3>
 * `found=false`（会话已被清理 / traceId 有误）与 `found=true` 但零迁移
 * （流程真卡在最开始）都表现为空列表，含义却完全相反。
 * 前者该去别处查，后者是**真实故障信号**。
 * 若都渲染成同一个「暂无数据」，会把人引向错误的排查方向——
 * 所以这里刻意分成两套文案与两种图标。
 */
import { computed, ref, watch } from 'vue'
import {
  agentStateColor,
  fetchAgentTrace,
  formatDuration,
  slowestTransition,
  type AgentTraceDetail,
  type AgentTransitionItem,
} from '@/api/agentTrace'

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  /** 要查询的会话追踪 ID。为空时不发请求 */
  traceId: string | null
}>()

const loading = ref(false)
const error = ref('')
const trace = ref<AgentTraceDetail | null>(null)

/**
 * 耗时最长的一段，用于「慢在哪」的一眼定位。
 *
 * 只在有两段以上时才提示：单段轨迹里「最慢的一段」就是它自己，
 * 标出来没有信息量，反而像是在报警。
 */
const slowest = computed<AgentTransitionItem | null>(() => {
  const list = trace.value?.transitions ?? []
  if (list.length < 2) return null
  return slowestTransition(list)
})

/** 空状态的类型——决定显示哪套文案 */
const emptyKind = computed<'none' | 'missing' | 'no-transition'>(() => {
  const t = trace.value
  if (!t) return 'none'
  if (!t.found) return 'missing'
  if (!t.transitions.length) return 'no-transition'
  return 'none'
})

async function load() {
  const id = props.traceId
  if (!id) return
  loading.value = true
  error.value = ''
  // 不清空 trace：加载中保留上一次内容，避免抽屉闪一下白屏
  try {
    trace.value = await fetchAgentTrace(id)
  } catch (e) {
    // 接口挂了必须说出来。吞成空轨迹会让人以为「流程没跑」，
    // 而实际是「没查到」——这两件事的排查方向完全相反
    error.value = e instanceof Error ? e.message : '获取执行轨迹失败'
    trace.value = null
  } finally {
    loading.value = false
  }
}

// 打开时加载；traceId 变了也要重新拉（同一抽屉可能被复用于不同会话）
watch(
  () => [visible.value, props.traceId] as const,
  ([open]) => {
    if (open) load()
  },
  { immediate: true }
)

defineExpose({ load })
</script>

<template>
  <el-drawer v-model="visible" title="执行轨迹" size="720px">
    <div class="trace-drawer">
      <div v-if="loading" class="trace-hint">加载中…</div>

      <div v-else-if="error" class="trace-hint trace-hint-error">
        {{ error }}
        <button class="link-btn" type="button" @click="load">重试</button>
      </div>

      <!-- 会话不存在：已被清理或 traceId 有误 -->
      <div v-else-if="emptyKind === 'missing'" class="trace-hint">
        <p class="trace-empty-title">查不到这条会话</p>
        <p class="trace-empty-desc">
          {{ trace?.message || '可能已超过 30 分钟空闲期被清理，或 traceId 有误。' }}
        </p>
        <p class="trace-empty-desc">
          长期记录请查「AI 调用日志」，那边按 traceId 落库保存。
        </p>
      </div>

      <!-- 会话在，但一次迁移都没发生：这是真实故障信号 -->
      <div v-else-if="emptyKind === 'no-transition'" class="trace-hint trace-hint-warn">
        <p class="trace-empty-title">会话已创建，但流程没有推进</p>
        <p class="trace-empty-desc">
          当前停在「{{ trace?.currentStateText || trace?.currentState }}」，
          尚未发生任何状态迁移——通常意味着请求在最开始就被中断了。
        </p>
      </div>

      <template v-else-if="trace">
        <!-- 概览 -->
        <div class="trace-summary">
          <div class="trace-summary-item">
            <span class="trace-label">当前状态</span>
            <span class="trace-state" :class="`is-${agentStateColor(trace.currentState)}`">
              {{ trace.currentStateText || trace.currentState }}
            </span>
          </div>
          <div class="trace-summary-item">
            <span class="trace-label">环节数</span>
            <span class="trace-value">{{ trace.transitionCount }}</span>
          </div>
          <div class="trace-summary-item">
            <span class="trace-label">总耗时</span>
            <span class="trace-value">{{ formatDuration(trace.totalDurationMs) }}</span>
          </div>
        </div>

        <p v-if="slowest" class="trace-slowest">
          最慢一段：{{ slowest.toStateText || slowest.toState }}
          （{{ formatDuration(slowest.durationMs) }}）
        </p>

        <!-- 时间轴 -->
        <ol class="trace-timeline">
          <li
            v-for="(t, i) in trace.transitions"
            :key="t.id || i"
            class="trace-node"
            :class="{ 'is-slowest': slowest && t.id === slowest.id }"
          >
            <span class="trace-dot" :class="`is-${agentStateColor(t.toState)}`"></span>
            <div class="trace-body">
              <div class="trace-head">
                <span class="trace-to">{{ t.toStateText || t.toState }}</span>
                <span class="trace-duration">{{ formatDuration(t.durationMs) }}</span>
              </div>
              <div class="trace-detail">{{ t.triggerDetail }}</div>
              <div class="trace-meta">
                <span>{{ t.fromStateText || t.fromState }} → {{ t.toStateText || t.toState }}</span>
                <span v-if="t.timestamp">{{ t.timestamp }}</span>
              </div>
            </div>
          </li>
        </ol>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.trace-drawer {
  color: var(--color-text-primary, var(--text-1));
  font-size: 13px;
}

.trace-hint {
  padding: 24px 8px;
  color: var(--color-text-secondary, var(--text-2));
  line-height: 1.7;
}
.trace-hint-error { color: var(--color-danger, #DC2626); }
.trace-hint-warn { color: var(--color-warning, #D97706); }

.trace-empty-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--color-text-primary, var(--text-1));
}
.trace-empty-desc { margin: 0 0 4px; }

.trace-summary {
  display: flex;
  gap: 28px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--color-bg-sunken, #F9FAFB);
  margin-bottom: 10px;
}
.trace-summary-item { display: flex; flex-direction: column; gap: 4px; }
.trace-label { font-size: 12px; color: var(--color-text-secondary, var(--text-2)); }
.trace-value { font-weight: 600; }

.trace-state { font-weight: 600; }
.trace-state.is-success { color: var(--color-success, #16A34A); }
.trace-state.is-danger { color: var(--color-danger, #DC2626); }
.trace-state.is-warning { color: var(--color-warning, #D97706); }
.trace-state.is-primary { color: var(--color-primary, var(--brand)); }
.trace-state.is-gray { color: var(--color-text-secondary, var(--text-2)); }

.trace-slowest {
  margin: 0 0 14px;
  font-size: 12px;
  color: var(--color-warning, #D97706);
}

.trace-timeline { list-style: none; margin: 0; padding: 0; }

.trace-node {
  position: relative;
  padding: 0 0 16px 20px;
  border-left: 1px solid var(--color-border, var(--border-1));
}
.trace-node:last-child { border-left-color: transparent; padding-bottom: 0; }

.trace-dot {
  position: absolute;
  left: -5px;
  top: 3px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--color-text-secondary, var(--text-2));
}
.trace-dot.is-success { background: var(--color-success, #16A34A); }
.trace-dot.is-danger { background: var(--color-danger, #DC2626); }
.trace-dot.is-warning { background: var(--color-warning, #D97706); }
.trace-dot.is-primary { background: var(--color-primary, var(--brand)); }
.trace-dot.is-gray { background: var(--color-text-secondary, var(--text-2)); }

.trace-body { display: flex; flex-direction: column; gap: 3px; }
.trace-head { display: flex; align-items: baseline; gap: 10px; }
.trace-to { font-weight: 600; }
.trace-duration { font-size: 12px; color: var(--color-text-secondary, var(--text-2)); }
.trace-detail { word-break: break-word; }
.trace-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--color-text-secondary, var(--text-2));
}

.trace-node.is-slowest .trace-duration {
  color: var(--color-warning, #D97706);
  font-weight: 600;
}

.link-btn {
  background: none;
  border: none;
  padding: 0 0 0 8px;
  color: var(--color-primary, var(--brand));
  cursor: pointer;
  font-size: 13px;
}
</style>
