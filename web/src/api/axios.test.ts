import { describe, it, expect, beforeEach, vi } from 'vitest'

type Headers = Record<string, string>
type RequestInterceptor = (config: {
  headers: Headers
}) => Promise<{ headers: Headers }>

const h = vi.hoisted(() => {
  const getIdToken = vi.fn<() => Promise<string | undefined>>()
  return {
    getIdToken,
    firebaseAuth: {
      currentUser: { getIdToken } as { getIdToken: typeof getIdToken } | null,
    },
    requestUse: vi.fn(),
    instance: vi.fn(),
  }
})

vi.mock('@/lib/firebaseAuth', () => ({ auth: h.firebaseAuth }))
// `loadAuth` imports both halves; the SDK itself is never called here, only pulled in.
vi.mock('firebase/auth', () => ({}))
vi.mock('axios', () => ({
  default: {
    create: vi.fn(() =>
      Object.assign(h.instance, {
        interceptors: { request: { use: h.requestUse } },
      }),
    ),
  },
}))

const { resetAuthModuleForTests } = await import('@/auth/authModule')
const { customAxiosInstance } = await import('./axios')

/** What Firebase persists a session under — the interceptor's cue that a token is worth fetching. */
const SESSION_KEY = 'firebase:authUser:test-api-key:[DEFAULT]'
// Captured once at import; the request interceptor was registered then.
const interceptor = h.requestUse.mock.calls[0][0] as RequestInterceptor

describe('customAxiosInstance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetAuthModuleForTests()
    window.localStorage.clear()
    // Signed-in unless a test says otherwise. Since #1091 the interceptor only reaches for the SDK
    // when a session looks persisted, so this key is what makes it look.
    window.localStorage.setItem(SESSION_KEY, '{}')
    h.firebaseAuth.currentUser = { getIdToken: h.getIdToken }
  })

  it('unwraps the axios response to its data', async () => {
    h.instance.mockResolvedValue({ data: { ok: true } })
    const result = await customAxiosInstance<{ ok: boolean }>({ url: '/x' })
    expect(result).toEqual({ ok: true })
    expect(h.instance).toHaveBeenCalledWith({ url: '/x' })
  })

  it('attaches the Firebase ID token as a Bearer header', async () => {
    h.getIdToken.mockResolvedValue('tok-123')
    const config = await interceptor({ headers: {} })
    expect(config.headers.Authorization).toBe('Bearer tok-123')
  })

  it('leaves the request unauthenticated when no user is signed in', async () => {
    h.firebaseAuth.currentUser = null
    const config = await interceptor({ headers: {} })
    expect(config.headers.Authorization).toBeUndefined()
  })

  /**
   * The public-page case #1091 exists for. An anonymous visitor's request must go out unauthenticated
   * *without* the interceptor ever reaching for the SDK — this mutator is imported by every generated
   * query, so a token lookup here is what dragged 162 kB onto `/players/:code`.
   *
   * `getIdToken` standing untouched is the proof: it is only reachable through the dynamic import.
   */
  it('does not reach for the SDK when no session is persisted', async () => {
    window.localStorage.clear()

    const config = await interceptor({ headers: {} })

    expect(config.headers.Authorization).toBeUndefined()
    expect(h.getIdToken).not.toHaveBeenCalled()
    // The build-id header is set unconditionally, so this proves the interceptor ran at all (#752).
    expect(config.headers['X-Client-Version']).toBeDefined()
  })
})
