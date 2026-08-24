import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'

/**
 * 资源详情加载状态（6.18 契约）。
 *
 * 用**判别式联合**而非多个独立布尔量：
 * 布尔组合（loading / loadError / notFound 各一个 ref）允许出现
 * `loading=true` 且 `loadError=true` 这类非法组合，模板的 v-if 顺序
 * 一旦写错就会展示错误的分支——本项目此前 AlertDetail 与 TicketDetail
 * 的 v-if 条件已经不一致（前者 `loading`、后者 `loading && !ticket`），
 * 正是布尔组合放任了这种漂移。
 *
 * 四态语义：
 * - `loading`  正在请求，尚不知结果
 * - `ready`    拿到数据
 * - `notFound` 确实不存在（后端 40004 / API 层返回 null）
 * - `error`    网络或服务异常，数据**可能仍在**，应给重试
 *
 * `notFound` 与 `error` 必须分开：前者数据已消失（引导返回列表），
 * 后者数据可能仍在（引导重试）——用户下一步动作完全不同。
 */
export type ResourceStatus = 'loading' | 'ready' | 'notFound' | 'error'

export interface UseResourceStateReturn<T> {
  /** 资源数据，仅 status === 'ready' 时保证非空 */
  data: Ref<T | null>
  /** 当前状态，模板按它单一分支渲染 */
  status: Ref<ResourceStatus>
  /** 原始错误对象，供 ApiErrorState 做友好提示 */
  error: Ref<unknown>

  isLoading: ComputedRef<boolean>
  isReady: ComputedRef<boolean>
  isNotFound: ComputedRef<boolean>
  isError: ComputedRef<boolean>

  /**
   * 执行一次加载。
   *
   * 内置请求序号防竞态：快速切换 id（如从工单 A 点「相似工单」跳 B）时，
   * 先发起的请求若后到达，其结果会被丢弃——否则 A 的数据会覆盖 B 的
   * （同 6.39 切换工单归属错乱的成因）。
   *
   * @param loader 返回资源的异步函数。约定 resolve(null) 表示「确实不存在」，
   *               reject 表示「加载失败」——这与 api 层「40004 返回 null、
   *               网络异常抛错」的约定一致（6.18）。
   * @returns 本次加载的数据；被竞态丢弃或失败时返回 null
   */
  load: (loader: () => Promise<T | null>) => Promise<T | null>

  /**
   * 重置为初始 loading 态。
   *
   * 切换实体（同路由不同 id）时必须调用：Vue 会复用组件实例，
   * `onMounted` 不再触发，若不重置则上一个实体的数据会残留显示。
   */
  reset: () => void
}

export function useResourceState<T>(): UseResourceStateReturn<T> {
  // shallowRef：资源多为普通对象，无需深响应式；避免大对象递归代理的开销
  const data = shallowRef<T | null>(null) as Ref<T | null>
  // 初值 loading 而非 ready/notFound —— 初值判为「不存在」会让首帧
  // 闪现「未找到」，用户误以为链接失效（6.18）
  const status = ref<ResourceStatus>('loading')
  const error = shallowRef<unknown>(null)

  let requestSequence = 0

  const load = async (loader: () => Promise<T | null>): Promise<T | null> => {
    const seq = ++requestSequence
    status.value = 'loading'
    error.value = null
    data.value = null

    try {
      const result = await loader()
      // 竞态：已有更新的请求发出，本次结果作废
      if (seq !== requestSequence) return null

      if (result === null || result === undefined) {
        status.value = 'notFound'
        return null
      }
      data.value = result
      status.value = 'ready'
      return result
    } catch (e) {
      if (seq !== requestSequence) return null
      error.value = e
      status.value = 'error'
      return null
    }
  }

  const reset = () => {
    // 递增序号使进行中的请求结果作废，防止旧实体数据落到新实体上
    requestSequence++
    data.value = null
    error.value = null
    status.value = 'loading'
  }

  return {
    data,
    status,
    error,
    isLoading: computed(() => status.value === 'loading'),
    isReady: computed(() => status.value === 'ready'),
    isNotFound: computed(() => status.value === 'notFound'),
    isError: computed(() => status.value === 'error'),
    load,
    reset,
  }
}

// ==================== 外部数据源变体 ====================

export interface UseExternalResourceStateReturn {
  status: Ref<ResourceStatus>
  error: Ref<unknown>

  isLoading: ComputedRef<boolean>
  isNotFound: ComputedRef<boolean>
  isError: ComputedRef<boolean>

  /**
   * 执行一次加载。
   *
   * 与 useResourceState 的区别：数据由外部持有（如 Pinia store），
   * 本 composable 只负责状态机。loader 完成后由 `hasData` 判定
   * 落到 ready 还是 notFound。
   */
  load: (loader: () => Promise<void>) => Promise<void>
  reset: () => void
}

/**
 * 数据由外部持有时的三态管理。
 *
 * 适用于「资源存在 Pinia store 中、详情页只是其中一条」的场景——
 * 此时数据不该被 composable 再持有一份（两份数据必然漂移），
 * 但状态机仍需统一，否则各页面又会写出不一致的 v-if 条件。
 *
 * @param hasData 判断外部数据是否已就绪，如 `() => !!store.getById(id)`
 */
export function useExternalResourceState(
  hasData: () => boolean
): UseExternalResourceStateReturn {
  const status = ref<ResourceStatus>('loading')
  const error = shallowRef<unknown>(null)

  let requestSequence = 0

  const load = async (loader: () => Promise<void>): Promise<void> => {
    const seq = ++requestSequence
    status.value = 'loading'
    error.value = null

    try {
      await loader()
      if (seq !== requestSequence) return
      // 数据在外部，加载完成后据其有无判定终态
      status.value = hasData() ? 'ready' : 'notFound'
    } catch (e) {
      if (seq !== requestSequence) return
      error.value = e
      status.value = 'error'
    }
  }

  const reset = () => {
    requestSequence++
    error.value = null
    status.value = 'loading'
  }

  return {
    status,
    error,
    isLoading: computed(() => status.value === 'loading'),
    isNotFound: computed(() => status.value === 'notFound'),
    isError: computed(() => status.value === 'error'),
    load,
    reset,
  }
}
