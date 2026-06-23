import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/ (extended with Vitest's test config)
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
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
