/**
 * The web build's deployed version (#229), injected at build time by deploy-web.yml
 * (`VITE_APP_VERSION`, the release tag) — analogous to the API's `/health` version. Falls back to
 * `dev` locally where the var is unset. Read at call time so tests can stub the env.
 */
export function webVersion(): string {
  // `||` (not `??`) so an empty/unset value falls back to "dev".
  return import.meta.env.VITE_APP_VERSION || 'dev'
}

/** The web build's commit SHA (`VITE_APP_COMMIT`), or '' when unset/empty (e.g. local dev). */
export function webCommit(): string {
  return import.meta.env.VITE_APP_COMMIT || ''
}
