import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * ESLint 配置（flat config）。
 *
 * 规则取向对齐 devops-platform-frontend 既有工程约定：
 * - 只把「会导致真实缺陷」的项设为 error，风格类设为 warn 或关闭，
 *   避免一次性产生数千条噪声导致 lint 沦为摆设。
 * - `as any` 一类的类型逃逸设为 error —— 项目 CLAUDE.md 6.45 已明确
 *   「as any 是技术债非解决方案」，需要工具层强制。
 * - unplugin-auto-import 注入的 ref/computed/watch 等为全局符号，
 *   通过 auto-imports.d.ts 提供类型，这里补 globals 让 ESLint 不误报 no-undef。
 */

/** unplugin-auto-import 从 vue / vue-router / pinia 注入的全局 API */
const autoImportGlobals = {
  // vue
  ref: 'readonly',
  reactive: 'readonly',
  computed: 'readonly',
  watch: 'readonly',
  watchEffect: 'readonly',
  onMounted: 'readonly',
  onUnmounted: 'readonly',
  onBeforeMount: 'readonly',
  onBeforeUnmount: 'readonly',
  onActivated: 'readonly',
  onDeactivated: 'readonly',
  nextTick: 'readonly',
  defineComponent: 'readonly',
  shallowRef: 'readonly',
  toRef: 'readonly',
  toRefs: 'readonly',
  toValue: 'readonly',
  unref: 'readonly',
  provide: 'readonly',
  inject: 'readonly',
  markRaw: 'readonly',
  h: 'readonly',
  // vue-router
  useRoute: 'readonly',
  useRouter: 'readonly',
  // pinia
  defineStore: 'readonly',
  storeToRefs: 'readonly',
}

