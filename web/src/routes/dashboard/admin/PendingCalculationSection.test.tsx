import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PendingCalculationSection } from './PendingCalculationSection'

const { useGetApiV1Matches, usePostApiV1RatingsCalculations, useGetApiV1Users } =
  vi.hoisted(() => ({
    useGetApiV1Matches: vi.fn(),
    usePostApiV1RatingsCalculations: vi.fn(),
    useGetApiV1Users: vi.fn(),
  }))

vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1Matches,
  getGetApiV1MatchesQueryKey: () => ['matches', 'pending-calculation'],
}))
vi.mock('@/api/generated/ratings/ratings', () => ({
  usePostApiV1RatingsCalculations,
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))

function match(overrides = {}) {
  return {
    id: 'm1',
    matchDate: '2026-01-01',
    team1: { teamId: 't1', userIds: ['p1'] },
    team2: { teamId: 't2', userIds: ['p2'] },
    winnerTeamId: 't1',
    sets: [
      { setNumber: 1, team1Games: 6, team2Games: 4, winnerTeamId: 't1' },
      { setNumber: 2, team1Games: 6, team2Games: 3, winnerTeamId: 't1' },
    ],
    ...overrides,
  }
}

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
      data: [match()],
      isLoading: false,
    })
    useGetApiV1Users.mockReturnValue({
      data: [
        { id: 'p1', displayName: 'Alice', capabilities: [] },
        { id: 'p2', displayName: 'Bob', capabilities: [] },
      ],
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

  it('falls back to shortened ids when participant names are unresolved', () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false })
    renderSection()
    expect(screen.getByText('p1 vs p2')).toBeInTheDocument()
  })

  it('uses the id when a resolved participant has no display name', () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        { id: 'p1', displayName: null, capabilities: [] },
        { id: 'p2', displayName: 'Bob', capabilities: [] },
      ],
      isLoading: false,
    })
    renderSection()
    expect(screen.getByText('p1 vs Bob')).toBeInTheDocument()
  })

  it('shows a card per pending match with players, date, scores, and winner', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [
        match({ id: 'm1', winnerTeamId: 't1' }),
        match({ id: 'm2', winnerTeamId: 't2' }),
        match({ id: 'm3', winnerTeamId: null, sets: [] }),
      ],
      isLoading: false,
    })
    renderSection()

    expect(screen.getAllByText('Alice vs Bob')).toHaveLength(3)
    // team1 win → Alice; team2 win → Bob; no winner / no sets → bare date.
    expect(screen.getByText('2026-01-01 · 6-4 6-3 · Winner: Alice')).toBeInTheDocument()
    expect(screen.getByText('2026-01-01 · 6-4 6-3 · Winner: Bob')).toBeInTheDocument()
    expect(screen.getByText('2026-01-01')).toBeInTheDocument()
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
