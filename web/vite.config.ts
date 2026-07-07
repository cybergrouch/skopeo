import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Emit a machine-checkable version.json into the build (#229) — the web's "/health": the deployed
// version (VITE_APP_VERSION, the release tag), commit, and build time. Verify a deploy with
// `curl https://<host>/version.json`. Values come from the deploy-web.yml build env (process.env);
// unset locally → "dev".
function emitVersionJson(): Plugin {
  const version = process.env.VITE_APP_VERSION ?? 'dev'
  const commit = process.env.VITE_APP_COMMIT ?? ''
  const builtAt = new Date().toISOString()
  return {
    name: 'emit-version-json',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'version.json',
        source: JSON.stringify({ version, commit, builtAt }, null, 2),
      })
    },
  }
}

// https://vite.dev/config/ (extended with Vitest's test config)
export default defineConfig({
  plugins: [react(), tailwindcss(), emitVersionJson()],
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
