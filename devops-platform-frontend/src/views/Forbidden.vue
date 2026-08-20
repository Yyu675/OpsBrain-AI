<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ShieldAlert, Home, ArrowLeft } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()

const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<template>
  <div class="forbidden">
    <main class="main">
      <div class="card">
        <div class="icon">
          <ShieldAlert :size="40" />
        </div>
        <h1 class="title">403 · 无权访问该页面</h1>
        <p class="path">{{ route.query.from || route.fullPath }}</p>
        <p class="hint">当前账号权限不足，若认为是异常情况请联系管理员开通。</p>
        <div class="actions">
          <button class="btn btn-primary" @click="router.push('/')">
            <Home :size="14" />
            返回首页
          </button>
          <button class="btn" @click="goBack">
            <ArrowLeft :size="14" />
            上一页
          </button>
        </div>
        <div class="shortcuts">
          <RouterLink to="/dashboard" class="shortcut">数据仪表盘</RouterLink>
          <RouterLink to="/tickets" class="shortcut">智能工单</RouterLink>
          <RouterLink to="/knowledge" class="shortcut">知识库</RouterLink>
          <RouterLink to="/help" class="shortcut">帮助中心</RouterLink>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped lang="scss">
.forbidden {
  min-height: 100vh;
  background: var(--color-bg);
}

.main {
  max-width: 720px;
  margin: 0 auto;
  padding: 64px 24px;
}

.card {
  padding: 40px 32px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  text-align: center;
}

.icon {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.title {
  font-size: var(--text-2xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 8px 0;
}

.path {
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  padding: 4px 10px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-sm);
  display: inline-block;
  margin: 0 0 12px 0;
  max-width: 100%;
  overflow-wrap: break-word;
}

.hint {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  margin: 0 0 24px 0;
}

.actions {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 24px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }

  &.btn-primary {
    background: var(--color-primary);
    border-color: var(--color-primary);
    color: white;

    &:hover {
      background: var(--color-primary-light);
      color: white;
    }
  }
}

.shortcuts {
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  padding-top: 20px;
  border-top: 1px solid var(--color-border-light);
}

.shortcut {
  font-size: var(--text-sm);
  color: var(--color-primary-light);
  text-decoration: none;

  &:hover {
    color: var(--color-primary);
    text-decoration: underline;
  }
}
</style>
