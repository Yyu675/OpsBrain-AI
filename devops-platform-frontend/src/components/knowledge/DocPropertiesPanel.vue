<script setup lang="ts">
/**
 * 知识文档编辑器 · 右侧「文档属性」面板。
 *
 * 从 `KnowledgeEditor.vue` 抽出。它是该文件里边界最清晰的 UI 单元：
 * 分类 / 标签 / 摘要 / 发布开关四组表单，彼此独立，
 * 与编辑器主体只通过 v-model 与四个回调交互。
 *
 * ── 为什么抽成子组件而不是又一批函数 ──────────────────────────
 * 前几轮抽的都是纯函数（内容转换、标题操作、分类路径）。
 * 这一块不同：它<b>没有可抽的纯逻辑，全是模板与绑定</b>。
 * 留在主文件里只会让那 105 行模板继续淹没编辑器本身的结构，
 * 而它的样式（150 行）也一直混在主文件的 scoped style 里。
 *
 * 抽出后主文件少了 255 行，且这块 UI 可以单独测试与改版。
 *
 * ── 状态归属：受控组件 ────────────────────────────────────────
 * 分类 / 标签 / 摘要 / 发布开关全部走 `defineModel`，
 * 真实状态仍在 `KnowledgeEditor` 的 `formData` 上。
 *
 * 这是刻意的：草稿自动暂存、离开确认、保存校验都读那一份 `formData`，
 * 面板自己持有状态会立刻产生两份真相——用户改了分类却没进草稿，
 * 是这类拆分最典型的回归。
 */
import { Plus, Settings2, Sparkles } from 'lucide-vue-next'

import type { KnowledgeCategoryEntity, KnowledgeTag } from '@/api/types'
import { buildCategoryPath, MAX_TAGS } from '@/utils/editorContent'

/** 分类名（非 ID）——与 formData.category 一致，ID 由主文件按名反查 */
const category = defineModel<string>('category', { required: true })
const tags = defineModel<string[]>('tags', { required: true })
const summary = defineModel<string>('summary', { required: true })
/** 仅新建时有意义：保存后是否立即发布 */
const publishOnCreate = defineModel<boolean>('publishOnCreate', { required: true })
const changeReason = defineModel<string>('changeReason', { required: true })

const props = defineProps<{
  /** 目录分类全集，用于下拉与路径展示 */
  categories: KnowledgeCategoryEntity[]
  /** 已登记标签，供下拉选择（用户仍可 allow-create 新建） */
  managedTags: KnowledgeTag[]
  /** 热门标签，一键添加 */
  hotTags: Array<{ tag: string; count?: number }>
  /** 新建 or 编辑——两种模式下最后一组展示完全不同 */
  isNew: boolean
  /** 编辑模式下的当前版本号 */
  currentVersion: number
  /** 编辑模式下文档是否仍是草稿 */
  isDraft: boolean
  /** 正文是否非空——决定「自动生成摘要」按钮是否出现 */
  hasContent: boolean
}>()

const emit = defineEmits<{
  createCategory: []
  generateSummary: []
  addTag: [tag: string]
  normalizeTags: []
}>()

/** 分类下拉的展示文案：完整路径（含防环，见 buildCategoryPath） */
const categoryLabel = (cat: KnowledgeCategoryEntity) =>
  buildCategoryPath(cat, props.categories)

/**
 * 热门标签是否不可点。
 *
 * 两个条件缺一不可：已添加过、或已达上限。
 * 只判其一会让用户点了没反应却不知道为什么——
 * 达上限时按钮仍可点，点完弹一句警告，比直接置灰差。
 */
const hotTagDisabled = (tag: string) =>
  tags.value.includes(tag) || tags.value.length >= MAX_TAGS
</script>

