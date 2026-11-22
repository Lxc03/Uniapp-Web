import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// UniApp 插件负责页面注册和 H5 构建。
export default defineConfig({
  plugins: [uni()],
})
