/**
 * Vitest 全局测试环境准备。
 *
 * 只补 jsdom 缺失的浏览器 API（matchMedia / ResizeObserver / rAF / scrollIntoView），
 * 不在此处 mock 任何业务模块 —— 业务 mock 必须在用例内显式声明，
 * 以免测试之间通过全局状态互相影响。
 */
import { afterEach, vi } from 'vitest'

// jsdom 未实现 matchMedia，useMediaQuery 等 composable 依赖它
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  writable: true,
  value: (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList,
})

class ResizeObserverMock implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: ResizeObserverMock,
})

Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
  configurable: true,
  writable: true,
  value: () => undefined,
})

// 每个用例后清理本地存储，避免持久化状态跨用例泄漏
afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.useRealTimers()
})
