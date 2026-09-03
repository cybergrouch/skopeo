import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The identity of THIS build (#229/#752). Computed once so the copy baked into the bundle and the copy
// served at /version.json are the same values — a stale-client check that compared two independently
// derived ids would report a false mismatch on every load. builtAt is what makes it unique: two builds
// of the same commit are still two deployments.
const BUILD = {
  version: process.env.VITE_APP_VERSION ?? 'dev',
  commit: process.env.VITE_APP_COMMIT ?? '',
  builtAt: new Date().toISOString(),
}

// Emit a machine-checkable version.json into the build (#229) — the web's "/health": the deployed
// version (VITE_APP_VERSION, the release tag), commit, and build time. Verify a deploy with
// `curl https://<host>/version.json`. Values come from the deploy-web.yml build env (process.env);
// unset locally → "dev". Also polled by the running app to notice a newer deployment (#752).
function emitVersionJson(): Plugin {
  return {
    name: 'emit-version-json',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'version.json',
        source: JSON.stringify(BUILD, null, 2),
      })
    },
  }
}

// https://vite.dev/config/ (extended with Vitest's test config)
export default defineConfig({
  plugins: [react(), tailwindcss(), emitVersionJson()],
  // The running bundle's own identity, compared against /version.json to spot a newer deployment (#752).
  define: {
    __APP_BUILD__: JSON.stringify(BUILD),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // Isolate large, rarely-changing vendors into their own long-cached chunks (#277): app-code
        // changes no longer bust their cache, and the browser can fetch them in parallel. Route-level
        // React.lazy (see App.tsx) handles per-page splitting; this covers the heavy shared deps.
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('/firebase/') || id.includes('/@firebase/')) return 'firebase'
            if (id.includes('/@tanstack/')) return 'react-query'
            if (
              id.includes('/react/') ||
              id.includes('/react-dom/') ||
              id.includes('/react-router') ||
              id.includes('/scheduler/')
            ) {
              return 'react-vendor'
            }
          }
          return undefined
        },
      },
    },
  },
  server: {
    // Proxy API calls to the Ktor backend in dev so the browser talks to one
    // origin (no CORS) and the Firebase ID token rides along unchanged.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Heavy component tests (many rows + userEvent) can exceed the 5s default under full-suite
    // parallel load on a saturated CI runner, surfacing as intermittent timeouts. Give every test
    // headroom so load spikes don't fail otherwise-passing tests. (Root-cause speedups still apply
    // per-test, e.g. userEvent.setup({ delay: null }).)
    testTimeout: 15000,
    // JUnit XML feeds the drillable "Web Test Report" check in CI (dorny);
    // the default reporter keeps console output readable locally.
    reporters: ['default', 'junit'],
    outputFile: { junit: './test-results/junit.xml' },
    coverage: {
      provider: 'v8',
      reportsDirectory: './coverage',
      reporter: ['text', 'lcov'],
      // Mirror the backend: exclude generated code and pure SDK-init/composition
      // glue (like the backend excludes config/ and Application) from coverage.
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/api/generated/**',
        'src/test/**',
        'src/main.tsx',
        'src/App.tsx', // router/provider composition
        'src/lib/firebase.ts', // Firebase SDK initialization
        'src/vite-env.d.ts',
        '**/*.d.ts',
      ],
    },
  },
})
