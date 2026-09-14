<script setup lang="ts">
import { notify } from '@/utils/notify'
import { ref, watch, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'
import { X, Settings, RotateCcw } from 'lucide-vue-next'
import { useAppStore, type AppSettings } from '@/stores/app'
import { useTheme, type ColorMode } from '@/composables/useTheme'
import { useFocusTrap } from '@/utils/focusTrap'

interface Props {
  visible: boolean
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const app = useAppStore()
const dialogRef = ref<HTMLElement | null>(null)
const trap = useFocusTrap(() => dialogRef.value)

const form = ref<AppSettings>({ ...app.settings })

/**
 * 外观（明/暗/跟随系统）。
 *
 * ── 为什么加在这里 ────────────────────────────────────────────
 * `useTheme` + `theme.css` + `theme-bridge.css` 是一套**已经完整可用**的
 * 主题系统：令牌齐备、localStorage 持久化、`index.html` 里还有防首屏白闪的
 * 内联脚本。但全项目唯一挂载 `ThemeSwitcher` 的地方是 `/design-system`——
 * 一个 `hiddenFromNavigation` 的演示页。也就是说这套能力**用户根本到不了**，
 * 写好的暗色主题谁也切不出来。系统设置在用户菜单里，是它的正确归属。
 *
 * ── 为什么不进 form ──────────────────────────────────────────
 * 主题不属于 `AppSettings`（它是 useTheme 的模块级单例，走自己的
 * storage key）。且外观必须**即时生效**：用户点「深色」是想立刻看到效果，
 * 而不是先保存、关弹窗、再发现颜色变了。所以这一节与下方
 * 「保存后生效」的表单项语义不同，UI 上用 hint 明确说明，不让用户误判。
 */
const { state: themeState, setMode } = useTheme()

const colorModeOptions: { value: ColorMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' }
]

const timeoutOptions: { value: number; label: string }[] = [
  { value: 5, label: '5 分钟' },
  { value: 10, label: '10 分钟' },
  { value: 15, label: '15 分钟（默认）' },
  { value: 30, label: '30 分钟' },
  { value: 60, label: '60 分钟' }
]

const close = () => emit('update:visible', false)

const submit = () => {
  app.updateSettings(form.value)
  notify.success('设置已保存')
  close()
}

const doReset = async () => {
  try {
    await ElMessageBox.confirm('确认恢复默认设置？当前修改将丢失。', '恢复默认', {
      type: 'warning',
      confirmButtonText: '恢复',
      cancelButtonText: '取消'
    })
    app.resetSettings()
    form.value = { ...app.settings }
    notify.success('已恢复默认设置')
  } catch { /* cancel */ }
}

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') { e.stopPropagation(); close() }
}

watch(() => props.visible, v => {
  if (v) {
    form.value = { ...app.settings }
    document.addEventListener('keydown', onKeydown)
    trap.activate()
  } else {
    document.removeEventListener('keydown', onKeydown)
    trap.deactivate()
  }
})

onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <transition name="dialog-fade">
      <div v-if="visible" class="dialog-mask" @click.self="close">
        <div ref="dialogRef" class="dialog" role="dialog" aria-modal="true" aria-labelledby="settings-title">
          <header class="dialog-header">
            <h3 id="settings-title">
              <Settings :size="18" />
              系统设置
            </h3>
            <button class="btn-icon" @click="close" aria-label="关闭">
              <X :size="18" />
            </button>
          </header>

          <div class="dialog-body">
            <section class="section">
              <div class="section-title">通知</div>
              <label class="switch-row">
                <div class="switch-text">
                  <div class="switch-title">桌面通知</div>
                  <div class="switch-hint">工单更新、告警触发时显示浮动提示</div>
                </div>
                <input v-model="form.notificationsEnabled" type="checkbox" class="switch" />
              </label>
              <!-- 邮件摘要：L2 通知能力（钉钉/企微/邮件，见 6.3）尚未落地，
                   后端无邮件设施。此前是无标注的假开关——切换后持久化却不触发任何行为，
                   hint 还承诺「每日 08:00 汇总」。改为诚实占位（禁用 + 即将上线），
                   与帮助中心「在线咨询」同款处理，避免用户等一封永远不来的邮件 -->
              <label class="switch-row is-disabled">
                <div class="switch-text">
                  <div class="switch-title">邮件摘要（即将上线）</div>
                  <div class="switch-hint">每日汇总未处理工单，随 L2 通知能力上线</div>
                </div>
                <input
                  v-model="form.emailDigest"
                  type="checkbox"
                  class="switch"
                  disabled
                  title="邮件摘要将随 L2 通知能力上线"
                />
              </label>
            </section>

            <section class="section">
              <div class="section-title">显示</div>
              <!--
                外观即时生效，与本弹窗其它「保存后生效」的项语义不同，
                故 hint 里明确写出来——否则用户点了深色又点「取消」，
                会以为主题会跟着回滚。
              -->
              <div class="field-row">
                <label class="field-label">外观</label>
                <select
                  class="field-select"
                  :value="themeState.mode"
                  @change="setMode(($event.target as HTMLSelectElement).value as ColorMode)"
                >
                  <option v-for="o in colorModeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
              </div>
              <div class="field-hint">立即生效并记住选择；「跟随系统」会随操作系统的深浅色设置自动切换</div>
              <label class="switch-row">
                <div class="switch-text">
                  <div class="switch-title">紧凑表格</div>
                  <div class="switch-hint">压缩行高，一屏显示更多工单</div>
                </div>
                <input v-model="form.compactTable" type="checkbox" class="switch" />
              </label>
            </section>

            <section class="section">
              <div class="section-title">安全</div>
              <div class="field-row">
                <label class="field-label">会话超时</label>
                <select v-model.number="form.idleTimeoutMinutes" class="field-select">
                  <option v-for="o in timeoutOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
              </div>
              <div class="field-hint">超过指定时长无操作，将提示并自动退出登录</div>
            </section>
          </div>

          <footer class="dialog-footer">
            <button class="btn btn-plain btn-with-icon" @click="doReset">
              <RotateCcw :size="14" />
              恢复默认
            </button>
            <div class="footer-right">
              <button class="btn btn-plain" @click="close">取消</button>
              <button class="btn btn-primary" @click="submit">保存</button>
            </div>
          </footer>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped lang="scss">
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.dialog {
  width: 100%;
  max-width: 520px;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border-light);

  h3 {
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
    margin: 0;
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
}

.btn-icon {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;

  &:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
}

.dialog-body {
  padding: 8px 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
}

.section-title {
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 4px;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color 0.15s ease;

  &:hover { border-color: var(--color-primary-light); }

  /* 诚实占位：未落地的设置项禁用 + 置灰，不触发 hover 高亮 */
  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.6;
    &:hover { border-color: var(--color-border-light); }
    .switch { cursor: not-allowed; }
  }
}

