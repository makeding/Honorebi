import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/connections/',
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8081',
    },
  },
})
