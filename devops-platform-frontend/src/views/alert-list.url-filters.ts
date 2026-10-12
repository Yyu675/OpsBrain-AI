/**
 * 告警列表的 URL 筛选声明（从 AlertList.vue 拆出，行数红线纪律）。
 *
 * 告警页此前完全没有 URL 状态：值班同事筛出「FIRING + P0」后没法把结果
 * 甩给同事，刷新也会丢——而「把这个告警列表发给你看」恰恰是值班交接时
 * 最高频的动作。六个筛选/分页参数全部进 URL，见各条 parse 规则。
 */
import type { Ref } from 'vue'
import {
  useUrlFilters,
  defineUrlFilter,
  enumParser,
  positiveIntParser,
  textParser,
  flagParser,
} from '@/composables/useUrlFilters'
import type { AlertStatus } from '@/api/types'

interface AlertListFilterRefs {
  statusFilter: Ref<AlertStatus | ''>
  levelFilter: Ref<string | ''>
  systemFilter: Ref<string>
  observingOnly: Ref<boolean>
  /** 只看误报（V17 抽检视图，URL 键 fp——质量复盘名单也要能甩链接） */
  fpOnly: Ref<boolean>
  pageRef: Ref<number>
  sizeRef: Ref<number>
}

/** 在 setup 内调用：七个筛选/分页 ref 与 URL query 双向绑定 */
export function registerAlertListUrlFilters(refs: AlertListFilterRefs) {
  useUrlFilters([
    defineUrlFilter({
      ref: refs.statusFilter,
      key: 'status',
      defaultValue: '' as AlertStatus | '',
      parse: enumParser(['FIRING', 'ACKNOWLEDGED', 'RESOLVED'] as const),
    }),
    defineUrlFilter({
      ref: refs.levelFilter,
      key: 'level',
      defaultValue: '' as string,
      parse: textParser(32),
    }),
    defineUrlFilter({
      ref: refs.systemFilter, key: 'system', defaultValue: '' as string, parse: textParser(64),
    }),
    defineUrlFilter({
      ref: refs.observingOnly, key: 'observing', defaultValue: false, parse: flagParser(),
    }),
    defineUrlFilter({
      ref: refs.fpOnly, key: 'fp', defaultValue: false, parse: flagParser(),
    }),
    defineUrlFilter({
      ref: refs.pageRef, key: 'page', defaultValue: 1, parse: positiveIntParser(10000),
    }),
    defineUrlFilter({
      ref: refs.sizeRef, key: 'size', defaultValue: 10, parse: positiveIntParser(200),
    }),
  ])
}
