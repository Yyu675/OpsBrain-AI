<script setup lang="ts">
/**
 * B4 复盘归档抽屉。
 *
 * 从 TicketDetail 拆出（该文件近 2900 行）。逻辑在 useTicketPostmortem，
 * 本组件只负责渲染与事件转发。
 *
 * 拆分时修掉一处样式缺陷：原模板用裸 `.form-input` / `.form-row` 类名，
 * 而 TicketDetail 里这两个类是定义在 `.dialog-form` 之下的后代选择器，
 * 抽屉的容器是 `.pm-drawer`，因此**这些样式从未命中**——抽屉里的输入框
 * 一直是浏览器默认样式。本组件自带样式，不再依赖外部作用域。
 */
import type { ActionItemData, PostmortemData } from '@/api/tickets'

/** 改进项状态选项（与后端 sys_postmortem_action_item.status 对齐） */
const STATUS_OPTIONS = [
  { value: 'OPEN', label: '待开始' },
  { value: 'DOING', label: '进行中' },
  { value: 'DONE', label: '已完成' },
  { value: 'DROPPED', label: '已放弃' },
]

export interface PostmortemForm {
  timeline: string
  impactScope: string
  impactDuration: number | null | undefined
  lessons: string
}

export interface NewActionItemForm {
  content: string
  owner: string
  dueDate: string
}

/**
 * 双向绑定用 defineModel 而非直接改 prop。
 *
 * 表单状态由父组件（useTicketPostmortem）持有——保存/加载逻辑都在那里，
 * 组件内再复制一份会与之漂移。defineModel 是 Vue 3.4+ 的正规双向绑定，
 * 直接写 `props.form.x = v` 会触发 vue/no-mutating-props（改 prop 是反模式：
 * 父组件不知道值被改了，重渲染时可能覆盖回去）。
 */
const visible = defineModel<boolean>('visible', { required: true })
const form = defineModel<PostmortemForm>('form', { required: true })
const newActionItem = defineModel<NewActionItemForm>('newActionItem', { required: true })

const props = defineProps<{
  actionItems: ActionItemData[]
  /** 复盘记录。为 null 表示尚未保存过——此时不能添加改进项（无 postmortemId 可挂） */
  postmortem: PostmortemData | null
  saving: boolean
}>()

const emit = defineEmits<{
  'generate-draft': []
  save: []
  'add-item': []
  'update-item-status': [itemId: number, status: string]
}>()

