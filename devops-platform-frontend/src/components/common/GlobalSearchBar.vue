<script setup lang="ts">
/**
 * GlobalSearchBar — 跨模块全局搜索（批88 P1）
 *
 * 在 AppNavbar 中渲染一个搜索框，输入关键词后调用 /api/v1/search?q=，
 * 下拉展示工单/知识库/告警匹配结果。选中后导航到对应详情页。
 *
 * 实现要点：
 * - 300ms 防抖，避免每次按键都发请求
 * - Esc 关闭下拉、Enter 导航到首个结果
 * - kind 图标语义：工单=#、知识=📄、告警=🔔（Unicode，零依赖）
 */
import { ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '@/utils/http'

const router = useRouter()
const q = ref('')
const results = ref<Array<{ id: string; title: string; kind: string; sub: string }>>([])
const open = ref(false)
const selectedIdx = ref(-1)
const searching = ref(false)

let timer: ReturnType<typeof setTimeout> | null = null

const kindIcon = (k: string) => k === 'ticket' ? '#' : k === 'knowledge' ? '📄' : '🔔'
const kindLabel = (k: string) => k === 'ticket' ? '工单' : k === 'knowledge' ? '知识库' : '告警'
const navPath = (r: { kind: string; id: string }) =>
  r.kind === 'ticket' ? `/tickets/${r.id}` : r.kind === 'knowledge' ? `/knowledge/${r.id}` : `/alerts/${r.id}`

const search = async () => {
  const term = q.value.trim()
  if (!term || term.length < 1) { results.value = []; open.value = false; return }
  searching.value = true
  try {
    const resp = await http.get<typeof results.value>(`/api/v1/search?q=${encodeURIComponent(term)}`)
    results.value = resp ?? []
    open.value = (resp ?? []).length > 0
    selectedIdx.value = -1
  } catch { /* 搜索失败无声降级，不弹 toast 干扰用户 */ } finally { searching.value = false }
}

watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(search, 300)
})

const select = (r: typeof results.value[0]) => {
  open.value = false; q.value = ''; router.push(navPath(r))
}

const onKeydown = (e: KeyboardEvent) => {
  if (!open.value) return
  if (e.key === 'ArrowDown') { e.preventDefault(); selectedIdx.value = Math.min(selectedIdx.value + 1, results.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); selectedIdx.value = Math.max(selectedIdx.value - 1, -1) }
  else if (e.key === 'Enter' && selectedIdx.value >= 0) { e.preventDefault(); select(results.value[selectedIdx.value]) }
  else if (e.key === 'Escape') { open.value = false }
}

const onBlur = () => { nextTick(() => { if (!document.activeElement?.closest('.gsearch')) open.value = false }) }
</script>

<template>
  <div class="gsearch" @keydown="onKeydown">
    <input
      v-model="q"
      class="gsearch-input"
      type="text"
      placeholder="搜索工单 / 知识 / 告警…"
      autocomplete="off"
      @focus="q.trim() && results.length && (open = true)"
      @blur="onBlur"
    />
    <span v-if="searching" class="gsearch-spinner" />

    <transition name="gsearch-fade">
      <div v-if="open && results.length" class="gsearch-dropdown">
        <div
          v-for="(r, i) in results" :key="`${r.kind}-${r.id}`"
          class="gsearch-item"
          :class="{ 'gsearch-item--sel': i === selectedIdx }"
          @mousedown.prevent="select(r)"
        >
          <span class="gsearch-kind">{{ kindIcon(r.kind) }}</span>
          <span class="gsearch-title">{{ r.title }}</span>
          <span class="gsearch-tag">{{ kindLabel(r.kind) }}</span>
          <span v-if="r.sub" class="gsearch-sub">{{ r.sub }}</span>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.gsearch { position: relative; width: 260px; }
.gsearch-input {
  width: 100%; height: 32px; padding: 0 32px 0 12px; border: 1px solid var(--border-1);
  border-radius: 6px; font-size: 13px; background: var(--surface-2); outline: none;
  transition: border-color .2s;
}
.gsearch-input:focus { border-color: var(--brand, #2563eb); background: var(--surface-1, #fff); }
.gsearch-spinner {
  position: absolute; right: 10px; top: 8px; width: 14px; height: 14px;
  border: 2px solid #d9d9d9; border-top-color: var(--brand, #2563eb);
  border-radius: 50%; animation: gs-spin .6s linear infinite;
}
@keyframes gs-spin { to { transform: rotate(360deg); } }
.gsearch-dropdown {
  position: absolute; top: 38px; left: 0; right: 0; background: var(--surface-1, #fff);
  border: 1px solid var(--border-1); border-radius: 8px; box-shadow: 0 4px 16px rgba(0,0,0,.1);
  max-height: 360px; overflow-y: auto; z-index: 2000;
}
.gsearch-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px; cursor: pointer; font-size: 13px;
  border-bottom: 1px solid var(--border-1);
}
.gsearch-item:last-child { border-bottom: none; }
.gsearch-item:hover, .gsearch-item--sel { background: rgba(37,99,235,.06); }
.gsearch-kind { flex-shrink: 0; width: 22px; text-align: center; font-size: 13px; }
.gsearch-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-1); }
.gsearch-tag { flex-shrink: 0; font-size: 11px; padding: 1px 6px; border-radius: 3px; background: rgba(37,99,235,.1); color: var(--brand, #2563eb); }
.gsearch-sub { flex-shrink: 0; font-size: 11px; color: var(--text-3, #909399); max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gsearch-fade-enter-active, .gsearch-fade-leave-active { transition: opacity .15s; }
.gsearch-fade-enter-from, .gsearch-fade-leave-to { opacity: 0; }
</style>