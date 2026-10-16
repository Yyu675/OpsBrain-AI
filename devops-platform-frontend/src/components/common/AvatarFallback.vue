<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  name: string
  size?: number
  color?: string
}

const props = withDefaults(defineProps<Props>(), {
  size: 32,
  color: ''
})

const palette = ['#1B4F9C', '#0891B2', '#7C3AED', '#DB2777', '#F59E0B', '#16A34A', '#DC2626', '#4B5563']

const hashCode = (s: string): number => {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) - h) + s.charCodeAt(i)
    h |= 0
  }
  return Math.abs(h)
}

const bg = computed(() => props.color || palette[hashCode(props.name || '?') % palette.length])

const initial = computed(() => {
  const trimmed = (props.name || '').trim()
  return trimmed ? trimmed.charAt(0).toUpperCase() : '?'
})

const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.max(10, Math.floor(props.size * 0.42))}px`,
  background: bg.value
}))
</script>

<template>
  <span class="avatar-fallback" :style="style" :title="name">
    {{ initial }}
  </span>
</template>

<style scoped>
.avatar-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  font-weight: 600;
  flex-shrink: 0;
  user-select: none;
}
</style>
