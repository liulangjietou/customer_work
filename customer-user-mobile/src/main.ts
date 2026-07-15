import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Vant from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

// 电脑浏览器鼠标模拟触摸：用户只在电脑浏览器验证移动端页面，van-swipe-cell/van-list 等手势交互
// 依赖 touchstart/touchmove/touchend，仅有 mouse 事件的桌面浏览器需要这个 polyfill 才能操作。
import '@vant/touch-emulator'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Vant)

app.mount('#app')
