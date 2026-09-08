<script setup lang="ts">
import { ref } from 'vue'
import ThemeSwitcher from '@/components/common/ThemeSwitcher.vue'
import { useTheme } from '@/composables/useTheme'

/**
 * 设计系统展示页（`/design-system`）。
 *
 * 作用有三：
 * 1. **可验收**——四个主题轴的效果一眼可见，不用翻遍业务页去确认；
 * 2. **可对照**——新组件按这里的样式写，避免各页各自发明一套；
 * 3. **可回归**——改令牌后先看这一页，颜色对比度、密度是否还成立。
 *
 * 这页本身也是「组件应当只用语义令牌」的示范：
 * 全文没有一个硬编码色值。
 */

const { isDark } = useTheme()

const severities = [
  { key: 'p0', label: 'P0 致命', desc: '核心链路不可用' },
  { key: 'p1', label: 'P1 严重', desc: '主要功能受损' },
  { key: 'p2', label: 'P2 一般', desc: '次要功能异常' },
  { key: 'p3', label: 'P3 轻微', desc: '体验问题' },
]

const tickets = [
  { id: 'TKT-20260824-0007', title: 'order-service Pod 反复 CrashLoopBackOff', sev: 'p0', status: '处理中', owner: '张伟', sla: '12 分钟' },
  { id: 'TKT-20260824-0006', title: 'MySQL 主从延迟超过 30s', sev: 'p1', status: '待认领', owner: '—', sla: '1 小时' },
  { id: 'TKT-20260824-0005', title: 'Nginx 证书 15 天后到期', sev: 'p2', status: '已排期', owner: '李娜', sla: '3 天' },
  { id: 'TKT-20260824-0004', title: '日志磁盘使用率 82%', sev: 'p3', status: '已解决', owner: '王强', sla: '—' },
]

const kpis = [
  { label: '待处理工单', value: '23', delta: '+4', up: true },
  { label: '今日告警', value: '156', delta: '-12%', up: false },
  { label: 'AI 解决率', value: '87.3%', delta: '+2.1%', up: true },
  { label: 'P95 响应', value: '1.8s', delta: '-0.3s', up: false },
]

const showPanel = ref(true)
</script>