<template>
  <div class="ce-side-head">
    <Settings2 :size="15" />
    <span>文档属性</span>
  </div>

  <div class="ce-side-group">
    <label class="ce-side-label">文档分类</label>
    <div class="ce-category-control">
      <el-select v-model="category" filterable clearable placeholder="选择目录分类">
        <el-option
          v-for="cat in categories"
          :key="cat.id"
          :label="categoryLabel(cat)"
          :value="cat.name"
        />
      </el-select>
      <button type="button" title="新建分类" @click="emit('createCategory')">
        <Plus :size="15" />
      </button>
    </div>
  </div>

  <div class="ce-side-group">
    <label class="ce-side-label">标签（最多 {{ MAX_TAGS }} 个）</label>
    <el-select
      v-model="tags"
      multiple
      filterable
      allow-create
      default-first-option
      :multiple-limit="MAX_TAGS"
      placeholder="输入标签后回车"
      style="width: 100%"
      @change="emit('normalizeTags')"
    >
      <el-option v-for="tag in managedTags" :key="tag.id" :label="tag.name" :value="tag.name" />
    </el-select>
    <div v-if="hotTags.length" class="ce-hot-tags">
      <span class="ce-hot-title">热门：</span>
      <button
        v-for="ht in hotTags.slice(0, 8)"
        :key="ht.tag"
        type="button"
        class="ce-hot-tag"
        :disabled="hotTagDisabled(ht.tag)"
        @click="emit('addTag', ht.tag)"
      >
        {{ ht.tag }}
      </button>
    </div>
  </div>

  <div class="ce-side-group">
    <label class="ce-side-label">摘要（可选）</label>
    <textarea
      v-model="summary"
      class="ce-excerpt-input"
      placeholder="简要描述文档内容"
      rows="4"
      maxlength="200"
    ></textarea>
    <div class="ce-excerpt-footer">
      <span class="ce-excerpt-count">
        {{ summary.length }}/200 · 留空将自动提取前 150 字
      </span>
      <button
        v-if="hasContent"
        class="ce-auto-excerpt"
        type="button"
        @click="emit('generateSummary')"
      >
        <Sparkles :size="13" />
        <span>自动生成</span>
      </button>
    </div>
  </div>

  <div class="ce-side-group">
    <!-- 新建：发布开关 -->
    <div v-if="isNew" class="ce-publish-block">
      <div class="ce-publish-head">
        <span class="ce-publish-title">保存后立即发布</span>
        <el-switch v-model="publishOnCreate" />
      </div>
      <p class="ce-publish-desc">发布后触发向量化，可被 AI 检索；关闭则仅存草稿</p>
    </div>
    <!-- 编辑：版本信息 + 变更说明 -->
    <div v-else class="ce-publish-block">
      <div class="ce-publish-head">
        <span class="ce-publish-title">
          当前版本 v{{ currentVersion }}
          <span v-if="isDraft" class="ce-draft-tag">草稿</span>
        </span>
      </div>
      <p class="ce-publish-desc">保存后版本号 +1；状态为草稿时可在详情页发布</p>
      <el-input
        v-model="changeReason"
        maxlength="100"
        placeholder="变更说明（可选，将记入版本历史）"
        style="width: 100%"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 样式随组件一起搬过来——留在主文件里会成为找不到归属的孤儿规则 */
.ce-category-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  gap: 6px;
}

.ce-category-control > button {
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 5px;
  color: var(--color-text-secondary);
}

.ce-category-control > button:hover { border-color: var(--color-primary-light); color: var(--color-primary); }

.ce-side-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.ce-side-label {
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  color: var(--color-text-tertiary);
}

.ce-hot-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.ce-hot-title {
  color: var(--color-text-tertiary);
}

.ce-hot-tag {
  padding: 3px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-full);
  background: var(--color-surface-hover);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  transition: all 0.15s ease;

  &:hover:not(:disabled) {
    border-color: var(--color-primary-light);
    color: var(--color-primary);
  }

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.ce-excerpt-input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: inherit;
  line-height: 1.5;
  resize: none;
  box-sizing: border-box;
  transition: border-color 0.15s ease;

  &:focus {
    border-color: var(--color-primary-light);
  }
}

.ce-excerpt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ce-excerpt-count {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  line-height: 1.4;
}

.ce-auto-excerpt {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  background: var(--color-bg-elevated);
  color: var(--color-primary);
  font-size: var(--text-xs);
  white-space: nowrap;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    background: var(--color-primary-lighter);
  }
}

.ce-publish-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ce-publish-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ce-publish-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
}

.ce-publish-desc {
  margin: 0;
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--color-text-tertiary);
}

.ce-draft-tag {
  margin-left: 6px;
  padding: 1px 8px;
  font-size: var(--text-xs);
  font-weight: var(--weight-normal);
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
  border-radius: var(--radius-full);
}
</style>
