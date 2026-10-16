import { QueryClient, VueQueryPlugin, type VueQueryPluginOptions } from '@tanstack/vue-query'

/**
 * TanStack Query 客户端配置。
 *
 * 引入动因：CLAUDE.md 6.9 / 6.16 / 6.17 / 6.18 四轮缺陷同一根因——
 * 「服务端分页下仍按全量数据的习惯写代码」，写操作后要手动决定刷新哪些数据，
 * 漏一处就出现 total 失准、页内行数错、状态显示与库中不一致。
 * Query 用 queryKey 失效机制把「哪些数据该重新拉」变成声明式，
 * 消除手工维护缓存一致性的心智负担。
 *
 * 配置取向：**关掉 Query 层的重试与自动刷新**，避免与项目既有机制叠加。
 */

/**
 * 不在 Query 层重试。
 *
 * utils/http.ts 已内建重试（GET 默认 2 次指数退避，写操作 0 次——
 * 写操作超时重试会建重复工单）。Query 默认再重试 3 次的话，
 * 一次 GET 最坏会发出 3×3=9 个请求，对后端是无谓压力，
 * 对用户是「点一下卡很久」。
 *
 * 若将来确需在 Query 层重试，务必排除业务错误：
 * unwrapBiz 对 code!==0 抛 HttpError(status=200, code='BIZ')，
 * 而业务拒绝（40009 版本冲突、40021 内容重复）重试只会重复失败。
 * 判据应为 `error instanceof HttpError && error.code !== 'BIZ'
 *   && [408,429,500,502,503,504].includes(error.status)`。
 */
const RETRY = false

function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: RETRY,
        /**
         * 数据多久内视为新鲜。
         *
         * 30 秒：运维数据变化快（告警秒级、工单分钟级），过长会让用户
         * 看到过期数据并据此决策；过短则失去缓存意义。
         * 需要实时性的场景（如告警流）走 WebSocket，不靠轮询。
         */
        staleTime: 30_000,
        /**
         * 缓存保留时长。超过后即使组件重新挂载也会重新拉取。
         * 比 staleTime 长得多——用户切走再切回时先显示缓存再后台刷新，
         * 比转圈体验好。
         */
        gcTime: 5 * 60_000,
        /**
         * 窗口重新聚焦时不自动重拉。
         *
         * 本项目多为服务端分页列表，聚焦即重拉会在用户切回浏览器时
         * 突然刷新整页数据（可能连页码都变），干扰正在进行的操作。
         * 需要刷新的地方都有显式「刷新」按钮。
         */
        refetchOnWindowFocus: false,
        /** 断网恢复时重拉：这是真正需要的——离线期间数据必然已过期 */
        refetchOnReconnect: true,
      },
      mutations: {
        // 写操作绝不自动重试：超时重试会建重复工单（同 http.ts 的判断）
        retry: false,
      },
    },
  })
}

/**
 * main.ts 注册用的插件选项。
 *
 * 不启用 devtools 自动注入——项目已有自建的排障手段（trace id、审计日志），
 * 且 devtools 会增大生产包体积。
 */
export const vueQueryOptions: VueQueryPluginOptions = {
  queryClient: createAppQueryClient(),
}

export { VueQueryPlugin }
