import { describe, it, expect, vi, beforeEach } from 'vitest'

type Headers = Record<string, string>
type RequestInterceptor = (config: { headers: Headers }) => Promise<{ headers: Headers }>

const h = vi.hoisted(() => ({
  requestUse: vi.fn(),
  instance: vi.fn(),
  loadAuth: vi.fn<() => Promise<unknown>>(),
}))

// A signed-in caller whose SDK will not load: `authLoaded` true so the interceptor skips the
// session probe and goes straight for a token.
vi.mock('@/auth/authModule', () => ({
  authLoaded: () => true,
  hasPersistedSession: () => true,
  loadAuth: h.loadAuth,
}))
vi.mock('axios', () => ({
  default: {
    create: vi.fn(() =>
      Object.assign(h.instance, { interceptors: { request: { use: h.requestUse } } }),
    ),
  },
}))

await import('./axios')
const interceptor = h.requestUse.mock.calls[0][0] as RequestInterceptor

/**
 * The auth chunk 404s — a deploy landed while this tab was open (#1091).
 *
 * The interceptor runs on every generated query, so throwing here would break every request in the
 * app with a message about module loading. Sending the request unauthenticated lets the server
 * answer 401, which the app already knows how to handle.
 */
describe('customAxiosInstance when the auth SDK cannot load', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    h.loadAuth.mockRejectedValue(new Error('Failed to fetch dynamically imported module'))
  })

  it('sends the request unauthenticated rather than rejecting it', async () => {
    const config = await interceptor({ headers: {} })

    expect(config.headers.Authorization).toBeUndefined()
  })

  it('still stamps the build id, so the request is not silently degraded either', async () => {
    const config = await interceptor({ headers: {} })

    // #752: the server uses this to see which bundles are still live. A failed token lookup must
    // not cost the one header that makes a support conversation possible.
    expect(config.headers['X-Client-Version']).toBeDefined()
  })

  it('reports the failure rather than swallowing it', async () => {
    await interceptor({ headers: {} })

    expect(console.error).toHaveBeenCalled()
  })
})
