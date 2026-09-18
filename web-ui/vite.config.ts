import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// During `npm run dev`, API calls are proxied to a running ME Control Center server.
// Override with MECC_DEV_API=http://<host>:<port>.
const apiTarget = process.env.MECC_DEV_API ?? 'http://127.0.0.1:18181';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      // Keeps the browser's Host header, so the server's WebSocket origin check sees a same-origin request.
      '/ws': { target: apiTarget.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: {
    // Gradle copies dist/ into the mod jar under mecc-web/.
    outDir: 'dist',
    assetsDir: 'assets',
    emptyOutDir: true,
    sourcemap: false,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
