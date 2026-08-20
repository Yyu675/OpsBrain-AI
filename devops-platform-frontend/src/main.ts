import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-overlay.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-icon.css'
import './assets/styles/variables.css'
import App from './App.vue'
import router from './router'
import { permission } from './directives/permission'
import { toFriendlyError, HttpError } from './utils/http'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.directive('permission', permission)

// 已展示过的错误去重 key，避免同一错误同时被 errorHandler 和 unhandledrejection 重复 toast
let lastErrorKey = ''
let lastErrorTime = 0
const DEDUP_WINDOW = 2000

const shouldShowError = (key: string): boolean => {
  const now = Date.now()
  if (key === lastErrorKey && now - lastErrorTime < DEDUP_WINDOW) return false
  lastErrorKey = key
  lastErrorTime = now
  return true
}

app.config.errorHandler = (err, _instance, info) => {
  console.error('[vue:errorHandler]', err, info)
  const friendly = toFriendlyError(err)
  if (friendly.title.includes('ResizeObserver')) return
  const key = `errorHandler:${friendly.title}`
  if (!shouldShowError(key)) return
  try {
    ElMessage.error({
      message: `${friendly.title}：${friendly.detail}`,
      duration: 5000,
      grouping: true,
      showClose: true
    })
  } catch { /* toast unavailable during boot */ }
}

app.config.warnHandler = (msg, _instance, trace) => {
  if (import.meta.env.DEV) {
    console.warn('[vue:warn]', msg, trace)
  }
}

window.addEventListener('unhandledrejection', (e) => {
  console.error('[unhandledrejection]', e.reason)

  // 资源加载失败由 router.onError 处理，此处不重复
  const reasonStr = e.reason instanceof Error ? e.reason.message : String(e.reason || '')
  if (reasonStr.includes('Failed to fetch dynamically imported module') || reasonStr.includes('Loading chunk')) {
    e.preventDefault()
    return
  }

  // HttpError 已经在各 store / page 级 catch 中处理过，这里仅兜底未被 catch 的
  if (e.reason instanceof HttpError) {
    const friendly = toFriendlyError(e.reason)
    const key = `unhandled:${friendly.title}`
    if (shouldShowError(key)) {
      ElMessage.error({
        message: `${friendly.title}：${friendly.detail}${friendly.hint ? `（${friendly.hint}）` : ''}`,
        duration: 6000,
        grouping: true,
        showClose: true
      })
    }
    e.preventDefault()
    return
  }

  // 非 HttpError 的未知 rejection
  const msg = reasonStr || '未知错误'
  const key = `unhandled:${msg}`
  if (shouldShowError(key)) {
    ElMessage.error({
      message: `发生意外错误：${msg}`,
      duration: 5000,
      grouping: true,
      showClose: true
    })
  }
})

window.addEventListener('error', (e) => {
  if (e.message?.includes('ResizeObserver loop')) {
    e.stopImmediatePropagation()
  }
})

app.mount('#app')
