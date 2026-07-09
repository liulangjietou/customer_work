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
    port: 5174,
    // 监听所有网卡（不只是 127.0.0.1），局域网内其它设备才能通过本机 IP（如 10.123.45.678:5174）访问；
    // /api 代理运行在这台机器的 Vite dev server 进程里，转发目标固定是同机 localhost:8082，
    // 跟浏览器用什么地址访问 Vite 无关，不用跟着改。
    host: '0.0.0.0',
    proxy: {
      // 开发期把 /api 代理到 customer-admin-server，避免 CORS，与生产反向代理路径保持一致
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
