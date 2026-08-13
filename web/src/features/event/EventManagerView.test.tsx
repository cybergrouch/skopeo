import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { ReactNode } from 'react'
import { act, render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { setupUser } from '@/test/user'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EventManagerView } from './EventManagerView'

// The view now lives on the public event route (#741), so "back to the list" is navigation, not a
// callback prop; capture it to assert the post-delete return.
const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }))
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const { toastSuccess, toastError } = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))
vi.mock('sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

// dnd-kit needs layout measurement jsdom lacks; stub to passthrough + capture DndContext onDragEnd so a
// test can simulate a seeding drop (same technique as SeedingTable.test).
const { dnd } = vi.hoisted(() => ({ dnd: { onDragEnd: undefined as undefined | ((e: unknown) => void) } }))
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children, onDragEnd }: { children: ReactNode; onDragEnd: (e: unknown) => void }) => {
    dnd.onDragEnd = onDragEnd
    return children
  },
  closestCenter: () => undefined,
  KeyboardSensor: function KeyboardSensor() {},
  PointerSensor: function PointerSensor() {},
  useSensor: () => ({}),
  useSensors: () => [],
}))
vi.mock('@dnd-kit/sortable', () => ({
  SortableContext: ({ children }: { children: ReactNode }) => children,
  verticalListSortingStrategy: {},
  sortableKeyboardCoordinates: () => undefined,
  useSortable: () => ({
    attributes: {},
    listeners: {},
    setNodeRef: () => undefined,
    transform: null,
    transition: undefined,
    isDragging: false,
  }),
  arrayMove: <T,>(arr: T[], from: number, to: number): T[] => {
    const copy = [...arr]
    const [moved] = copy.splice(from, 1)
    copy.splice(to, 0, moved)
    return copy
  },
}))
vi.mock('@dnd-kit/utilities', () => ({ CSS: { Transform: { toString: () => undefined } } }))

const {
  useGetApiV1EventsId,
  useGetApiV1EventsIdSeeding,
  useGetApiV1EventsIdTeams,
  useGetApiV1Clubs,
  useGetApiV1UsersMe,
  addMutate,
  removeMutate,
  decideMutate,
  createFixtureMutate,
  createTeamMutate,
  dissolveTeamMutate,
  deleteMutate,
  renameMutate,
  clubMutate,
  finalizeMutate,
  unfinalizeMutate,
  reverseMutate,
  generateSeedingMutate,
  saveSeedingOrderMutate,
  state,
} =
  vi.hoisted(() => ({
    useGetApiV1EventsId: vi.fn(),
    useGetApiV1EventsIdSeeding: vi.fn(),
    useGetApiV1EventsIdTeams: vi.fn(),
    useGetApiV1Clubs: vi.fn(),
    useGetApiV1UsersMe: vi.fn(),
    addMutate: vi.fn(),
    removeMutate: vi.fn(),
    decideMutate: vi.fn(),
    createFixtureMutate: vi.fn(),
    createTeamMutate: vi.fn(),
    dissolveTeamMutate: vi.fn(),
    deleteMutate: vi.fn(),
    renameMutate: vi.fn(),
    clubMutate: vi.fn(),
    finalizeMutate: vi.fn(),
    unfinalizeMutate: vi.fn(),
    reverseMutate: vi.fn(),
    generateSeedingMutate: vi.fn(),
    saveSeedingOrderMutate: vi.fn(),
    state: {
      addFail: false,
      fixtureFail: false,
      teamCreateFail: false,
      teamDissolveFail: false,
      deleteFail: false,
      deletePending: false,
      deleteErrorMessage: null as string | null,
      renameFail: false,
      renamePending: false,
      renameErrorMessage: null as string | null,
      finalizeFail: false,
      finalizePending: false,
      finalizeErrorMessage: null as string | null,
      unfinalizeFail: false,
      unfinalizePending: false,
      unfinalizeErrorMessage: null as string | null,
      reverseFail: false,
      reversePending: false,
      reverseErrorMessage: null as string | null,
      generateSeedingFail: false,
      generateSeedingPending: false,
      generateSeedingErrorMessage: null as string | null,
    },
  }))

vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1EventsId,
  useGetApiV1EventsIdSeeding,
  useGetApiV1EventsIdTeams,
  getGetApiV1EventsIdQueryKey: () => ['event'],
  getGetApiV1EventsIdSeedingQueryKey: () => ['event-seeding'],
  getGetApiV1EventsIdTeamsQueryKey: () => ['event-teams'],
  getGetApiV1EventsQueryKey: () => ['events'],
  // react-query runs the hook-level callback *and* the per-call one; both matter here, since the
  // section that owns the draft learns a create landed from the per-call `onSuccess` (#741).
  usePostApiV1EventsIdTeams: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (
      vars: unknown,
      handlers?: { onSuccess?: () => void; onError?: (err: unknown) => void },
    ) => {
      createTeamMutate(vars)
      if (state.teamCreateFail) handlers?.onError?.(new Error('boom'))
      else {
        opts?.mutation?.onSuccess?.()
        handlers?.onSuccess?.()
      }
    },
  }),
  useDeleteApiV1EventsIdTeamsTeamId: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: (err: unknown) => void }) => {
      dissolveTeamMutate(vars)
      if (state.teamDissolveFail) handlers?.onError?.(new Error('boom'))
      else opts?.mutation?.onSuccess?.()
    },
  }),
  usePostApiV1EventsIdSeeding: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.generateSeedingPending,
    mutateAsync: async (vars: unknown) => {
      if (state.generateSeedingFail) {
        throw state.generateSeedingErrorMessage
          ? { response: { data: { message: state.generateSeedingErrorMessage } } }
          : new Error('boom')
      }
      generateSeedingMutate(vars)
      opts?.mutation?.onSuccess?.()
    },
  }),
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
  usePostApiV1EventsIdUnfinalize: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.unfinalizePending,
    mutateAsync: async (vars: unknown) => {
      unfinalizeMutate(vars)
      if (state.unfinalizeFail) {
        throw state.unfinalizeErrorMessage
          ? { response: { data: { message: state.unfinalizeErrorMessage } } }
          : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePostApiV1EventsIdReverseRatings: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: state.reversePending,
    mutateAsync: async (vars: unknown) => {
      reverseMutate(vars)
      if (state.reverseFail) {
        throw state.reverseErrorMessage
          ? { response: { data: { message: state.reverseErrorMessage } } }
          : new Error('boom')
      }
      opts?.mutation?.onSuccess?.()
    },
  }),
  usePutApiV1EventsIdSeeding: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      saveSeedingOrderMutate(vars)
      opts?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/api/generated/clubs/clubs', () => ({ useGetApiV1Clubs }))
