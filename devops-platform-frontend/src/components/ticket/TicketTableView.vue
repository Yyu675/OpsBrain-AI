<script setup lang="ts">
/**
 * 工单列表（表格）视图。
 *
 * 继 `TicketCardGrid.vue` 之后从 `TicketList.vue` 拆出的第二块模板。
 * 卡片视图那次验证了拆分路径可行，本次搬的是更复杂的一块：
 * el-table 加 13 个可配置列、列宽持久化、排序、行选择。
 *
 * <h3>拆分的前置条件（上一轮特意补齐）</h3>
 * `TicketList.render.smoke.test.ts` 里加了 5 例列可见性断言——
 * 「隐藏一列后 DOM 真的少一列」。没有它，拆分时漏搬某个 `v-if`
 * 不会有任何报错：用户在列设置里取消勾选，表格纹丝不动。
 * 那组用例注入缺陷时精确失败 2 例，证明确实兜得住。
 *
 * <h3>为什么用这么多 prop 而不是直接读 store</h3>
 * 列宽/可见性来自 `useTicketColumns`、SLA 与状态映射来自父页面已有的
 * 工具函数。子组件自己再引一份会与父页面漂移——
 * 尤其是状态/优先级的中文标签，两处各写一遍必然在某次改词表时对不上。
 * 宁可 prop 多一些，也要保证**只有一个真相来源**。
 *
 * 例外：`slaRemainText` / `firstResponseText` 等纯函数直接从
 * `@/utils/sla` 导入。它们本身就是共享工具，不存在漂移风险。
 */
import { Trash2 } from 'lucide-vue-next'
import type { Sort, TableColumnCtx } from 'element-plus'
import RelativeTime from '@/components/common/RelativeTime.vue'
import {
  firstResponseText,
  firstResponseTitle,
  slaRemainText
} from '@/utils/sla'
import type { Ticket } from '@/stores/tickets'

defineProps<{
  tickets: Ticket[]
  /** 列宽（px）。来自 useTicketColumns，含用户拖拉后的持久化结果 */
  columnWidths: Record<string, number>
  /** 列可见性。每个 el-table-column 的 v-if 都读它 */
  columnVisible: Record<string, boolean>
  /** 标题列宽度：仅在用户拖拉过后才固定，否则交给 min-width 弹性吸收 */
  titleWidth: number | undefined
  /** 是否有用户调整过的列布局，决定「恢复默认」提示显不显示 */
  columnsResized: boolean
  tableSort: Sort
  rowClassName: (ctx: { row: Ticket }) => string
  /** 以下映射由父页面注入，保证与筛选栏、卡片视图用的是同一份词表 */
  getStatusClass: (s: Ticket['status']) => string
  getStatusLabel: (s: Ticket['status']) => string
  getPriorityClass: (p: Ticket['priority']) => string
  getPriorityLabel: (p: Ticket['priority']) => string
  frClass: (row: { firstResponseState?: string }) => string
  slaClass: (row: { slaProgress?: number; slaBreached?: boolean }) => string
  stageLabel: (stage: string) => string
  rcLabel: (cat: string) => string
}>()

const emit = defineEmits<{
  (e: 'row-click', row: Ticket, column: TableColumnCtx<Ticket> | null): void
  (e: 'selection-change', rows: Ticket[]): void
  (e: 'header-dragend', newWidth: number, oldWidth: number, column: { property?: string; label?: string }): void
  (e: 'sort-change', data: { prop: string | null; order: 'ascending' | 'descending' | null }): void
  (e: 'edit', ticket: Ticket): void
  (e: 'delete', ticket: Ticket): void
  (e: 'reset-columns'): void
}>()
</script>