export default tseslint.config(
  {
    // 生成物与产物目录不参与 lint
    ignores: [
      'dist/**',
      'node_modules/**',
      'coverage/**',
      'auto-imports.d.ts',
      'components.d.ts',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],

  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.es2023,
        ...autoImportGlobals,
      },
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      // —— 会导致真实缺陷的，一律 error ——

      // 6.45 契约：as any 绕过类型系统是技术债
      '@typescript-eslint/no-explicit-any': 'error',
      // 空 catch 会静默吞掉错误 —— 项目多次因此排查困难
      'no-empty': ['error', { allowEmptyCatch: false }],

      /**
       * 禁止直接用 ElMessage —— 必须走 @/utils/notify。
       *
       * notify 在 ElMessage 之上加了 1 秒冷却去重：批量操作（如批量删除
       * 10 条）若直连 ElMessage 会连弹 10 个提示条刷屏，用户根本读不完，
       * 真正重要的那条也被淹没。
       *
       * ElMessageBox（确认框）不在限制内 —— 它是模态交互，语义不同。
       */
      'no-restricted-imports': ['error', {
        paths: [{
          name: 'element-plus',
          importNames: ['ElMessage'],
          message: '请改用 @/utils/notify 的 notify.success/error/warning/info（含冷却去重，避免批量操作刷屏）'
        }]
      }],
      /**
       * 禁止 `new Date(<表达式>)` —— 必须改用 `@/utils/time` 的 parseDate。
       *
       * 后端所有时间字段都是 Java `LocalDateTime`，序列化成 `2026-08-24T10:30:00`
       * **不带时区后缀**。ES 规范规定这种形式按**浏览器本地时区**解析，
       * 而数据是按服务器时区（Asia/Shanghai）产生的。两者不一致时，
       * 实测美东用户看到「1 小时前创建的工单」显示为「11 小时后」——偏差 12 小时，
       * 且页面上的绝对时间看起来完全正常，几乎不可能靠肉眼发现。
       *
       * parseDate 会给无时区字符串补上 +08:00，是唯一正确的入口。
       * 无参 `new Date()`（取当前时刻）不受限制，无时区歧义。
       */
      'no-restricted-syntax': ['error', {
        selector: 'NewExpression[callee.name="Date"][arguments.length>0]',
        message: '请改用 @/utils/time 的 parseDate()。后端 LocalDateTime 不带时区，new Date(str) 会按浏览器时区解析，跨时区下时间可差 12 小时。'
      }, {
        /**
         * `chatStream` 的回调对象必须包含 `onClose`。
         *
         * fetchEventSource 在服务端关流时**正常 resolve**——不抛错、不进 catch、
         * 不触发 onError。只在 complete/error 里复位「生成中」状态的话，
         * 后端超时 / 网关 502 / Nginx proxy_read_timeout 到期时状态永远不复位，
         * 界面卡死（输入框禁用、停止按钮点了没反应）。
         *
         * 这个缺陷在项目里出现过**三次**（ChatMode、useTicketAnalysis 的两处、
         * KnowledgeSinkDrawer），每次都是新增调用点时照着旧写法抄。
         * 靠 review 记住不现实，用规则挡住。
         */
        selector: "CallExpression[callee.name='chatStream'] > ObjectExpression:not(:has(Property[key.name='onClose']))",
        message: 'chatStream 必须提供 onClose 回调：服务端关流时不触发 onError，缺了它会让「生成中」状态永不复位、界面卡死。'
      }, {
        /**
         * 禁止 `toISOString()` —— 它返回 UTC。
         *
         * 后端所有时间字段的约定是「服务器本地时间（+08:00）且不带时区后缀」，
         * parseDate 也按 +08:00 解析。把 toISOString 的结果写进这类字段，
         * 就是写 UTC、读 +08:00，**恒定差 8 小时**。
         *
         * 实测：北京时间 10:30 编辑工单，乐观更新把 updatedAt 写成 UTC 的 02:30，
         * 列表「更新时间」立刻显示「8 小时前」——时间倒流。
         *
         * 需要「与后端同格式的当前时刻」用 nowAsBackendTime()；
         * 真要 UTC（如日志、traceId）请在 utils/time.ts 内实现并加注释说明。
         */
        selector: 'CallExpression[callee.property.name="toISOString"]',
        message: 'toISOString() 返回 UTC，写入后端时间字段会差 8 小时。请改用 @/utils/time 的 nowAsBackendTime()。'
      }],
      // == 在 null/undefined 判断外一律不允许
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      // 未使用变量：下划线前缀视为有意忽略
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'all', caughtErrorsIgnorePattern: '^_' },
      ],
      // Vue 模板里不得直接改 prop
      'vue/no-mutating-props': 'error',
      // v-html 需经 DOMPurify（6.32 契约），此处只警告，白名单在 safeMarkdown.ts
      'vue/no-v-html': 'warn',

      // —— 风格类：不阻塞，逐步收敛 ——
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/html-closing-bracket-spacing': 'off',
      'vue/attributes-order': 'off',
      'vue/first-attribute-linebreak': 'off',
      'vue/attribute-hyphenation': 'off',
      'vue/v-on-event-hyphenation': 'off',
      'no-console': 'off',
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },

  {
    // 测试文件：允许 any（mock 场景）与更宽松的断言
    files: ['**/*.{test,spec}.ts', 'src/test-setup.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },

  {
    /**
     * notify.ts 是 ElMessage 的封装层本身，其测试也需直接引用它做断言。
     * 这两处是规则的合法例外，而非违规。
     */
    files: ['src/utils/notify.ts', 'src/utils/__tests__/notify.test.ts'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },

  {
    /**
     * time.ts 是 parseDate 的实现本身，必须调用 `new Date(value)`；
     * 测试文件需要构造固定时刻做断言。这两处是规则的合法例外。
     */
    files: ['src/utils/time.ts', '**/*.{test,spec}.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },

  {
    // Node 侧配置文件
    files: ['*.config.ts', 'vite.config.ts', 'vitest.config.ts', 'knip.config.ts'],
    languageOptions: {
      globals: { ...globals.node },
    },
  }
)
