<script setup lang="ts">
import { computed } from 'vue'

/**
 * 工单标签编辑器 —— **受控展示组件**。
 *
 * ── 拆分时刻意没做的事 ────────────────────────────────────────
 * 没有把「加标签／删标签」的业务逻辑一起搬进来。
 * 那三个函数（`addTag` / `addTagFromSuggestion` / `removeTag`）都要
 * 调 `store.updateTags` 并弹提示，`TicketDetail.actions.test.ts` 里
 * 有 6 例直接打在 `vm.addTag()` 上。
 *
 * 逻辑跟着模板一起搬，那 6 例就得改写——而**拆分的验收标准正是
 * 「既有用例一行不改直接通过」**。改了测试再看它绿，等于把标尺和被测物
 * 一起挪动，测不出搬运有没有出错。
 *
 * 所以这里只做一件事：**把 DOM 搬出来，把交互原样转成事件抛回去**。
 * 输入框走 `v-model:draft`——它是父组件的 `newTagInput`，
 * 校验「重复标签」「trim 后为空」的口径留在父组件里没有变。
 *
 * ── 为什么 `busy` 而不是沿用 `tagRemoving` ────────────────────
 * 父组件那个变量名是历史遗留：它其实是「增/删都在跑」的通用忙碌位，
 * 名字却只提了删除。跨组件边界正是重命名的时机——
 * 组件内部叫 `busy` 语义准确，父组件传的仍是同一个 ref，行为不变。
 */

const props = defineProps<{
  /** 当前标签。父组件传 `ticket.tags || []`，本组件不再处理 undefined */
  tags: string[]
  /** 热门标签建议池，取前 5 个展示 */
  hotTags: string[]
  /** 增删请求进行中——期间禁用全部交互，避免连点产生并发写 */
  busy: boolean
}>()

/** 输入框内容。用 defineModel 而非 props+emit，父组件仍能 `v-model:draft` 双向绑 */
const draft = defineModel<string>('draft', { required: true })

const emit = defineEmits<{
  /** 点「添加」或输入框回车。标签内容由父组件从 draft 读取 */
  (e: 'add'): void
  /** 点某个标签上的 × */
  (e: 'remove', tag: string): void
  /** 点热门标签建议 */
  (e: 'addSuggested', tag: string): void
}>()

/** 只展示前 5 个：右栏宽度有限，全列出来会把卡片撑成两屏 */
const hotTags = computed(() => props.hotTags)
</script>

<template>
  <div class="tag-edit-area">
    <span v-for="tag in (tags)" :key="tag" class="tag-chip">
      {{ tag }}
      <button class="tag-remove" @click="emit('remove', tag)" :disabled="busy">×</button>
    </span>
    <div class="tag-add-row">
      <input
        v-model="draft"
        class="tag-input"
        placeholder="添加标签..."
        @keydown.enter="emit('add')"
        :disabled="busy"
      />
      <button class="tag-add-btn" @click="emit('add')" :disabled="!draft.trim() || busy">添加</button>
    </div>
    <div v-if="hotTags.length" class="tag-suggestions">
      <span class="tag-suggest-label">热门：</span>
      <button
        v-for="tag in hotTags.slice(0, 5)"
        :key="tag"
        class="tag-suggest"
        @click="emit('addSuggested', tag)"
        :disabled="busy || tags.includes(tag)"
      >{{ tag }}</button>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ========== Tag Edit ========== */
.tag-edit-area { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.tag-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; font-size: 0.75rem;
  background: rgba(64, 158, 255, 0.1); color: var(--brand);
  border-radius: 4px;
}
.tag-remove {
  border: none; background: none; cursor: pointer;
  color: var(--brand); font-size: 0.875rem; line-height: 1;
  padding: 0; opacity: 0.6;
}
.tag-remove:hover { opacity: 1; }
.tag-remove:disabled { opacity: 0.3; cursor: not-allowed; }
.tag-add-row { display: flex; gap: 6px; width: 100%; margin-top: 4px; }
.tag-input {
  flex: 1; padding: 4px 8px; font-size: 0.8125rem;
  border: 1px solid var(--border-1); border-radius: 4px;
}
.tag-add-btn {
  padding: 4px 12px; font-size: 0.8125rem;
  border: 1px solid var(--brand); background: var(--brand); color: #fff;
  border-radius: 4px; cursor: pointer;
}
.tag-add-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.tag-suggestions { display: flex; flex-wrap: wrap; gap: 4px; width: 100%; margin-top: 6px; }
.tag-suggest-label { font-size: 0.75rem; color: var(--text-3); line-height: 1.6; }
.tag-suggest {
  border: 1px solid var(--border-1); background: var(--color-surface, var(--surface-1)); color: var(--text-2);
  font-size: 0.75rem; padding: 2px 8px; border-radius: 4px; cursor: pointer;
}
.tag-suggest:hover { border-color: var(--brand); color: var(--brand); }
.tag-suggest:disabled { opacity: 0.3; cursor: not-allowed; }
</style>
