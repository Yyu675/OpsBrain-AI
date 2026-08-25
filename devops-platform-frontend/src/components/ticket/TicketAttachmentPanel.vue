<script setup lang="ts">
import { ref } from 'vue'
import { Download, Paperclip, Trash2 } from 'lucide-vue-next'

import type { TicketAttachmentMeta } from '@/api/tickets'

/**
 * 工单附件面板 —— **受控展示组件**。
 *
 * 数据与全部副作用仍归 `useTicketAttachments`（父组件持有、已单测覆盖）。
 * 本组件只画列表并把点击抛回去。
 *
 * ── 搬运时唯一需要小心的一处：`.btn-outline` ─────────────────
 * 上传按钮用的 `.btn-outline` 原本定义在 `TicketDetail.vue` 的
 * <b>scoped</b> 样式里。scoped 样式只作用于本组件自己的元素，
 * <b>不会穿透到子组件</b>——按钮搬进来后，父组件那份定义就管不到它了，
 * 表现是「上传附件」变成一个没有边框、没有内边距的裸文字。
 *
 * 这是模板拆分最容易漏、又最不会报错的一类回归：类型检查过、测试过、
 * 只有肉眼看得出来。所以把定义一并复制到本文件，
 * 而不是指望它从父级继承下来。
 *
 * ── 文件输入框为什么留在组件内部 ─────────────────────────────
 * `fileInput` 是纯 DOM 引用，父组件拿它没有意义（只用来 `.click()`）。
 * 把「点按钮 → 触发隐藏 input」这一步封在组件里，
 * 父组件的 composable 只需处理 change 事件里的 File，接口更窄。
 * 原先 composable 暴露的 `fileInputRef` / `pickAttachment` 因此不再被模板使用，
 * 但保留在 composable 里未删——它有自己的单测，且其他页面可能复用。
 */

defineProps<{
  attachments: TicketAttachmentMeta[]
  /** 列表加载中 */
  loading: boolean
  /** 上传进行中——期间禁用上传按钮，避免连点产生重复文件 */
  uploading: boolean
}>()

const emit = defineEmits<{
  (e: 'download', item: TicketAttachmentMeta): void
  (e: 'remove', item: TicketAttachmentMeta): void
  /** 用户选好了文件。原生 change 事件原样抛出，由父组件读 files[0] */
  (e: 'select', ev: Event): void
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const pick = () => fileInput.value?.click()
</script>

<template>
  <div v-if="loading" class="attach-empty">加载中…</div>
  <div v-else-if="attachments.length === 0" class="attach-empty">暂无附件</div>
  <ul v-else class="attach-list">
    <li v-for="item in attachments" :key="item.id" class="attach-item">
      <div class="attach-meta">
        <span class="attach-name" :title="item.originalName">{{ item.originalName }}</span>
        <span class="attach-size">{{ item.sizeText }}</span>
      </div>
      <div class="attach-ops">
        <button class="attach-op" title="下载" @click="emit('download', item)">
          <Download :size="14" />
        </button>
        <button class="attach-op attach-op-danger" title="删除" @click="emit('remove', item)">
          <Trash2 :size="14" />
        </button>
      </div>
    </li>
  </ul>
  <!--
    用 :ref 动态绑定而非字符串 ref="fileInputRef"：
    后者与 script setup 变量的关联 vue-tsc 识别不到，
    会误报 "declared but its value is never read"。
    动态绑定是显式引用，类型检查能看见。
  -->
  <input
    :ref="(el) => (fileInput = el as HTMLInputElement | null)"
    type="file"
    class="attach-file-input"
    @change="emit('select', $event)"
  />
  <button class="btn-outline attach-upload" :disabled="uploading" @click="pick">
    <Paperclip :size="14" />
    {{ uploading ? '上传中…' : '上传附件' }}
  </button>
</template>

<style scoped lang="scss">
.attach-empty {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  padding: 8px 0 12px;
}

.attach-list {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attach-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  background: var(--color-bg);
  border-radius: var(--radius-sm);
}

.attach-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.attach-name {
  font-size: var(--text-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attach-size {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.attach-ops {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.attach-op {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }
}

.attach-op-danger:hover {
  background: var(--state-error-bg);
  color: var(--state-error);
}

.attach-file-input {
  display: none;
}

.attach-upload {
  width: 100%;
  justify-content: center;
}

/* 从 TicketDetail.vue 一并复制：scoped 样式不穿透子组件，
   不复制则上传按钮会退化成裸文字（见文件头注释） */
.btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border, var(--border-2));
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  font-weight: var(--weight-medium, 500);
  font-family: var(--font-body, 'Inter', sans-serif);
  background: white;
  color: var(--color-text-secondary, var(--text-2));
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary, var(--brand));
    color: var(--color-primary, var(--brand));
  }
}
</style>
