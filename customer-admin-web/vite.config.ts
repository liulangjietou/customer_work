import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板里的 el-* 组件与 ElMessage 等函数式 API 由 resolver
    // 自动注入 import 及配套样式。约定：业务代码不再手写 element-plus 的值 import
    //（type import 不受影响，可保留），否则样式不会被按需带出。
    // 图标是例外：MenuTree/MenuManage/IconPicker 按运行时字符串渲染图标，静态分析
    // 覆盖不了，保持 plugins/icons.ts 全量全局注册，不做图标按需。
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      // 只用于解析 Element Plus，本地组件维持显式 import 的现状
      dirs: [],
      // 不生成组件类型声明：生成后模板会启用 EP 组件的严格类型检查，
      // 存量模板（el-table slot 的 DefaultRow、:value 传 null 等）会暴露 100+ 处
      // 既有类型摩擦，与"按需引入行为不变"的目标无关；保持与全量引入时一致的
      // 宽松模板类型，严格化留作后续独立改造。
      dts: false,
    }),
  ],
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
