import { ref, onMounted, onBeforeUnmount } from 'vue'

interface HeartbeatOptions {
  url?: string
  intervalMs?: number
  timeoutMs?: number
  /** 浏览器在线时是否仍周期性探测后端基础设施健康（默认 true） */
  probeInfra?: boolean
  /** 基础设施健康探测端点（零成本、免鉴权） */
  infraUrl?: string
  infraIntervalMs?: number
}

/**
 * 网络 + 基础设施心跳探测。
 *
 * 三层判定：
 * 1. 浏览器 `online/offline` 事件（网络通断）
 * 2. 断网后心跳探测 `url`（网通但不可达前端资源）
 * 3. `infraHealthy`：浏览器在线时仍周期性探测后端基础设施健康端点，
 *    —— 用于识别「网络正常但 DB/Redis/后端整体故障」的隐蔽场景，
 *    避免用户看到页面正常、实际所有接口 500 却毫无提示。
 */
export const useNetworkHeartbeat = (opts: HeartbeatOptions = {}) => {
  const {
    url = '/favicon.ico',
    intervalMs = 10000,
    timeoutMs = 4000,
    probeInfra = true,
    infraUrl = '/api/v1/health/db',
    infraIntervalMs = 15000
  } = opts

  const online = ref(typeof navigator !== 'undefined' ? navigator.onLine : true)
  const infraHealthy = ref(true)
  const checking = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null
  let infraTimer: ReturnType<typeof setInterval> | null = null
  let abortCtl: AbortController | null = null

  const check = async () => {
    if (checking.value) return
    checking.value = true
    abortCtl = new AbortController()
    const timeoutId = setTimeout(() => abortCtl?.abort(), timeoutMs)
    try {
      const res = await fetch(`${url}?_=${Date.now()}`, {
        method: 'HEAD',
        cache: 'no-store',
        signal: abortCtl.signal
      })
      online.value = res.ok
    } catch {
      online.value = false
    } finally {
      clearTimeout(timeoutId)
      checking.value = false
      abortCtl = null
    }
  }

  /**
   * 探测后端基础设施健康。返回的 response.ok 为 true 表示后端可达且
   * 健康端点响应 2xx；否则判定基础设施降级/故障。
   */
  const checkInfra = async () => {
    try {
      const res = await fetch(`${infraUrl}?_=${Date.now()}`, {
        method: 'GET',
        cache: 'no-store'
      })
      infraHealthy.value = res.ok
    } catch {
      infraHealthy.value = false
    }
  }

  const startPolling = () => {
    if (timer) return
    timer = setInterval(check, intervalMs)
  }

  const startInfraPolling = () => {
    if (!probeInfra) return
    if (infraTimer) return
    // 首次立即探测一次（避免最长等一个周期才第一次告警）
    checkInfra()
    infraTimer = setInterval(checkInfra, infraIntervalMs)
  }

  const stopPolling = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    abortCtl?.abort()
    abortCtl = null
  }

  const stopInfraPolling = () => {
    if (infraTimer) {
      clearInterval(infraTimer)
      infraTimer = null
    }
  }

  const onOnline = () => {
    online.value = true
    stopPolling()
  }

  const onOffline = () => {
    online.value = false
    stopInfraPolling()
    infraHealthy.value = true // 网络已断，基础设施健康与否无从判定，归位避免误报
    startPolling()
  }

  onMounted(() => {
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    if (online.value) {
      // 浏览器在线 → 周期性探测后端基础设施（DB/Redis 故障防线）
      startInfraPolling()
    } else {
      startPolling()
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('online', onOnline)
    window.removeEventListener('offline', onOffline)
    stopPolling()
    stopInfraPolling()
  })

  return { online, infraHealthy, checking, check }
}
