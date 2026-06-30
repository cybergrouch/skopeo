import { useQuery } from '@tanstack/react-query'
import { axiosInstance } from '@/api/axios'

/** The API's `/health` payload (an infra endpoint, not part of the `/api/v1` OpenAPI contract). */
export interface ApiHealth {
  status: string
  service: string
  version: string
}

/**
 * Read the API's deployed version from `/health` (#229). Not in the generated client — `/health`
 * lives outside `/api/v1` — so it's fetched directly via the shared axios instance. Used by the
 * Admin build-info advisory to compare against the web bundle's version.
 */
export function useApiHealth() {
  return useQuery({
    queryKey: ['api-health'],
    queryFn: async (): Promise<ApiHealth> => {
      const { data } = await axiosInstance.get<ApiHealth>('/health')
      return data
    },
    staleTime: 60_000,
    retry: false,
  })
}
