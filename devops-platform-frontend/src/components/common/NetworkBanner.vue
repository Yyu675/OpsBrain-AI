<script setup lang="ts">
import { ref, watch } from 'vue'
import { WifiOff, Wifi, Database } from 'lucide-vue-next'
import { useNetworkHeartbeat } from '@/composables/useNetworkHeartbeat'
import { API_BASE } from '@/config/api'

/**
 * 断网/基础设施故障横幅。
 * - 浏览器离线 + 心跳探测失败 → 断网横幅
 * - 网络正常但后端 DB 探测失败 → 基础设施故障横幅（区分于断网）
 */
const { online, infraHealthy } = useNetworkHeartbeat({
  url: '/favicon.ico',
  intervalMs: 10000,
  timeoutMs: 4000,
  // 后端 DB 连通性探针（免鉴权、零成本、返回 UP/DOWN）。
  // 必须用 API_BASE（绝对后端地址）而非相对路径——相对路径会打到前端自身。
  infraUrl: `${API_BASE}/health/db`,
  infraIntervalMs: 15000
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
</script>

<template>
  <transition name="network-slide">
    <!-- 网络断开 -->
    <div v-if="!online" class="network-banner network-banner-offline" role="status">
      <WifiOff :size="14" />
      <span>当前网络已断开，部分功能可能不可用</span>
    </div>
    <!-- 网络正常但后端基础设施（DB/Redis）故障 -->
    <div v-else-if="!infraHealthy" class="network-banner network-banner-infra" role="status">
      <Database :size="14" />
      <span>后端数据服务异常（数据库/缓存），部分查询可能不可用</span>
    </div>
    <!-- 网络刚恢复 -->
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
  background: var(--danger-subtle);
  color: var(--danger);
  border-bottom: 1px solid #FCA5A5;
}

.network-banner-infra {
  background: #FEF3C7;
  color: #B45309;
  border-bottom: 1px solid #FDE68A;
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
