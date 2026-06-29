import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RatingRequestSection } from './RatingRequestSection'

const { useGetApiV1RatingRequests, approveMutate, denyMutate, state } = vi.hoisted(() => ({
  useGetApiV1RatingRequests: vi.fn(),
  approveMutate: vi.fn(),
  denyMutate: vi.fn(),
  state: { approveFail: false, denyFail: false },
}))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1RatingRequests,
  getGetApiV1RatingRequestsQueryKey: () => ['rating-requests'],
  usePostApiV1RatingRequestsIdApprove: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      approveMutate(vars)
      if (state.approveFail) handlers?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
  usePostApiV1RatingRequestsIdDeny: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      denyMutate(vars)
      if (state.denyFail) handlers?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
}))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RatingRequestSection />
    </QueryClientProvider>,
  )
}

const request = {
  id: 'r1',
  userId: 'u1',
  status: 'PENDING',
  justification: 'I beat two 4.5s',
  createdAt: '2026-06-01T00:00:00Z',
  requester: { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111' },
}

function page(items: unknown[]) {
  return { data: { items, total: items.length }, isLoading: false }
}

describe('RatingRequestSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.approveFail = false
    state.denyFail = false
    useGetApiV1RatingRequests.mockReturnValue(page([request]))
  })

  it('shows loading and empty states', () => {
    useGetApiV1RatingRequests.mockReturnValue({ data: undefined, isLoading: true })
    const { rerender } = renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    useGetApiV1RatingRequests.mockReturnValue(page([]))
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <RatingRequestSection />
      </QueryClientProvider>,
    )
    expect(screen.getByText('No open re-rate requests.')).toBeInTheDocument()
  })

  it('lists a request and queries the pending status', () => {
    renderSection()
    expect(useGetApiV1RatingRequests).toHaveBeenCalledWith({ status: 'PENDING' })
    expect(screen.getByText('Ana (AAA111)')).toBeInTheDocument()
    expect(screen.getByText('I beat two 4.5s')).toBeInTheDocument()
  })

  it('falls back to a sliced id and a dash for a requester with no name or code', () => {
    useGetApiV1RatingRequests.mockReturnValue(
      page([{ ...request, requester: { userId: 'abcdef120000', displayName: null, publicCode: null } }]),
    )
    renderSection()
    expect(screen.getByText('abcdef12 (—)')).toBeInTheDocument()
  })

  it('approves with a new rating and denies with a reason', async () => {
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    await user.type(screen.getByLabelText('New rating'), '4.5')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    expect(approveMutate).toHaveBeenCalledWith({ id: 'r1', data: { rating: '4.5' } })

    expect(screen.getByRole('button', { name: 'Deny' })).toBeDisabled()
    await user.type(screen.getByLabelText('Denial reason'), 'Not enough')
    await user.click(screen.getByRole('button', { name: 'Deny' }))
    expect(denyMutate).toHaveBeenCalledWith({ id: 'r1', data: { reason: 'Not enough' } })
  })

  it('shows an error when approval fails, and falls back to an id without a requester', async () => {
    state.approveFail = true
    useGetApiV1RatingRequests.mockReturnValue(page([{ ...request, requester: null, userId: 'abcdef120000' }]))
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('abcdef12')).toBeInTheDocument() // requester fallback
    await user.type(screen.getByLabelText('New rating'), 'oops')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    expect(screen.getByText(/Could not approve/)).toBeInTheDocument()
  })

  it('shows an error when denial fails', async () => {
    state.denyFail = true
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Denial reason'), 'nope')
    await user.click(screen.getByRole('button', { name: 'Deny' }))
    expect(screen.getByText(/Could not deny the request/)).toBeInTheDocument()
  })
})
