<script setup lang="ts">
import { X, Keyboard } from 'lucide-vue-next'
import { useFocusTrap } from '@/utils/focusTrap'
import { ref, watch } from 'vue'

interface Props { visible: boolean }
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:visible': [v: boolean] }>()

const dialogRef = ref<HTMLElement | null>(null)
const trap = useFocusTrap(() => dialogRef.value)

const close = () => emit('update:visible', false)

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') { e.stopPropagation(); close() }
}

watch(() => props.visible, v => {
  if (v) {
    document.addEventListener('keydown', onKeydown)
    trap.activate()
  } else {
    document.removeEventListener('keydown', onKeydown)
    trap.deactivate()
  }
})

const hotkeys: Array<{ combo: string; description: string }> = [
  { combo: '/', description: '聚焦搜索框（列表页）' },
  { combo: 'N', description: '新建（工单 / 文章，视当前页面）' },
  { combo: 'Esc', description: '关闭弹窗 / 清除筛选' },
  { combo: 'G 然后 H', description: '跳转到首页' },
  { combo: 'G 然后 T', description: '跳转到工单列表' },
  { combo: 'G 然后 K', description: '跳转到知识库' },
  { combo: 'G 然后 D', description: '跳转到数据大屏' },
  { combo: '?', description: '打开快捷键面板' }
]
</script>

<template>
  <Teleport to="body">
    <transition name="dialog-fade">
      <div v-if="visible" class="dialog-mask" @click.self="close">
        <div ref="dialogRef" class="dialog" role="dialog" aria-modal="true" aria-labelledby="hotkeys-title">
          <header class="dialog-header">
            <div class="header-left">
              <Keyboard :size="18" />
              <h3 id="hotkeys-title">键盘快捷键</h3>
            </div>
            <button class="btn-icon" @click="close" aria-label="关闭">
              <X :size="18" />
            </button>
          </header>
          <div class="dialog-body">
            <div class="hint">按下 <kbd>?</kbd> 随时打开此面板。焦点在输入框时快捷键失效。</div>
            <ul class="key-list">
              <li v-for="hk in hotkeys" :key="hk.combo">
                <span class="key-combo">
                  <kbd v-for="(part, i) in hk.combo.split(' ')" :key="i">{{ part }}</kbd>
                </span>
                <span class="key-desc">{{ hk.description }}</span>
              </li>
            </ul>
          </div>
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
  max-width: 560px;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-light);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-primary);

  h3 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
  }
}

.btn-icon {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &:hover { background: var(--color-surface-hover); color: var(--color-text-primary); }
}

.dialog-body {
  padding: 20px;
}

.hint {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  margin-bottom: 12px;
}

.key-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 24px;

  @media (max-width: 640px) { grid-template-columns: 1fr; }
}

.key-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--color-border-light);
}

.key-combo {
  display: inline-flex;
  gap: 4px;
  flex-shrink: 0;
}

kbd {
  display: inline-block;
  padding: 2px 6px;
  border: 1px solid var(--color-border);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--color-bg-sunken);
  color: var(--color-text-primary);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1;
}

.key-desc {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  text-align: right;
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.15s ease;

  .dialog { transition: transform 0.15s ease; }
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
  .dialog { transform: translateY(-16px); }
}
</style>
