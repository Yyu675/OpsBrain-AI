<script setup lang="ts">
/**
 * 登录页（方向三：Sa-Token 鉴权）
 *
 * 用户名密码登录 → app.login（调后端 + 存 token）→ 跳回来源页或首页。
 * 未登录被路由守卫重定向到此，带 ?redirect= 来源路径，登录后回跳。
 */
import { ref, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { Bot, User, Lock, Loader2 } from 'lucide-vue-next'
import { useAppStore } from '@/stores/app'
import { notify, handleServerError } from '@/utils/notify'
import { safeInternalPath } from '@/utils/safeRedirect'
import SecretInput from '@/components/common/SecretInput.vue'

const route = useRoute()
const router = useRouter()
const app = useAppStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const usernameRef = ref<HTMLInputElement | null>(null)

// import.meta 只能在 <script> 中使用，不能出现在模板表达式里（Vue 编译器/打包器无法解析）
const isDev = import.meta.env.DEV

/**
 * 登录后的回跳目标。
 *
 * 校验交给 safeInternalPath——原先的 `startsWith('/') && !startsWith('//')`
 * 挡不住 `/\evil.com` 与 `/\t/evil.com`（反斜杠按正斜杠解析、
 * 制表符被剥离后重解析），二者都会被浏览器当成 https://evil.com。
 */
const redirectTarget = (): string => safeInternalPath(route.query.redirect)

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

onMounted(async () => {
  // 已登录用户直接跳走，不停留在登录页。
  // 带 token 直达 /login（书签/后退/分享链接）时内存态还是初始 false——
  // 本路由是 public，守卫不跑 restoreSession，故这里主动恢复一次。
  // 历史缺陷：此页此前只看内存态，"明明登录了又见登录页、被迫重新输密码"。
  if (!app.isAuthenticated) {
    await app.restoreSession()
  }
  if (app.isAuthenticated) {
    router.replace(redirectTarget())
    return
  }
  // 未登录时自动聚焦用户名框，用户可立即输入
  nextTick(() => usernameRef.value?.focus())
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
            ref="usernameRef"
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
          <SecretInput
            v-model="password"
            class="login-secret"
            placeholder="密码"
            autocomplete="current-password"
            :disabled="submitting"
          />
        </div>
        <button type="submit" class="login-btn" :disabled="submitting">
          <Loader2 v-if="submitting" :size="16" class="spin" />
          <span>{{ submitting ? '登录中…' : '登 录' }}</span>
        </button>
      </form>

      <p v-if="isDev" class="login-hint">开发模式：默认账号 admin / admin123</p>
      <p v-else class="login-hint production-hint">请输入您的账号密码登录</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 全页令牌化（批91）：登录页此前是全站色值孤岛——深蓝渐变/#fff/#b0b7c3
   硬编码，暗色与配色轴切过去整页不变。现由 --brand 派生，四轴（明暗/配色/
   圆角/密度）自动跟随主题；bare 页无壳，更要跟着主题走。 */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(100% 80% at 18% 8%,
      color-mix(in oklch, var(--brand) 30%, var(--surface-2)) 0%, transparent 62%),
    linear-gradient(150deg,
      color-mix(in oklch, var(--brand) 16%, var(--surface-2)) 0%,
      var(--surface-2) 58%,
      color-mix(in oklch, var(--brand) 12%, var(--surface-3)) 100%);
  padding: var(--space-5);
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  padding: var(--space-10) var(--space-8) var(--space-8);
  box-shadow: var(--shadow-lg);
}

.login-brand {
  text-align: center;
  margin-bottom: var(--space-6);
}

.brand-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto var(--space-3);
  border-radius: var(--radius-lg);
  /* 品牌渐变块：--brand 与 --info 掺面明暗都成立；投影带品牌色让块「浮」起来 */
  background: linear-gradient(135deg, var(--brand) 0%, var(--info) 100%);
  color: var(--text-inverse);
  box-shadow: 0 6px 18px color-mix(in oklch, var(--brand) 40%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-title {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--text-1);
  letter-spacing: 0.5px;
}

.brand-sub {
  margin: var(--space-1) 0 0;
  font-size: var(--text-sm);
  color: var(--text-3);
  letter-spacing: 2px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.field {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-1);
  transition: border-color var(--duration-fast) var(--ease-out),
              box-shadow var(--duration-fast) var(--ease-out);

  &:focus-within {
    border-color: var(--brand);
    box-shadow: 0 0 0 3px var(--brand-subtle);
  }
}

.field-icon {
  color: var(--text-3);
  flex-shrink: 0;
}

.field-input {
  flex: 1;
  border: none;
  outline: none;
  padding: var(--space-3) 0;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: transparent;
  color: var(--text-1);

  &::placeholder {
    color: var(--text-3);
  }
}

/* 密码框内嵌眼睛后的行内适配：wrap 撑满 .field，输入区与用户名框同高。
   @keydown.enter 落在 wrap 根上冒泡自内层 input——回车登录不丢。 */
.login-secret {
  flex: 1;

  :deep(.secret-wrap__input) {
    padding: var(--space-3) 26px var(--space-3) 0;
    font-size: var(--text-sm);

    &::placeholder { color: var(--text-3); }
  }
}

.login-btn {
  margin-top: var(--space-2);
  height: 44px;
  border: none;
  border-radius: var(--radius);
  background: var(--brand);
  color: var(--text-inverse);
  font-size: var(--text-base);
  font-family: var(--font-body);
  font-weight: var(--weight-semibold);
  letter-spacing: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  transition: filter var(--duration-fast) var(--ease-out);

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
  margin: var(--space-5) 0 0;
  text-align: center;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.spin {
  animation: spin 1s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
