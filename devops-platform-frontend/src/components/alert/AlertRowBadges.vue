<script setup lang="ts">
/**
 * 告警行的状态徽标组（观察中 / 未建单 / 误报）。
 *
 * 从 AlertList 标题单元格抽出（行数红线纪律）：几个徽标都是
 * 「有则显示 + tooltip 说明书」的同构小件，与行的其余渲染无耦合。
 */
defineProps<{
  /** 自愈观察窗中（FR-3.1 派生标识） */
  observing?: boolean
  /**
   * 未建单原因（后端读路径派生，F2）：只有「不会再自动建单」时才非空——
   * 历史/回放事件超出补单回溯窗。没有它，这类告警看上去就是系统漏单。
   */
  unticketedReason?: string | null
  /** 已被值班标记为误报（V17 Feedback 回流） */
  falsePositive?: boolean
}>()
</script>

<template>
  <!-- 自愈观察窗（FR-3.1）：观察级告警先观察再建单——
       没挂工单不是漏单，是系统在等它自己好 -->
  <el-tooltip
    v-if="observing"
    content="自愈观察中：该级别告警在观察窗内暂不建单，窗口内自愈则只留统计"
    placement="top"
    :show-after="200"
  >
    <span class="observing-badge">观察中</span>
  </el-tooltip>
  <!-- 未建单（F2）：不是「静默无单」，而是「不会再自动建单」——文案由后端给，
       前端不自己拼阈值口径（两处实现必然漂移） -->
  <el-tooltip
    v-if="unticketedReason"
    :content="unticketedReason"
    placement="top"
    :show-after="200"
  >
    <span class="unticketed-badge">未建单</span>
  </el-tooltip>
  <!-- 误报徽标（V17）：质检结论上列表——复盘时一眼认出哪些规则在被值班投票 -->
  <el-tooltip
    v-if="falsePositive"
    content="已被标记为误报（反馈将计入周报误报率，反哺阈值调优）"
    placement="top"
    :show-after="200"
  >
    <span class="fp-badge">误报</span>
  </el-tooltip>
</template>

<style scoped lang="scss">
/* 自愈观察窗（FR-3.1）标识：观察级告警在窗口内暂不建单 */
.observing-badge {
  flex-shrink: 0;
  margin-left: var(--space-2, 8px);
  padding: 1px 7px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 16px;
  color: var(--info);
  background: var(--info-subtle);
  border: 1px solid color-mix(in oklch, var(--info) 35%, transparent);
}

/* 未建单（F2）徽标：neutral 语义——不是故障，是「系统明确说了不会建单」 */
.unticketed-badge {
  flex-shrink: 0;
  margin-left: var(--space-2, 8px);
  padding: 1px 7px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-secondary);
  background: var(--surface-2);
  border: 1px solid var(--border-default);
}

/* 误报标注（V17 Feedback 回流）徽标：warning 语义——不是危险，是「规则在被投票」 */
.fp-badge {
  flex-shrink: 0;
  margin-left: var(--space-2, 8px);
  padding: 1px 7px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 16px;
  color: var(--warning);
  background: var(--warning-subtle);
  border: 1px solid color-mix(in oklch, var(--warning) 35%, transparent);
}
</style>
