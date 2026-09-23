import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端开发服务器将 /api 代理到 Spring Boot 后端（端口 8090），
// 这样浏览器与后端之间走同源，JSESSIONID Cookie 会话可正常工作。
// 生产构建（npm run build）直接输出到 Spring Boot 的 static 目录，
// 由后端 jar 一同托管前端，无需额外部署。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  },
  base: './',
  build: {
    outDir: '../srtp-server/src/main/resources/static',
    emptyOutDir: true
  }
})
