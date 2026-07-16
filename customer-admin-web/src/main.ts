import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'highlight.js/styles/github.css'
import App from './App.vue'
import router from './router'
import { installPermissionDirective } from './directives/permission'
import { installElementPlusIcons } from './plugins/icons'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
installPermissionDirective(app)
installElementPlusIcons(app)

app.mount('#app')
