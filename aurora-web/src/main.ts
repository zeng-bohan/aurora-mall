import { createPinia } from 'pinia'
import { createApp } from 'vue'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { router } from './router'
import './styles/main.css'

createApp(App)
  .use(createPinia())
  .use(router)
  // 组件库统一中文：后端文案、表头、分页控件都是中文语境
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
