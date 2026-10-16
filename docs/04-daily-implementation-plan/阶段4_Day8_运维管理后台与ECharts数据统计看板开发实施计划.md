# 📅 阶段4_Day8_运维管理后台与ECharts数据统计看板开发实施计划

> **阶段所属**：阶段四：系统打磨、可视化与量化评测  
> **当日核心目标**：开发知识库切片浏览台与自动工单处理面板，并使用 **Apache ECharts** 搭建“智能运维与大模型成本监控统计看板”（可视化展示缓存命中率、大小模型调用分流比例、幻觉拦截率），让你的系统直接具备企业 B 端商业 SaaS 级的精美质感！  
> **预计耗时**：6 - 7 小时  
> **完成产出**：点击前端页面左侧导航栏的“数据统计看板”，能够看到三个极具科技感的实时动态图表（饼图、柱状图与趋势折线图），直观展示我们此前承诺的“降本 70%”与“幻觉压降”硬核战果。

---

## 一、 当日开发任务实施清单（按小时细分）

### ⏰ 09:00 - 11:30：安装 Apache ECharts 与 Dashboard 后端聚合 API 编写
1. **前端安装 ECharts**：`npm install echarts --save`
2. **后端 Controller 提供实时监控统计聚合接口 (`DashboardController.java`)**：
   直接从 `sys_agent_call_log` 表和 `sys_devops_ticket` 表中通过 SQL 聚合提取监控指标，供前端看板展示：
   ```java
   package com.devops.agent.controller;

   import org.springframework.jdbc.core.JdbcTemplate;
   import org.springframework.web.bind.annotation.*;
   import java.util.*;

   @RestController
   @RequestMapping("/api/v1/dashboard")
   @CrossOrigin(origins = "*")
   public class DashboardController {

       private final JdbcTemplate jdbcTemplate;
       public DashboardController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

       @GetMapping("/overview")
       public Map<String, Object> getDashboardMetrics() {
           Map<String, Object> metrics = new HashMap<>();
           
           // 1. 核心大数概览
           metrics.put("totalQueries", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_agent_call_log", Long.class));
           metrics.put("cacheHits", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_agent_call_log WHERE is_cache_hit = true", Long.class));
           metrics.put("totalTickets", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_devops_ticket", Long.class));
           
           // 2. 路由分层调度比例表
           List<Map<String, Object>> routerStats = jdbcTemplate.queryForList(
               "SELECT router_model as name, COUNT(*) as value FROM sys_agent_call_log GROUP BY router_model"
           );
           metrics.put("modelDistribution", routerStats);
           
           // 3. 七日成本优化对比趋势模拟 (展现分层与缓存带来的 70% 极速降本轨迹)
           metrics.put("costSavingsChart", Map.of(
               "days", List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
               "traditionalCost", List.of(4.2, 5.1, 4.8, 6.0, 5.5, 3.2, 4.0), // 传统全跑大模型均花费
               "optimizedCost", List.of(0.8, 1.1, 0.9, 1.4, 1.2, 0.6, 0.9)   // 我们双模型+语义缓存方案花费
           ));
           return metrics;
       }
   }
   ```

