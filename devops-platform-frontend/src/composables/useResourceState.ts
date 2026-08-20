import { ref, computed } from 'vue'

/**
 * 资源详情页三态管理（CLAUDE.md 6.18 契约）
 *
 * 三态严格区分：加载中 / 加载失败 / 确实不存在。
 * - loading 初值必须 true（避免首帧闪现「未找到」）
 * - notFound 必须排除 loading 与 loadError
 * - 失败给重试，不存在给返回列表
 */
export function useResourceState<T>() {
  const data = ref<T | null>(null) as { value: T | null }
  const loading = ref(true)
  const loadError = ref(false)
  const errorMsg = ref('')

  const notFound = computed(() => !loading.value && !loadError.value && !data.value)

  function startLoading() {
    loading.value = true
    loadError.value = false
    errorMsg.value = ''
  }

  function onSuccess(result: T | null) {
    data.value = result
    loading.value = false
  }

  function onError(message?: string) {
    loading.value = false
    loadError.value = true
    errorMsg.value = message || '加载失败'
  }

  function reset() {
    data.value = null
    loading.value = true
    loadError.value = false
    errorMsg.value = ''
  }

  return {
    data,
    loading,
    loadError,
    errorMsg,
    notFound,
    startLoading,
    onSuccess,
    onError,
    reset
  }
}
