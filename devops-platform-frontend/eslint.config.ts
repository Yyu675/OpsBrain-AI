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
    // Node 侧配置文件
    files: ['*.config.ts', 'vite.config.ts', 'vitest.config.ts', 'knip.config.ts'],
    languageOptions: {
      globals: { ...globals.node },
    },
  }
)
