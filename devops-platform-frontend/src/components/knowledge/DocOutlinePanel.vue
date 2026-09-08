<script setup lang="ts">
/**
 * 知识文档编辑器 · 右侧「文章大纲」面板。
 *
 * 从 `KnowledgeEditor.vue` 抽出，与 `DocPropertiesPanel` 同属右栏两个 Tab。
 *
 * ── 它只负责展示与派发，不碰正文 ──────────────────────────────
 * 标题的增 / 改 / 删都通过事件交回父组件，由父组件调用
 * `utils/editorContent` 里的 `appendHeading` / `renameHeadingIn` /
 * `removeHeadingIn`（那三个函数有 21 例单测，含索引越界保护）。
 *
 * 面板自己改正文会绕过那层保护，而**改错索引的表现是
 * 「我改了 A，B 却变了」**——不报错，用户只会觉得系统有鬼。
 *
 * ── 空态是个按钮，不是一句提示 ────────────────────────────────
 * 没有标题时展示「添加第一个二级标题」的可点区域。
 * 纯文字提示会让用户知道该做什么却不知道从哪做——
 * 大纲面板里并没有别的入口。
 */
import { FileText, Pencil, Plus, Trash2 } from 'lucide-vue-next'

import type { TocItem } from '@/utils/editorContent'

defineProps<{
  /** 由 extractToc 从正文提取，HTML 用 elementIndex、Markdown 用 lineIndex */
  items: TocItem[]
}>()

const emit = defineEmits<{
  insert: []
  rename: [item: TocItem]
  remove: [item: TocItem]
  scrollTo: [item: TocItem]
}>()
</script>

<template>
  <div class="ce-side-head">
    <FileText :size="15" />
    <span>文章大纲</span>
    <button
      class="ce-side-head-action"
      type="button"
      title="新增二级标题"
      @click="emit('insert')"
    >
      <Plus :size="15" />
    </button>
  </div>

  <div v-if="items.length" class="ce-toc-list">
    <div v-for="item in items" :key="item.id" class="ce-toc-row">
      <button
        class="ce-toc-item"
        :class="{ 'level-three': item.level === 3 }"
        type="button"
        @click="emit('scrollTo', item)"
      >{{ item.text }}</button>
      <button type="button" title="重命名标题" @click="emit('rename', item)">
        <Pencil :size="13" />
      </button>
      <button type="button" title="删除标题" @click="emit('remove', item)">
        <Trash2 :size="13" />
      </button>
    </div>
  </div>

  <!-- 空态做成可点按钮：纯文字提示会让用户知道该做什么却不知道从哪做 -->
  <button v-else class="ce-toc-empty ce-toc-empty-action" type="button" @click="emit('insert')">
    <Plus :size="14" /> 添加第一个二级标题
  </button>
</template>

<style scoped lang="scss">
.ce-toc-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ce-toc-item {
  flex: 1;
  min-width: 0;
  display: block;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  text-decoration: none;
  padding: 6px 8px;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;

  &:hover {
    color: var(--color-primary);
    background: var(--color-bg);
  }
}

.ce-toc-item.level-three {
  padding-left: 22px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.ce-toc-row { display: flex; align-items: center; gap: 2px; border-radius: 4px; }

.ce-toc-row > button:not(.ce-toc-item) { width: 25px; height: 25px; display: none; align-items: center; justify-content: center; color: var(--color-text-tertiary); border-radius: 4px; }

.ce-toc-row:hover { background: var(--color-bg); }

.ce-toc-row:hover > button { display: inline-flex; }

.ce-toc-row > button:not(.ce-toc-item):hover { color: var(--color-primary); background: var(--color-primary-lighter); }

.ce-toc-empty {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  padding: 4px 10px;
}

.ce-side-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border-light);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
}

.ce-side-head-action { width: 26px; height: 26px; margin-left: auto; display: inline-flex; align-items: center; justify-content: center; border-radius: 4px; color: var(--color-text-tertiary); }

.ce-side-head-action:hover { color: var(--color-primary); background: var(--color-primary-lighter); }</style>
