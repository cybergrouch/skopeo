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