vi.mock('@/api/generated/matches/matches', () => ({
  getGetApiV1MatchesQueryKey: () => ['matches'],
  usePostApiV1Matches: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onSuccess?: () => void; onError?: () => void }) => {
      createFixtureMutate(vars, handlers)
      if (state.fixtureFail) handlers?.onError?.()
      else {
        opts?.mutation?.onSuccess?.()
        handlers?.onSuccess?.()
      }
    },
  }),
}))
vi.mock('@/components/PlayerPicker', () => ({
  PlayerPicker: ({
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
vi.mock('@/routes/dashboard/matches/AwaitingResultsSection', () => ({
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
        <EventManagerView eventId="e1" />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('EventManagerView', () => {
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
    state.unfinalizeFail = false
    state.unfinalizePending = false
    state.unfinalizeErrorMessage = null
    state.reverseFail = false
    state.reversePending = false
    state.reverseErrorMessage = null
    state.generateSeedingFail = false
    state.generateSeedingPending = false
    state.generateSeedingErrorMessage = null
    state.teamCreateFail = false
    state.teamDissolveFail = false
    useGetApiV1EventsId.mockReturnValue({ data: event, isLoading: false })
    useGetApiV1EventsIdSeeding.mockReturnValue({ data: undefined })
    useGetApiV1EventsIdTeams.mockReturnValue({ data: [] })
    useGetApiV1Clubs.mockReturnValue({ data: [], isLoading: false })
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
        <EventManagerView eventId="e1" />
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
    const user = setupUser()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Search players…' }))
    expect(addMutate).toHaveBeenCalledWith({ id: 'e1', data: { userId: 'u3' } })

    await user.click(screen.getAllByRole('button', { name: 'Remove' })[0])
    expect(removeMutate).toHaveBeenCalledWith({ id: 'e1', userId: 'u1' })
  })

  it('schedules a participant-scoped fixture (disabled until valid)', async () => {
    const user = setupUser()
    renderDetail()

    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
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

  it('clears the fixture draft once the create lands, back to the event start date (#668)', async () => {
    // The form owns the draft and the caller owns the mutation (#741), so "it landed" is what the
    // caller reports back — only then are the picks dropped, ready for the next fixture.
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))

    expect(screen.getByLabelText('Player 1')).toHaveValue('')
    expect(screen.getByLabelText('Player 2')).toHaveValue('')
    expect(screen.getByLabelText('Date')).toHaveValue('2026-03-01')
  })

  it('keeps the fixture draft when the create is refused (#741)', async () => {
    // A refused fixture leaves the picks in place: the host has something to correct, not re-enter.
    state.fixtureFail = true
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))

    expect(screen.getByLabelText('Player 1')).toHaveValue('u1')
    expect(screen.getByLabelText('Player 2')).toHaveValue('u2')
  })

  it('creates a degenerate one-member team from a single member (#734)', async () => {
    const user = setupUser()
    // Format is irrelevant to team size now (#734); a one-member team is allowed but degenerate.
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'DOUBLES' }, isLoading: false })
    renderDetail()

    // Create stays disabled until at least one member is picked; member 2 is optional.
    expect(screen.getByRole('button', { name: 'Create team' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Member 1'), 'u1')
    await user.click(screen.getByRole('button', { name: 'Create team' }))

    expect(createTeamMutate).toHaveBeenCalledWith({ id: 'e1', data: { memberUserIds: ['u1'] } })
  })

  it('creates a two-member team regardless of event format (#734)', async () => {
    const user = setupUser()
    // Even on a SINGLES event the organizer can build a two-member team (#734).
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Member 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Member 2 (optional)'), 'u2')
    await user.click(screen.getByRole('button', { name: 'Create team' }))

    expect(createTeamMutate).toHaveBeenCalledWith({ id: 'e1', data: { memberUserIds: ['u1', 'u2'] } })
  })

  it('clears the new-team form once the create lands (#720)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Member 1'), 'u1')
    await user.type(screen.getByLabelText('Team name (optional)'), 'Dream Team')
    await user.click(screen.getByRole('button', { name: 'Create team' }))

    expect(screen.getByLabelText('Member 1')).toHaveValue('')
    expect(screen.getByLabelText('Team name (optional)')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Create team' })).toBeDisabled()
  })

  it('schedules a fixture from durable team refs (#720)', async () => {
    const user = setupUser()
    const teams = [
      {
        id: 't1',
        eventId: 'e1',
        name: 'Team A',
        members: [
          { userId: 'u1', position: 1, displayName: 'Ana' },
          { userId: 'u2', position: 2, displayName: 'Bob' },
        ],
      },
      {
        id: 't2',
        eventId: 'e1',
        name: 'Team B',
        members: [
          { userId: 'u3', position: 1, displayName: 'Cara' },
          { userId: 'u4', position: 2, displayName: 'Dan' },
        ],
      },
    ]
    useGetApiV1EventsId.mockReturnValue({ data: { ...doublesRoster, format: 'DOUBLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({ data: teams })
    renderDetail()

    // Flip the fixture to team refs, pick both teams, then schedule.
    await user.click(screen.getByLabelText('Pick sides from teams'))
    await user.selectOptions(screen.getByLabelText('Team 1'), 't1')
    await user.selectOptions(screen.getByLabelText('Team 2'), 't2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })

    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: {
          matchFormat: 'DOUBLES',
          matchType: 'OPEN_PLAY',
          matchDate: '2026-03-02',
          team1Id: 't1',
          team2Id: 't2',
          eventId: 'e1',
        },
      },
      expect.anything(),
    )
  })

  it('creates a team with a name override (#720)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Member 1'), 'u1')
    await user.type(screen.getByLabelText('Team name (optional)'), 'Dream Team')
    await user.click(screen.getByRole('button', { name: 'Create team' }))

    expect(createTeamMutate).toHaveBeenCalledWith({
      id: 'e1',
      data: { memberUserIds: ['u1'], name: 'Dream Team' },
    })
  })

  it('surfaces an error when creating a team fails (#720)', async () => {
    const user = setupUser()
    state.teamCreateFail = true
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Member 1'), 'u1')
    await user.click(screen.getByRole('button', { name: 'Create team' }))

    expect(await screen.findByText(/Could not create the team/)).toBeInTheDocument()
  })

  it('dissolves a team (#720)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        { id: 't1', eventId: 'e1', name: 'Team A', members: [{ userId: 'u1', position: 1, displayName: 'Ana' }] },
      ],
    })
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Dissolve' }))
    expect(dissolveTeamMutate).toHaveBeenCalledWith({ id: 'e1', teamId: 't1' })
  })

  it('toasts an error when dissolving a team fails (#720)', async () => {
    const user = setupUser()
    state.teamDissolveFail = true
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        { id: 't1', eventId: 'e1', name: 'Team A', members: [{ userId: 'u1', position: 1, displayName: 'Ana' }] },
      ],
    })
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Dissolve' }))
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not dissolve the team.', expect.anything()),
    )
  })

  it('lists a wrong-sized team as an unselectable option explaining the size it needs (#736)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...doublesRoster, format: 'DOUBLES' }, isLoading: false })
    // A one-member team can't fill a doubles side (#734 allows teams of 1).
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        { id: 't1', eventId: 'e1', name: 'Team A', members: [{ userId: 'u1', position: 1, displayName: 'Ana' }] },
        {
          id: 't2',
          eventId: 'e1',
          name: 'Team B',
          members: [
            { userId: 'u3', position: 1, displayName: 'Cara' },
            { userId: 'u4', position: 2, displayName: 'Dan' },
          ],
        },
      ],
    })
    renderDetail()

    await user.click(screen.getByLabelText('Pick sides from teams'))
    const side1 = screen.getByLabelText('Team 1')

    // The 1-member team is offered but disabled, and says what it would need; the 2-member team is free.
    const tooSmall = within(side1).getByRole('option', { name: 'Team A (1 player — needs 2)' })
    expect(tooSmall).toBeDisabled()
    expect(within(side1).getByRole('option', { name: 'Team B (2 players)' })).not.toBeDisabled()
    // Nothing was selected, so no stale-selection alert yet.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('says no team fits when every team is the wrong size for the format (#736)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    // A two-member team can't fill a singles side, and it's the only team there is.
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        {
          id: 't1',
          eventId: 'e1',
          name: 'Team A',
          members: [
            { userId: 'u1', position: 1, displayName: 'Ana' },
            { userId: 'u2', position: 2, displayName: 'Bob' },
          ],
        },
      ],
    })
    renderDetail()

    await user.click(screen.getByLabelText('Pick sides from teams'))

    expect(screen.getByRole('status')).toHaveTextContent('No team fits Singles')
    expect(screen.getByRole('status')).toHaveTextContent('1 player a side')
  })

  it('names the stranded team when the format changes after a team was picked (#736)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...doublesRoster, format: 'DOUBLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        {
          id: 't1',
          eventId: 'e1',
          name: 'Team A',
          members: [
            { userId: 'u1', position: 1, displayName: 'Ana' },
            { userId: 'u2', position: 2, displayName: 'Bob' },
          ],
        },
        {
          id: 't2',
          eventId: 'e1',
          name: 'Team B',
          members: [
            { userId: 'u3', position: 1, displayName: 'Cara' },
            { userId: 'u4', position: 2, displayName: 'Dan' },
          ],
        },
      ],
    })
    renderDetail()

    await user.click(screen.getByLabelText('Pick sides from teams'))
    await user.selectOptions(screen.getByLabelText('Team 1'), 't1')
    await user.selectOptions(screen.getByLabelText('Team 2'), 't2')
    // Both sides were valid for doubles; dropping the fixture to singles strands them at 2 members.
    await user.selectOptions(screen.getByLabelText('Format'), 'SINGLES')

    expect(screen.getByRole('alert')).toHaveTextContent('Team A and Team B don’t match the fixture format')
    expect(screen.getByRole('alert')).toHaveTextContent('1 player a side')
    expect(screen.getByRole('button', { name: 'Schedule fixture' })).toBeDisabled()
  })

  it('uses the singular when only one picked team is stranded by a format change (#736)', async () => {
    const user = setupUser()
    useGetApiV1EventsId.mockReturnValue({ data: { ...doublesRoster, format: 'DOUBLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({
      data: [
        {
          id: 't1',
          eventId: 'e1',
          name: 'Team A',
          members: [
            { userId: 'u1', position: 1, displayName: 'Ana' },
            { userId: 'u2', position: 2, displayName: 'Bob' },
          ],
        },
      ],
    })
    renderDetail()

    await user.click(screen.getByLabelText('Pick sides from teams'))
    await user.selectOptions(screen.getByLabelText('Team 1'), 't1')
    await user.selectOptions(screen.getByLabelText('Format'), 'SINGLES')

    expect(screen.getByRole('alert')).toHaveTextContent('Team A doesn’t match the fixture format')
  })

  it('shows how to get teams when the event has none (#736)', () => {
    useGetApiV1EventsId.mockReturnValue({ data: { ...event, format: 'SINGLES' }, isLoading: false })
    useGetApiV1EventsIdTeams.mockReturnValue({ data: [] })
    renderDetail()

    expect(screen.queryByLabelText('Pick sides from teams')).not.toBeInTheDocument()
    expect(
      screen.getByText(/Create teams for this event to pick a whole team as a side/),
    ).toBeInTheDocument()
  })

  it('pre-fills the fixture date with the event start date (#668)', () => {
    renderDetail()
    expect(screen.getByLabelText('Date')).toHaveValue('2026-03-01')
  })

  it('schedules a tournament placement match with a bracket (#525)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT' },
      isLoading: false,
    })
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByLabelText('Placement match'))
    await user.selectOptions(screen.getByLabelText('Placement'), 'PLATE_FINALS')

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
          isPlacementMatch: true,
          placementBracket: 'PLATE_FINALS',
        },
      },
      expect.anything(),
    )
  })

  it('does not show the placement input for a non-tournament event (#525)', () => {
    renderDetail()
    expect(screen.queryByLabelText('Placement match')).not.toBeInTheDocument()
  })

  it('hides the handicap input until the "Apply handicap" box is ticked, and clears it on un-tick (#486)', async () => {
    const user = setupUser()
    renderDetail()

    // Hidden by default (discouraged by design); the checkbox + tooltip trigger are present.
    expect(screen.queryByLabelText('Side 1 handicap')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Apply handicap')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'What is a handicap?' })).toBeInTheDocument()

    // Tick → the two inputs are revealed.
    await user.click(screen.getByLabelText('Apply handicap'))
    expect(screen.getByLabelText('Side 1 handicap')).toBeInTheDocument()
    expect(screen.getByLabelText('Side 2 handicap')).toBeInTheDocument()

    // Enter a value, then un-tick → the inputs are hidden and the draft is cleared.
    await user.type(screen.getByLabelText('Side 2 handicap'), '0.3')
    await user.click(screen.getByLabelText('Apply handicap'))
    expect(screen.queryByLabelText('Side 2 handicap')).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('Apply handicap'))
    expect((screen.getByLabelText('Side 2 handicap') as HTMLInputElement).value).toBe('')
  })

  it('sends the per-side handicap in the create payload when applied (#486)', async () => {
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByLabelText('Apply handicap'))
    await user.type(screen.getByLabelText('Side 2 handicap'), '0.3')

    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: expect.objectContaining({ team2Handicap: '0.3' }),
      },
      expect.anything(),
    )
  })

  it('sends both per-side handicaps in the create payload when applied (#486)', async () => {
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByLabelText('Apply handicap'))
    await user.type(screen.getByLabelText('Side 1 handicap'), '0.4')
    await user.type(screen.getByLabelText('Side 2 handicap'), '0.2')

    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    expect(createFixtureMutate).toHaveBeenCalledWith(
      {
        data: expect.objectContaining({ team1Handicap: '0.4', team2Handicap: '0.2' }),
      },
      expect.anything(),
    )
  })

  it('excludes the player chosen in one dropdown from the other', async () => {
    const user = setupUser()
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

  it('marks a placeholder participant as (Unclaimed) in the fixture player dropdowns (#505)', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: {
        ...event,
        participants: [
          { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111', status: 'APPROVED', isPlaceholder: true },
          { userId: 'u2', displayName: 'Bob', publicCode: 'BBB222', status: 'APPROVED' },
        ],
      },
      isLoading: false,
    })
    renderDetail()
    const player1 = screen.getByLabelText('Player 1')
    // The `<option>` can't host the badge component, so a placeholder gets an "(Unclaimed)" text suffix.
    expect(within(player1).getByRole('option', { name: 'Ana (Unclaimed)' })).toBeInTheDocument()
    expect(within(player1).getByRole('option', { name: 'Bob' })).toBeInTheDocument()
  })

  it('schedules a doubles fixture with two players a side (disabled until all four picked)', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Format'), 'DOUBLES')
    // Partner pickers appear only for doubles.
    expect(screen.getByLabelText('Partner 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Partner 2')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Partner 1'), 'u2')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u3')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
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
    const user = setupUser()
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Format'), 'MIXED_DOUBLES')
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Partner 1'), 'u2')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u3')
    await user.selectOptions(screen.getByLabelText('Partner 2'), 'u4')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))

    expect(createFixtureMutate.mock.calls[0][0].data.matchFormat).toBe('MIXED_DOUBLES')
  })

  it('excludes a player picked in any doubles slot from the other three', async () => {
    useGetApiV1EventsId.mockReturnValue({ data: doublesRoster, isLoading: false })
    const user = setupUser()
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
    const user = setupUser()
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

    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
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
    const user = setupUser()
    renderDetail()
    await user.selectOptions(screen.getByLabelText('Match type'), 'TOURNAMENT')
    await user.selectOptions(screen.getByLabelText('Player 1'), 'u1')
    await user.selectOptions(screen.getByLabelText('Player 2'), 'u2')
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-03-02' } })
    await user.click(screen.getByRole('button', { name: 'Schedule fixture' }))
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        expect.stringMatching(/Could not schedule the fixture/),
        expect.anything(),
      ),
    )
    expect(createFixtureMutate.mock.calls[0][0].data.matchType).toBe('TOURNAMENT')
  })

  it('surfaces a roster error when adding a participant fails', async () => {
    state.addFail = true
    const user = setupUser()
    renderDetail()
    await user.click(screen.getByRole('button', { name: 'Search players…' }))
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not add that participant.', expect.anything()),
    )
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
    const user = setupUser()
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

  it('deletes the event after a confirm step and returns to the organizer list (#243)', async () => {
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(deleteMutate).toHaveBeenCalledWith({ id: 'e1' })
    expect(navigate).toHaveBeenCalledWith('/dashboard')
  })

  it('shows a busy label while the delete is in flight', async () => {
    state.deletePending = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    expect(screen.getByRole('button', { name: 'Deleting…' })).toBeInTheDocument()
  })

  it('cancels a pending delete without calling the API', async () => {
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(deleteMutate).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Delete event' })).toBeInTheDocument()
  })

  it('shows a generic message when a delete fails without server guidance', async () => {
    state.deleteFail = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not delete this event.', expect.anything()),
    )
  })

  it('surfaces the server guidance when a delete is refused (#243)', async () => {
    state.deleteFail = true
    state.deleteErrorMessage = "Delete this event's recorded matches first, then delete the event"
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Delete event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        expect.stringContaining('recorded matches first'),
        expect.anything(),
      ),
    )
    expect(navigate).not.toHaveBeenCalled()
  })

  it('renames the event, trimming the name and sending a PATCH (#269)', async () => {
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    const input = screen.getByLabelText('Event name')
    await user.clear(input)
    await user.type(input, '  Summer Classic  ')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(renameMutate).toHaveBeenCalledWith({ id: 'e1', data: { name: 'Summer Classic' } })
  })

  it('rejects a blank rename without calling the API', async () => {
    const user = setupUser()
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
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Nope', expect.anything()))
  })

  it('shows a busy label while the rename is in flight', async () => {
    state.renamePending = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
  })

  it('cancels a rename without calling the API', async () => {
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Rename' })).toBeInTheDocument()
    expect(renameMutate).not.toHaveBeenCalled()
  })

  it('sets and clears the event club (#319)', async () => {
    const user = setupUser()
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
    const user = setupUser()
    useGetApiV1Clubs.mockReturnValue({ data: [{ id: 'c1', name: 'Riverside' }], isLoading: false })
    clubMutate.mockImplementationOnce(() => {
      throw { response: { data: { message: 'Club not found' } } }
    })
    renderDetail()

    await user.selectOptions(screen.getByLabelText('Club'), 'c1')

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Club not found', expect.anything()),
    )
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
    const user = setupUser()
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
    const user = setupUser()
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
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    const confirm = screen.getByRole('button', { name: 'Finalizing…' })
    expect(confirm).toBeDisabled()
  })

  it('shows the type and a Finalized badge, and locks controls when finalized (#403)', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    renderDetail()

    // The type is shown in the header and the finalized badge is present.
    expect(screen.getByText(/Tournament/)).toBeInTheDocument()
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
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Event is already finalized', expect.anything()),
    )
  })

  // ---- Un-finalize (#477) ----

  it('un-finalizes a finalized event after a confirm step and calls the mutation (#477)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    const user = setupUser()
    renderDetail()

    // The Un-finalize card is shown only when finalized; Finalize is not.
    expect(screen.queryByRole('button', { name: 'Finalize event' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Un-finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm un-finalize (revokes points)' }))

    expect(unfinalizeMutate).toHaveBeenCalledWith({ id: 'e1' })
  })

  it('cancels a pending un-finalize without calling the API (#477)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Un-finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(
      screen.queryByRole('button', { name: 'Confirm un-finalize (revokes points)' }),
    ).not.toBeInTheDocument()
    expect(unfinalizeMutate).not.toHaveBeenCalled()
  })

  it('surfaces a server error when un-finalize fails, e.g. a rated match (#477)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    state.unfinalizeFail = true
    state.unfinalizeErrorMessage = 'This event has already-rated matches'
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Un-finalize event' }))
    await user.click(screen.getByRole('button', { name: 'Confirm un-finalize (revokes points)' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        'This event has already-rated matches',
        expect.anything(),
      ),
    )
  })

  it('shows an un-finalizing label while the un-finalize mutation is pending (#477)', async () => {
    // With the un-finalize mutation in flight, the confirm button reads "Un-finalizing…"
    // and is disabled (the `unfinalizeEvent.isPending` arm).
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    state.unfinalizePending = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Un-finalize event' }))

    const unfinalizing = screen.getByRole('button', { name: 'Un-finalizing…' })
    expect(unfinalizing).toBeDisabled()
  })

  // ---- Reverse Ratings (#478) ----

  it('reverses an already-rated event after a confirm step and calls the mutation (#478)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    const user = setupUser()
    renderDetail()

    // The destructive Reverse ratings action is shown for an admin on a finalized event.
    await user.click(screen.getByRole('button', { name: 'Reverse ratings' }))
    await user.click(
      screen.getByRole('button', {
        name: 'Confirm reverse (rewinds ratings, revokes points)',
      }),
    )

    expect(reverseMutate).toHaveBeenCalledWith({ id: 'e1' })
  })

  it('shows the not-at-tip refusal inline when reverse ratings fails (#478)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    state.reverseFail = true
    state.reverseErrorMessage =
      "This event's ratings can't be reversed because later matches have already been rated on top of them."
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Reverse ratings' }))
    await user.click(
      screen.getByRole('button', {
        name: 'Confirm reverse (rewinds ratings, revokes points)',
      }),
    )

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        expect.stringContaining('later matches have already been rated on top of them'),
        expect.anything(),
      ),
    )
  })

  it('hides the Reverse ratings action from a non-administrator (#478)', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: { capabilities: ['HOST'] } })
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    renderDetail()

    // A HOST still sees Un-finalize (their action) but not the admin-only Reverse ratings.
    expect(screen.getByRole('button', { name: 'Un-finalize event' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reverse ratings' })).not.toBeInTheDocument()
  })

  it('shows a pending label on the reverse confirm while the mutation runs (#478)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    state.reversePending = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Reverse ratings' }))
    expect(screen.getByRole('button', { name: 'Reversing ratings…' })).toBeDisabled()
  })

  it('cancels the reverse confirm without calling the mutation (#478)', async () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, type: 'TOURNAMENT', endDate: '2999-01-01', isFinalized: true },
      isLoading: false,
    })
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Reverse ratings' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(
      screen.queryByRole('button', { name: 'Confirm reverse (rewinds ratings, revokes points)' }),
    ).not.toBeInTheDocument()
    expect(reverseMutate).not.toHaveBeenCalled()
  })

  // ---- Ranking points read-only summary (#559) ----

  it('summarizes an event that awards ranking points on finalize (#559)', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, awardRankingPoints: true },
      isLoading: false,
    })
    renderDetail()

    expect(screen.getByText('Ranking points')).toBeInTheDocument()
    expect(screen.getByTestId('award-ranking-points-summary')).toHaveTextContent(
      'This event awards ranking points on finalize.',
    )
  })

  it('summarizes an event that awards no ranking points (#559)', () => {
    useGetApiV1EventsId.mockReturnValue({
      data: { ...event, awardRankingPoints: false },
      isLoading: false,
    })
    renderDetail()

    expect(screen.getByTestId('award-ranking-points-summary')).toHaveTextContent(
      'This event awards no ranking points.',
    )
  })

  // ---- Event seeding (#714) ----

  it('generates an event seeding and calls the mutation (#714)', async () => {
    const user = setupUser()
    renderDetail()

    // No seeding yet → the empty prompt and a "Generate seeding" action.
    expect(
      screen.getByText('No seeding yet. Generate one from the approved participants above.'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Generate seeding' }))
    expect(generateSeedingMutate).toHaveBeenCalledWith({ id: 'e1' })
  })

  it('shows an error toast when generating the seeding fails (#714)', async () => {
    state.generateSeedingFail = true
    const user = setupUser()
    renderDetail()

    await user.click(screen.getByRole('button', { name: 'Generate seeding' }))
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not generate the seeding.', expect.anything()),
    )
  })

  it('disables the action with a generating label while the seeding is pending (#714)', () => {
    state.generateSeedingPending = true
    renderDetail()

    expect(screen.getByRole('button', { name: 'Generating…' })).toBeDisabled()
  })

  it('renders the seeding table and offers a CSV download when a seeding exists (#714)', () => {
    useGetApiV1EventsIdSeeding.mockReturnValue({
      data: {
        generatedAt: '2026-03-02T09:00:00',
        entries: [
          {
            seed: 1,
            position: 1,
            userId: 'u1',
            displayName: 'Ana',
            publicCode: 'AAA111',
            ntrpBand: '4.0',
            rating: '4.000000',
            sex: 'Female',
            age: 34,
          },
          {
            seed: null,
            position: 2,
            userId: 'u2',
            displayName: 'Bob',
            publicCode: 'BBB222',
            ntrpBand: null,
            rating: '3.500000',
            sex: null,
            age: null,
          },
        ],
      },
    })
    renderDetail()

    // With a seeding present the action reads "Regenerate seeding" and the table + CSV export show.
    expect(screen.getByRole('button', { name: 'Regenerate seeding' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download CSV' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Seed' })).toBeInTheDocument()
    // The staff-editable table numbers rows by draft position (#733), so Bob (stored seed blank) shows 2.
    const seedCells = screen
      .getAllByRole('cell')
      .filter((c) => (c as HTMLTableCellElement).cellIndex === 0)
    expect(seedCells[0]).toHaveTextContent('1')
    expect(seedCells[1]).toHaveTextContent('2')
  })

  const eventSeeding = {
    generatedAt: '2026-03-02T09:00:00',
    entries: [
      { seed: 1, position: 1, userId: 'u1', displayName: 'Ana', publicCode: 'AAA111', ntrpBand: '4.0', rating: null, sex: 'Female', age: 34 },
      { seed: 2, position: 2, userId: 'u2', displayName: 'Bob', publicCode: 'BBB222', ntrpBand: null, rating: null, sex: null, age: null },
    ],
  }

  it('saves a reordered event seeding order (#718)', async () => {
    useGetApiV1EventsIdSeeding.mockReturnValue({ data: eventSeeding })
    const user = setupUser()
    renderDetail()

    act(() => dnd.onDragEnd?.({ active: { id: 'u2' }, over: { id: 'u1' } }))
    await user.click(screen.getByRole('button', { name: 'Save order' }))

    await waitFor(() =>
      expect(saveSeedingOrderMutate).toHaveBeenCalledWith({ id: 'e1', data: { userIds: ['u2', 'u1'] } }),
    )
  })

  it('shows an error toast when saving the event seeding order fails (#718)', async () => {
    useGetApiV1EventsIdSeeding.mockReturnValue({ data: eventSeeding })
    saveSeedingOrderMutate.mockImplementationOnce(() => {
      throw new Error('boom')
    })
    const user = setupUser()
    renderDetail()

    act(() => dnd.onDragEnd?.({ active: { id: 'u2' }, over: { id: 'u1' } }))
    await user.click(screen.getByRole('button', { name: 'Save order' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not save the seeding order.', expect.anything()),
    )
  })
})
