import Axios, { type AxiosRequestConfig } from 'axios'
import { auth } from '@/lib/firebase'
import { APP_BUILD_ID } from '@/lib/appBuild'

// Single axios instance used by every generated query/mutation (orval's
// `mutator`). It attaches the current user's Firebase ID token to each request;
// the backend verifies it against Firebase's JWKS.
export const axiosInstance = Axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
})

axiosInstance.interceptors.request.use(async (config) => {
  const token = await auth.currentUser?.getIdToken()
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
