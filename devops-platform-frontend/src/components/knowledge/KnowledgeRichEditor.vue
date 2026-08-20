<script setup lang="ts">
import { onBeforeUnmount, shallowRef } from 'vue'
import { Editor as WangEditor, Toolbar as WangToolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'

defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorRef = shallowRef<IDomEditor>()

const toolbarConfig: Partial<IToolbarConfig> = {
  toolbarKeys: [
    'headerSelect',
    'blockquote',
    '|',
    'bold',
    'underline',
    'italic',
    'through',
    '|',
    'bulletedList',
    'numberedList',
    'todo',
    '|',
    'insertLink',
    'uploadImage',
    'insertTable',
    'codeBlock',
    'divider',
    '|',
    'undo',
    'redo',
  ],
}

const editorConfig: Partial<IEditorConfig> = {
  placeholder: '开始编写文档内容...',
  scroll: true,
  MENU_CONF: {
    uploadImage: {
      base64LimitSize: 2 * 1024 * 1024,
    },
  },
}

const handleCreated = (editor: IDomEditor) => {
  editorRef.value = editor
}

const insertHtml = (html: string) => {
  if (!editorRef.value) return
  editorRef.value.focus()
  editorRef.value.dangerouslyInsertHtml(html)
}

const focus = () => editorRef.value?.focus()

defineExpose({ insertHtml, focus })

onBeforeUnmount(() => {
  editorRef.value?.destroy()
  editorRef.value = undefined
})
</script>

<template>
  <div class="kr-editor">
    <WangToolbar
      class="kr-toolbar"
      :editor="editorRef"
      :default-config="toolbarConfig"
      mode="default"
    />
    <WangEditor
      class="kr-content"
      :model-value="modelValue"
      :default-config="editorConfig"
      mode="default"
      @update:model-value="emit('update:modelValue', $event)"
      @on-created="handleCreated"
    />
  </div>
</template>

<style scoped lang="scss">
.kr-editor {
  min-height: calc(100vh - 230px);
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--color-border-light);
  background: var(--color-bg-elevated);
}

.kr-toolbar {
  position: sticky;
  top: 0;
  z-index: 2;
  flex-shrink: 0;
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface-hover);
}

:deep(.kr-toolbar .w-e-bar) {
  min-height: 42px;
  padding: 4px 6px;
  flex-wrap: wrap;
}

.kr-content {
  flex: 1;
  min-height: calc(100vh - 280px);
  overflow-y: auto;
}

:deep(.w-e-bar) {
  background: transparent;
}

:deep(.w-e-bar-item button) {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  color: var(--color-text-secondary);
}

:deep(.w-e-bar-item button:hover) {
  background: var(--color-primary-lighter);
  color: var(--color-primary);
}

:deep(.w-e-text-container) {
  min-height: calc(100vh - 280px);
  background: var(--color-bg-elevated);
}

:deep(.w-e-text-placeholder) {
  top: 22px;
  color: var(--color-text-tertiary);
  font-style: normal;
}

:deep(.w-e-text) {
  min-height: calc(100vh - 280px);
  padding: 18px 18px 56px;
  font-size: 15px;
  line-height: 1.8;
  color: var(--color-text-primary);
}

:deep(.w-e-text h2) {
  margin: 28px 0 12px;
  font-size: 22px;
  line-height: 1.35;
}

:deep(.w-e-text h3) {
  margin: 22px 0 10px;
  font-size: 18px;
  line-height: 1.4;
}

:deep(.w-e-text pre > code) {
  font-family: var(--font-mono);
}

@media (max-width: 760px) {
  :deep(.kr-toolbar .w-e-bar) { max-height: 82px; overflow-y: auto; }
  :deep(.w-e-text) { padding: 16px 14px 44px; }
}
</style>