const onStatusChange = (itemId: number | undefined, event: Event) => {
  if (itemId === undefined) return
  emit('update-item-status', itemId, (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <el-drawer
    v-model="visible"
    title="复盘归档"
    size="700px"
    :close-on-click-modal="false"
  >
    <div class="pm-drawer">
      <div class="pm-section">
        <div class="pm-section-head">
          <h4>时间线</h4>
          <button class="link-btn" type="button" @click="emit('generate-draft')">生成草稿</button>
        </div>
        <textarea
          v-model="form.timeline"
          class="pm-input pm-textarea"
          rows="10"
          placeholder="可自动生成草稿后编辑"
        ></textarea>
      </div>

      <div class="pm-section">
        <h4>影响与教训</h4>
        <div class="pm-row">
          <label>影响范围</label>
          <input
            v-model="form.impactScope"
            type="text"
            class="pm-input"
            placeholder="受影响的服务/用户/时长"
          />
        </div>
        <div class="pm-row">
          <label>影响时长(分钟)</label>
          <input v-model.number="form.impactDuration" type="number" class="pm-input" min="0" />
        </div>
        <div class="pm-row">
          <label>经验教训</label>
          <textarea v-model="form.lessons" class="pm-input" rows="4"></textarea>
        </div>
      </div>

      <div class="pm-section">
        <h4>改进项</h4>
        <div class="pm-action-list">
          <div v-for="item in props.actionItems" :key="item.id" class="pm-action-item">
            <span class="pm-action-content">{{ item.content }}</span>
            <span v-if="item.owner" class="pm-action-owner">@{{ item.owner }}</span>
            <span v-if="item.dueDate" class="pm-action-due">{{ item.dueDate }}</span>
            <select
              :value="item.status"
              class="pm-action-status"
              @change="onStatusChange(item.id, $event)"
            >
              <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>
          <div v-if="!props.actionItems.length" class="pm-empty">暂无改进项</div>
        </div>

        <!--
          复盘未保存时禁用添加：改进项挂在复盘记录下（需 postmortemId），
          此时点添加会静默无反应——置灰并说明原因比让用户困惑更好
        -->
        <div class="pm-action-add">
          <input
            v-model="newActionItem.content"
            type="text"
            class="pm-input"
            placeholder="改进项内容"
            :disabled="!props.postmortem?.id"
          />
          <input
            v-model="newActionItem.owner"
            type="text"
            class="pm-input pm-owner-input"
            placeholder="责任人"
            :disabled="!props.postmortem?.id"
          />
          <input
            v-model="newActionItem.dueDate"
            type="date"
            class="pm-input pm-date-input"
            :disabled="!props.postmortem?.id"
          />
          <button
            class="pm-btn-outline"
            type="button"
            :disabled="!props.postmortem?.id"
            :title="props.postmortem?.id ? '' : '请先保存复盘，改进项需挂在复盘记录下'"
            @click="emit('add-item')"
          >添加</button>
        </div>
        <p v-if="!props.postmortem?.id" class="pm-hint">
          先保存复盘后才能添加改进项
        </p>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="props.saving" @click="emit('save')">
        {{ props.saving ? '保存中…' : '保存复盘' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.pm-drawer {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.pm-section h4 {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary, #1f2937);
  margin: 0 0 10px 0;
}

.pm-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.pm-section-head h4 { margin: 0; }

/* 表单控件样式随组件自带 —— 原实现依赖 TicketDetail 的 `.dialog-form .form-input`
   后代选择器，在 `.pm-drawer` 下从未命中 */
.pm-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;

  label {
    font-size: 13px;
    font-weight: 500;
    color: var(--color-text-secondary, #4b5563);
  }
}

.pm-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--color-border, #E5E7EB);
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  box-sizing: border-box;

  &:focus {
    outline: none;
    border-color: var(--color-primary, #1B4F9C);
  }

  &:disabled {
    background: var(--color-bg-sunken, #F9FAFB);
    cursor: not-allowed;
  }
}

.pm-textarea {
  font-family: var(--font-mono, monospace);
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}

.pm-action-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pm-action-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--color-bg-sunken, #F9FAFB);
  border-radius: 6px;
  font-size: 13px;
}

.pm-action-content { flex: 1; color: var(--color-text-primary, #1f2937); }
.pm-action-owner { color: var(--color-text-secondary, #4b5563); font-size: 12px; }
.pm-action-due { color: var(--color-text-tertiary, #9ca3af); font-size: 12px; }

.pm-action-status {
  padding: 2px 8px;
  border: 1px solid var(--color-border, #E5E7EB);
  border-radius: 4px;
  font-size: 12px;
  background: #fff;
}

.pm-empty {
  text-align: center;
  color: var(--color-text-tertiary, #9ca3af);
  font-size: 13px;
  padding: 16px;
}

.pm-action-add {
  display: flex;
  gap: 6px;
  margin-top: 10px;
}

.pm-owner-input { width: 100px; flex-shrink: 0; }
.pm-date-input { width: 140px; flex-shrink: 0; }

.pm-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.pm-btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--color-border, #D1D5DB);
  border-radius: var(--radius-md, 8px);
  font-size: var(--text-sm, 0.875rem);
  font-weight: var(--weight-medium, 500);
  font-family: var(--font-body, 'Inter', sans-serif);
  background: white;
  color: var(--color-text-secondary, #4B5563);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;

  &:hover:not(:disabled) {
    border-color: var(--color-primary, #1B4F9C);
    color: var(--color-primary, #1B4F9C);
  }

  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.link-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  color: var(--color-primary, #3B82F6);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}
</style>
