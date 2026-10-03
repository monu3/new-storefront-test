import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig(({ mode }) => {
  // Every var, not just VITE_-prefixed ones. The api-client package reads
  // NEXT_PUBLIC_API_URL at module scope because it is shared with the Next.js storefront, and
  // filtering here would silently give the console a different API URL from the storefront.
  const env = loadEnv(mode, process.cwd(), '');

  return {
  plugins: [react(), tailwindcss()],
  /*
   * Vite does not define `process` in the browser at all, and four workspace packages read
   * environment variables — several of them at MODULE SCOPE, where a throw takes down the whole
   * app before React ever mounts.
   *
   * It bit three times: enums/hooks.ts, api-client/client.ts and theme-engine/registry.ts each
   * produced a blank page, and each was patched in isolation before the pattern was recognised.
   * Fixing them one file at a time is how four became three.
   *
   * Defining it once here is the correct place. `define` is a textual replacement, so it also
   * covers the COMPUTED access in auth/config.ts (`process.env[key]`), which Vite's default
   * handling would miss, and it covers any dependency that reads process.env.
   */
  define: {
    'process.env': JSON.stringify({
      NODE_ENV: mode === 'production' ? 'production' : 'development',
      NEXT_PUBLIC_API_URL: env.NEXT_PUBLIC_API_URL,
      NEXT_PUBLIC_KEYCLOAK_URL: env.NEXT_PUBLIC_KEYCLOAK_URL,
      NEXT_PUBLIC_KEYCLOAK_CLIENT_ID: env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID,
      NEXT_PUBLIC_APP_URL: env.NEXT_PUBLIC_APP_URL,
      VITE_API_URL: env.VITE_API_URL,
      VITE_KEYCLOAK_URL: env.VITE_KEYCLOAK_URL,
      VITE_KEYCLOAK_OPS_CLIENT_ID: env.VITE_KEYCLOAK_OPS_CLIENT_ID,
      VITE_APP_URL: env.VITE_APP_URL,
    }),
  },
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
};
});
