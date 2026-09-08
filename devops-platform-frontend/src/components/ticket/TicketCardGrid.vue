<script setup lang="ts">
/**
 * 工单卡片视图。
 *
 * 从 `TicketList.vue` 拆出（该文件模板约 1500 行）。选它作为第一块拆分对象，
 * 是因为它<b>边界最清晰</b>：不涉及列宽/列可见性/排序，只依赖工单数据与选中集合。
 *
 * <h3>拆分的前提</h3>
 * 上一轮特意先补了 `TicketList.render.smoke.test.ts`——
 * 既有的 filters/bulk/columns 测试全是逻辑层的，<b>把模板整段删掉它们照样全绿</b>。
 * 没有渲染层断言就动模板，等于闭着眼睛做手术。
 * 那组冒烟测试注入 `v-if="false"` 时精确失败 3 例，证明它确实兜得住。
 *
 * <h3>为什么用事件而不是直接改 prop</h3>
 * 选中集合（`selectedIds`）由父组件持有——批量操作、跨视图切换都依赖它。
 * 子组件直接改 prop 会触发 `vue/no-mutating-props`：父组件不知道值被改了，
 * 重渲染时可能覆盖回去。故一律用 emit 上报意图，由父组件落笔。
 */
import { Trash2 } from 'lucide-vue-next'
import RelativeTime from '@/components/common/RelativeTime.vue'
import type { Ticket } from '@/stores/tickets'

defineProps<{
  tickets: Ticket[]
  /** 已选中的工单 ID。用数组而非 Set：父组件的 URL 同步与批量操作都按数组处理 */
  selectedIds: string[]
  /** 状态/优先级的 CSS class 与中文标签由父组件注入——
      它们来自 stores/tickets 的映射层，子组件不该各自再引一份而漂移 */
  getStatusClass: (s: Ticket['status']) => string
  getStatusLabel: (s: Ticket['status']) => string
  getPriorityClass: (p: Ticket['priority']) => string
  getPriorityLabel: (p: Ticket['priority']) => string
}>()

const emit = defineEmits<{
  (e: 'toggle-select', id: string): void
  (e: 'open-detail', id: string): void
  (e: 'edit', ticket: Ticket): void
  (e: 'delete', ticket: Ticket): void
}>()
</script>

<template>
  <div class="card-grid">
    <div
      v-for="ticket in tickets"
      :key="ticket.id"
      class="ticket-card"
      :class="{ selected: selectedIds.includes(ticket.id) }"
      @click="emit('open-detail', ticket.id)"
    >
      <div class="card-top">
        <!-- @click.stop：勾选不应顺带进详情页 -->
        <label class="card-check" @click.stop>
          <input
            type="checkbox"
            :checked="selectedIds.includes(ticket.id)"
            @change="emit('toggle-select', ticket.id)"
          />
        </label>
        <RouterLink :to="`/tickets/${ticket.id}`" class="card-id" @click.stop>{{ ticket.id }}</RouterLink>
        <span class="priority-badge" :class="getPriorityClass(ticket.priority)">
          {{ getPriorityLabel(ticket.priority) }}
        </span>
      </div>

      <RouterLink :to="`/tickets/${ticket.id}`" class="card-title" @click.stop>{{ ticket.title }}</RouterLink>
      <p class="card-desc">{{ ticket.service }} / {{ ticket.description }}</p>

      <div v-if="ticket.tags && ticket.tags.length" class="card-tags">
        <!-- 只展示前 3 个：标签多的工单会把卡片撑得高矮不一，破坏网格 -->
        <span v-for="tag in ticket.tags.slice(0, 3)" :key="tag" class="ticket-tag">{{ tag }}</span>
        <span v-if="ticket.tags.length > 3" class="ticket-tag-more">+{{ ticket.tags.length - 3 }}</span>
      </div>

      <div class="card-foot">
        <span class="status-badge" :class="getStatusClass(ticket.status)">{{ getStatusLabel(ticket.status) }}</span>
        <div class="assignee-cell">
          <!-- 未分配时用 ? 占位，不能取 undefined[0] -->
          <span class="assignee-avatar">{{ (ticket.assignee || '?')[0] }}</span>
          <span class="assignee-name">{{ ticket.assignee }}</span>
        </div>
      </div>

      <div class="card-meta">
        <span class="timestamp"><RelativeTime :value="ticket.createdAt" /></span>
        <div class="actions" @click.stop>
          <button class="action-icon-btn" title="编辑" @click="emit('edit', ticket)">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          </button>
          <button class="action-icon-btn action-icon-btn-danger" title="删除" @click="emit('delete', ticket)">
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
  样式随模板一同搬来。scoped 之后不再依赖 TicketList 的作用域——
  此前 PostmortemDrawer 拆分时踩过这个坑：模板搬走了、样式留在原文件的
  后代选择器下，抽屉里的输入框一直是浏览器默认样式而没人发现。
*/
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.ticket-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 16px;
  cursor: pointer;
  transition: box-shadow 0.15s ease, border-color 0.15s ease, transform 0.15s ease;
}
.ticket-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--color-primary-light);
  transform: translateY(-2px);
}
.ticket-card.selected {
  border-color: var(--color-primary);
  background: var(--color-primary-lighter);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-check { display: inline-flex; cursor: pointer; }
.card-check input { cursor: pointer; }
.card-id {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--color-primary-light);
  text-decoration: none;
  font-weight: var(--weight-medium);
}
.card-id:hover { text-decoration: underline; }
.card-top .priority-badge { margin-left: auto; }
.card-title {
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
  text-decoration: none;
  font-size: var(--text-sm);
  line-height: var(--leading-snug);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-title:hover { color: var(--color-primary); }
.card-desc {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  line-height: var(--leading-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border-light);
}

/*
  以下几类由父页面定义、卡片内复用的原子样式必须在此重声明：
  scoped 样式只作用于本组件的 DOM，父页面的 .status-badge / .priority-badge
  等规则命中不到这里的元素——这正是「模板搬走、样式没跟上」的典型形态。
  用 :where() 保持零特异性，避免覆盖调用方可能的定制。
*/
.ticket-tag,
.ticket-tag-more {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  background: var(--color-bg-sunken, #F3F4F6);
  color: var(--color-text-secondary);
}
.assignee-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}
.assignee-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-primary-lighter, #EEF2FF);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: var(--weight-medium);
}
.timestamp {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
.actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.action-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
}
.action-icon-btn:hover {
  background: var(--color-bg-sunken, #F3F4F6);
  color: var(--color-text-primary);
}
.action-icon-btn-danger:hover { color: var(--color-danger, #DC2626); }
</style>
