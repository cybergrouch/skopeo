import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReRateRequestCard } from './ReRateRequestCard'

const { useGetApiV1RatingRequestsMe, createMutate, state } = vi.hoisted(() => ({
  useGetApiV1RatingRequestsMe: vi.fn(),
  createMutate: vi.fn(),
  state: { fail: false },
}))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1RatingRequestsMe,
  getGetApiV1RatingRequestsMeQueryKey: () => ['rating-requests', 'me'],
  usePostApiV1RatingRequests: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      createMutate(vars)
      if (state.fail) handlers?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
}))

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ReRateRequestCard />
    </QueryClientProvider>,
  )
}

describe('ReRateRequestCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.fail = false
    useGetApiV1RatingRequestsMe.mockReturnValue({ data: undefined })
  })

  it('shows the form and submits a justification when there is no open request', async () => {
    const user = userEvent.setup()
    renderCard()
    expect(screen.getByRole('button', { name: 'Request re-rate' })).toBeDisabled()
    await user.type(screen.getByLabelText('Justification'), 'I improved a lot')
    await user.click(screen.getByRole('button', { name: 'Request re-rate' }))
    expect(createMutate).toHaveBeenCalledWith({ data: { justification: 'I improved a lot' } })
  })

  it('shows the pending status without a form', () => {
    useGetApiV1RatingRequestsMe.mockReturnValue({
      data: { id: 'r1', userId: 'u1', status: 'PENDING', justification: 'pending one', createdAt: '2026-06-01T00:00:00Z' },
    })
    renderCard()
    expect(screen.getByText('Your request is pending review.')).toBeInTheDocument()
    expect(screen.getByText('pending one')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request re-rate' })).not.toBeInTheDocument()
  })

  it('shows an approval outcome (with the new band) above a fresh form', () => {
    useGetApiV1RatingRequestsMe.mockReturnValue({
      data: { id: 'r1', userId: 'u1', status: 'APPROVED', justification: 'x', newRating: '4.5', createdAt: '2026-06-01T00:00:00Z' },
    })
    renderCard()
    expect(screen.getByText('approved')).toBeInTheDocument()
    expect(screen.getByText(/new band 4\.5/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request re-rate' })).toBeInTheDocument()
  })

  it('shows a denial outcome with its reason, and surfaces a submit error', async () => {
    state.fail = true
    useGetApiV1RatingRequestsMe.mockReturnValue({
      data: { id: 'r1', userId: 'u1', status: 'DENIED', justification: 'x', reason: 'Insufficient', createdAt: '2026-06-01T00:00:00Z' },
    })
    const user = userEvent.setup()
    renderCard()
    expect(screen.getByText('denied')).toBeInTheDocument()
    expect(screen.getByText(/Insufficient/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Justification'), 'try again')
    await user.click(screen.getByRole('button', { name: 'Request re-rate' }))
    expect(screen.getByText(/Could not submit your request/)).toBeInTheDocument()
  })

  it('handles an approval with no new band and a denial with no reason', () => {
    useGetApiV1RatingRequestsMe.mockReturnValue({
      data: { id: 'r1', userId: 'u1', status: 'APPROVED', justification: 'x', createdAt: '2026-06-01T00:00:00Z' },
    })
    const { rerender } = renderCard()
    expect(screen.getByText('approved')).toBeInTheDocument()

    useGetApiV1RatingRequestsMe.mockReturnValue({
      data: { id: 'r2', userId: 'u1', status: 'DENIED', justification: 'x', createdAt: '2026-06-01T00:00:00Z' },
    })
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <ReRateRequestCard />
      </QueryClientProvider>,
    )
    expect(screen.getByText('denied')).toBeInTheDocument()
  })
})
