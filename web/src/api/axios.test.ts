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

vi.mock('@/lib/firebase', () => ({ auth: h.firebaseAuth }))
vi.mock('axios', () => ({
  default: {
    create: vi.fn(() =>
      Object.assign(h.instance, {
        interceptors: { request: { use: h.requestUse } },
      }),
    ),
  },
}))

const { customAxiosInstance } = await import('./axios')
// Captured once at import; the request interceptor was registered then.
const interceptor = h.requestUse.mock.calls[0][0] as RequestInterceptor

describe('customAxiosInstance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
})
