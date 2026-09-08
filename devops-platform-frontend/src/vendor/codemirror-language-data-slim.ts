/**
 * `@codemirror/language-data` 的精简替代（构建期通过 Vite alias 替换）。
 *
 * ## 为什么需要它
 *
 * `md-editor-v3` 内部 import 了 `@codemirror/language-data`——CodeMirror 的
 * **全语言注册表**，含 **136 个动态 import**。Rollup 会把每个都切成独立 chunk，
 * 实测产出 148 个小碎片、合计 **630 KB**，其中绝大多数是
 * `z80` / `yacas` / `xquery` / `verilog` / `vbscript` / `vhdl` / `webidl` 这类语言。
 *
 * **运维手册里不会出现 Z80 汇编。** 这些碎片虽然是懒加载、不进首屏，
 * 但它们会：
 * - 让 `dist/assets` 多出上百个文件，CDN 刷新与产物审查都变噪；
 * - 占用构建时间与 CI 缓存；
 * - 让「产物体积异常」这类问题淹没在噪声里。
 *
 * ## 保留哪些语言：按运维文档的真实需要
 *
 * 保留的都是运维手册里真正会贴的代码块。判断依据是这些内容会出现在
 * 故障处置手册、部署文档、排查记录里：
 *
 * | 语言 | 运维场景 |
 * |---|---|
 * | Shell/Bash | 排查命令、运维脚本——**最高频** |
 * | YAML | K8s 清单、Prometheus 规则、CI 配置 |
 * | JSON | API 载荷、配置片段、告警 payload |
 * | SQL | 数据库排查、慢查询分析 |
 * | Java / Python / Go | 本项目后端与常见服务端语言，贴堆栈与片段 |
 * | JS/TS | 前端排查 |
 * | Dockerfile | 镜像构建问题 |
 * | XML / HTML / CSS | 配置文件与前端 |
 * | Markdown | 文档内嵌文档 |
 * | Nginx | 反代与网关配置——运维高频 |
 * | Properties | Spring 配置 |
 * | Rust / PHP / C / C++ | 覆盖面兜底，包已安装、零额外成本 |
 *
 * ## 加语言的正确做法
 *
 * 若将来确实需要某个语言（例如团队引入了 Erlang 服务）：
 * 1. 确认 `@codemirror/lang-xxx` 或 `@codemirror/legacy-modes` 里有它；
 * 2. 在本文件的 `languages` 数组里加一项；
 * 3. **不要**把 alias 去掉退回全量——那会让 630 KB 的碎片全部回来。
 *
 * 契约测试 `codemirrorLanguageSlim.test.ts` 会守住这一点。
 *
 * @see vite.config.ts 中的 resolve.alias 配置
 */
import {
  LanguageDescription,
  LanguageSupport,
  StreamLanguage,
  type StreamParser,
} from '@codemirror/language'

/**
 * legacy-modes 的解析器包装。
 *
 * `LanguageDescription.load()` 的返回类型契约是 `Promise<LanguageSupport>`，
 * 而 `StreamLanguage.define()` 返回的是 `StreamLanguage`——两者不同，
 * 必须再包一层 `LanguageSupport`。上游 `@codemirror/language-data` 里
 * 也有同名的 `legacy()` helper 做这件事。
 *
 * 漏掉这层包装时 `vue-tsc` 会报
 * 「Type 'StreamLanguage<unknown>' is missing ... language, support」。
 */
function legacy(parser: StreamParser<unknown>): LanguageSupport {
  return new LanguageSupport(StreamLanguage.define(parser))
}

/**
 * 精简语言注册表。
 *
 * 结构与上游 `@codemirror/language-data` 的 `languages` 导出一致：
 * 每项是 `LanguageDescription`，`load()` 返回动态 import 的 Promise，
 * 因此**仍然是懒加载**——只有用户真的写了对应语言的代码块才会拉取。
 */
