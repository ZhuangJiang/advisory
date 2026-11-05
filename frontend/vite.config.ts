import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * Dev server proxies `/api` to the Spring backend, so the browser only ever talks to one origin and
 * the SSE endpoint needs no CORS configuration.
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 后端端口约定：20001–20999（advisory-server = 20001）。改后端端口时同步这里。
        target: 'http://localhost:20001',
        changeOrigin: true
      }
    }
  }
})
