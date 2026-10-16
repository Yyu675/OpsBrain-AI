/**
 * 类型生成入口（项3 Phase B）：绕 orval bin + 定点修复 ajv 解析链。
 *
 * 病历一（绕 bin）：orval 的 CLI 在本机 Node 22 起不来——
 * `@commander-js/extra-typings` 的 CJS re-export 过不了 Node ESM named-export
 * 探测（does not provide an export named 'program'）。generate() 编程 API 不触 commander。
 *
 * 病历二（ajv hook）：eslint 把 ajv@6 钉在 node_modules 顶层，orval 的校验链
 * （spectral / ibm-ruleset / ajv-draft-04）全员要求 ajv@8，npm 却把它们 hoist 到
 * 顶层——require 上溯拿到 ajv@6 → Cannot find module 'ajv/dist/compile/codegen'。
 * npm 判「逻辑兄弟已满足」不自愈（invalid dedupe），overrides 也不触发重算。
 * 此处只在**本进程内**把 'ajv'/'ajv/*' 解析改道到树内真实存在的 ajv@8 实体：
 * 零 node_modules 手术、零 postinstall，eslint 进程的 ajv@6 分毫不动。
 *
 * 用法（cwd = devops-platform-frontend）：npm run gen:api
 * 前置：契约有变更时先 npm run api:refresh（= sync 快照 + 生成）。
 */
import { Module } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'

// ---- ajv 改道 hook（必须在 import orval 之前生效，故置于模块顶层）----
const AJV8_ROOT = path.resolve(
  'node_modules/@stoplight/spectral-functions/node_modules/ajv'
)
const ajvPkg = JSON.parse(fs.readFileSync(path.join(AJV8_ROOT, 'package.json'), 'utf8'))
if (!String(ajvPkg.version).startsWith('8.')) {
  console.error(`✗ 期望树内 ajv@8 实体，实得 ${ajvPkg.version}：${AJV8_ROOT}`)
  console.error('  树形态变了，先跑 npm i 重建，再核对本路径。')
  process.exit(1)
}
const origResolve = Module._resolveFilename
Module._resolveFilename = function (request, ...rest) {
  if (request === 'ajv' || request.startsWith('ajv/')) {
    return origResolve.call(this, path.join(AJV8_ROOT, request.slice(3)), ...rest)
  }
  return origResolve.call(this, request, ...rest)
}

// ---- 正常生成 ----
try {
  const { generate } = await import('orval')
  await generate('./orval.config.ts')
  console.log(`✓ 类型生成完成 → src/api/generated/（ajv hook 用 v${ajvPkg.version}）`)
} catch (err) {
  console.error('✗ 类型生成失败:', err)
  process.exit(1)
}
