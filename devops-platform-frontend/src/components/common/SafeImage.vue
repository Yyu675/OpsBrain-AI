<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ImageOff } from 'lucide-vue-next'

interface Props {
  src: string
  alt?: string
  fallbackText?: string
  eager?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  alt: '',
  fallbackText: '',
  eager: false
})

const failed = ref(false)

watch(() => props.src, () => { failed.value = false })

const loading = computed<'eager' | 'lazy'>(() => (props.eager ? 'eager' : 'lazy'))
</script>

<template>
  <div class="safe-image" :class="{ 'safe-image-failed': failed }">
    <img
      v-if="!failed"
      :src="src"
      :alt="alt"
      :loading="loading"
      decoding="async"
      @error="failed = true"
    />
    <div v-else class="safe-image-fallback" role="img" :aria-label="alt || '图片加载失败'">
      <ImageOff :size="20" />
      <span v-if="fallbackText" class="safe-image-fallback-text">{{ fallbackText }}</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.safe-image {
  display: block;
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: cover;
  }
}

.safe-image-fallback {
  width: 100%;
  height: 100%;
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--color-bg-sunken);
  color: var(--color-text-tertiary);
  font-size: var(--text-xs);
}

.safe-image-fallback-text {
  color: var(--color-text-secondary);
}
</style>
