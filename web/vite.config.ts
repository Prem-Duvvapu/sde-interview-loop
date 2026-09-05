import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const BACKEND = 'http://localhost:8123';

/**
 * Dev server proxies /api and /ws to the Spring Boot app on :8123, so the
 * browser only ever talks to one origin and CORS never enters the picture.
 *
 * Both ports are deliberately non-default (not 8080/5173) — this machine runs several
 * unrelated projects that default to those, and collisions between them have bitten us
 * before.
 *
 * The backend is frequently not running (it is built by a different agent /
 * started by hand). A proxy error must therefore never take the dev server
 * down — it is logged once and the browser request fails normally, which the
 * client is written to degrade gracefully around.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5273,
    strictPort: true,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('error', (err) => {
            console.warn(`[proxy] /api -> ${BACKEND} unreachable: ${err.message}`);
          });
        },
      },
      '/ws': {
        target: BACKEND,
        ws: true,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('error', (err) => {
            console.warn(`[proxy] /ws -> ${BACKEND} unreachable: ${err.message}`);
          });
        },
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/monaco-editor')) return 'monaco';
          if (id.includes('node_modules/react')) return 'react';
          return undefined;
        },
      },
    },
  },
});
