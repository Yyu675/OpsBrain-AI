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
  40005: { title: '请求超出配额限制', hint: '本周期配额已用完，请联系管理员调整额度', retry: 'NEVER' },
  40009: { title: '数据已被他人修改', hint: '请刷新页面查看最新内容后再提交', retry: 'CLIENT' },
  40010: { title: '接口已废弃', hint: '请升级客户端或联系管理员', retry: 'NEVER' },
  40021: { title: '内容重复', hint: '知识库中已存在高度相似的文档', retry: 'NEVER' },
  // 登录失败与登录失效分列：前者用户还在登录页，提示改密码即可；
  // 后者是会话过期，需要跳转登录页。合并会让密码输错时也执行跳转
  40100: { title: '用户名或密码错误', hint: '请检查后重新输入', retry: 'NEVER' },
  40101: { title: '登录已失效', hint: '请重新登录', retry: 'NEVER' },
  40102: { title: '该审批单已被处理', hint: '请刷新查看最新决策', retry: 'NEVER' },
  40103: { title: '权限不足', hint: '如需访问请联系管理员开通', retry: 'NEVER' },
  40104: { title: 'Webhook 鉴权失败', hint: '请检查 X-Webhook-Token 配置', retry: 'NEVER' },
  40301: { title: '操作被拦截', hint: '该操作存在安全风险', retry: 'NEVER' },
  40400: { title: '资源不存在', hint: '它可能已被删除或从未创建', retry: 'NEVER' },
  42901: { title: '请求过于频繁', hint: '请稍等片刻后再试', retry: 'BACKOFF' },

  // 5xx 服务端
  50001: { title: '服务内部异常', hint: '请稍后重试，若持续出现请联系管理员', retry: 'SAFE' },
  50002: { title: '连接异常', hint: '网络或服务波动，请重试', retry: 'SAFE' },
  50010: { title: '知识检索暂不可用', hint: '这是检索链路故障，不是知识库没有内容', retry: 'SAFE' },
  // retry 为 NEVER 是刻意的：数据源连不上是环境问题，重试无效。
  // 提示语引导用户去「接入管理」检查，而不是反复刷新。
  50020: { title: '监控数据源不可用', hint: '请在「接入管理」中检查 Prometheus 连接', retry: 'NEVER' },

  // 5xx 上游模型
  50210: { title: '模型响应超时', hint: '复杂问题耗时较长，可稍后重试或精简问题', retry: 'SAFE' },
  50211: { title: '模型服务繁忙', hint: '上游限流中，请稍后重试', retry: 'BACKOFF' },
  50212: { title: '内容被模型安全策略拦截', hint: '请调整表述后重新提问', retry: 'NEVER' },
}

/**
 * 具名业务码。
 *
 * 各 api 模块此前直接裸写 `e.bizCode === 40004` 判断「资源不存在」，
 * 而 40004 的真实语义是 **STATE_CONFLICT（当前状态不允许该操作）**，
 * 资源不存在是 40400。两者一个是「对象在但现在不能这么操作」、
 * 一个是「对象根本不存在」，混用会让「已作废的工单不能改状态」
 * 被渲染成「工单不存在」的空页面——用户以为数据丢了。
 *
 * 裸数字看不出这层区别，故一律走常量。
 */
export const BizCode = {
  /** 参数不合法 */
  PARAM_ERROR: 40001,
  /** 请求超出配额限制 */
  QUOTA_EXCEEDED: 40005,
  /** 当前状态不允许该操作（HTTP 409）——不是「不存在」 */
  STATE_CONFLICT: 40004,
  /** 数据已被他人修改（乐观锁冲突） */
  OPTIMISTIC_LOCK: 40009,
  /** 接口已废弃 */
  ENDPOINT_DEPRECATED: 40010,
  /** 内容重复 */
  DUPLICATE_CONTENT: 40021,
  /** 用户名或密码错误——用户还在登录页，原地提示即可 */
  LOGIN_FAILED: 40100,
  /** 未登录或登录已失效——需跳转登录页 */
  NOT_LOGIN: 40101,
  /** 审批单已被他人处理 */
  APPROVAL_ALREADY_DECIDED: 40102,
  /** 权限不足——跳转登录页解决不了，重新登录还是同一个账号 */
  NO_PERMISSION: 40103,
  /** 资源不存在 */
  NOT_FOUND: 40400,
  /** 服务内部异常 */
  INTERNAL_ERROR: 50001,
} as const

/** 查表；未知码返回 undefined，由调用方兜底 */
export function getBizError(code: number | undefined): BizErrorMeta | undefined {
  return code === undefined ? undefined : BIZ_ERRORS[code]
}

/** 是否可自动重试（CLIENT 不算——需要用户决定） */
export function isAutoRetryable(code: number | undefined): boolean {
  const meta = getBizError(code)
  return meta?.retry === 'SAFE' || meta?.retry === 'BACKOFF'
}
