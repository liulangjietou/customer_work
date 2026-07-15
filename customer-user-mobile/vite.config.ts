import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5175,
    // 监听所有网卡，局域网内其它设备可通过本机 IP 访问；代理目标固定同机 customer-work-app(8080)，
    // 与浏览器访问 Vite 用的地址无关。
    host: '0.0.0.0',
    proxy: {
      // 开发期把 /api 代理到 customer-work-app，避免 CORS，与生产反向代理路径保持一致
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket 独立走 /ws 前缀，ws: true 开启协议升级转发
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
