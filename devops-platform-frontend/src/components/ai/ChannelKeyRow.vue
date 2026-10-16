<script setup lang="ts">
/**
 * 渠道卡片「脱敏 Key」行（批89 补 reveal）。
 *
 * 默认显示打码串；点眼睛走 reveal 端点换明文（限 ADMIN + 后端审计留痕），
 * 再点收起。复制按钮只在明文态出现——拷的是屏幕上看清的那个值，
 * 盲拷打码串是假功能。
 */
import { Copy, Eye, EyeOff } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    /** 打码展示串（列表接口给的） */
    masked: string | null
    /** reveal 明文；null=未展开。空串=渠道没配 */
    revealed: string | null | undefined
    /** 该渠道没有配 Key（如 reranker 未填）→ 不给眼睛 */
    busy?: boolean
  }>(),
  { revealed: undefined, busy: false },
)

defineEmits<{ toggle: []; copy: [] }>()
</script>

<template>
  <span class="field-value field-value--mono key-row">
    <span v-if="revealed !== undefined && revealed !== null" class="key-row__plain">{{ revealed || '未配置' }}</span>
    <span v-else-if="revealed === null" class="key-row__warn">解不开（主密钥变更？请重存）</span>
    <span v-else>{{ masked || '—' }}</span>
    <button
      v-if="masked"
      class="key-row__btn"
      :disabled="busy"
      :title="revealed == null ? '查看明文（记入审计）' : '收起明文'"
      :aria-label="revealed == null ? '查看明文' : '收起明文'"
      @click="$emit('toggle')"
    >
      <component :is="revealed == null ? Eye : EyeOff" :size="12" />
    </button>
    <button
      v-if="revealed"
      class="key-row__btn"
      title="复制明文"
      aria-label="复制明文"
      @click="$emit('copy')"
    >
      <Copy :size="12" />
    </button>
  </span>
</template>

<style scoped>
.key-row { display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.key-row__plain { word-break: break-all; }
.key-row__warn { color: var(--warning); }
.key-row__btn {
  display: inline-flex; align-items: center;
  border: none; background: transparent; cursor: pointer;
  color: var(--text-3); padding: 2px;
  &:hover { color: var(--brand); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}
</style>
