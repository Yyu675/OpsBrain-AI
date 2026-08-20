<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { relativeTime, formatAbsolute, parseDate } from '@/utils/time'

interface Props {
  value: string | number | Date | null | undefined
  absoluteFallback?: boolean
  refreshMs?: number
}

const props = withDefaults(defineProps<Props>(), {
  absoluteFallback: false,
  refreshMs: 60000
})

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const start = () => {
  if (timer) return
  timer = setInterval(() => { now.value = Date.now() }, props.refreshMs)
}

const stop = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(start)
onBeforeUnmount(stop)
watch(() => props.refreshMs, () => { stop(); start() })

const valid = computed(() => !!parseDate(props.value))
const rel = computed(() => relativeTime(props.value, now.value))
const abs = computed(() => formatAbsolute(props.value))

const label = computed(() => {
  if (!valid.value) return props.absoluteFallback ? String(props.value ?? '—') : '—'
  return rel.value
})
</script>

<template>
  <time :datetime="typeof value === 'string' ? value : undefined" :title="abs" class="relative-time">
    {{ label }}
  </time>
</template>

<style scoped>
.relative-time {
  cursor: help;
}
</style>
