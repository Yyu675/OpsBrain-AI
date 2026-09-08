<script setup lang="ts">
/**
 * 人工介入中心 —— Saga 补偿中心的聚焦视图
 *
 * 自动化无法安全闭环的事务（MANUAL_INTERVENTION_REQUIRED / COMPENSATION_FAILED）
 * 集中在此处理：查看上下文快照（业务主键/失败原因/处理建议）、展开执行链路、触发补偿。
 * 完整功能（分组/统计/全量列表）见 SagaCompensation.vue。
 */
import SagaCompensation from '@/views/SagaCompensation.vue'
</script>

<template>
  <SagaCompensation initial-tab="MANUAL" />
</template>
