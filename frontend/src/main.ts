import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { router } from './router'
import { setErrorHandlers } from './api/client'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// The HTTP client must not import the router or the store (that would be a cycle and would make it
// untestable), so the two redirects it can trigger are wired here, once.
setErrorHandlers({
  onUnauthorized: () => {
    useAuthStore().clear()
    void router.replace({ name: 'login' })
  },
  onPasswordChangeRequired: () => {
    void router.replace({ name: 'change-password' })
  }
})

app.mount('#app')
