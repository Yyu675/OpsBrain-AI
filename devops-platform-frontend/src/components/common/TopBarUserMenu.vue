<script setup lang="ts">
/**
 * TopBarUserMenu —— 右上角账户区（头像 + 下拉四项）（批93 自 AppTopBar 拆出）。
 *
 * 纯展示组件：姓名/角色由宿主解析后传入，跳转与登出一律上抛
 * （profile / settings / help / logout / toggle），ProfileDialog 留在宿主
 * ——AppTopBar 测试对宿主 vm 的断言因此原样成立。
 */
import { User, Settings, LifeBuoy, LogOut } from 'lucide-vue-next'
import AvatarFallback from '@/components/common/AvatarFallback.vue'

defineProps<{ show: boolean; name: string; role: string }>()
defineEmits<{ toggle: []; profile: []; settings: []; help: []; logout: [] }>()
</script>

<template>
  <div class="user-menu-wrapper">
    <button
      class="user-btn"
      type="button"
      :title="`${name}（${role}）`"
      @click.stop="$emit('toggle')"
    >
      <AvatarFallback :name="name" :size="28" />
    </button>

    <div v-if="show" class="user-dropdown" @click.stop>
      <div class="user-dropdown-head">
        <span class="user-dropdown-name">{{ name }}</span>
        <span class="user-dropdown-role">{{ role }}</span>
      </div>
      <button class="dropdown-item" @click="$emit('profile')">
        <User :size="15" />
        个人中心
      </button>
      <button class="dropdown-item" @click="$emit('settings')">
        <Settings :size="15" />
        系统设置
      </button>
      <button class="dropdown-item" @click="$emit('help')">
        <LifeBuoy :size="15" />
        帮助中心
      </button>
      <div class="dropdown-divider"></div>
      <button class="dropdown-item dropdown-item-danger" @click="$emit('logout')">
        <LogOut :size="15" />
        退出登录
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ===== 用户菜单（右上角账户区） ===== */
.user-menu-wrapper {
  position: relative;
}

.user-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--border-1);
  border-radius: 50%;
  background: var(--surface-1);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);

  &:hover {
    background: var(--surface-hover);
    border-color: var(--border-2, var(--border-1));
  }
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 200px;
  padding: 6px;
  background: var(--surface-3);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
  animation: dropdown-in var(--duration-fast) var(--ease-out);
}

/* 两个下拉组件各持一份（scoped keyframes 按 SFC 归属，跨组件引用会失效） */
@keyframes dropdown-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.user-dropdown-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px 10px;
  border-bottom: 1px solid var(--border-1);
  margin-bottom: 4px;
}

.user-dropdown-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-dropdown-role {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  color: var(--text-1);
  cursor: pointer;
  border-radius: var(--radius-sm);
  text-align: left;
  transition: background var(--duration-fast) var(--ease-out);

  &:hover { background: var(--surface-hover); }
}

.dropdown-item-danger {
  color: var(--danger);

  &:hover { background: var(--danger-subtle, var(--surface-hover)); }
}

.dropdown-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--border-1);
}
</style>
