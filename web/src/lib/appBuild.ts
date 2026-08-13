/**
 * The identity of the bundle currently running in this tab (#752).
 *
 * Injected by Vite's `define` from the same object written to `/version.json`, so the two are equal by
 * construction rather than by two independent derivations agreeing. `builtAt` is what makes it unique:
 * two builds of the same commit are still two deployments, and a user on the older one is still stale.
 *
 * Unset locally (`vite dev` never defines it) → the fallback below, which the freshness check treats as
 * "don't nag": a dev server has no deployment to be behind.
 */
export type AppBuild = {
  version: string
  commit: string
  builtAt: string
}

declare const __APP_BUILD__: AppBuild | undefined

const DEV_BUILD: AppBuild = { version: 'dev', commit: '', builtAt: '' }

export const APP_BUILD: AppBuild = typeof __APP_BUILD__ === 'undefined' ? DEV_BUILD : __APP_BUILD__

/**
 * A build's identity as one comparable string. `builtAt` alone would do, but including the version and
 * commit makes the value legible in a request header and in a bug report.
 */
export function buildId(build: AppBuild): string {
  return [build.version, build.commit, build.builtAt].filter(Boolean).join('+')
}

/** This tab's build id — sent as `X-Client-Version` so the server can see which builds are live. */
export const APP_BUILD_ID = buildId(APP_BUILD)

/** True for a bundle served by `vite dev` or a test, where there is no deployment to be behind. */
export const IS_DEV_BUILD = APP_BUILD.builtAt === ''
