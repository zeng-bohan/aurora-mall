import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 端口允许被 PORT 覆盖：本机 5173 常被别的项目占着，
    // 预览工具会自动分配端口并通过环境变量传进来。
    port: Number(process.env.PORT) || 5173,
    // 前端只认同源的 /api：开发期由 Vite 反代到网关，生产由 nginx 做同样的事。
    // 这样前端代码里不出现网关地址，两种环境的跨域/凭据行为也一致。
    proxy: {
      '/api': {
        target: process.env.VITE_GATEWAY ?? 'http://localhost:8000',
        changeOrigin: true
      }
    }
  }
})
