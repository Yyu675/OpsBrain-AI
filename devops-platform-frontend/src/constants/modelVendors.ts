/**
 * 供应商端点模板（编辑弹窗「一键填入」按钮的数据源，批89 自 ModelChannels.vue 抽出）。
 *
 * 与后端 ChannelTemplate（V13 数据库模板）是两回事：这里只是编辑表单的
 * 输入辅助预设，不落库、不参与运行时裁决；改这里不影响任何已存配置。
 */
export const VENDOR_TEMPLATES: Record<string, { label: string; baseUrl: string }> = {
  aliyun:  { label: '阿里云 DashScope', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  deepseek:{ label: 'DeepSeek',         baseUrl: 'https://api.deepseek.com/v1' },
  openai:  { label: 'OpenAI',           baseUrl: 'https://api.openai.com/v1' },
  custom:  { label: '自定义',           baseUrl: '' },
}
