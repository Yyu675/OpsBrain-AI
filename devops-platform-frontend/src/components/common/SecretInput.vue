<script setup lang="ts">
/**
 * 密钥/密码输入框（批89 清查统一——此前模型渠道 API Key、登录密码全是裸
 * `type=password`，管理员粘贴一长串 Key 无从核对有没有贴错，只能盲存赌运气）。
 *
 * 与原生 password 框的唯一差别：右侧眼睛可**随时切换明文/掩码**——
 * 密钥本来就出自管理员之手，输入框里遮它防的是肩窥与录屏，不防本人；
 * 「本人随时能看自己正在输入的值」不破坏任何安全边界，却消灭一整类
 * 「贴错一位、存完才发现」的排障浪费。
 *
 * 语义边界：本组件只管「正在输入的新值」。库里**已存**的密钥查看走
 * reveal 端点（ADMIN + 审计留痕），不要把已存值回填到这里——那会绕过留痕。
 *
 * 用法：`<SecretInput v-model="x" class="secret-wrap--box" placeholder="…" />`；
 * 需要前缀图标时放默认插槽。宽度/外边距由宿主 class 决定（class 落根节点）。
 */
import { ref, useSlots } from 'vue'
import { Eye, EyeOff } from 'lucide-vue-next'

defineOptions({ name: 'SecretInput' })

const value = defineModel<string>({ default: '' })

withDefaults(
  defineProps<{
    placeholder?: string
    autocomplete?: string
    disabled?: boolean
  }>(),
  { placeholder: '', autocomplete: 'new-password', disabled: false },
)

const slots = useSlots()
const visible = ref(false)
</script>

<template>
  <span class="secret-wrap">
    <span v-if="slots.default" class="secret-wrap__lead"><slot /></span>
    <input
      class="secret-wrap__input"
      :type="visible ? 'text' : 'password'"
      :placeholder="placeholder"
      :autocomplete="autocomplete"
      :disabled="disabled"
      :aria-label="placeholder || undefined"
      v-model="value"
    />
    <button
      type="button"
      class="secret-wrap__eye"
      :title="visible ? '隐藏' : '显示'"
      :aria-label="visible ? '隐藏密钥' : '显示密钥'"
      :aria-pressed="visible"
      tabindex="-1"
      @click="visible = !visible"
    >
      <component :is="visible ? EyeOff : Eye" :size="15" />
    </button>
  </span>
</template>

<style scoped>
.secret-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  position: relative;
  min-width: 0;
}

.secret-wrap__lead {
  display: inline-flex;
  align-items: center;
  color: var(--text-3);
  flex-shrink: 0;
}

.secret-wrap__input {
  flex: 1;
  min-width: 0;
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font: inherit;
  color: inherit;
  padding: 6px 24px 6px 0;
}

.secret-wrap__eye {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  padding: 0;

  &:hover { color: var(--text-1); }
}

/* 盒式变体：自带边框/底色/聚焦态，宿主不再需要给内部 input 写样式 */
.secret-wrap--box {
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  background: var(--surface-1);
  color: var(--text-1);
  padding: 0 10px;
  height: 32px;
  font-size: var(--text-sm);
  font-family: var(--font-body);
  transition: border-color 0.15s ease;

  &:focus-within { border-color: var(--brand); }
}
</style>
