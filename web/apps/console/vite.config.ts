import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(dir, 'src'),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    // Keycloak is remote and CORS is strict (R12). Proxying the API in dev means the browser
    // only ever talks to one origin, so a CORS mistake cannot masquerade as a broken auth
    // flow while developing. Production is a separate concern (see plan section 9).
    proxy: {
      '/api': {
        target: process.env.VITE_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        // The console is one bundle split by role, so separating the heavy admin-only
        // dependencies keeps the shop dashboard light for the many shops that never open
        // the admin panel.
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
});
