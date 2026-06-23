import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PendingAssessmentSection } from './PendingAssessmentSection'

const { useGetApiV1UsersPendingAssessment, mutateAsync, setRatingState } =
  vi.hoisted(() => ({
    useGetApiV1UsersPendingAssessment: vi.fn(),
    mutateAsync: vi.fn(),
    setRatingState: { isPending: false },
  }))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersPendingAssessment,
  // Invoke the caller's onSuccess on a successful assignment (drives invalidation).
  usePutApiV1UsersUserIdRatingsSystem: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: setRatingState.isPending,
    mutateAsync: async (vars: unknown) => {
      const result = await mutateAsync(vars)
      options?.mutation?.onSuccess?.()
      return result
    },
  }),
  getGetApiV1UsersPendingAssessmentQueryKey: () => ['pending-assessment'],
}))

function renderSection() {
  const client = new QueryClient()
  return render(
    <QueryClientProvider client={client}>
      <PendingAssessmentSection />
    </QueryClientProvider>,
  )
}

describe('PendingAssessmentSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setRatingState.isPending = false
    useGetApiV1UsersPendingAssessment.mockReturnValue({
      data: [{ userId: 'u1', displayName: 'Roger F.' }],
      isLoading: false,
    })
    mutateAsync.mockResolvedValue({})
  })

  it('shows a loading state', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when no one is pending', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue({
      data: [],
      isLoading: false,
    })
    renderSection()
    expect(
      screen.getByText('No players are pending assessment.'),
    ).toBeInTheDocument()
  })

  it('falls back to the user id when there is no display name', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue({
      data: [{ userId: 'u-no-name', displayName: null }],
      isLoading: false,
    })
    renderSection()
    expect(screen.getByText('u-no-name')).toBeInTheDocument()
  })

  it('assigns a rating with the chosen system and value', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.selectOptions(screen.getByLabelText('System'), 'UTR')
    await user.type(screen.getByLabelText('Rating'), '8.5')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        userId: 'u1',
        system: 'UTR',
        data: { value: '8.5' },
      }),
    )
  })

  it('shows a busy label while the assignment is in flight', () => {
    setRatingState.isPending = true
    renderSection()
    expect(screen.getByRole('button', { name: 'Setting…' })).toBeDisabled()
  })

  it('shows an error when the assignment fails', async () => {
    mutateAsync.mockRejectedValue(new Error('bad'))
    const user = userEvent.setup()
    renderSection()

    await user.type(screen.getByLabelText('Rating'), '99')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    expect(
      await screen.findByText(/Could not set the rating/i),
    ).toBeInTheDocument()
  })
})
