import { describe, it, expect, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useApiHealth } from './health'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/api/axios', () => ({ axiosInstance: { get } }))

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return createElement(QueryClientProvider, { client }, children)
}

describe('useApiHealth', () => {
  it('reads the API version from /health (#229)', async () => {
    get.mockResolvedValue({ data: { status: 'UP', service: 'Skopeo API', version: '0.0.2' } })

    const { result } = renderHook(() => useApiHealth(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(get).toHaveBeenCalledWith('/health')
    expect(result.current.data?.version).toBe('0.0.2')
  })
})
