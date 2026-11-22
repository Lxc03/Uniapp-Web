import { createSSRApp } from 'vue'
import App from './App.vue'

/** 返回由 UniApp 挂载的根 Vue 应用。 */
export function createApp() {
  return { app: createSSRApp(App) }
}
