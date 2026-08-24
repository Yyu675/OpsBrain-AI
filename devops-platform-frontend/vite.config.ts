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
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 开发服务器：监听 0.0.0.0 使局域网其他设备可通过本机 IP 访问；
  // 代理 /ai 到后端，前端用相对路径即可，无需硬编码 localhost。
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/ai': {
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
          if (id.includes('vue-router') || id.includes('pinia') || id.includes('/vue/') || id.includes('@vue/')) return 'vendor-vue'
          return 'vendor'
        }
      }
    }
  }
})