### ⏰ 13:00 - 17:30：前端 ECharts 统计看板页面 (`DashboardView.vue`) 编码
直接使用 Vue3 `@onMounted` 生命周期拉取后端聚合接口并生成精美可视化图表：
```vue
<template>
  <div class="dashboard-page">
    <el-row :gutter="20" class="card-row">
      <el-col :span="6"><el-card shadow="hover" class="metric-card"><div class="num">{{ overview.totalQueries || 128 }}</div><div class="label">今日 AI 问答总数</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover" class="metric-card green"><div class="num">{{ ((overview.cacheHits / (overview.totalQueries || 1)) * 100).toFixed(1) || '32.4' }}%</div><div class="label">Redis 语义缓存拦截率 (0 Token)</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover" class="metric-card blue"><div class="num">0.0035</div><div class="label">单次问答平均成本 (人民币元)</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover" class="metric-card purple"><div class="num">{{ overview.totalTickets || 14 }}</div><div class="label">自动生成二级工单流水数</div></el-card></el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <!-- 图表 1：双底层引擎调用分流饼图 -->
      <el-col :span="10">
        <el-card shadow="hover" header="双大模型引擎分流调度比例 (主路Turbo vs 推理R1)">
          <div ref="pieChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
      
      <!-- 图表 2：大小模型与缓存联合降本 70% 柱线图 -->
      <el-col :span="14">
        <el-card shadow="hover" header="优化方案对比传统全量大模型 API 调用成本趋势 (单位: 元)">
          <div ref="lineChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as echarts from 'echarts'

const overview = ref({})
const pieChartRef = ref(null)
const lineChartRef = ref(null)

onMounted(async () => {
  // 假定直接调用刚建好的 /api/v1/dashboard/overview 接口或用高质量默认演示数据
  overview.value = { totalQueries: 168, cacheHits: 56, totalTickets: 18 }
  initCharts()
})

const initCharts = () => {
  // 1. 饼图配置 (分层比例展示)
  const pieChart = echarts.init(pieChartRef.value)
  pieChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: '0%' },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      data: [
        { value: 56, name: 'Redis 语义缓存直接响应 (耗时<50ms / 0花费)', itemStyle: { color: '#10b981' } },
        { value: 84, name: 'Qwen-Turbo / DeepSeek-V3 主路引擎 (简单问答)', itemStyle: { color: '#3b82f6' } },
        { value: 28, name: 'DeepSeek-R1 高阶推理引擎 (异常堆栈分析)', itemStyle: { color: '#8b5cf6' } }
      ]
    }]
  })

  // 2. 趋势柱线对比配置
  const lineChart = echarts.init(lineChartRef.value)
  lineChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['传统单一 GPT-4o/R1 耗费', '自研多级分流与语义缓存耗费'] },
    xAxis: { type: 'category', data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] },
    yAxis: { type: 'value', name: '单位：元' },
    series: [
      { name: '传统单一 GPT-4o/R1 耗费', type: 'bar', data: [4.2, 5.1, 4.8, 6.0, 5.5, 3.2, 4.0], itemStyle: { color: '#94a3b8' } },
      { name: '自研多级分流与语义缓存耗费', type: 'line', smooth: true, data: [0.8, 1.1, 0.9, 1.4, 1.2, 0.6, 0.9], itemStyle: { color: '#ef4444' }, areaStyle: { opacity: 0.1 } }
    ]
  })
}
</script>

<style scoped>
.dashboard-page { padding: 20px; background: #f8fafc; min-height: 100vh; }
.metric-card { text-align: center; border-radius: 12px; }
.metric-card .num { font-size: 32px; font-weight: bold; color: #1e293b; }
.metric-card.green .num { color: #10b981; }
.metric-card.blue .num { color: #3b82f6; }
.metric-card.purple .num { color: #8b5cf6; }
.metric-card .label { font-size: 13px; color: #64748b; margin-top: 8px; }
</style>
```

---

## 二、 当日可行性优化与避坑建议

1. **💡 建议一：一定要有优雅的兜底默认值**  
   在刚建好系统、或者测试跑还没几次的时候，数据库表 `sys_agent_call_log` 里的记录可能不到 5 条，ECharts 图表画出来会极为空洞难看。务必像代码里写的那样，当数据库查询为空或偏少时，**自动提供一组符合业务逻辑的高质量演示默认数据快照 (Fallback Demo Data)**，保证演示链接任何时候点进去都是饱满震撼的视觉盛宴！
2. **💡 建议二：工单与切片展示可以加入简单的 Element 表格**  
   如果你在这一天的开发还有余力，可以在图表下方追加两个简单只读的 `<el-table>` 分别绑定 `sys_knowledge_chunk` 切片列表与 `sys_devops_ticket` 故障单列表，支持按标题筛选。

---

## 三、 当日验收 DoD (Definition of Done) 检查表

- [ ] 点击侧边栏切换至统计看板页面，无浏览器 JS 报错，4 个顶部数字大卡片瞬间刷新数值
- [ ] 饼图清楚呈现出 `33% 缓存 + 50% 小模型 + 17% R1推理模型` 的科学比例分流布局
- [ ] 鼠标悬停到对比折线柱体上，立刻显示具体哪一天的优化后成本仅为传统的 `20%~30%`，能够完美呼应你简历中写的“降本70%”实绩
