/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    // 5173 is inside the gateway's CORS allow-list (http://localhost:*).
    port: 5173,
    strictPort: true,
  },
  build: {
    // MapLibre alone is ~800 kB minified; it gets its own chunk.
    chunkSizeWarningLimit: 1100,
    rollupOptions: {
      output: {
        manualChunks: (id) => (id.includes('maplibre-gl') ? 'maplibre' : undefined),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Screen tests that type and click through a whole flow can pass 5 s when every file runs at once.
    testTimeout: 15_000,
    // e2e/ holds Playwright specs that need the running stack (npm run e2e).
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