export const languages = [
  // ── 运维最高频 ─────────────────────────────────────
  LanguageDescription.of({
    name: 'Shell',
    alias: ['bash', 'sh', 'zsh', 'shell'],
    extensions: ['sh', 'bash', 'zsh'],
    load() {
      return import('@codemirror/legacy-modes/mode/shell').then((m) =>
        legacy(m.shell)
      )
    },
  }),
  LanguageDescription.of({
    name: 'YAML',
    alias: ['yml'],
    extensions: ['yaml', 'yml'],
    load() {
      return import('@codemirror/lang-yaml').then((m) => m.yaml())
    },
  }),
  LanguageDescription.of({
    name: 'JSON',
    extensions: ['json', 'map'],
    load() {
      return import('@codemirror/lang-json').then((m) => m.json())
    },
  }),
  LanguageDescription.of({
    name: 'SQL',
    alias: ['mysql', 'postgresql', 'postgres', 'pgsql'],
    extensions: ['sql'],
    load() {
      return import('@codemirror/lang-sql').then((m) => m.sql())
    },
  }),
  LanguageDescription.of({
    name: 'Dockerfile',
    alias: ['docker'],
    filename: /^Dockerfile$/,
    load() {
      return import('@codemirror/legacy-modes/mode/dockerfile').then((m) =>
        legacy(m.dockerFile)
      )
    },
  }),
  LanguageDescription.of({
    name: 'Nginx',
    load() {
      return import('@codemirror/legacy-modes/mode/nginx').then((m) =>
        legacy(m.nginx)
      )
    },
  }),
  LanguageDescription.of({
    name: 'Properties',
    alias: ['ini', 'properties', 'conf'],
    extensions: ['properties', 'ini', 'cfg', 'conf'],
    load() {
      return import('@codemirror/legacy-modes/mode/properties').then((m) =>
        legacy(m.properties)
      )
    },
  }),

  // ── 服务端语言 ─────────────────────────────────────
  LanguageDescription.of({
    name: 'Java',
    extensions: ['java'],
    load() {
      return import('@codemirror/lang-java').then((m) => m.java())
    },
  }),
  LanguageDescription.of({
    name: 'Python',
    alias: ['py'],
    extensions: ['py', 'pyi'],
    load() {
      return import('@codemirror/lang-python').then((m) => m.python())
    },
  }),
  LanguageDescription.of({
    name: 'Go',
    extensions: ['go'],
    load() {
      return import('@codemirror/lang-go').then((m) => m.go())
    },
  }),
  LanguageDescription.of({
    name: 'Rust',
    alias: ['rs'],
    extensions: ['rs'],
    load() {
      return import('@codemirror/lang-rust').then((m) => m.rust())
    },
  }),
  LanguageDescription.of({
    name: 'PHP',
    extensions: ['php'],
    load() {
      return import('@codemirror/lang-php').then((m) => m.php())
    },
  }),
  LanguageDescription.of({
    name: 'C',
    extensions: ['c', 'h'],
    load() {
      return import('@codemirror/lang-cpp').then((m) => m.cpp())
    },
  }),
  LanguageDescription.of({
    name: 'C++',
    alias: ['cpp'],
    extensions: ['cpp', 'c++', 'cc', 'hpp'],
    load() {
      return import('@codemirror/lang-cpp').then((m) => m.cpp())
    },
  }),

  // ── 前端与标记语言 ─────────────────────────────────
  LanguageDescription.of({
    name: 'JavaScript',
    alias: ['js', 'node'],
    extensions: ['js', 'mjs', 'cjs'],
    load() {
      return import('@codemirror/lang-javascript').then((m) => m.javascript())
    },
  }),
  LanguageDescription.of({
    name: 'TypeScript',
    alias: ['ts'],
    extensions: ['ts', 'mts', 'cts'],
    load() {
      return import('@codemirror/lang-javascript').then((m) =>
        m.javascript({ typescript: true })
      )
    },
  }),
  LanguageDescription.of({
    name: 'HTML',
    alias: ['xhtml'],
    extensions: ['html', 'htm'],
    load() {
      return import('@codemirror/lang-html').then((m) => m.html())
    },
  }),
  LanguageDescription.of({
    name: 'CSS',
    extensions: ['css'],
    load() {
      return import('@codemirror/lang-css').then((m) => m.css())
    },
  }),
  LanguageDescription.of({
    name: 'XML',
    extensions: ['xml', 'xsl', 'xsd', 'svg'],
    load() {
      return import('@codemirror/lang-xml').then((m) => m.xml())
    },
  }),
  LanguageDescription.of({
    name: 'Markdown',
    alias: ['md'],
    extensions: ['md', 'markdown'],
    load() {
      return import('@codemirror/lang-markdown').then((m) => m.markdown())
    },
  }),
  LanguageDescription.of({
    name: 'Vue',
    extensions: ['vue'],
    load() {
      return import('@codemirror/lang-vue').then((m) => m.vue())
    },
  }),
]

export default languages
