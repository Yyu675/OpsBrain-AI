import { ref, onMounted, onBeforeUnmount } from 'vue'

export interface HeartbeatOptions {
  url?: string
  intervalMs?: number
  timeoutMs?: number
}

export const useNetworkHeartbeat = (opts: HeartbeatOptions = {}) => {
  const {
    url = '/favicon.ico',
    intervalMs = 10000,
    timeoutMs = 4000
  } = opts

  const online = ref(typeof navigator !== 'undefined' ? navigator.onLine : true)
  const checking = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null
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

  const startPolling = () => {
    if (timer) return
    timer = setInterval(check, intervalMs)
  }

  const stopPolling = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    abortCtl?.abort()
    abortCtl = null
  }

  const onOnline = () => {
    online.value = true
    stopPolling()
  }

  const onOffline = () => {
    online.value = false
    startPolling()
  }

  onMounted(() => {
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    if (!online.value) startPolling()
  })

  onBeforeUnmount(() => {
    window.removeEventListener('online', onOnline)
    window.removeEventListener('offline', onOffline)
    stopPolling()
  })

  return { online, checking, check }
}
