import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 暗色模式变量（html.dark 作用域），组件样式本身由按需引入注入
import 'element-plus/theme-chalk/dark/css-vars.css'
// highlight.js 主题不在此静态引入：utils/hljsTheme.ts 按明暗模式运行时切换两套主题
import App from './App.vue'
import router from './router'
import { installPermissionDirective } from './directives/permission'
import { installElementPlusIcons } from './plugins/icons'
import './style.css'
import './theme.css'
import './page-templates.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
installPermissionDirective(app)
installElementPlusIcons(app)

app.mount('#app')
