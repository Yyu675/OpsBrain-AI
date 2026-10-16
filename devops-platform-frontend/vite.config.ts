import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    // 数组形式（而非对象）：Element Plus 的两条别名必须是**精确正则**——
    // 对象形式的字符串 key 是前缀匹配，会把 `element-plus/es/components/xx/style/css`
    // 这类深度路径也一并改写掉，那些恰恰是 resolver 注入的样式副作用导入。
    alias: [
      { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
      // md-editor-v3 内部引用 @codemirror/language-data —— CodeMirror 的全语言注册表，
      // 含 136 个动态 import。Rollup 会把每个切成独立 chunk，实测产出 148 个碎片、
      // 合计 630 KB，绝大多数是 z80/yacas/xquery/verilog/vbscript 这类
      // 运维手册永远用不到的语言。
      //
      // 用别名换成精简注册表（只留运维实际会贴的语言），而不是 patch node_modules：
      // 别名是构建期行为，升级依赖不会被覆盖，也不需要 postinstall 脚本。
      //
      // ⚠️ 去掉这行别名会让 630 KB 的碎片全部回来。
      // 契约测试 codemirrorLanguageSlim.test.ts 守住这一点。
      {
        find: '@codemirror/language-data',
        replacement: fileURLToPath(
          new URL('./src/vendor/codemirror-language-data-slim.ts', import.meta.url)
        ),
      },
      /**
       * Element Plus 也换精简入口：resolver 给每个组件/指令生成的导入路径都是
       * barrel `element-plus/es`，其顶层 `makeInstaller([...全部组件])` 让
       * tree-shaking 失效（实测产物含 cascader/tour/watermark 等零引用组件，
       * vendor-element 单块 966KB）。换成本仓库显式列名的精简入口后，
       * 只有真正用到的组件进入依赖图。
       *
       * ⚠️ 必须是 `^...$` 精确匹配：前缀匹配会连
       * `element-plus/es/components/<comp>/style/css`（resolver 注入的样式）
       * 一起改写，导致组件样式丢失。
       *
       * ⚠️ 去掉这两条别名会让 vendor-element 回弹到整包体积。
       */
      {
        find: new RegExp('^element-plus/es$'),
        replacement: fileURLToPath(new URL('./src/vendor/element-plus-slim.ts', import.meta.url)),
      },
      {
        find: new RegExp('^element-plus$'),
        replacement: fileURLToPath(new URL('./src/vendor/element-plus-slim.ts', import.meta.url)),
      },
    ],
  },
  // 开发服务器：监听 0.0.0.0 使局域网其他设备可通过本机 IP 访问；
  // 代理 /ai 到后端，前端用相对路径即可，无需硬编码 localhost。
  server: {
    host: '0.0.0.0',
    port: 5173,
    // 允许云端 IDE / 预览代理的域名访问。
    // Vite 5.x 起默认校验 Host 头防 DNS rebinding，
    // 反向代理域名不在白名单会被 403 拒绝，表现为「预览页打不开」。
    // 仅影响开发服务器，不影响生产构建。
    allowedHosts: ['.e2b.app', '.gitpod.io', '.github.dev', 'localhost'],
    /*
     * 预热：把 e2e 会走的入口与视图**在服务器启动时**就转换一遍。
     *
     * ── 为什么要这个（2026-10-15 本机复现定案，销台账 §七 A6）──────────
     * `unplugin-vue-components` + ElementPlusResolver 是**按需**注入
     * `element-plus/es/components/<x>/style/css` 这类副作用导入的，于是 Vite 只在
     * 「某个视图第一次被转换」时才发现这些依赖 → 重跑 optimizer → **强制整页
     * reload**。本机实测日志（拉 `/src/views/TicketDetail.vue`、`AlertList.vue`
     * 的过程中）：
     *
     *   [vite] dependency optimized: element-plus/es/components/drawer/style/css
     *   [vite] optimized dependencies changed. reloading      ← 整页 reload
     *   [vite] dependencies optimized: …table/style/css, tag/style/css
     *   [vite] optimized dependencies changed. reloading      ← 又一次
     *
     * 而 e2e 里 `page.goto('/tickets/<id>')` 恰恰就是「第一次转换那个懒加载视图」
     * 的时刻——reload 与还在进行中的导航相撞，浏览器报 `net::ERR_ABORTED`。
     * 这就是 `boundary-edge-cases.spec.ts:59`（多 Tab 并发写工单）偶发红、并曾把
     * main 打红一次的机制。**红只是表象**：用例本身没问题，是 dev server 在
     * 用例跑到一半时把页面重启了。
     *
     * 预热把这些依赖的发现时机整体挪到「服务器启动、还没有任何导航」的窗口里，
     * reload 就不再打断用例。下面的 `optimizeDeps.include` 是同一件事的另一半。
     */
    warmup: {
      clientFiles: [
        './src/main.ts',
        './src/router/index.ts',
        './src/views/*.vue',
        './src/components/**/*.vue',
      ],
    },
    proxy: {
      /*
       * 后端 context-path 是 /ai，但**不能直接用 '/ai' 作为 key**。
       *
       * Vite（http-proxy）的字符串 key 是**前缀匹配**，'/ai' 会连带吃掉
       * 任何以 /ai 开头的前端路由（历史上 /ai-chat 就因此被代理到后端
       * 导致刷新白屏；该页已于 2026-09-27 随方案乙下线，正则 key 保留
       * 以防未来的前端路由再踩同一个坑）。
       *
       * 用正则 key 精确限定「/ai 后面必须跟 / 或结束」，
       * 这样 /ai/api/v1/... 与 /ai/ws/alerts 照常代理。
       */
      '^/ai(/.*)?$': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        // WebSocket 代理：/ai/ws/alerts 也走此代理，无需前端直连后端 WS 端口
        ws: true,
      },
    },
  },
  /*
   * 依赖预打包：把 element-plus 各组件的样式副作用导入**一次收全**。
   *
   * 与上面的 `server.warmup` 是同一件事的两半：warmup 让 Vite 提前**发现**这些
   * 导入，这里让它提前**打包**完，两者缺一，第一次转换某个视图时仍可能触发
   * 「optimized dependencies changed. reloading」（→ e2e 导航被打断报
   * net::ERR_ABORTED，见 server.warmup 处的实测日志与说明）。
   *
   * 用通配而不是逐个列组件名：resolver 会随页面用到的组件变化而注入新的样式
   * 路径，逐个列必然漏（漏一个就又是一次中途 reload）。
   */
  optimizeDeps: {
    include: ['element-plus/es/components/*/style/css'],
  },
  build: {
    // wangeditor 富文本库体 ~870kB 已拆分到独立懒加载块 vendor-editor，
    // 仅编辑文档的路由才拉取；警告线上调避免对「按需加载的第三方库」误报
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        /**
         * 分包用 rolldown 原生 codeSplitting，不用兼容层 manualChunks——
         * Vite 8（rolldown）下 manualChunks 对「被多 chunk 再导出的符号」
         * （element-plus/es/utils/easings.mjs 的 easeInOutCubic）会触发
         * `should belong to a chunk` panic。
         * 组按数组顺序先匹配先生效；不命中任何组的模块交回 rolldown
         * 自动分块（同原 manualChunks return undefined 语义）。
         */
        codeSplitting: {
          groups: [
            { name: 'vendor-echarts', test: /node_modules[\\/](echarts|zrender)/ },
            { name: 'vendor-element', test: /node_modules[\\/](element-plus|@element-plus)/ },
            { name: 'vendor-icons', test: /node_modules[\\/]lucide-vue-next/ },
            // TanStack Query 独立成块：稳定依赖，业务发版不让用户重下
            { name: 'vendor-query', test: /node_modules[\\/]@tanstack/ },
            // Markdown 渲染链路（marked + dompurify）：阅读页也要用，与编辑器分开
            { name: 'vendor-markdown', test: /node_modules[\\/](marked|dompurify)/ },
            // 富文本编辑器（wangeditor）独立成块：库体 ~700kB，仅编辑路由拉取
            { name: 'vendor-editor', test: /node_modules[\\/](@wangeditor|wang-editor)/ },
            { name: 'vendor-vue', test: /node_modules[\\/](vue-router|pinia|vue|@vue)/ },
          ],
        },
      }
    }
  }
})
