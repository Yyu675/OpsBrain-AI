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
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
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
      '@codemirror/language-data': fileURLToPath(
        new URL('./src/vendor/codemirror-language-data-slim.ts', import.meta.url)
      )
    }
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
    proxy: {
      /*
       * 后端 context-path 是 /ai，但**不能直接用 '/ai' 作为 key**。
       *
       * Vite（http-proxy）的字符串 key 是**前缀匹配**，'/ai' 会连带吃掉
       * 前端自己的路由 `/ai-chat`——直接访问或刷新 AI 对话页时，请求被转发到
       * 后端 8088，后端没有这个路径（或未启动）就返回 502/404。
       * 而 AI 对话页挂在全站悬浮按钮上，是高频入口，刷新即白屏。
       *
       * 用正则 key 精确限定「/ai 后面必须跟 / 或结束」，
       * 这样 /ai/api/v1/... 与 /ai/ws/alerts 照常代理，/ai-chat 留给前端路由。
       */
      '^/ai(/.*)?$': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        // WebSocket 代理：/ai/ws/alerts 也走此代理，无需前端直连后端 WS 端口
        ws: true,
      },
    },
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
