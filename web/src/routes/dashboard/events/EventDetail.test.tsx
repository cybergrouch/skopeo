import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EventDetail } from './EventDetail'

const { useGetApiV1EventsId, addMutate, removeMutate, createFixtureMutate, state } = vi.hoisted(() => ({
  useGetApiV1EventsId: vi.fn(),
  addMutate: vi.fn(),
  removeMutate: vi.fn(),
  createFixtureMutate: vi.fn(),
  state: { addFail: false, fixtureFail: false },
}))

vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1EventsId,
  getGetApiV1EventsIdQueryKey: () => ['event'],
  usePostApiV1EventsIdParticipants: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      addMutate(vars)
      if (state.addFail) handlers?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
  useDeleteApiV1EventsIdParticipantsUserId: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      removeMutate(vars)
      opts?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/api/generated/matches/matches', () => ({
  getGetApiV1MatchesQueryKey: () => ['matches'],
  usePostApiV1Matches: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      createFixtureMutate(vars, handlers)
      if (state.fixtureFail) handlers?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({
    placeholder,
    onSelect,
  }: {
    placeholder?: string
    onSelect: (u: { id: string; publicCode: string; displayName: string }) => void
  }) => (
    <button type="button" onClick={() => onSelect({ id: 'u3', publicCode: 'CCC333', displayName: 'Cara' })}>
      {placeholder}
    </button>
  ),
}))
vi.mock('../matches/AwaitingResultsSection', () => ({
  AwaitingResultsSection: ({ eventId }: { eventId?: string }) => <div>awaiting:{eventId}</div>,
}))

const event = {
  id: 'e1',
  publicCode: 'EV1',
  name: 'Spring Open',
  startDate: '2026-03-01',
  endDate: '2026-03-03',
  isActive: true,
  participants: [
    { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111' },
    { userId: 'u2', displayName: 'Bob', publicCode: 'BBB222' },
  ],
}

function renderDetail() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <EventDetail eventId="e1" onBack={() => {}} />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('EventDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.addFail = false
    state.fixtureFail = false
    useGetApiV1EventsId.mockReturnValue({ data: event, isLoading: false })
  })

  it('shows a loading then a not-found state', () => {
    useGetApiV1EventsId.mockReturnValue({ data: undefined, isLoading: true })
    const { rerender } = renderDetail()
    expect(screen.getByText('Loading event…')).toBeInTheDocument()

    useGetApiV1EventsId.mockReturnValue({ data: undefined, isLoading: false })
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <EventDetail eventId="e1" onBack={() => {}} />
      </QueryClientProvider>,
    )
    expect(screen.getByText(/could not be loaded/)).toBeInTheDocument()
  })

  it('renders the header, roster, and the scoped awaiting-results section', () => {
    renderDetail()
    expect(screen.getByText('Spring Open')).toBeInTheDocument()
    expect(screen.getByText('EV1')).toBeInTheDocument()
    // Name appears in both the roster and the fixture pickers; the code is unique to the roster line.
    expect(screen.getByText(/\(AAA111\)/)).toBeInTheDocument()
    expect(screen.getByText(/\(BBB222\)/)).toBeInTheDocument()
    expect(screen.getByText('awaiting:e1')).toBeInTheDocument()
  })

  it('falls back to code, then to a sliced id, for participants without a display name', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: {
        ...event,
        participants: [
          { userId: 'u4', displayName: null, publicCode: 'DDD444' },
          { userId: 'abcdef120000', displayName: null, publicCode: null },
        ],
      },
      isLoading: false,
    })
    renderDetail()
    expect(screen.getAllByText(/DDD444/).length).toBeGreaterThan(0) // name falls back to the code
    expect(screen.getAllByText(/abcdef12/).length).toBeGreaterThan(0) // both null → sliced id
  })

  it('shows the empty-roster message when there are no participants', () => {
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, participants: [] }, isLoading: false })
    renderDetail()
    expect(screen.getByText('No participants yet.')).toBeInTheDocument()
  })

  it('adds and removes a participant', async () => {
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Search players…' }))
    expect(addMutate).toHaveBeenCalledWith({ id: 'e1', data: { userId: 'u3' } })

    await user.click(screen.getAllByRole('button', { name: 'Remove' })[0])
    expect(removeMutate).toHaveBeenCalledWith({ id: 'e1', userId: 'u1' })
  })

  it('schedules a participant-scoped fixture (disabled until valid)', async () => {
    const user = userEvent.setup()
    renderDetail()

    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: {
          matchFormat: 'SINGLES',
          matchType: 'OPEN_PLAY',
          matchDate: '2026-03-02',
          team1: ['u1'],
          team2: ['u2'],
          eventId: 'e1',
        },
      },
      expect.anything(),
    )
  })

  it('keeps the schedule button disabled when both players are the same', async () => {
    const user = userEvent.setup()
    renderDetail()
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u1')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
  })

  it('surfaces a fixture error and lets the match type change', async () => {
    state.fixtureFail = true
    const user = userEvent.setup()
    renderDetail()
    await user.selectOptions(screen.getByLabelText('Match type'), 'LEAGUE_PLAY')
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(screen.getByText(/Could not schedule the fixture/)).toBeInTheDocument()
    expect(createFixtureMutate.mock.calls[0][0].data.matchType).toBe('LEAGUE_PLAY')
  })

  it('surfaces a roster error when adding a participant fails', async () => {
    state.addFail = true
    const user = userEvent.setup()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Search players…' }))
    expect(screen.getByText('Could not add that participant.')).toBeInTheDocument()
  })
})
