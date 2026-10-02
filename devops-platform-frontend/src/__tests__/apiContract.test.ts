/**
 * 方法级 API 契约测试（方案⑦，2026-10-01）——前端 API_ENDPOINTS ↔ 后端 Controller 映射的校验。
 *
 * 背景：ControllerApiPrefixContractTest（后端）只保证「类级前缀是 /api/v1」，
 * 保证不了「前端 config/api.ts 里写的每个 path 后端真的有」。诊断链 404 事故
 * （DiagnosisController 缺 /v1）正是这类失配：两边各自编译通过、纯直调测试全绿，
 * 唯独 URL 对不上。本测试把 config/api.ts 当唯一真相源，逐条到后端 Java 源里找映射。
 *
 * 口径：
 * - 扫描范围 = src/main/java 下 controller 包的类级 @RequestMapping + 方法级
 *   GetMapping/PostMapping/PutMapping/DeleteMapping/RequestMapping（先剥注释——
 *   javadoc 里常引用 @RequestMapping("/api/...") 示例，不剥会把示例当真映射；
 *   正则容忍 FQN 注解（@org...PostMapping）与命名参数（path=/value="/stream")）；
 * - API_ENDPOINTS 的值是纯路径/取 id 函数，不带 method → 只校验 path 段级
 *   （{xxx}/${xxx} 通配任意段）；method 语义由各端点行为测试兜底；
 * - 用法定性：src 中常量每次出现的下一字符全是 '/' → 纯前缀拼接
 *   （${HEALING}/executions 形态）→ 按前缀存在性断言；出现过裸调用 → 必须精确命中；
 * - 方向性：前端要的必须在后端存在（前端多写 = 404 事故形态）；
 *   后端存在但前端未收录 = 允许（API 先行/内部端点/webhook 属合法）。
 */
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

import { API_ENDPOINTS } from '../config/api'

/** 后端 controller 源码目录：vitest 从 frontend/ 启动，../ 即仓库根 */
const CONTROLLERS_DIR = path.resolve(
  process.cwd(), '../src/main/java/com/devops/agent/controller')

/** 前端源码根（用法扫描用） */
const FE_SRC = path.resolve(process.cwd(), 'src')

/** 剥块注释与行注释——javadoc 示例里的 @RequestMapping 不是真映射 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
}

/** 路径归一：剥 origin+context-path（从 /api/v1 起截），占位段 → '*'，丢 query/hash */
function normalize(p: string): string[] {
  const at = p.indexOf('/api/v1')
  const clean = (at >= 0 ? p.slice(at) : p).split('?')[0].split('#')[0]
  return clean.split('/').filter(s => s.length > 0)
    .map(s => (s.startsWith('{') && s.endsWith('}')) || (s.startsWith('${') && s.endsWith('}')) ? '*' : s)
}

/** 段级通配匹配：'*' 吃任意一段，其余必须字面相等 */
function pathMatches(front: string[], back: string[]): boolean {
  if (front.length !== back.length) return false
  return front.every((seg, i) => seg === '*' || back[i] === '*' || seg === back[i])
}

/** 前缀存在性：front 是 back 的段级前缀（前缀型常量用） */
function pathPrefix(front: string[], back: string[]): boolean {
  if (front.length > back.length) return false
  return front.every((seg, i) => seg === '*' || back[i] === '*' || seg === back[i])
}

describe('前后端接口契约（config/api.ts ↔ Controller 映射）', () => {
  it('前端 API_ENDPOINTS 每个 path 后端都有映射', () => {
    const files = fs.existsSync(CONTROLLERS_DIR)
      ? fs.readdirSync(CONTROLLERS_DIR).filter(f => f.endsWith('.java'))
      : []
    expect(files.length, `controller 源码目录不可用: ${CONTROLLERS_DIR}`).toBeGreaterThan(10)

    // 收集 classPath + methodPath 全集（类级映射本身也是一条合法 path）
    const backendPaths: string[][] = []
    for (const f of files) {
      const src = stripComments(fs.readFileSync(path.join(CONTROLLERS_DIR, f), 'utf8'))
      const classM = /@RequestMapping\s*\(\s*"([^"]*)"/.exec(src)
      const classPath = classM ? classM[1] : ''
      const methodRe = /@(?:[\w.]+\.)?(?:Get|Post|Put|Delete|Request)Mapping\s*(?:\(\s*(?:path\s*=\s*|value\s*=\s*)?"([^"]*)")?/g
      let m: RegExpExecArray | null
      while ((m = methodRe.exec(src)) !== null) {
        const methodPath = m[1] ?? ''
        backendPaths.push(normalize(classPath + methodPath))
      }
    }
    expect(backendPaths.length).toBeGreaterThan(50)

    // 前端源码全集：判定常量是「裸调用」还是「纯前缀拼接」
    const feSrc = (fs.readdirSync(FE_SRC, { recursive: true }) as string[])
      .filter(f => /\.(ts|vue)$/.test(String(f)))
      .map(f => fs.readFileSync(path.join(FE_SRC, String(f)), 'utf8'))
      .join(' ')   // 文件间留空格，防常量名在文件边界处被拼成另一个标识符

    const missing: string[] = []
    for (const [key, url] of Object.entries(API_ENDPOINTS)) {
      // 值有三种形态：字符串 / 取 id 的箭头函数 / 其他。
      // 函数用哨兵参 '{probe}' 调用——归一后成占位段，正好与后端 {id} 通配
      const raw = typeof url === 'function'
        ? String((url as (a: string) => string)('{probe}'))
        : url
      if (typeof raw !== 'string') {
        missing.push(`${key}: 无法解析的值类型`)
        continue
      }
      const front = normalize(raw)

      // 用法定性：每次出现的下一字符若全是 '/' → 纯前缀拼接（${X}/子路径 形态）。
      // 注意模板形态是 `${API_ENDPOINTS.X}/子路径`——KEY 后紧跟 '}'，要再看一位
      const useRe = new RegExp(`API_ENDPOINTS\\.${key}(?![\\w$])`, 'g')
      const nextChars: string[] = []
      let um: RegExpExecArray | null
      while ((um = useRe.exec(feSrc)) !== null) {
        const c1 = feSrc[um.index + um[0].length] ?? ''
        nextChars.push(c1 === '}' ? (feSrc[um.index + um[0].length + 1] ?? '') : c1)
      }
      const prefixOnly = nextChars.length > 0 && nextChars.every(c => c === '/')

      const hit = prefixOnly
        ? backendPaths.some(b => pathPrefix(front, b))
        : backendPaths.some(b => pathMatches(front, b))
      if (!hit) missing.push(`${key}: ${raw}${prefixOnly ? '（前缀型）' : ''}`)
    }
    expect(missing, `前端调了后端不存在的 path（404 事故形态）：${missing.join(' | ')}`).toEqual([])
  })
})
