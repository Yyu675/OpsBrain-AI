<script setup lang="ts">
/**
 * 告警行的状态徽标组（观察中 / 误报）。
 *
 * 从 AlertList 标题单元格抽出（行数红线纪律）：两个徽标都是
 * 「有则显示 + tooltip 说明书」的同构小件，与行的其余渲染无耦合。
 */
defineProps<{
  /** 自愈观察窗中（FR-3.1 派生标识） */
  observing?: boolean
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
