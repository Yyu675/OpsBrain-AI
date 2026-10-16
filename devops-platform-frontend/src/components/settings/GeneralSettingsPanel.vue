<script setup lang="ts">
/**
 * 常规设置面板（设置页「常规」标签）。
 *
 * 内容原样承接自已删除的 SettingsDialog（通知 / 显示 / 安全三节）：
 * 弹窗改为设置页标签后，表单语义不变——
 *   - 「外观」即时生效（useTheme 模块级单例，自己的 storage key），
 *     与本面板其它「保存后生效」的表单项语义不同，hint 里明确写出；
 *   - 「邮件摘要」是诚实占位：L2 通知能力（钉钉/企微/邮件）未落地，
 *     后端无邮件设施，禁用 + 即将上线，不做切了没反应的假开关。
 */
import { notify } from '@/utils/notify'
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { RotateCcw } from 'lucide-vue-next'
import { useAppStore, type AppSettings } from '@/stores/app'
import { useTheme, type ColorMode } from '@/composables/useTheme'

defineOptions({ name: 'GeneralSettingsPanel' })

const app = useAppStore()

const form = ref<AppSettings>({ ...app.settings })

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

const submit = () => {
  app.updateSettings(form.value)
  notify.success('设置已保存')
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
</script>

<template>
  <div class="general-settings">
    <header class="panel-header">
      <h2 class="panel-title">常规</h2>
      <p class="panel-sub">个人偏好只作用于当前浏览器；外观即时生效，其余项保存后生效</p>
    </header>

    <section class="section">
      <div class="section-title">通知</div>
      <label class="switch-row">
        <div class="switch-text">
          <div class="switch-title">桌面通知</div>
          <div class="switch-hint">工单更新、告警触发时显示浮动提示</div>
        </div>
        <input v-model="form.notificationsEnabled" type="checkbox" class="switch" />
      </label>
      <!-- 邮件摘要：L2 通知能力（钉钉/企微/邮件）尚未落地，后端无邮件设施。
           诚实占位（禁用 + 即将上线），与帮助中心「在线咨询」同款处理，
           避免用户等一封永远不来的邮件 -->
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

    <footer class="panel-footer">
      <button class="btn btn-plain btn-with-icon" @click="doReset">
        <RotateCcw :size="14" />
        恢复默认
      </button>
      <button class="btn btn-primary" @click="submit">保存</button>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.general-settings {
  max-width: 640px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel-header {
  .panel-title {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    color: var(--text-1);
  }

  .panel-sub {
    margin: 4px 0 0;
    font-size: var(--text-xs);
    color: var(--text-3);
    line-height: 1.5;
  }
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
  color: var(--text-3);
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
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  cursor: pointer;
  transition: border-color 0.15s ease;

  &:hover { border-color: var(--brand-hover); }

  /* 诚实占位：未落地的设置项禁用 + 置灰，不触发 hover 高亮 */
  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.6;
    &:hover { border-color: var(--border-1); }
    .switch { cursor: not-allowed; }
  }
}

.switch-text { display: flex; flex-direction: column; gap: 2px; }
.switch-title { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--text-1); }
.switch-hint { font-size: var(--text-xs); color: var(--text-3); line-height: 1.4; }

/* Custom checkbox → switch */
.switch {
  appearance: none;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--border-2);
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
       它是开关的滑块，始终压在有色轨道（--border-2 / --color-primary）上，
       需要与轨道形成固定对比。跟随主题的话，暗色下滑块会变成深灰压在深灰轨道上，
       开关看起来像是消失了。全局那次「硬编码白 → 主题令牌」的清理跳过了这一处。 */
    background: white;
    border-radius: 50%;
    transition: transform 0.15s ease;
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
  }

  &:checked {
    background: var(--brand);
    &::after { transform: translateX(16px); }
  }
}

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
}

.field-label { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--text-1); flex-shrink: 0; }

.field-select {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--surface-1);
  color: var(--text-1);
  outline: none;

  &:focus { border-color: var(--brand); }
}

.field-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
  padding-left: 12px;
}

.panel-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--border-1);
}

.btn {
  padding: 8px 20px;
  border-radius: var(--radius);
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
  background: var(--surface-1);
  color: var(--text-1);
  border-color: var(--border-1);

  &:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
}

.btn-primary {
  background: var(--brand);
  color: var(--text-inverse);

  &:hover:not(:disabled) { background: var(--brand-hover); }
}
</style>
