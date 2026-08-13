import { describe, it, expect, afterEach, vi } from 'vitest'
import { buildId } from './appBuild'

const injected = { version: '1.4.2', commit: 'deadbee', builtAt: '2026-08-13T09:00:00.000Z' }

async function loadWithGlobalBuild(value: unknown) {
  vi.resetModules()
  ;(globalThis as Record<string, unknown>).__APP_BUILD__ = value
  return import('./appBuild')
}

describe('buildId', () => {
  it('joins the identifying parts so the value is legible in a header or a bug report', () => {
    expect(buildId(injected)).toBe('1.4.2+deadbee+2026-08-13T09:00:00.000Z')
  })

  it('drops the empty parts a local build leaves behind', () => {
    expect(buildId({ version: 'dev', commit: '', builtAt: '' })).toBe('dev')
  })
})

describe('APP_BUILD', () => {
  afterEach(() => {
    delete (globalThis as Record<string, unknown>).__APP_BUILD__
    vi.resetModules()
  })

  it('uses the identity Vite injected at build time', async () => {
    const mod = await loadWithGlobalBuild(injected)
    expect(mod.APP_BUILD).toEqual(injected)
    expect(mod.APP_BUILD_ID).toBe('1.4.2+deadbee+2026-08-13T09:00:00.000Z')
    expect(mod.IS_DEV_BUILD).toBe(false)
  })

  it('falls back to a dev identity when nothing was injected, so the check stays quiet', async () => {
    // `vite dev` and the test runner never define it: there is no deployment to be behind.
    vi.resetModules()
    delete (globalThis as Record<string, unknown>).__APP_BUILD__
    const mod = await import('./appBuild')
    expect(mod.IS_DEV_BUILD).toBe(true)
    expect(mod.APP_BUILD.version).toBe('dev')
  })
})
