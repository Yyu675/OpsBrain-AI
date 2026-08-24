/**
 * AI 分析结构化解析测试（6.32）。
 *
 * 保护两条契约：
 * - 结构化输出优先：可能原因 / 排查命令 / 置信度 三段须被正确解析，
 *   命令要能逐条复制、置信度要能标签化
 * - 格式漂移必须降级而非报错：模型不遵守骨架时 structured=false 且
 *   原文进 other 走全量 markdown 渲染，用户不能面对空白
 *
 * 以及 6.20 的溯源契约：语义缓存命中时后端不返回 citations，
 * 必须能从正文【来源：X - Y】标记回退提取。
 */
import { describe, expect, it } from 'vitest'

import { extractCitationsFromText, parseStructuredAnalysis } from '../useTicketAnalysis'

describe('parseStructuredAnalysis — 空输入', () => {
  it('空串返回未结构化的空结果', () => {
    const r = parseStructuredAnalysis('')
    expect(r.structured).toBe(false)
    expect(r.reasons).toEqual([])
    expect(r.commands).toEqual([])
    expect(r.confidence).toBeNull()
    expect(r.other).toBe('')
  })

  it('纯空白视同空输入', () => {
    expect(parseStructuredAnalysis('   \n\n  ').structured).toBe(false)
  })
})

describe('parseStructuredAnalysis — 可能原因', () => {
  it('解析阿拉伯数字有序列表并去掉序号前缀', () => {
    const r = parseStructuredAnalysis(`## 可能原因
1. 连接池上限过低
2. 慢查询堆积导致连接不释放
3. 上游流量突增`)
    expect(r.reasons).toEqual([
      '连接池上限过低',
      '慢查询堆积导致连接不释放',
      '上游流量突增',
    ])
  })

  it('中文顿号与括号序号同样被剥离', () => {
    const r = parseStructuredAnalysis(`## 可能原因
1、连接池打满
2）慢查询
3）索引缺失`)
    expect(r.reasons).toEqual(['连接池打满', '慢查询', '索引缺失'])
  })

  it('无序列表（- 与 *）同样被剥离', () => {
    const r = parseStructuredAnalysis(`## 可能原因
- 磁盘写满
* inode 耗尽`)
    expect(r.reasons).toEqual(['磁盘写满', 'inode 耗尽'])
  })

  it('原因段落无列表标记时取首行作为单条原因，不整段丢弃', () => {
    const r = parseStructuredAnalysis(`## 可能原因
连接池被慢查询占满。`)
    expect(r.reasons).toEqual(['连接池被慢查询占满。'])
  })

  it('原因项中的行内 markdown 保留，供后续 renderMarkdown 处理', () => {
    const r = parseStructuredAnalysis(`## 可能原因
1. \`max_connections\` 配置过低`)
    expect(r.reasons).toEqual(['`max_connections` 配置过低'])
  })
})

describe('parseStructuredAnalysis — 排查命令', () => {
  it('从 bash 代码块逐行提取命令', () => {
    const r = parseStructuredAnalysis(`## 排查命令
\`\`\`bash
kubectl get pods -n prod
kubectl describe pod payment-service
\`\`\``)
    expect(r.commands).toEqual([
      'kubectl get pods -n prod',
      'kubectl describe pod payment-service',
    ])
  })

  it('流式过程中代码块尚未闭合也能提取已写入的命令 —— 否则命令区会一直空白到最后', () => {
    const r = parseStructuredAnalysis(`## 排查命令
\`\`\`bash
kubectl get pods -n prod
kubectl logs payment-service`)
    expect(r.commands).toEqual([
      'kubectl get pods -n prod',
      'kubectl logs payment-service',
    ])
  })

  it('代码块内的空行被过滤', () => {
    const r = parseStructuredAnalysis(`## 排查命令
\`\`\`bash
df -h

free -m
\`\`\``)
    expect(r.commands).toEqual(['df -h', 'free -m'])
  })

  it('无代码块时退化为按行提取并剥离反引号', () => {
    const r = parseStructuredAnalysis(`## 排查命令
1. \`df -h\`
2. \`free -m\``)
    expect(r.commands).toEqual(['df -h', 'free -m'])
  })

  it('多个 bash 代码块的命令合并', () => {
    const r = parseStructuredAnalysis(`## 排查命令
\`\`\`bash
df -h
\`\`\`
然后：
\`\`\`bash
free -m
\`\`\``)
    expect(r.commands).toEqual(['df -h', 'free -m'])
  })
})