<template>
  <div class="ds">
    <header class="ds-head">
      <div>
        <h1 class="ds-h1">设计系统</h1>
        <p class="ds-sub">
          四个正交主题轴 · 当前 {{ isDark ? '深色' : '浅色' }} —— 切换右侧任意选项，整页实时响应
        </p>
      </div>
      <button class="ds-btn ds-btn-ghost" @click="showPanel = !showPanel">
        {{ showPanel ? '隐藏' : '显示' }}主题面板
      </button>
    </header>

    <div class="ds-body">
      <main class="ds-main">
        <!-- KPI -->
        <section class="ds-sec">
          <h2 class="ds-h2">指标卡</h2>
          <div class="ds-kpis">
            <article v-for="k in kpis" :key="k.label" class="ds-kpi">
              <span class="ds-kpi-label">{{ k.label }}</span>
              <strong class="ds-kpi-value">{{ k.value }}</strong>
              <span class="ds-kpi-delta" :class="k.up ? 'up' : 'down'">{{ k.delta }}</span>
            </article>
          </div>
        </section>

        <!-- 告警等级 -->
        <section class="ds-sec">
          <h2 class="ds-h2">告警等级</h2>
          <p class="ds-note">
            P0-P3 单列为专用令牌而非复用通用状态色：密集列表里 P0 必须一眼跳出来
          </p>
          <div class="ds-sevs">
            <div v-for="s in severities" :key="s.key" class="ds-sev">
              <span class="ds-dot" :data-sev="s.key" aria-hidden="true" />
              <div>
                <div class="ds-sev-label">{{ s.label }}</div>
                <div class="ds-sev-desc">{{ s.desc }}</div>
              </div>
            </div>
          </div>
        </section>

        <!-- 表格 -->
        <section class="ds-sec">
          <h2 class="ds-h2">数据表格</h2>
          <p class="ds-note">切换「信息密度」观察行高变化——运维列表的核心诉求是一屏行数</p>
          <div class="ds-table-wrap">
            <table class="ds-table">
              <thead>
                <tr>
                  <th>工单号</th><th>标题</th><th>等级</th>
                  <th>状态</th><th>负责人</th><th>SLA 剩余</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in tickets" :key="t.id">
                  <td class="mono">{{ t.id }}</td>
                  <td>{{ t.title }}</td>
                  <td><span class="ds-badge" :data-sev="t.sev">{{ t.sev.toUpperCase() }}</span></td>
                  <td>{{ t.status }}</td>
                  <td>{{ t.owner }}</td>
                  <td :class="{ urgent: t.sla.includes('分钟') }">{{ t.sla }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 状态语义 -->
        <section class="ds-sec">
          <h2 class="ds-h2">状态提示</h2>
          <div class="ds-alerts">
            <div class="ds-alert" data-kind="success">已自动修复：清理临时日志释放 12GB</div>
            <div class="ds-alert" data-kind="warning">证书将在 15 天后到期，建议提前轮换</div>
            <div class="ds-alert" data-kind="danger">主库连接池耗尽，已触发熔断</div>
            <div class="ds-alert" data-kind="info">知识库索引重建完成，共 1,284 个切片</div>
          </div>
        </section>

        <!-- 按钮 -->
        <section class="ds-sec">
          <h2 class="ds-h2">按钮</h2>
          <div class="ds-row">
            <button class="ds-btn ds-btn-primary">主要操作</button>
            <button class="ds-btn ds-btn-default">次要操作</button>
            <button class="ds-btn ds-btn-ghost">幽灵按钮</button>
            <button class="ds-btn ds-btn-danger">危险操作</button>
            <button class="ds-btn ds-btn-default" disabled>禁用</button>
          </div>
        </section>

        <!-- 图表色板 -->
        <section class="ds-sec">
          <h2 class="ds-h2">图表色板</h2>
          <p class="ds-note">ECharts 直接读这组变量，暗色下自动跟随，无需在图表配置里写死颜色</p>
          <div class="ds-chart">
            <div v-for="i in 6" :key="i" class="ds-bar"
                 :style="{ height: `${28 + i * 11}px`, background: `var(--chart-${i})` }" />
          </div>
        </section>
      </main>

      <aside v-if="showPanel" class="ds-aside">
        <ThemeSwitcher />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.ds { padding: var(--space-6); max-width: 1400px; margin: 0 auto; }

.ds-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: var(--space-4); margin-bottom: var(--space-6);
}
.ds-h1 { margin: 0 0 var(--space-1); font-size: var(--text-2xl); color: var(--text-1); }
.ds-sub { margin: 0; font-size: var(--text-sm); color: var(--text-2); }

.ds-body { display: grid; grid-template-columns: 1fr auto; gap: var(--space-6); align-items: start; }
@media (max-width: 1024px) { .ds-body { grid-template-columns: 1fr; } }

.ds-main { display: flex; flex-direction: column; gap: var(--space-6); min-width: 0; }

.ds-aside {
  position: sticky; top: var(--space-4);
  padding: var(--space-4);
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}

.ds-sec {
  padding: var(--space-5);
  background: var(--surface-1);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
}
.ds-h2 { margin: 0 0 var(--space-2); font-size: var(--text-base); color: var(--text-1); }
.ds-note { margin: 0 0 var(--space-4); font-size: var(--text-xs); color: var(--text-3); }

/* KPI */
.ds-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--space-3); }
.ds-kpi {
  display: flex; flex-direction: column; gap: var(--space-1);
  padding: var(--space-4);
  background: var(--surface-2);
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
}
.ds-kpi-label { font-size: var(--text-xs); color: var(--text-2); }
.ds-kpi-value { font-size: var(--text-2xl); color: var(--text-1); line-height: 1.1; }
.ds-kpi-delta { font-size: var(--text-xs); font-weight: 600; }
.ds-kpi-delta.up { color: var(--success); }
.ds-kpi-delta.down { color: var(--danger); }

