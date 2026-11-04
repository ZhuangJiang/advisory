import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * Tests run in jsdom (the API client touches `localStorage` and `fetch`), against the real source
 * through the same `@` alias the app uses. Only `tests/**` is collected: component snapshots are not
 * part of this project's contract, behaviour is.
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    restoreMocks: true,
    clearMocks: true
  }
})
