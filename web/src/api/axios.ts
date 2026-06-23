import Axios, { type AxiosRequestConfig } from 'axios'
import { auth } from '@/lib/firebase'

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
  return config
})

export const customAxiosInstance = <T>(config: AxiosRequestConfig): Promise<T> =>
  axiosInstance({ ...config }).then(({ data }) => data)

export default customAxiosInstance
