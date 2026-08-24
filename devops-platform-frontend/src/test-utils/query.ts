import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

/**
 * Query 测试辅助。
 *
 * 用到 useQuery/useMutation 的 composable 必须在有 QueryClient 的组件上下文中运行，
 * 直接调用会抛「No queryClient found」。此处提供最小挂载壳。
 *
 * 测试用的 QueryClient 与生产配置不同：**关掉缓存与重试**，
 * 让每个用例从干净状态开始——否则前一个用例的缓存会让后一个用例
 * 拿到陈旧数据，断言时序变得不可预测。
 */

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        // 不缓存：用例间不共享数据
        gcTime: 0,
        staleTime: 0,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
      },
      mutations: { retry: false },
    },
  })
}

/**
 * 在带 QueryClient 的上下文中运行一个 composable，返回其结果。
 *
 * @returns setup 返回值 + wrapper（供 unmount 清理）+ queryClient（供断言缓存状态）
 */
export function withQueryClient<T>(
  composable: () => T,
  options: { queryClient?: QueryClient } = {}
) {
  const queryClient = options.queryClient ?? createTestQueryClient()
  let result!: T

  const wrapper = mount(
    defineComponent({
      setup() {
        result = composable()
        return () => h('div')
      },
    }),
    {
      global: {
        plugins: [[VueQueryPlugin, { queryClient }]],
      },
    }
  )

  return { result, wrapper, queryClient }
}

/**
 * 挂载组件时注入 QueryClient。
 *
 * 参数类型故意保持宽松（`Record<string, unknown>`）：@vue/test-utils 的
 * ComponentMountingOptions 泛型会把 props 类型与组件绑死，在这层通用包装里
 * 无法既保留组件推导又接受任意 options。测试内的类型精度由用例自身的断言保证，
 * 不值得为此在辅助函数里写复杂的条件类型。
 *
 * 当前尚无组件级 Query 测试（只有 composable 测试走 withQueryClient），
 * 保留此函数是因为 SlaRiskPanel / AlertList 等组件迁移到 Query 后需要它——
 * knip 会报未使用，属预期。
 */
export function mountWithQuery(
  component: Parameters<typeof mount>[0],
  options: Record<string, unknown> = {},
  queryClient: QueryClient = createTestQueryClient()
) {
  const globalOptions = (options.global ?? {}) as { plugins?: unknown[] }
  return mount(component, {
    ...options,
    global: {
      ...globalOptions,
      plugins: [...(globalOptions.plugins ?? []), [VueQueryPlugin, { queryClient }]],
    },
  } as Parameters<typeof mount>[1])
}

/** 等待若干个微任务 tick，让 Query 的异步状态落定 */
export async function flushQuery(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) {
    await Promise.resolve()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}
