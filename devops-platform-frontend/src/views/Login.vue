<script setup lang="ts">
/**
 * 登录页（方向三：Sa-Token 鉴权）
 *
 * 用户名密码登录 → app.login（调后端 + 存 token）→ 跳回来源页或首页。
 * 未登录被路由守卫重定向到此，带 ?redirect= 来源路径，登录后回跳。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { Bot, User, Lock, Loader2 } from 'lucide-vue-next'
import { useAppStore } from '@/stores/app'
import { notify, handleServerError } from '@/utils/notify'

const route = useRoute()
const router = useRouter()
const app = useAppStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)

const redirectTarget = (): string => {
  const r = route.query.redirect
  const target = Array.isArray(r) ? r[0] : r
  // 只接受站内相对路径，防开放重定向
  if (target && typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    return target
  }
  return '/'
}

const doLogin = async () => {
  if (submitting.value) return
  if (!username.value.trim() || !password.value) {
    notify.warning('请输入用户名和密码')
    return
  }
  submitting.value = true
  try {
    await app.login(username.value.trim(), password.value)
    notify.success('登录成功')
    router.replace(redirectTarget())
  } catch (e) {
    handleServerError(e, { action: '登录' })
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 已登录用户直接跳走，不停留在登录页
  if (app.isAuthenticated) {
    router.replace(redirectTarget())
  }
})
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-icon"><Bot :size="28" /></div>
        <h1 class="brand-title">OpsBrain AI</h1>
        <p class="brand-sub">智维大脑 · 智能运维平台</p>
      </div>

      <form class="login-form" @submit.prevent="doLogin">
        <div class="field">
          <User :size="16" class="field-icon" />
          <input
            v-model="username"
            type="text"
            class="field-input"
            placeholder="用户名"
            autocomplete="username"
            :disabled="submitting"
          />
        </div>
        <div class="field">
          <Lock :size="16" class="field-icon" />
          <input
            v-model="password"
            type="password"
            class="field-input"
            placeholder="密码"
            autocomplete="current-password"
            :disabled="submitting"
            @keydown.enter="doLogin"
          />
        </div>
        <button type="submit" class="login-btn" :disabled="submitting">
          <Loader2 v-if="submitting" :size="16" class="spin" />
          <span>{{ submitting ? '登录中…' : '登 录' }}</span>
        </button>
      </form>

      <p class="login-hint">默认账号 admin / admin123（首次登录后请及时改密）</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a8a 0%, #0f172a 100%);
  padding: 20px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 16px;
  padding: 40px 32px 28px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.login-brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: var(--color-primary, #409eff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1f2937;
}

.brand-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #9ca3af;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  transition: border-color 0.15s;

  &:focus-within {
    border-color: var(--color-primary, #409eff);
  }
}

.field-icon {
  color: #9ca3af;
  flex-shrink: 0;
}

.field-input {
  flex: 1;
  border: none;
  outline: none;
  padding: 12px 0;
  font-size: 14px;
  background: transparent;
  color: #1f2937;

  &::placeholder {
    color: #b0b7c3;
  }
}

.login-btn {
  margin-top: 6px;
  height: 44px;
  border: none;
  border-radius: 10px;
  background: var(--color-primary, #409eff);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: filter 0.15s;

  &:hover:not(:disabled) {
    filter: brightness(1.08);
  }
  &:disabled {
    opacity: 0.7;
    cursor: not-allowed;
    letter-spacing: normal;
  }
}

.login-hint {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: #9ca3af;
}

.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
