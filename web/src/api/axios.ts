import Axios, { type AxiosRequestConfig } from 'axios'
import { authLoaded, hasPersistedSession, loadAuth } from '@/auth/authModule'
import { APP_BUILD_ID } from '@/lib/appBuild'

// Single axios instance used by every generated query/mutation (orval's
// `mutator`). It attaches the current user's Firebase ID token to each request;
// the backend verifies it against Firebase's JWKS.
export const axiosInstance = Axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
})

/**
 * The caller's ID token, if there is one to have — **without** dragging the Firebase SDK into every
 * page that makes an API call (#1091).
 *
 * This mutator is imported by every generated query and mutation, so a static `import { auth }` here
 * put 162 kB gzip in front of anyone viewing a public-by-code page (#193), who will never sign in.
 *
 * Two cheap checks decide, in order:
 *
 * - **already loaded** — some other surface has the SDK, so just ask it;
 * - **a session looks persisted** — a returning user mid-restore, so load and ask properly.
 *
 * An anonymous visitor matches neither and the SDK is never fetched. Their requests go out without an
 * `Authorization` header, which is exactly right: the endpoints behind public pages do not want one.
 */
async function currentIdToken(): Promise<string | undefined> {
  if (!authLoaded() && !hasPersistedSession()) return undefined
  const { auth } = await loadAuth()
  return auth.currentUser?.getIdToken()
}

axiosInstance.interceptors.request.use(async (config) => {
  const token = await currentIdToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // Which bundle is talking (#752): lets the server see which builds are still live, and gives a
  // support conversation a fact instead of "try refreshing".
  // Named on the server too (CLIENT_VERSION_HEADER) and allowed there in CORS — a custom header the
  // backend hasn't allowed fails the preflight and takes down every cross-origin call, not just this one.
  config.headers['X-Client-Version'] = APP_BUILD_ID
  return config
})

export const customAxiosInstance = <T>(config: AxiosRequestConfig): Promise<T> =>
  axiosInstance({ ...config }).then(({ data }) => data)

export default customAxiosInstance
