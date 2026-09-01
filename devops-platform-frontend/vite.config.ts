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
  css: {
    preprocessorOptions: {
      scss: {},
    },
  },
  build: {
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts'
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
          if (id.includes('lucide-vue-next')) return 'vendor-icons'
          // TanStack Query 独立成块：它是稳定依赖，与业务代码分离后
          // 业务发版不会让用户重新下载它
          if (id.includes('@tanstack')) return 'vendor-query'
          // Markdown 渲染链路（marked + dompurify）：阅读页也要用，
          // 与编辑器分开，避免阅读者被迫下载编辑器
          if (id.includes('marked') || id.includes('dompurify')) return 'vendor-markdown'
          if (id.includes('vue-router') || id.includes('pinia') || id.includes('/vue/') || id.includes('@vue/')) return 'vendor-vue'

          /**
           * 其余依赖**不再归入兜底 'vendor' 块**。
           *
           * 原先 `return 'vendor'` 把所有剩余依赖强行合成一个块。
           * 只要其中有任意一个被入口用到，整块（含只被懒加载路由使用的
           * 编辑器等）就会被预加载——2.9MB 首屏下发，而其中大半
           * 普通用户永远用不到。
           *
           * 返回 undefined 交回 Rollup 自动分块：它会按实际引用关系
           * 把「入口用的」与「仅懒加载路由用的」分开，
           * 只有真正进入对应路由才拉取。
           */
          return undefined
        }
      }
    }
  }
})
