import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AwaitingResultsSection } from './AwaitingResultsSection'

const { useGetApiV1Matches, useGetApiV1Users, mutateAsync, busy } = vi.hoisted(() => ({
  useGetApiV1Matches: vi.fn(),
  useGetApiV1Users: vi.fn(),
  mutateAsync: vi.fn(),
  busy: { value: false },
}))

vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1Matches,
  getGetApiV1MatchesQueryKey: () => ['matches', 'awaiting-results'],
  usePostApiV1MatchesIdResult: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: busy.value,
    mutateAsync: async (vars: unknown) => {
      const r = await mutateAsync(vars)
      options?.mutation?.onSuccess?.()
      return r
    },
  }),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))

const match = {
  id: 'm1',
  matchDate: '2026-01-01',
  team1: { teamId: 't1', userIds: ['p1'] },
  team2: { teamId: 't2', userIds: ['p2'] },
}

function renderSection(eventId?: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AwaitingResultsSection eventId={eventId} />
    </QueryClientProvider>,
  )
}

describe('AwaitingResultsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Matches.mockReturnValue({ data: [match], isLoading: false })
    useGetApiV1Users.mockReturnValue({
      data: [
        { id: 'p1', displayName: 'Alice', capabilities: [] },
        { id: 'p2', displayName: 'Bob', capabilities: [] },
      ],
      isLoading: false,
    })
    mutateAsync.mockResolvedValue({})
    busy.value = false
  })

  it('shows a busy label while recording', () => {
    busy.value = true
    renderSection()
    expect(screen.getByRole('button', { name: 'Recording…' })).toBeDisabled()
  })

  it('scopes the query to an event when given an eventId (#138)', () => {
    renderSection('evt-1')
    expect(useGetApiV1Matches).toHaveBeenCalledWith({ filter: 'awaiting-results', eventId: 'evt-1' })
  })

  it('shows a loading state', () => {
    useGetApiV1Matches.mockReturnValue({ data: undefined, isLoading: true })
    renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when nothing is awaiting results', () => {
    useGetApiV1Matches.mockReturnValue({ data: [], isLoading: false })
    renderSection()
    expect(screen.getByText('No fixtures awaiting results.')).toBeInTheDocument()
  })

  it('resolves participant names from ids', () => {
    renderSection()
    expect(screen.getByText('Alice vs Bob')).toBeInTheDocument()
  })

  it('falls back to a shortened id when names are unresolved', () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false })
    renderSection()
    expect(screen.getByText('p1 vs p2')).toBeInTheDocument()
  })

  it('uses the id when a resolved user has no display name', () => {
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

  it('badges an overdue fixture, a fixture today, and an upcoming one', () => {
    const now = new Date()
    const isoToday = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`

    useGetApiV1Matches.mockReturnValue({
      data: [
        { ...match, id: 'past', matchDate: '2020-01-01' },
        { ...match, id: 'today', matchDate: isoToday },
        { ...match, id: 'future', matchDate: '2999-12-31' },
      ],
      isLoading: false,
    })
    renderSection()

    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.getByText('Today')).toBeInTheDocument()
    expect(screen.getByText('Upcoming')).toBeInTheDocument()
  })

  it('records a result from the entered set scores', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('set 1 player 1 games'), '6')
    await user.type(screen.getByLabelText('set 1 player 2 games'), '4')

    await user.click(screen.getByRole('button', { name: 'Record result' }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        id: 'm1',
        data: { sets: [{ team1Games: 6, team2Games: 4 }] },
      }),
    )
  })

  it('refuses to submit with no sets entered', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Record result' }))
    expect(screen.getByText('Enter at least one set.')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('adds sets up to the max and removes down to one', async () => {
    const user = userEvent.setup()
    renderSection()
    expect(screen.queryByText('Set 3')).not.toBeInTheDocument()

    // Add from the default 2 rows up to the max of 5; then "Add set" disappears.
    await user.click(screen.getByRole('button', { name: 'Add set' }))
    expect(screen.getByText('Set 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add set' }))
    await user.click(screen.getByRole('button', { name: 'Add set' }))
    expect(screen.queryByRole('button', { name: 'Add set' })).not.toBeInTheDocument()

    // Remove down to a single row; then "Remove" disappears.
    const removeButtons = () => screen.queryAllByRole('button', { name: 'Remove' })
    while (removeButtons().length > 1) {
      await user.click(removeButtons()[0])
    }
    expect(removeButtons()).toHaveLength(0)
  })

  it('shows an error when recording fails', async () => {
    mutateAsync.mockRejectedValue(new Error('no winner'))
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('set 1 player 1 games'), '6')
    await user.type(screen.getByLabelText('set 1 player 2 games'), '6')

    await user.click(screen.getByRole('button', { name: 'Record result' }))

    expect(await screen.findByText(/Could not record the result/i)).toBeInTheDocument()
  })
})
