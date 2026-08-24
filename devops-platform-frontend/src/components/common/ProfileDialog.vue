<script setup lang="ts">
import { notify } from '@/utils/notify'
import { ref, watch, computed, onBeforeUnmount } from 'vue'

import { X, User } from 'lucide-vue-next'
import { useAppStore } from '@/stores/app'
import { useFocusTrap } from '@/utils/focusTrap'

interface Props {
  visible: boolean
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const app = useAppStore()
const dialogRef = ref<HTMLElement | null>(null)
const trap = useFocusTrap(() => dialogRef.value)

const form = ref({ name: '', email: '', title: '' })

const resetForm = () => {
  form.value = {
    name: app.currentUser.name,
    email: app.currentUser.email,
    title: app.currentUser.title
  }
}

const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const nameError = computed(() => {
  const v = form.value.name.trim()
  if (!v) return '请输入姓名'
  if (v.length > 20) return '姓名最多 20 个字符'
  return ''
})
const emailError = computed(() => {
  const v = form.value.email.trim()
  if (!v) return ''
  if (!emailRe.test(v)) return '邮箱格式不正确'
  return ''
})

const canSubmit = computed(() => !nameError.value && !emailError.value)

const close = () => emit('update:visible', false)

const submit = () => {
  if (!canSubmit.value) {
    notify.warning(nameError.value || emailError.value)
    return
  }
  app.updateProfile({
    name: form.value.name.trim(),
    email: form.value.email.trim(),
    title: form.value.title.trim()
  })
  notify.success('个人信息已更新')
  close()
}

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') { e.stopPropagation(); close() }
}

watch(() => props.visible, v => {
  if (v) {
    resetForm()
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
        <div ref="dialogRef" class="dialog" role="dialog" aria-modal="true" aria-labelledby="profile-title">
          <header class="dialog-header">
            <h3 id="profile-title">
              <User :size="18" />
              个人中心
            </h3>
            <button class="btn-icon" @click="close" aria-label="关闭">
              <X :size="18" />
            </button>
          </header>

          <div class="dialog-body">
            <div class="profile-header">
              <div class="profile-avatar">{{ app.currentUser.avatar }}</div>
              <div class="profile-meta">
                <div class="profile-role-tag">{{ app.roleLabel }}</div>
                <div class="profile-perm">
                  权限：<span>{{ app.hasAllPermissions ? '全部权限' : app.currentUser.permissions.join(' / ') || '无' }}</span>
                </div>
              </div>
            </div>

            <div class="form-row">
              <label class="form-label required">姓名</label>
              <input v-model="form.name" type="text" class="form-input" maxlength="20" placeholder="请输入姓名" />
              <div v-if="nameError" class="form-error">{{ nameError }}</div>
            </div>

            <div class="form-row">
              <label class="form-label">邮箱</label>
              <input v-model="form.email" type="email" class="form-input" placeholder="name@example.com" />
              <div v-if="emailError" class="form-error">{{ emailError }}</div>
            </div>

            <div class="form-row">
              <label class="form-label">职位</label>
              <input v-model="form.title" type="text" class="form-input" maxlength="30" placeholder="例：高级运维工程师" />
            </div>

            <div class="tip">
              修改仅在本地保存，用于个性化展示；角色与权限由管理员分配，不可自助修改。
            </div>
          </div>

          <footer class="dialog-footer">
            <button class="btn btn-plain" @click="close">取消</button>
            <button class="btn btn-primary" :disabled="!canSubmit" @click="submit">保存</button>
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
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  background: var(--color-primary-lighter);
  border-radius: var(--radius-md);
}

.profile-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--color-primary);
  color: white;
  font-size: var(--text-2xl);
  font-weight: var(--weight-semibold);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.profile-meta { display: flex; flex-direction: column; gap: 4px; }

.profile-role-tag {
  display: inline-block;
  align-self: flex-start;
  padding: 2px 10px;
  background: var(--color-primary);
  color: white;
  border-radius: var(--radius-full);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
}

.profile-perm { font-size: var(--text-xs); color: var(--color-text-secondary); }
.profile-perm span { color: var(--color-text-primary); font-weight: var(--weight-medium); }

.form-row { display: flex; flex-direction: column; gap: 6px; }

.form-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);

  &.required::after { content: ' *'; color: var(--state-error); }
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus { border-color: var(--color-primary); }
}

.form-error { font-size: var(--text-xs); color: var(--state-error); }

.tip {
  padding: 10px 12px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-md);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid var(--color-border-light);
}

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
