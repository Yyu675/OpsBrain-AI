/**
 * 业务错误码（与后端 `com.devops.agent.common.error.BizError` 一一对应）。
 *
 * 为什么要有这张表：
 * 修复前前后端各自硬编码同一批数字（40009 / 40021 / 40101 ...），
 * 后端新增一个码而前端没同步，用户就只能看到一句无意义的兜底文案，
 * 且没有任何编译期或测试期信号能提示你漏了。
 *
 * ⚠️ 修改本文件时必须同步后端枚举，反之亦然。
 * `__tests__/bizCode.contract.test.ts` 会校验两侧一致性。
 */

/** 重试语义，与后端 `BizError.Retry` 对齐 */
export type RetryPolicy = 'NEVER' | 'SAFE' | 'BACKOFF' | 'CLIENT'

export interface BizErrorMeta {
  /** 面向用户的标题 */
  title: string
  /** 下一步该怎么做——比"出错了"有用得多 */
  hint?: string
  retry: RetryPolicy
}

/**
 * 业务码 → 展示元信息。
 *
 * 文案原则：告诉用户「发生了什么」和「下一步做什么」，
 * 不要暴露内部实现（表名、类名、堆栈）。
 */
export const BIZ_ERRORS: Record<number, BizErrorMeta> = {
  // 4xx 客户端
  40001: { title: '参数不合法', hint: '请检查输入内容后重试', retry: 'NEVER' },
  40003: { title: '请求被安全策略拦截', hint: '请调整提问方式，避免包含指令性内容', retry: 'NEVER' },
  40006: { title: '问题过长', hint: '超出模型上下文窗口，请精简后重新提问', retry: 'NEVER' },
  40004: {
    title: '当前状态不允许该操作',
    hint: '请刷新查看最新状态——例如已作废的工单不能再变更状态',
    retry: 'NEVER'
  },
  40009: { title: '数据已被他人修改', hint: '请刷新页面查看最新内容后再提交', retry: 'CLIENT' },
  40010: { title: '接口已废弃', hint: '请升级客户端或联系管理员', retry: 'NEVER' },
  40021: { title: '内容重复', hint: '知识库中已存在高度相似的文档', retry: 'NEVER' },
  40101: { title: '登录已失效', hint: '请重新登录', retry: 'NEVER' },
  40103: { title: '权限不足', hint: '如需访问请联系管理员开通', retry: 'NEVER' },
  40104: { title: 'Webhook 鉴权失败', hint: '请检查 X-Webhook-Token 配置', retry: 'NEVER' },
  40301: { title: '操作被拦截', hint: '该操作存在安全风险', retry: 'NEVER' },
  40400: { title: '资源不存在', hint: '它可能已被删除或从未创建', retry: 'NEVER' },
  42901: { title: '请求过于频繁', hint: '请稍等片刻后再试', retry: 'BACKOFF' },

  // 5xx 服务端
  50001: { title: '服务内部异常', hint: '请稍后重试，若持续出现请联系管理员', retry: 'SAFE' },
  50002: { title: '连接异常', hint: '网络或服务波动，请重试', retry: 'SAFE' },
  50010: { title: '知识检索暂不可用', hint: '这是检索链路故障，不是知识库没有内容', retry: 'SAFE' },

  // 5xx 上游模型
  50210: { title: '模型响应超时', hint: '复杂问题耗时较长，可稍后重试或精简问题', retry: 'SAFE' },
  50211: { title: '模型服务繁忙', hint: '上游限流中，请稍后重试', retry: 'BACKOFF' },
  50212: { title: '内容被模型安全策略拦截', hint: '请调整表述后重新提问', retry: 'NEVER' },
}

/** 查表；未知码返回 undefined，由调用方兜底 */
export function getBizError(code: number | undefined): BizErrorMeta | undefined {
  return code === undefined ? undefined : BIZ_ERRORS[code]
}

/** 是否可自动重试（CLIENT 不算——需要用户决定） */
export function isAutoRetryable(code: number | undefined): boolean {
  const meta = getBizError(code)
  return meta?.retry === 'SAFE' || meta?.retry === 'BACKOFF'
}