.switch-text { display: flex; flex-direction: column; gap: 2px; }
.switch-title { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text-primary); }
.switch-hint { font-size: var(--text-xs); color: var(--color-text-tertiary); line-height: 1.4; }

/* Custom checkbox → switch */
.switch {
  appearance: none;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--color-border);
  position: relative;
  cursor: pointer;
  transition: background 0.15s ease;
  flex-shrink: 0;
  margin: 0;

  &::after {
    content: '';
    position: absolute;
    top: 2px;
    left: 2px;
    width: 16px;
    height: 16px;
    /* 这里的 white 是**刻意**的，不要换成 --color-surface：
       它是开关的滑块，始终压在有色轨道（--color-border / --color-primary）上，
       需要与轨道形成固定对比。跟随主题的话，暗色下滑块会变成深灰压在深灰轨道上，
       开关看起来像是消失了。全局那次「硬编码白 → 主题令牌」的清理跳过了这一处。 */
    background: white;
    border-radius: 50%;
    transition: transform 0.15s ease;
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
  }

  &:checked {
    background: var(--color-primary);
    &::after { transform: translateX(16px); }
  }
}

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
}

.field-label { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text-primary); flex-shrink: 0; }

.field-select {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;

  &:focus { border-color: var(--color-primary); }
}

.field-hint {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  padding-left: 12px;
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid var(--color-border-light);
}

.footer-right { display: flex; gap: 8px; }

.btn {
  padding: 8px 20px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;

  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.btn-with-icon {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
}

.btn-plain {
  background: var(--color-surface);
  color: var(--color-text-primary);
  border-color: var(--color-border-light);

  &:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
}

.btn-primary {
  background: var(--color-primary);
  color: var(--color-text-inverse);

  &:hover:not(:disabled) { background: var(--color-primary-light); }
}

.dialog-fade-enter-active, .dialog-fade-leave-active { transition: opacity 0.15s ease; }
.dialog-fade-enter-active .dialog, .dialog-fade-leave-active .dialog { transition: transform 0.15s ease; }
.dialog-fade-enter-from, .dialog-fade-leave-to { opacity: 0; }
.dialog-fade-enter-from .dialog, .dialog-fade-leave-to .dialog { transform: scale(0.96); }
</style>
