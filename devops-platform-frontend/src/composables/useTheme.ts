import { ref, computed, watch, readonly } from 'vue'

/**
 * 主题控制：明暗 / 配色 / 圆角 / 密度 四个正交轴。
 *
 * 设计要点：
 * - **四轴独立**：任意组合都成立，不做互斥判断。这是借鉴 new-api 的核心，
 *   它把 preset / dark / radius / scale 拆成互不干扰的 data 属性。
 * - **'system' 是一等公民**：不是"初始化时读一次系统偏好然后固化"，
 *   而是持续跟随。用户在系统里切了暗色，页面要立刻跟着变。
 * - **写在 <html> 上**：CSS 变量需要在最顶层生效，写 body 会让
 *   fixed 定位的弹层（挂在 body 下）取不到变量。
 *
 * 持久化直接用 localStorage 而非项目的 persist.ts：主题必须在
 * 首屏渲染**之前**应用（见 index.html 的内联脚本），那时 Vue 尚未启动，
 * 两边必须用同一套裸 key 读写。
 */

export type ColorMode = 'light' | 'dark' | 'system'
export type ThemePreset = 'default' | 'graphite' | 'nord'
export type ThemeRadius = 'none' | 'sm' | 'md' | 'lg'
export type ThemeDensity = 'compact' | 'default' | 'comfortable'

const STORAGE_KEY = 'opsbrain-theme'

interface ThemeState {
  mode: ColorMode
  preset: ThemePreset
  radius: ThemeRadius
  density: ThemeDensity
}

const DEFAULTS: ThemeState = {
  mode: 'system',
  preset: 'default',
  radius: 'md',
  // 运维系统默认紧凑：一屏能多看几行，比"呼吸感"重要
  density: 'compact',
}

/** 安全读取（隐私模式下 localStorage 会抛错） */
function loadState(): ThemeState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw) as Partial<ThemeState>
    return { ...DEFAULTS, ...parsed }
  } catch {
    return { ...DEFAULTS }
  }
}

function saveState(state: ThemeState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    /* 隐私模式：仅内存生效，不影响使用 */
  }
}

// 模块级单例：多个组件调用 useTheme() 共享同一份状态，
// 否则各自持有副本会导致切换主题只有当前组件响应
const state = ref<ThemeState>(loadState())
const systemPrefersDark = ref(false)
let initialized = false

/** 当前是否为暗色（system 模式下取决于系统偏好） */
const isDark = computed(() =>
  state.value.mode === 'system' ? systemPrefersDark.value : state.value.mode === 'dark'
)

/**
 * 应用到 DOM。
 * @param animate 是否加过渡。首次应用不加——否则页面加载瞬间会看到颜色渐变
 */
function applyToDom(animate = true): void {
  const html = document.documentElement

  if (animate) {
    html.classList.add('theme-transition')
    window.setTimeout(() => html.classList.remove('theme-transition'), 300)
  }

  html.classList.toggle('dark', isDark.value)
  html.dataset.theme = state.value.preset
  html.dataset.radius = state.value.radius
  html.dataset.density = state.value.density

  // 同步浏览器 UI 色（移动端地址栏）
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) {
    meta.setAttribute('content', isDark.value ? '#2b2b30' : '#ffffff')
  }
}

export function useTheme() {
  if (!initialized) {
    initialized = true

    const mql = window.matchMedia('(prefers-color-scheme: dark)')
    systemPrefersDark.value = mql.matches
    // 持续跟随系统：用户在系统设置里切换时页面立即响应
    mql.addEventListener('change', (e) => {
      systemPrefersDark.value = e.matches
    })

    // 首次不加过渡，避免首屏出现颜色渐变
    applyToDom(false)

    watch(
      [state, isDark],
      () => {
        saveState(state.value)
        applyToDom(true)
      },
      { deep: true }
    )
  }

  return {
    state: readonly(state),
    isDark,

    setMode: (mode: ColorMode) => { state.value.mode = mode },
    setPreset: (preset: ThemePreset) => { state.value.preset = preset },
    setRadius: (radius: ThemeRadius) => { state.value.radius = radius },
    setDensity: (density: ThemeDensity) => { state.value.density = density },

    /**
     * 在明/暗之间切换。
     * 当前是 system 时，切到与「系统当前表现」相反的那一档——
     * 用户点"切暗色"时期望立刻变暗，而不是停留在 system 不动。
     */
    toggleDark: () => {
      state.value.mode = isDark.value ? 'light' : 'dark'
    },

    reset: () => { state.value = { ...DEFAULTS } },
  }
}

/**
 * 首屏防闪烁脚本（内联进 index.html 的 <head>）。
 *
 * 不这样做的话，页面会先以浅色渲染，等 Vue 启动后才切暗色，
 * 用户看到一次刺眼的白闪——暗色主题最典型的体验瑕疵。
 * 必须是**同步内联**执行，任何异步都来不及。
 */
export const THEME_INIT_SCRIPT = `
(function(){try{
  var s=JSON.parse(localStorage.getItem('${STORAGE_KEY}')||'{}');
  var m=s.mode||'system';
  var d=m==='dark'||(m==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);
  var h=document.documentElement;
  if(d)h.classList.add('dark');
  h.dataset.theme=s.preset||'default';
  h.dataset.radius=s.radius||'md';
  h.dataset.density=s.density||'compact';
}catch(e){}})();
`.trim()
