import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EventDetail } from './EventDetail'

const {
  useGetApiV1EventsId,
  useGetApiV1Clubs,
  useGetApiV1UsersMe,
  useGetApiV1PointsPolicies,
  useGetApiV1PointsBudgets,
  addMutate,
  removeMutate,
  decideMutate,
  createFixtureMutate,
  deleteMutate,
  renameMutate,
  clubMutate,
  finalizeMutate,
  pointsConfigMutate,
  state,
} =
  vi.hoisted(() => ({
    useGetApiV1EventsId: vi.fn(),
    useGetApiV1Clubs: vi.fn(),
    useGetApiV1UsersMe: vi.fn(),
    useGetApiV1PointsPolicies: vi.fn(),
    useGetApiV1PointsBudgets: vi.fn(),
    addMutate: vi.fn(),
    removeMutate: vi.fn(),
    decideMutate: vi.fn(),
    createFixtureMutate: vi.fn(),
    deleteMutate: vi.fn(),
    renameMutate: vi.fn(),
    clubMutate: vi.fn(),
    finalizeMutate: vi.fn(),
    pointsConfigMutate: vi.fn(),
    state: {
      addFail: false,
      fixtureFail: false,
      deleteFail: false,
      deletePending: false,
      deleteErrorMessage: null as string | null,
      renameFail: false,
      renamePending: false,
      renameErrorMessage: null as string | null,
      finalizeFail: false,
      finalizePending: false,
      finalizeErrorMessage: null as string | null,
      pointsConfigFail: false,
      pointsConfigErrorMessage: null as string | null,
    },
  }))

vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1EventsId,
  getGetApiV1EventsIdQueryKey: () => ['event'],
  getGetApiV1EventsQueryKey: () => ['events'],
  useDeleteApiV1EventsId: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.deletePending,
    mutateAsync: async (vars: unknown) => {
      deleteMutate(vars)
      if (state.deleteFail) {
        throw state.deleteErrorMessage ? { response: { data: { message: state.deleteErrorMessage } } } : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePatchApiV1EventsId: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.renamePending,
    mutateAsync: async (vars: unknown) => {
      renameMutate(vars)
      if (state.renameFail) {
        throw state.renameErrorMessage ? { response: { data: { message: state.renameErrorMessage } } } : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
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
  usePostApiV1EventsIdParticipantsUserIdDecision: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      decideMutate(vars)
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePutApiV1EventsIdClub: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      clubMutate(vars)
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePostApiV1EventsIdFinalize: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.finalizePending,
    mutateAsync: async (vars: unknown) => {
      finalizeMutate(vars)
      if (state.finalizeFail) {
        throw state.finalizeErrorMessage ? { response: { data: { message: state.finalizeErrorMessage } } } : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePutApiV1EventsIdPointsConfig: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      pointsConfigMutate(vars)
      if (state.pointsConfigFail) {
        throw state.pointsConfigErrorMessage
          ? { response: { data: { message: state.pointsConfigErrorMessage } } }
          : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/api/generated/clubs/clubs', () => ({ useGetApiV1Clubs }))
vi.mock('@/api/generated/points-budget/points-budget', () => ({
  useGetApiV1PointsPolicies,
  useGetApiV1PointsBudgets,
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
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))
vi.mock('../matches/AwaitingResultsSection', () => ({
  AwaitingResultsSection: ({ eventId, readOnly }: { eventId?: string; readOnly?: boolean }) => (
    <div>awaiting:{eventId}:{String(readOnly ?? false)}</div>
  ),
  RecordedResultsSection: ({ eventId, readOnly }: { eventId?: string; readOnly?: boolean }) => (
    <div>recorded:{eventId}:{String(readOnly ?? false)}</div>
  ),
}))

const event = {
  id: 'e1',
  publicCode: 'EV1',
  name: 'Spring Open',
  startDate: '2026-03-01',
  endDate: '2026-03-03',
  isActive: true,
  participants: [
    {
      userId: 'u1',
      displayName: 'Ana',
      publicCode: 'AAA111',
      sex: 'Female',
      age: 34,
      rating: { value: '4.000000', level: '4.0', confidence: '0.87' },
      status: 'APPROVED',
    },
    { userId: 'u2', displayName: 'Bob', publicCode: 'BBB222', status: 'APPROVED' },
  ],
}

// A four-strong approved roster, enough to fill both sides of a doubles fixture.
const doublesRoster = {
  ...event,
  participants: [
    { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111', status: 'APPROVED' },
    { userId: 'u2', displayName: 'Bob', publicCode: 'BBB222', status: 'APPROVED' },
    { userId: 'u3', displayName: 'Cara', publicCode: 'CCC333', status: 'APPROVED' },
    { userId: 'u4', displayName: 'Dan', publicCode: 'DDD444', status: 'APPROVED' },
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
    state.deleteFail = false
    state.deletePending = false
    state.deleteErrorMessage = null
    state.renameFail = false
    state.renamePending = false
    state.renameErrorMessage = null
    state.finalizeFail = false
    state.finalizePending = false
    state.finalizeErrorMessage = null
    state.pointsConfigFail = false
    state.pointsConfigErrorMessage = null
    useGetApiV1EventsId.mockReturnValue({ data: event, isLoading: false })
    useGetApiV1Clubs.mockReturnValue({ data: [], isLoading: false })
    useGetApiV1PointsPolicies.mockReturnValue({ data: [], isLoading: false })
    useGetApiV1PointsBudgets.mockReturnValue({ data: [], isLoading: false })
    // Default to an administrator so data-entry controls stay available on the (past-dated) fixture;
    // the #310 tests below override this to a plain HOST.
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ['ADMINISTRATOR'] } })
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
    // The roster shows sex · age · NTRP band (with the computed confidence % appended, #343).
    expect(screen.getByText('Female · 34 · NTRP 4.0 · 87%')).toBeInTheDocument()
    expect(screen.getByText('awaiting:e1:false')).toBeInTheDocument()
    expect(screen.getByText('recorded:e1:false')).toBeInTheDocument()
    // The event's share/QR card is surfaced in the dashboard (#179).
    expect(screen.getByText('Share this event')).toBeInTheDocument()
  })

  it('falls back to code, then to a sliced id, for participants without a display name', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: {
        ...event,
        participants: [
          { userId: 'u4', displayName: null, publicCode: 'DDD444', status: 'APPROVED' },
          { userId: 'abcdef120000', displayName: null, publicCode: null, status: 'APPROVED' },
        ],
      },
      isLoading: false,
    })
    renderDetail()
    expect(screen.getAllByText(/DDD444/).length).toBeGreaterThan(0) // name falls back to the code
    expect(screen.getAllByText(/abcdef12/).length).toBeGreaterThan(0) // both null → sliced id
  })

  it('falls back to the raw rating value when a participant has no published band', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: {
        ...event,
        participants: [
          {
            userId: 'u5',
            displayName: 'Cleo',
            publicCode: 'EEE555',
            rating: { value: '5.250000', level: null },
            status: 'APPROVED',
          },
        ],
      },
      isLoading: false,
    })
    renderDetail()
    expect(screen.getByText('NTRP 5.250000')).toBeInTheDocument()
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

  it('excludes the player chosen in one dropdown from the other', async () => {
    const user = userEvent.setup()
    renderDetail()
    const player1 = screen.getByLabelText('Player 1')
    const player2 = screen.getByLabelText('Player 2')

    // Before any choice, both players are offered in each dropdown.
    expect(within(player2).getByRole('option', { name: 'Ana' })).toBeInTheDocument()

    // Pick Ana as Player 1 → she's no longer selectable as Player 2.
    await user.selectOptions(player1, 'u1')
    expect(within(player2).queryByRole('option', { name: 'Ana' })).not.toBeInTheDocument()
    expect(within(player2).getByRole('option', { name: 'Bob' })).toBeInTheDocument()

    // Symmetrically, picking Bob as Player 2 removes him from Player 1.
    await user.selectOptions(player2, 'u2')
    expect(within(player1).queryByRole('option', { name: 'Bob' })).not.toBeInTheDocument()
  })

  it('schedules a doubles fixture with two players a side (disabled until all four picked)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = userEvent.setup()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Format'), 'DOUBLES')
    // Partner pickers appear only for doubles.
    expect(screen.getByLabelText('Partner 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Partner 2')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Partner 1'), 'u2')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u3')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    // Still missing Partner 2 → can't schedule yet.
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()

    await user.selectOptions(screen.getByLabelText('Partner 2'), 'u4')
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: {
          matchFormat: 'DOUBLES',
          matchType: 'OPEN_PLAY',
          matchDate: '2026-03-02',
          team1: ['u1', 'u2'],
          team2: ['u3', 'u4'],
          eventId: 'e1',
        },
      },
      expect.anything(),
    )
  })

  it('sends the mixed-doubles format', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = userEvent.setup()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Format'), 'MIXED_DOUBLES')
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Partner 1'), 'u2')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u3')
    await user.selectOptions(screen.getByLabelText('Partner 2'), 'u4')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))

    expect(createFixtureMutate.mock.calls[0][0].data.matchFormat).toBe('MIXED_DOUBLES')
  })

  it('excludes a player picked in any doubles slot from the other three', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = userEvent.setup()
    renderDetail()
    await user.selectOptions(screen.getByLabelText('Format'), 'DOUBLES')

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    // Ana (u1) is now off the other three slots.
    for (const label of ['Partner 1', 'Player 2', 'Partner 2']) {
      expect(within(screen.getByLabelText(label)).queryByRole('option', { name: 'Ana' })).not.toBeInTheDocument()
    }
    // …but stays selected in her own slot.
    expect(within(screen.getByLabelText('Player 1')).getByRole('option', { name: 'Ana' })).toBeInTheDocument()
  })

  it('retires the partner slots when switching back to singles', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = userEvent.setup()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Format'), 'DOUBLES')
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Partner 1'), 'u2')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u3')
    await user.selectOptions(screen.getByLabelText('Partner 2'), 'u4')

    await user.selectOptions(screen.getByLabelText('Format'), 'SINGLES')
    // Partner pickers are gone, and the retired partner (u2) is selectable again as the opponent.
    expect(screen.queryByLabelText('Partner 1')).not.toBeInTheDocument()
    expect(within(screen.getByLabelText('Player 2')).getByRole('option', { name: 'Bob' })).toBeInTheDocument()

    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: {
          matchFormat: 'SINGLES',
          matchType: 'OPEN_PLAY',
          matchDate: '2026-03-02',
          team1: ['u1'],
          team2: ['u3'],
          eventId: 'e1',
        },
      },
      expect.anything(),
    )
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

  it('lists join requests and approves or holds them, keeping requests off the roster (#201)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: {
        ...event,
        participants: [
          { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111', status: 'APPROVED' },
          {
            userId: 'u6',
            displayName: 'Pat',
            publicCode: 'PPP666',
            sex: 'Male',
            age: 30,
            rating: { value: '3.500000', level: '3.5' },
            status: 'PENDING',
          },
          { userId: 'u7', displayName: 'Hank', publicCode: 'HHH777', status: 'HOLD' },
        ],
      },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    // The pending player isn't in the fixture pickers (roster = approved only).
    expect(within(screen.getByLabelText('Player 1')).queryByRole('option', { name: 'Pat' })).not.toBeInTheDocument()

    expect(screen.getByText('Join requests')).toBeInTheDocument()
    expect(screen.getByText('Male · 30 · NTRP 3.5')).toBeInTheDocument() // request rows show facets too
    // Approve/Hold controls live only in the requests section; the pending row (first) is Pat.
    await user.click(screen.getAllByRole('button', { name: 'Approve' })[0])
    expect(decideMutate).toHaveBeenCalledWith({ id: 'e1', userId: 'u6', data: { status: 'APPROVED' } })

    // Only the pending request offers Hold (the held one shows just Approve).
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    expect(decideMutate).toHaveBeenCalledWith({ id: 'e1', userId: 'u6', data: { status: 'HOLD' } })
  })

  it('deletes the event after a confirm step and returns to the list (#243)', async () => {
    const user = userEvent.setup()
    const onBack = vi.fn()
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <EventDetail eventId="e1" onBack={onBack} />
        </QueryClientProvider>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(deleteMutate).toHaveBeenCalledWith({ id: 'e1' })
    expect(onBack).toHaveBeenCalled()
  })

  it('shows a busy label while the delete is in flight', async () => {
    state.deletePending = true
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    expect(screen.getByRole('button', { name: 'Deleting…' })).toBeInTheDocument()
  })

  it('cancels a pending delete without calling the API', async () => {
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(deleteMutate).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Delete event' })).toBeInTheDocument()
  })

  it('shows a generic message when a delete fails without server guidance', async () => {
    state.deleteFail = true
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not delete this event.')
  })

  it('surfaces the server guidance when a delete is refused (#243)', async () => {
    state.deleteFail = true
    state.deleteErrorMessage = "Delete this event's recorded matches first, then delete the event"
    const user = userEvent.setup()
    const onBack = vi.fn()
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <EventDetail eventId="e1" onBack={onBack} />
        </QueryClientProvider>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('recorded matches first')
    expect(onBack).not.toHaveBeenCalled()
  })

  it('renames the event, trimming the name and sending a PATCH (#269)', async () => {
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    const input = screen.getByLabelText('Event name')
    await user.clear(input)
    await user.type(input, '  Summer Classic  ')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(renameMutate).toHaveBeenCalledWith({ id: 'e1', data: { name: 'Summer Classic' } })
  })

  it('rejects a blank rename without calling the API', async () => {
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await user.clear(screen.getByLabelText('Event name'))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Event name is required')
    expect(renameMutate).not.toHaveBeenCalled()
  })

  it('surfaces a server error when a rename fails', async () => {
    state.renameFail = true
    state.renameErrorMessage = 'Nope'
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Nope')
  })

  it('shows a busy label while the rename is in flight', async () => {
    state.renamePending = true
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
  })

  it('cancels a rename without calling the API', async () => {
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Rename' })).toBeInTheDocument()
    expect(renameMutate).not.toHaveBeenCalled()
  })

  it('sets and clears the event club (#319)', async () => {
    const user = userEvent.setup()
    useGetApiV1Clubs.mockReturnValue({
      data: [
        { id: 'c1', name: 'Riverside' },
        { id: 'c2', name: 'Lakeside' },
      ],
      isLoading: false,
    })
    const { unmount } = renderDetail()

    // No club saved yet — assigning one sends the chosen id.
    await user.selectOptions(screen.getByLabelText('Club'), 'c2')
    expect(clubMutate).toHaveBeenCalledWith({ id: 'e1', data: { clubId: 'c2' } })
    unmount()

    // Clearing the club (choosing "Open") sends a null id.
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, clubId: 'c2' }, isLoading: false })
    renderDetail()
    await user.selectOptions(screen.getByLabelText('Club'), '')
    expect(clubMutate).toHaveBeenLastCalledWith({ id: 'e1', data: { clubId: null } })
  })

  it('renders the club picker with only the Open option when no clubs load (#319)', () => {
    useGetApiV1Clubs.mockReturnValue({ data: undefined, isLoading: false })
    renderDetail()

    const options = within(screen.getByLabelText('Club')).getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveTextContent('No club (Open)')
  })

  it('surfaces a server error when setting the club fails (#319)', async () => {
    const user = userEvent.setup()
    useGetApiV1Clubs.mockReturnValue({ data: [{ id: 'c1', name: 'Riverside' }], isLoading: false })
    clubMutate.mockImplementationOnce(() => {
      throw { response: { data: { message: 'Club not found' } } }
    })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Club'), 'c1')

    expect(await screen.findByRole('alert')).toHaveTextContent('Club not found')
  })

  it('blocks a HOST from entering data on an ended event (#310)', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ['HOST'] } })
    // The default fixture ended 2026-03-03 (in the past).
    renderDetail()

    expect(screen.getByText(/this event has ended/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Search players…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Schedule fixture' })).not.toBeInTheDocument()
    // The match sections are told to render read-only.
    expect(screen.getByText('awaiting:e1:true')).toBeInTheDocument()
    expect(screen.getByText('recorded:e1:true')).toBeInTheDocument()
  })

  it('keeps data-entry controls for a HOST while the event is still running (#310)', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ['HOST'] } })
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, endDate: '2999-01-01' }, isLoading: false })
    renderDetail()

    expect(screen.queryByText(/this event has ended/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Search players…' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeInTheDocument()
    expect(screen.getByText('awaiting:e1:false')).toBeInTheDocument()
  })

  it('keeps data-entry controls for an admin even on an ended event (#310)', () => {
    // beforeEach defaults the caller to ADMINISTRATOR; the fixture is ended.
    renderDetail()

    expect(screen.queryByText(/this event has ended/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Search players…' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeInTheDocument()
  })

  it('finalizes the event after a confirm step and calls the mutation (#403)', async () => {
    // A currently-running event so the ended-event gate doesn't also hide controls.
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: false },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))

    expect(finalizeMutate).toHaveBeenCalledWith({ id: 'e1' })
  })

  it('cancels a pending finalize without calling the API (#403)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: false },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    // The confirmation is dismissed and the mutation was never called.
    expect(screen.queryByRole('button', { name: 'Confirm finalize' })).not.toBeInTheDocument()
    expect(finalizeMutate).not.toHaveBeenCalled()
  })

  it('shows a pending label and disables the confirm while finalizing (#403)', async () => {
    state.finalizePending = true
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: false },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    const confirm = screen.getByRole('button', { name: 'Finalizing…' })
    expect(confirm).toBeDisabled()
  })

  it('shows the type and a Finalized badge, and locks controls when finalized (#403)', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'LEAGUE', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    renderDetail()

    // The type is shown in the header and the finalized badge is present.
    expect(screen.getByText(/League/)).toBeInTheDocument()
    expect(screen.getByTestId('finalized-badge')).toBeInTheDocument()
    // A finalized banner explains the terminal state.
    expect(screen.getByText(/this event is finalized/i)).toBeInTheDocument()
    // Edit / add / schedule controls are gone; no Finalize button (already finalized).
    expect(screen.queryByRole('button', { name: 'Rename' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Search players…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Schedule fixture' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Finalize event' })).not.toBeInTheDocument()
    // The match sections are told to render read-only.
    expect(screen.getByText('awaiting:e1:true')).toBeInTheDocument()
    expect(screen.getByText('recorded:e1:true')).toBeInTheDocument()
  })

  it('surfaces a server error when finalize fails (#403)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'OPEN_PLAY', endDate: '2999-01-01', isFinalized: false },
      isLoading: false,
    })
    state.finalizeFail = true
    state.finalizeErrorMessage = 'Event is already finalized'
    const user = userEvent.setup()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Event is already finalized')
  })

  // ---- Points config + fixture designation (#403 Phase C) ----

  const tournamentEvent = {
    ...event,
    type: 'TOURNAMENT',
    clubId: 'c1',
    endDate: '2999-01-01',
    minPointsPerMatch: 10,
    maxPointsPerMatch: 20,
    pointValidityStart: '2026-03-01',
    pointValidityEnd: '2026-06-01',
  }

  it('hides the points config section for an OPEN_PLAY event (#403 Phase C)', () => {
    // The default event is OPEN_PLAY.
    renderDetail()
    expect(screen.queryByText('Points config')).not.toBeInTheDocument()
    // …and no designated-points input in the fixture form.
    expect(screen.queryByLabelText(/Designated points/)).not.toBeInTheDocument()
  })

  it('hides the points config for a budgeted event with no club, and shows it once a club is set (#429)', () => {
    // A clubless budgeted event → no points editor (no budget source; mirrors "no club → no points").
    useGetApiV1EventsId.mockReturnValue({
      data: { ...tournamentEvent, clubId: null },
      isLoading: false,
    })
    const { unmount } = renderDetail()
    expect(screen.queryByText('Points config')).not.toBeInTheDocument()
    unmount()

    // Assigning a club later reveals the editor so the organizer can set the config.
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    renderDetail()
    expect(screen.getByText('Points config')).toBeInTheDocument()
  })

  it('shows the points config with global bounds and current values, and saves an edit (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    useGetApiV1PointsPolicies.mockReturnValue({
      data: [{ eventType: 'TOURNAMENT', minPoints: 5, maxPoints: 100, maxValidityDays: 365 }],
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    expect(screen.getByText('Points config')).toBeInTheDocument()
    // Global bounds render as helper text.
    expect(screen.getByText(/allows 5–100 points and up to 365 validity days/)).toBeInTheDocument()
    // Current config summary.
    expect(screen.getByTestId('points-config-summary')).toHaveTextContent('Currently 10–20 points')

    await user.type(screen.getByLabelText('Min points'), '8')
    await user.type(screen.getByLabelText('Max points'), '30')
    await user.type(screen.getByLabelText('Validity start'), '2026-04-01')
    await user.type(screen.getByLabelText('Validity end'), '2026-07-01')
    await user.click(screen.getByRole('button', { name: 'Save points config' }))

    expect(pointsConfigMutate).toHaveBeenCalledWith({
      id: 'e1',
      data: {
        minPointsPerMatch: 8,
        maxPointsPerMatch: 30,
        pointValidityStart: '2026-04-01',
        pointValidityEnd: '2026-07-01',
      },
    })
    expect(await screen.findByRole('status')).toHaveTextContent('Saved')
  })

  it('rejects a min greater than max in the points config without calling the API (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    const user = userEvent.setup()
    renderDetail()

    await user.type(screen.getByLabelText('Min points'), '30')
    await user.type(screen.getByLabelText('Max points'), '10')
    await user.type(screen.getByLabelText('Validity start'), '2026-04-01')
    await user.type(screen.getByLabelText('Validity end'), '2026-07-01')
    await user.click(screen.getByRole('button', { name: 'Save points config' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Min points cannot exceed max points')
    expect(pointsConfigMutate).not.toHaveBeenCalled()
  })

  it('surfaces a server error when the points config save fails (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    state.pointsConfigFail = true
    state.pointsConfigErrorMessage = "An existing fixture's designated points fall outside the new range"
    const user = userEvent.setup()
    renderDetail()

    await user.type(screen.getByLabelText('Min points'), '15')
    await user.type(screen.getByLabelText('Max points'), '25')
    await user.type(screen.getByLabelText('Validity start'), '2026-04-01')
    await user.type(screen.getByLabelText('Validity end'), '2026-07-01')
    await user.click(screen.getByRole('button', { name: 'Save points config' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('fall outside the new range')
  })

  it('defaults the fixture designation to round(avg) and sends it when changed (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    useGetApiV1PointsBudgets.mockReturnValue({
      data: [{ clubId: 'c1', eventType: 'TOURNAMENT', budgeted: 500, allocated: 100, free: 400 }],
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    const designated = screen.getByLabelText(/Designated points/)
    // Default = round(avg(10, 20)) = 15, shown as the placeholder.
    expect(designated).toHaveAttribute('placeholder', '15')
    // The free-budget hint reflects the club×type budget.
    expect(screen.getByText(/400 points free/)).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    await user.type(designated, '18')
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))

    expect(createFixtureMutate.mock.calls[0][0].data.designatedPoints).toBe(18)
  })

  it('blocks scheduling when the designation is out of the [min,max] range (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    const user = userEvent.setup()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    await user.type(screen.getByLabelText(/Designated points/), '99')

    expect(screen.getByText('Designated points must be between 10 and 20.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
  })

  it('blocks scheduling when the designation exceeds the club free budget (#403 Phase C)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: tournamentEvent, isLoading: false })
    useGetApiV1PointsBudgets.mockReturnValue({
      data: [{ clubId: 'c1', eventType: 'TOURNAMENT', budgeted: 20, allocated: 8, free: 12 }],
      isLoading: false,
    })
    const user = userEvent.setup()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.type(screen.getByLabelText('Date'), '2026-03-02')
    // 20 × 1 = 20 > 12 free → over budget.
    await user.type(screen.getByLabelText(/Designated points/), '20')

    expect(screen.getByText(/exceeds the club's remaining free budget/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
  })
})