<template>
  <div class="table-container">
      <el-table
        class="tickets-table"
        :data="tickets"
        border
        stripe
        row-key="id"
        :row-class-name="rowClassName"
        :default-sort="tableSort"
        @row-click="(row: Ticket, col: TableColumnCtx<Ticket> | null) => emit('row-click', row, col)"
        @selection-change="(rows: Ticket[]) => emit('selection-change', rows)"
        @header-dragend="(w: number, o: number, c: { property?: string; label?: string }) => emit('header-dragend', w, o, c)"
        @sort-change="(d: { prop: string | null; order: 'ascending' | 'descending' | null }) => emit('sort-change', d)"
      >
        <el-table-column type="selection" :width="columnWidths.selection" :resizable="false" />

        <el-table-column
          v-if="columnVisible.id"
          prop="id"
          label="工单 ID"
          :width="columnWidths.id"
          :min-width="120"
          sortable="custom"
        >
          <template #default="{ row }">
            <RouterLink :to="`/tickets/${row.id}`" class="ticket-id" @click.stop>{{ row.id }}</RouterLink>
          </template>
        </el-table-column>

        <!-- 标题列：不设固定 width，由 min-width 弹性吸收剩余空间
             （此前所有列定宽导致右侧一大片空白 gutter）。
             悬浮改为结构化「速览卡」——原先 show-overflow-tooltip 会把整个单元格
             文本（含描述、标签）糊成一片，描述属长文本不该进 tooltip -->
        <el-table-column
          prop="title"
          label="标题"
          :width="titleWidth"
          :min-width="240"
        >
          <template #default="{ row }">
            <el-tooltip placement="top-start" :show-after="250" effect="light" popper-class="ticket-peek-popper">
              <template #content>
                <div class="ticket-peek">
                  <div class="peek-title">{{ row.title }}</div>
                  <div class="peek-row">
                    <span class="peek-label">服务 · 分类</span>
                    <span class="peek-value">{{ row.service || '未分类' }} · {{ row.category || '其他' }}</span>
                  </div>
                  <div class="peek-row">
                    <span class="peek-label">SLA</span>
                    <span class="peek-value" :class="slaClass(row)">
                      {{ slaRemainText(row) }}
                      <template v-if="row.sla"> · 目标 {{ row.sla }}</template>
                    </span>
                  </div>
                  <div class="peek-row">
                    <span class="peek-label">首响</span>
                    <span class="peek-value" :class="frClass(row)">{{ firstResponseText(row) }}<template v-if="row.firstResponder"> · {{ row.firstResponder }}</template></span>
                  </div>
                  <div class="peek-row">
                    <span class="peek-label">负责人 · 状态</span>
                    <span class="peek-value">{{ row.assignee || '待分配' }} · {{ getStatusLabel(row.status) }}</span>
                  </div>
                  <div v-if="row.tags && row.tags.length" class="peek-row">
                    <span class="peek-label">标签</span>
                    <span class="peek-value">{{ row.tags.join('、') }}</span>
                  </div>
                  <div class="peek-row">
                    <span class="peek-label">创建</span>
                    <span class="peek-value">{{ row.creator || '未知' }} · {{ row.createdAt }}</span>
                  </div>
                  <div v-if="row.updatedAt" class="peek-row">
                    <span class="peek-label">更新</span>
                    <span class="peek-value">{{ row.updatedAt }}</span>
                  </div>
                </div>
              </template>
              <div class="ticket-title-cell">
                <RouterLink :to="`/tickets/${row.id}`" class="ticket-title" @click.stop>{{ row.title }}</RouterLink>
                <div v-if="row.tags && row.tags.length" class="ticket-tags">
                  <span v-for="tag in row.tags.slice(0, 3)" :key="tag" class="ticket-tag">{{ tag }}</span>
                  <span v-if="row.tags.length > 3" class="ticket-tag-more">+{{ row.tags.length - 3 }}</span>
                </div>
              </div>
            </el-tooltip>
          </template>
        </el-table-column>

        <!-- 服务：此前埋在标题副标题里，提为独立列可见可排序 -->
        <el-table-column
          v-if="columnVisible.service"
          prop="service"
          label="服务"
          :width="columnWidths.service"
          :min-width="110"
          sortable="custom"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="cell-muted">{{ row.service || '未分类' }}</span>
          </template>
        </el-table-column>

        <!-- 分类：后端一直有 category 数据，列表页此前从未展示 -->
        <el-table-column
          v-if="columnVisible.category"
          prop="category"
          label="分类"
          :width="columnWidths.category"
          :min-width="90"
          sortable="custom"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="cell-muted">{{ row.category || '其他' }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="columnVisible.priority"
          prop="priority"
          label="优先级"
          :width="columnWidths.priority"
          :min-width="90"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="priority-badge" :class="getPriorityClass(row.priority)">{{ getPriorityLabel(row.priority) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="columnVisible.status"
          prop="status"
          label="状态"
          :width="columnWidths.status"
          :min-width="90"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="status-badge" :class="getStatusClass(row.status)">{{ getStatusLabel(row.status) }}</span>
          </template>
        </el-table-column>

        <!-- 首响状态（B1）：区分「已派单但无人理」与「已在处理」。
             此前只有 assignee，看不出是否真有人响应过 -->
        <el-table-column
          v-if="columnVisible.firstResponse"
          label="首响"
          :width="columnWidths.firstResponse"
          :min-width="96"
        >
          <template #default="{ row }">
            <span class="fr-badge" :class="frClass(row)" :title="firstResponseTitle(row)">
              {{ firstResponseText(row) }}
            </span>
          </template>
        </el-table-column>

        <!-- SLA 进度：后端计算的派生字段（6.15），此前列表页未展示，
             运维看不到哪些单快超时。超时标红、≥70% 标橙 -->
        <el-table-column v-if="columnVisible.sla" label="SLA" :width="columnWidths.sla" :min-width="120">
          <template #default="{ row }">
            <div class="sla-cell" :title="row.sla || 'SLA 未设置'">
              <div class="sla-bar">
                <div
                  class="sla-bar-fill"
                  :class="slaClass(row)"
                  :style="{ width: Math.min(100, Math.max(0, row.slaProgress || 0)) + '%' }"
                />
              </div>
              <span class="sla-text" :class="slaClass(row)">
                {{ row.slaBreached ? '已超时' : (row.slaProgress ?? 0) + '%' }}
              </span>
            </div>
          </template>
        </el-table-column>

        <!-- B2 处置阶段（仅处理中时有值，其余为空） -->
        <el-table-column
          v-if="columnVisible.handlingStage"
          label="处置阶段"
          :width="columnWidths.handlingStage"
          :min-width="90"
        >
          <template #default="{ row }">
            <el-tooltip :content="'处置阶段: ' + row.handlingStage" placement="top" :show-after="300" :disabled="!row.handlingStage">
              <span v-if="row.handlingStage" class="stage-badge" :class="`stage-${(row.handlingStage || '').toLowerCase()}`">{{ stageLabel(row.handlingStage) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column
          v-if="columnVisible.assignee"
          prop="assignee"
          label="负责人"
          :width="columnWidths.assignee"
          :min-width="110"
          sortable="custom"
        >
          <template #default="{ row }">
            <div class="assignee-cell">
              <span class="assignee-avatar">{{ (row.assignee || '?')[0] }}</span>
              <span class="assignee-name">{{ row.assignee }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          v-if="columnVisible.createdAt"
          prop="createdAt"
          label="创建时间"
          :width="columnWidths.createdAt"
          :min-width="100"
          sortable="custom"
        >
          <template #default="{ row }">
            <div class="timestamp"><RelativeTime :value="row.createdAt" /></div>
          </template>
        </el-table-column>

        <!-- B3 根因分类（仅确认根因后有值） -->
        <el-table-column
          v-if="columnVisible.rootCause"
          label="根因分类"
          :width="columnWidths.rootCause"
          :min-width="90"
        >
          <template #default="{ row }">
            <el-tooltip :content="'根因分类: ' + row.rootCauseCategory" placement="top" :show-after="300" :disabled="!row.rootCauseCategory">
              <span v-if="row.rootCauseCategory" class="rc-badge">{{ rcLabel(row.rootCauseCategory) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <!-- 更新时间：判断工单是否停滞（首响/处置进度）的关键信号 -->
        <el-table-column
          v-if="columnVisible.updatedAt"
          prop="updatedAt"
          label="更新时间"
          :width="columnWidths.updatedAt"
          :min-width="100"
          sortable="custom"
        >
          <template #default="{ row }">
            <div class="timestamp"><RelativeTime :value="row.updatedAt" /></div>
          </template>
        </el-table-column>

        <el-table-column label="操作" :width="columnWidths.actions" :min-width="90" fixed="right">
          <template #default="{ row }">
            <div class="actions" @click.stop>
              <button class="action-icon-btn" title="编辑" @click.stop="emit('edit', row)">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              </button>
              <button
                v-permission.disable="{ roles: ['admin'] }"
                class="action-icon-btn action-icon-btn-danger"
                title="删除"
                @click.stop="emit('delete', row)"
              >
                <Trash2 :size="14" />
              </button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="columnsResized" class="table-footnote">
        <span>列布局已按你的调整保存</span>
        <button class="link-btn" @click="emit('reset-columns')">恢复默认列布局</button>
      </div>  </div>
</template>

<style scoped>
/*
  样式随模板一并迁入。这是拆分时最容易漏的一步——
  PostmortemDrawer 那次模板搬走、样式留在原文件的后代选择器下，
  抽屉里的输入框一直是浏览器默认样式而没人发现。

  scoped 之后父页面的规则命中不到这里，凡是本组件 DOM 用到的类
  都必须在此声明（含单元格内的徽章、头像等原子样式）。
*/
.table-container {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}

.tickets-table {
  width: 100%;
  font-size: var(--text-sm);

  /* 表头：沿用原有的小号大写灰字风格 */
  :deep(.el-table__header th.el-table__cell) {
    background: var(--color-bg-sunken);
    padding: 10px 0;
    font-size: var(--text-xs);
    font-weight: var(--weight-medium);
    color: var(--text-3);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    border-bottom: 1px solid var(--color-border-light);
  }

  /* 列宽拖拉手柄：加宽命中区域并给出明确的 col-resize 光标，
     默认 1px 边框太窄，用户常拖不到 */
  :deep(.el-table__header th.el-table__cell > .cell) {
    padding-left: 16px;
    padding-right: 16px;
  }
  :deep(.el-table--border th.el-table__cell:not(:last-child))::after {
    content: '';
    position: absolute;
    right: -3px;
    top: 25%;
    height: 50%;
    width: 6px;
    cursor: col-resize;
  }

  :deep(.el-table__body td.el-table__cell) {
    padding: 12px 0;
    vertical-align: top;
  }
  :deep(.el-table__body td.el-table__cell > .cell) {
    padding-left: 16px;
    padding-right: 16px;
  }

  /* 行 hover / 选中：沿用主色浅底 */
  :deep(.el-table__body tr:hover > td.el-table__cell) {
    background: var(--color-primary-lighter);
  }
  :deep(.el-table__body tr.selected > td.el-table__cell) {
    background: var(--color-primary-lighter);
  }

  :deep(.el-table__row) { cursor: pointer; }
}
.table-footnote {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 8px 16px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  border-top: 1px solid var(--color-border-light);
}

.link-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: var(--text-xs);
  font-family: var(--font-body);
  color: var(--color-primary);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}
</style>
