import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 移动端导航抽屉的共享开态。
 *
 * 侧栏布局（2026-09-27）后，抽屉由 AppSidebar 渲染、汉堡按钮在 AppTopBar——
 * 两个组件不互为父子，开态必须是共享事实。模块级单例 ref：
 * 全站同一时刻只可能有一个导航壳实例，不需要 provide/inject。
 */
const open = ref(false)

export function useMobileNavState() {
  const route = useRoute()

  const toggle = () => { open.value = !open.value }
  const close = () => { open.value = false }

  /**
   * 路由变化即关抽屉。
   * 不能只绑在链接 click 上：「点当前页自己」不触发路由变化，
   * 还有浏览器返回键这条路径。监听路由这个事实全覆盖。
   */
  watch(() => route.path, () => {
    close()
    // 抽屉关闭时恢复滚动（打开时 Sidebar 会锁 body）
    document.body.style.overflow = ''
  })

  return { open, toggle, close }
}
