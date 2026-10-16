<script setup lang="ts">
import { useTheme, type ColorMode, type ThemePreset, type ThemeRadius, type ThemeDensity } from '@/composables/useTheme'
import { Sun, Moon, Monitor, Palette } from 'lucide-vue-next'

/**
 * 主题切换器。
 *
 * 四个轴各自独立呈现，不做互斥——用户可以任意组合
 * （如「暗色 + 石墨 + 无圆角 + 紧凑」这种典型 NOC 大屏配置）。
 */

const { state, isDark, setMode, setPreset, setRadius, setDensity } = useTheme()

const MODES: { value: ColorMode; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: '浅色', icon: Sun },
  { value: 'dark', label: '深色', icon: Moon },
  { value: 'system', label: '跟随系统', icon: Monitor },
]

const PRESETS: { value: ThemePreset; label: string; desc: string }[] = [
  { value: 'default', label: '默认蓝', desc: '品牌主色' },
  { value: 'graphite', label: '石墨灰', desc: '降低色彩干扰，适合长时间盯盘' },
  { value: 'nord', label: 'Nord', desc: '低饱和冷色，护眼' },
]

const RADII: { value: ThemeRadius; label: string }[] = [
  { value: 'none', label: '直角' },
  { value: 'sm', label: '小' },
  { value: 'md', label: '中' },
  { value: 'lg', label: '大' },
]

const DENSITIES: { value: ThemeDensity; label: string; desc: string }[] = [
  { value: 'compact', label: '紧凑', desc: '一屏多看约 30% 行' },
  { value: 'default', label: '标准', desc: '' },
  { value: 'comfortable', label: '宽松', desc: '' },
]
</script>

<template>
  <div class="theme-switcher">
    <section class="ts-section">
      <h4 class="ts-title"><Palette :size="14" /> 外观</h4>
      <div class="ts-seg" role="radiogroup" aria-label="配色模式">
        <button
          v-for="m in MODES"
          :key="m.value"
          class="ts-seg-btn"
          :class="{ active: state.mode === m.value }"
          role="radio"
          :aria-checked="state.mode === m.value"
          @click="setMode(m.value)"
        >
          <component :is="m.icon" :size="14" aria-hidden="true" />
          <span>{{ m.label }}</span>
        </button>
      </div>
      <p class="ts-hint">
        当前实际为{{ isDark ? '深色' : '浅色' }}<template v-if="state.mode === 'system'">（跟随系统）</template>
      </p>
    </section>

    <section class="ts-section">
      <h4 class="ts-title">配色</h4>
      <div class="ts-presets">
        <button
          v-for="p in PRESETS"
          :key="p.value"
          class="ts-preset"
          :class="{ active: state.preset === p.value }"
          :title="p.desc"
          @click="setPreset(p.value)"
        >
          <span class="ts-swatch" :data-preset="p.value" aria-hidden="true" />
          <span class="ts-preset-label">{{ p.label }}</span>
        </button>
      </div>
    </section>

    <section class="ts-section">
      <h4 class="ts-title">圆角</h4>
      <div class="ts-seg">
        <button
          v-for="r in RADII"
          :key="r.value"
          class="ts-seg-btn"
          :class="{ active: state.radius === r.value }"
          @click="setRadius(r.value)"
        >{{ r.label }}</button>
      </div>
    </section>

    <section class="ts-section">
      <h4 class="ts-title">信息密度</h4>
      <div class="ts-seg">
        <button
          v-for="d in DENSITIES"
          :key="d.value"
          class="ts-seg-btn"
          :class="{ active: state.density === d.value }"
          :title="d.desc"
          @click="setDensity(d.value)"
        >{{ d.label }}</button>
      </div>
      <p class="ts-hint">运维列表建议「紧凑」——一屏能看多少行比留白更重要</p>
    </section>
  </div>
</template>

<style scoped>
.theme-switcher {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 260px;
}

.ts-section { display: flex; flex-direction: column; gap: var(--space-2); }

.ts-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-2);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

/* 分段控件 */
.ts-seg {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--surface-2);
  border-radius: var(--radius);
  border: 1px solid var(--border-1);
}

.ts-seg-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  min-height: calc(var(--control-h) - 8px);
  font-size: var(--text-xs);
  color: var(--text-2);
  background: transparent;
  border: none;
  border-radius: calc(var(--radius) - 2px);
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
              color var(--duration-fast) var(--ease-out);
}
.ts-seg-btn:hover { background: var(--surface-hover); color: var(--text-1); }
.ts-seg-btn.active {
  background: var(--surface-1);
  color: var(--brand);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

/* 配色预设 */
.ts-presets { display: flex; flex-direction: column; gap: var(--space-1); }

.ts-preset {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius);
  cursor: pointer;
  text-align: left;
  transition: background-color var(--duration-fast) var(--ease-out);
}
.ts-preset:hover { background: var(--surface-hover); }
.ts-preset.active { background: var(--brand-subtle); border-color: var(--brand); }

.ts-swatch {
  width: 18px;
  height: 18px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-2);
  flex-shrink: 0;
}
/* 色块直接用各预设的品牌色，所见即所得 */
.ts-swatch[data-preset='default']  { background: oklch(0.42 0.13 258); }
.ts-swatch[data-preset='graphite'] { background: oklch(0.42 0.02 260); }
.ts-swatch[data-preset='nord']     { background: oklch(0.52 0.08 230); }

.ts-preset-label { font-size: var(--text-sm); color: var(--text-1); }

.ts-hint {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-3);
  line-height: var(--leading-normal);
}
</style>
