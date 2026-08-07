import { defineConfig } from 'orval'

// Generates a typed TanStack Query client from the backend's hand-maintained,
// test-verified OpenAPI spec. Output (src/api/generated/) is gitignored and
// produced by `npm run api:generate` (run automatically before build/lint).
export default defineConfig({
  skopeo: {
    input: {
      target: '../src/main/resources/openapi/documentation.yaml',
    },
    output: {
      mode: 'tags-split',
      target: 'src/api/generated',
      schemas: 'src/api/generated/model',
      client: 'react-query',
      httpClient: 'axios',
      // Bundle the axios mutator with esbuild targeting our real TS config
      // (ES2023) instead of orval's es6 default. Without this, orval finds the
      // references-only root tsconfig.json (no compilerOptions.target), falls
      // back to es6, and floods `npm run dev`/`build` startup with
      // "import.meta is not available (es2015)" warnings for src/api/axios.ts
      // (which reads import.meta.env.VITE_API_BASE_URL). See issue #707.
      tsconfig: 'tsconfig.app.json',
      override: {
        // axios mutator injects the Firebase ID token on every request.
        mutator: {
          path: 'src/api/axios.ts',
          name: 'customAxiosInstance',
        },
      },
    },
  },
})