describe('parseStructuredAnalysis — 置信度', () => {
  it('提取百分比数值', () => {
    const r = parseStructuredAnalysis(`## 置信度
85%`)
    expect(r.confidence).toBe(85)
  })

  it('带说明文字时仍能提取数值并保留原文', () => {
    const r = parseStructuredAnalysis(`## 置信度
78% —— 依据日志中的 OOMKilled 记录`)
    expect(r.confidence).toBe(78)
    expect(r.confidenceText).toContain('78%')
  })

  it('边界值 0 与 100 均被接受', () => {
    expect(parseStructuredAnalysis('## 置信度\n0%').confidence).toBe(0)
    expect(parseStructuredAnalysis('## 置信度\n100%').confidence).toBe(100)
  })

  it('越界数值不采纳为置信度，但保留原文供展示', () => {
    const r = parseStructuredAnalysis(`## 置信度
120%`)
    expect(r.confidence).toBeNull()
    expect(r.confidenceText).toBe('120%')
  })

  it('无数值时置信度为 null —— 不渲染该段而非显示 0%', () => {
    const r = parseStructuredAnalysis(`## 置信度
较高`)
    expect(r.confidence).toBeNull()
  })
})

describe('parseStructuredAnalysis — 完整骨架与降级', () => {
  const full = `## 可能原因
1. 连接池上限过低
2. 慢查询堆积

## 排查命令
\`\`\`bash
SHOW PROCESSLIST;
\`\`\`

## 置信度
82%`

  it('三段齐全时 structured 为 true', () => {
    const r = parseStructuredAnalysis(full)
    expect(r.structured).toBe(true)
    expect(r.reasons).toHaveLength(2)
    expect(r.commands).toEqual(['SHOW PROCESSLIST;'])
    expect(r.confidence).toBe(82)
  })

  it('无任何二级标题时降级：structured=false 且原文进 other 走全量渲染', () => {
    const raw = '数据库连接池被慢查询占满，建议先 kill 长事务。'
    const r = parseStructuredAnalysis(raw)
    expect(r.structured).toBe(false)
    expect(r.other).toBe(raw)
    expect(r.reasons).toEqual([])
  })

  it('只有置信度段（无原因无命令）时 structured 仍为 false —— 单个标签不足以走结构化渲染', () => {
    const r = parseStructuredAnalysis('## 置信度\n80%')
    expect(r.structured).toBe(false)
    expect(r.confidence).toBe(80)
  })

  it('未识别的二级标题内容归入 other，不被丢弃', () => {
    const r = parseStructuredAnalysis(`## 可能原因
1. 连接池打满

## 补充说明
该问题在上周也出现过。`)
    expect(r.structured).toBe(true)
    expect(r.other).toContain('该问题在上周也出现过。')
  })

  it('流式首个 token 尚未形成标题时安全降级，不抛错', () => {
    expect(() => parseStructuredAnalysis('##')).not.toThrow()
    expect(() => parseStructuredAnalysis('## ')).not.toThrow()
    expect(() => parseStructuredAnalysis('## 可能原因')).not.toThrow()
  })
})

describe('extractCitationsFromText（6.20 溯源回退）', () => {
  it('提取标题与章节并以「标题 - 章节」形式返回', () => {
    const r = extractCitationsFromText(
      '建议检查资源限制【来源：K8s故障排查手册 - Pod CrashLoopBackOff 问题排查】'
    )
    expect(r).toEqual(['K8s故障排查手册 - Pod CrashLoopBackOff 问题排查'])
  })

  it('多条引用全部提取', () => {
    const r = extractCitationsFromText(
      '第一步【来源：手册A - 章节1】，第二步【来源：手册B - 章节2】'
    )
    expect(r).toEqual(['手册A - 章节1', '手册B - 章节2'])
  })

  it('重复引用去重 —— 同一出处被多次标注时不应重复展示', () => {
    const r = extractCitationsFromText(
      '【来源：手册A - 章节1】以及【来源：手册A - 章节1】'
    )
    expect(r).toEqual(['手册A - 章节1'])
  })

  it('无引用标记时返回空数组', () => {
    expect(extractCitationsFromText('这段回答没有标注来源')).toEqual([])
  })

  it('空文本返回空数组', () => {
    expect(extractCitationsFromText('')).toEqual([])
  })

  it('标题与章节前后空白被去除', () => {
    const r = extractCitationsFromText('【来源：  手册A   -   章节1  】')
    expect(r).toEqual(['手册A - 章节1'])
  })
})
