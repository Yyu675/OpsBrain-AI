<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { WifiOff, Wifi } from 'lucide-vue-next'
import { useNetworkHeartbeat } from '@/composables/useNetworkHeartbeat'

/**
 * 断网横幅：浏览器 online/offline + 心跳探测。
 * 仅监听事件无法发现「网通但后端不可达」；断网后心跳 fetch favicon，
 * 恢复后提示并停止轮询。
 */
const { online } = useNetworkHeartbeat({
  url: '/favicon.ico',
  intervalMs: 10000,
  timeoutMs: 4000
})

const recoveredVisible = ref(false)
let recoveredTimer: number | undefined

watch(online, (now, prev) => {
  if (now && prev === false) {
    recoveredVisible.value = true
    window.clearTimeout(recoveredTimer)
    recoveredTimer = window.setTimeout(() => {
      recoveredVisible.value = false
    }, 3500)
  }
  if (!now) {
    recoveredVisible.value = false
    window.clearTimeout(recoveredTimer)
  }
})

onBeforeUnmount(() => {
  window.clearTimeout(recoveredTimer)
})
</script>

<template>
  <transition name="network-slide">
    <div v-if="!online" class="network-banner network-banner-offline" role="status">
      <WifiOff :size="14" />
      <span>当前网络已断开，部分功能可能不可用</span>
    </div>
    <div v-else-if="recoveredVisible" class="network-banner network-banner-online" role="status">
      <Wifi :size="14" />
      <span>网络已恢复</span>
    </div>
  </transition>
</template>

<style scoped lang="scss">
.network-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08);
}

.network-banner-offline {
  background: #FEF2F2;
  color: #B91C1C;
  border-bottom: 1px solid #FCA5A5;
}

.network-banner-online {
  background: #ECFDF5;
  color: #047857;
  border-bottom: 1px solid #6EE7B7;
}

.network-slide-enter-active,
.network-slide-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.network-slide-enter-from,
.network-slide-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
