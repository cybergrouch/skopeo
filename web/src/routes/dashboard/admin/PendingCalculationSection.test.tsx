import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PendingCalculationSection } from './PendingCalculationSection'

const { useGetApiV1Matches, usePostApiV1RatingsCalculations } = vi.hoisted(
  () => ({
    useGetApiV1Matches: vi.fn(),
    usePostApiV1RatingsCalculations: vi.fn(),
  }),
)

vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1Matches,
  getGetApiV1MatchesQueryKey: () => ['matches', 'pending-calculation'],
}))
vi.mock('@/api/generated/ratings/ratings', () => ({
  usePostApiV1RatingsCalculations,
}))

const PREVIEW = {
  dryRun: true,
  matchesProcessed: 1,
  matches: [
    {
      matchId: 'm1',
      matchDate: '2026-01-01',
      changes: [
        {
          userId: 'abcdef1234',
          system: 'NTRP',
          previousRating: '4.000000',
          newRating: '4.100000',
          change: '0.100000',
          percentChange: '2.5%',
          levelChanged: false,
        },
      ],
    },
  ],
}
const COMMITTED = { dryRun: false, matchesProcessed: 1, matches: [] }

function renderSection() {
  const client = new QueryClient()
  return render(
    <QueryClientProvider client={client}>
      <PendingCalculationSection />
    </QueryClientProvider>,
  )
}

describe('PendingCalculationSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Matches.mockReturnValue({
      data: [{ id: 'm1' }],
      isLoading: false,
    })
    // mutate(vars) drives the component's onSuccess with a crafted response.
    usePostApiV1RatingsCalculations.mockImplementation(
      (options: {
        mutation: { onSuccess: (data: typeof PREVIEW | typeof COMMITTED) => void }
      }) => ({
        isPending: false,
        mutate: (vars: { data: { dryRun: boolean } }) =>
          options.mutation.onSuccess(vars.data.dryRun ? PREVIEW : COMMITTED),
      }),
    )
  })

  it('shows the pending count', () => {
    renderSection()
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('shows a loading state', () => {
    useGetApiV1Matches.mockReturnValue({ data: undefined, isLoading: true })
    renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('disables Preview when nothing is pending', () => {
    useGetApiV1Matches.mockReturnValue({ data: [], isLoading: false })
    renderSection()
    expect(screen.getByRole('button', { name: 'Preview' })).toBeDisabled()
  })

  it('previews a dry run, then commits', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(screen.getByRole('button', { name: 'Preview' }))
    expect(screen.getByTestId('calculation-preview')).toBeInTheDocument()
    expect(screen.getByText('4.000000 → 4.100000 (0.100000)')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Commit' }))
    await waitFor(() =>
      expect(
        screen.getByText('Committed ratings for 1 match.'),
      ).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('calculation-preview')).not.toBeInTheDocument()
  })

  it('discards a preview without committing', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(screen.getByRole('button', { name: 'Preview' }))
    expect(screen.getByTestId('calculation-preview')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.queryByTestId('calculation-preview')).not.toBeInTheDocument()
  })
})