/* 告警等级 */
.ds-sevs { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-3); }
.ds-sev { display: flex; align-items: center; gap: var(--space-3); }
.ds-dot { width: 10px; height: 10px; border-radius: var(--radius-full); flex-shrink: 0; }
.ds-dot[data-sev='p0'] { background: var(--sev-p0); box-shadow: 0 0 0 3px oklch(from var(--sev-p0) l c h / 0.18); }
.ds-dot[data-sev='p1'] { background: var(--sev-p1); }
.ds-dot[data-sev='p2'] { background: var(--sev-p2); }
.ds-dot[data-sev='p3'] { background: var(--sev-p3); }
.ds-sev-label { font-size: var(--text-sm); color: var(--text-1); font-weight: 500; }
.ds-sev-desc { font-size: var(--text-xs); color: var(--text-3); }

/* 表格 */
.ds-table-wrap { overflow-x: auto; border: 1px solid var(--border-1); border-radius: var(--radius); }
.ds-table { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.ds-table th {
  padding: 0 var(--space-3); height: var(--row-h);
  text-align: left; white-space: nowrap;
  font-size: var(--text-xs); font-weight: 600; color: var(--text-2);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border-1);
}
.ds-table td {
  padding: 0 var(--space-3); height: var(--row-h);
  color: var(--text-1);
  border-bottom: 1px solid var(--border-1);
}
.ds-table tbody tr:last-child td { border-bottom: none; }
.ds-table tbody tr:hover { background: var(--surface-hover); }
.mono { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-2); }
.urgent { color: var(--danger); font-weight: 600; }

.ds-badge {
  display: inline-block; padding: 1px var(--space-2);
  font-size: var(--text-xs); font-weight: 700;
  border-radius: var(--radius-sm);
  color: var(--text-inverse);
}
.ds-badge[data-sev='p0'] { background: var(--sev-p0); }
.ds-badge[data-sev='p1'] { background: var(--sev-p1); }
.ds-badge[data-sev='p2'] { background: var(--sev-p2); }
.ds-badge[data-sev='p3'] { background: var(--sev-p3); }

/* 状态提示 */
.ds-alerts { display: flex; flex-direction: column; gap: var(--space-2); }
.ds-alert {
  padding: var(--space-3) var(--space-4);
  font-size: var(--text-sm);
  border-radius: var(--radius);
  border-left: 3px solid;
}
.ds-alert[data-kind='success'] { background: var(--success-subtle); border-color: var(--success); color: var(--text-1); }
.ds-alert[data-kind='warning'] { background: var(--warning-subtle); border-color: var(--warning); color: var(--text-1); }
.ds-alert[data-kind='danger']  { background: var(--danger-subtle);  border-color: var(--danger);  color: var(--text-1); }
.ds-alert[data-kind='info']    { background: var(--info-subtle);    border-color: var(--info);    color: var(--text-1); }

/* 按钮 */
.ds-row { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.ds-btn {
  display: inline-flex; align-items: center; justify-content: center;
  height: var(--control-h); padding: 0 var(--space-4);
  font-size: var(--text-sm); font-weight: 500;
  border-radius: var(--radius); border: 1px solid transparent;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
              border-color var(--duration-fast) var(--ease-out);
}
.ds-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.ds-btn-primary { background: var(--brand); color: var(--brand-fg); }
.ds-btn-primary:hover:not(:disabled) { background: var(--brand-hover); }
.ds-btn-default { background: var(--surface-1); color: var(--text-1); border-color: var(--border-2); }
.ds-btn-default:hover:not(:disabled) { background: var(--surface-hover); }
.ds-btn-ghost { background: transparent; color: var(--text-2); }
.ds-btn-ghost:hover:not(:disabled) { background: var(--surface-hover); color: var(--text-1); }
.ds-btn-danger { background: var(--danger); color: var(--text-inverse); }

/* 图表 */
.ds-chart { display: flex; align-items: flex-end; gap: var(--space-3); height: 120px; padding-top: var(--space-2); }
.ds-bar { flex: 1; border-radius: var(--radius-sm) var(--radius-sm) 0 0; }
</style>
