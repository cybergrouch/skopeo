import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { ReactNode } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AwaitingResultsSection, RecordedResultsSection } from './AwaitingResultsSection'

const {
  useGetApiV1Matches,
  useGetApiV1Users,
  mutateAsync,
  deleteMutateAsync,
  reorderMutate,
  busy,
  deleteBusy,
  dnd,
} = vi.hoisted(() => ({
  useGetApiV1Matches: vi.fn(),
  useGetApiV1Users: vi.fn(),
  mutateAsync: vi.fn(),
  deleteMutateAsync: vi.fn(),
  reorderMutate: vi.fn(),
  busy: { value: false },
  deleteBusy: { value: false },
  dnd: { onDragEnd: undefined as undefined | ((e: unknown) => void) },
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
  usePutApiV1MatchesIdState: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: deleteBusy.value,
    mutateAsync: async (vars: unknown) => {
      const r = await deleteMutateAsync(vars)
      options?.mutation?.onSuccess?.()
      return r
    },
  }),
  usePutApiV1MatchesCalculationOrder: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      reorderMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))
// dnd-kit relies on layout measurement that jsdom can't provide; stub it to passthrough components
// and capture the DndContext onDragEnd so a test can simulate a drop.
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
  SortableContext: ({ children }: { children: React.ReactNode }) => children,
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

const match = {
  id: 'm1',
  publicCode: 'MPUB1',
  matchDate: '2026-01-01',
  team1: { teamId: 't1', userIds: ['p1'] },
  team2: { teamId: 't2', userIds: ['p2'] },
  sets: [],
}

// A recorded (completed, unrated) fixture carries its set scores.
const recordedMatch = {
  ...match,
  id: 'm2',
  publicCode: 'MPUB2',
  sets: [
    { setNumber: 1, team1Games: 6, team2Games: 4, winnerTeamId: 't1' },
    { setNumber: 2, team1Games: 6, team2Games: 3, winnerTeamId: 't1' },
  ],
}

function renderSection(eventId?: string) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <AwaitingResultsSection eventId={eventId} />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

function renderRecorded(eventId = 'evt-1') {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <RecordedResultsSection eventId={eventId} />
      </QueryClientProvider>
    </MemoryRouter>,
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
    deleteMutateAsync.mockResolvedValue({})
    busy.value = false
    deleteBusy.value = false
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

  it('shows the applied per-side handicap transparently to organizers (#486)', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [{ ...match, team1Handicap: '0.4', team2Handicap: '0.2' }],
      isLoading: false,
    })
    renderSection()
    expect(screen.getByText(/Handicap:/)).toBeInTheDocument()
    expect(screen.getByText(/−0\.4 to Side 1/)).toBeInTheDocument()
    expect(screen.getByText(/−0\.2 to Side 2/)).toBeInTheDocument()
  })

  it('omits the handicap line when no handicap is set (#486)', () => {
    renderSection()
    expect(screen.queryByText(/Handicap:/)).not.toBeInTheDocument()
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

    expect(await screen.findByText(/Could not save the result/i)).toBeInTheDocument()
  })

  it('deletes a fixture after a confirm step', async () => {
    const user = userEvent.setup()
    renderSection()

    // First click only arms the confirmation; nothing is deleted yet.
    await user.click(screen.getByRole('button', { name: 'Delete fixture' }))
    expect(deleteMutateAsync).not.toHaveBeenCalled()

    // Confirm soft-disables the fixture (#138).
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))
    await waitFor(() =>
      expect(deleteMutateAsync).toHaveBeenCalledWith({ id: 'm1', data: { isActive: false } }),
    )
  })

  it('cancels a pending delete without calling the API', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Delete fixture' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Delete fixture' })).toBeInTheDocument()
    expect(deleteMutateAsync).not.toHaveBeenCalled()
  })

  it('shows a busy label while deleting', async () => {
    deleteBusy.value = true
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Delete fixture' }))
    expect(screen.getByRole('button', { name: 'Deleting…' })).toBeDisabled()
  })

  it('shows an error when deleting fails', async () => {
    deleteMutateAsync.mockRejectedValue(new Error('rated'))
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Delete fixture' }))
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(await screen.findByText(/Could not delete the fixture/i)).toBeInTheDocument()
    // The confirm step resets so the row returns to its default actions.
    expect(screen.getByRole('button', { name: 'Delete fixture' })).toBeInTheDocument()
  })
})

describe('RecordedResultsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Matches.mockReturnValue({ data: [recordedMatch], isLoading: false })
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

  it('queries the event-scoped completed-results list', () => {
    renderRecorded('evt-9')
    expect(useGetApiV1Matches).toHaveBeenCalledWith({
      filter: 'results',
      eventId: 'evt-9',
    })
  })

  it('shows a rated fixture as a read-only record, with no edit or delete', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [{ ...recordedMatch, ratedAt: '2026-02-01T00:00:00Z' }],
      isLoading: false,
    })
    renderRecorded()
    expect(screen.getByText('Rated')).toBeInTheDocument()
    expect(screen.getByText('6–4, 6–3')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit result' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete fixture' })).not.toBeInTheDocument()
    // Every fixture row links to the match's public page (where the QR lives).
    expect(screen.getByRole('link', { name: 'Public page (QR)' })).toHaveAttribute(
      'href',
      '/matches/MPUB2',
    )
  })

  it('shows loading and empty states', () => {
    useGetApiV1Matches.mockReturnValue({ data: undefined, isLoading: true })
    const { rerender } = renderRecorded()
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    useGetApiV1Matches.mockReturnValue({ data: [], isLoading: false })
    rerender(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText('No recorded results yet.')).toBeInTheDocument()
  })

  it('shows a recorded fixture collapsed as a score summary until expanded', () => {
    renderRecorded()
    expect(screen.getByText('Recorded')).toBeInTheDocument()
    expect(screen.getByText('6–4, 6–3')).toBeInTheDocument()
    // The entry form is hidden until "Edit result" is clicked.
    expect(screen.queryByLabelText('set 1 player 1 games')).not.toBeInTheDocument()
  })

  it('edits a recorded result, prefilling the existing scores and saving the changes', async () => {
    const user = userEvent.setup()
    renderRecorded()
    await user.click(screen.getByRole('button', { name: 'Edit result' }))

    // Existing scores are prefilled.
    expect(screen.getByLabelText('set 1 player 1 games')).toHaveValue('6')
    expect(screen.getByLabelText('set 2 player 2 games')).toHaveValue('3')

    // Change set 2 and save.
    await user.clear(screen.getByLabelText('set 2 player 2 games'))
    await user.type(screen.getByLabelText('set 2 player 2 games'), '0')
    await user.click(screen.getByRole('button', { name: 'Save result' }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        id: 'm2',
        data: {
          sets: [
            { team1Games: 6, team2Games: 4 },
            { team1Games: 6, team2Games: 0 },
          ],
        },
      }),
    )
  })

  it('cancels an edit, restoring the collapsed summary without calling the API', async () => {
    const user = userEvent.setup()
    renderRecorded()
    await user.click(screen.getByRole('button', { name: 'Edit result' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Edit result' })).toBeInTheDocument()
    expect(screen.queryByLabelText('set 1 player 1 games')).not.toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('shows a saving label while an edit is in flight', async () => {
    busy.value = true
    const user = userEvent.setup()
    renderRecorded()
    await user.click(screen.getByRole('button', { name: 'Edit result' }))
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
  })

  it('renders an awaiting row read-only, with no entry controls (#310)', () => {
    useGetApiV1Matches.mockReturnValue({ data: [match], isLoading: false })
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <AwaitingResultsSection eventId="evt-1" readOnly />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText('Awaiting result')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Record result/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete fixture/ })).not.toBeInTheDocument()
    // The public page link (navigation, not data entry) stays available.
    expect(screen.getByRole('link', { name: 'Public page (QR)' })).toBeInTheDocument()
  })

  it('renders a recorded row read-only: score shown, no edit or delete (#310)', () => {
    useGetApiV1Matches.mockReturnValue({ data: [recordedMatch], isLoading: false })
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" readOnly />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText('6–4, 6–3')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit result/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete fixture/ })).not.toBeInTheDocument()
  })

  it('lets a host drag to reorder same-date matches, persisting the new order (#331/#332)', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [
        { ...match, id: 'm1', matchDate: '2026-01-01' },
        { ...recordedMatch, id: 'm2', matchDate: '2026-01-01' },
      ],
      isLoading: false,
    })
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    // Two matches on the same date → each card offers a reorder handle.
    expect(screen.getAllByRole('button', { name: 'Reorder match' })).toHaveLength(2)

    // A drop with no target, or onto itself, is a no-op.
    act(() => dnd.onDragEnd?.({ active: { id: 'm2' }, over: null }))
    act(() => dnd.onDragEnd?.({ active: { id: 'm2' }, over: { id: 'm2' } }))
    expect(reorderMutate).not.toHaveBeenCalled()

    // Dropping m2 above m1 persists the new order.
    act(() => dnd.onDragEnd?.({ active: { id: 'm2' }, over: { id: 'm1' } }))
    expect(reorderMutate).toHaveBeenCalledWith({ data: { matchIds: ['m2', 'm1'] } })
  })

  it('offers no reorder handle for a single match or a read-only list (#332)', () => {
    useGetApiV1Matches.mockReturnValue({ data: [{ ...recordedMatch, id: 'm2' }], isLoading: false })
    const { rerender } = render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Reorder match' })).not.toBeInTheDocument()

    // Two same-date matches but read-only → still no handles (#310).
    useGetApiV1Matches.mockReturnValue({
      data: [
        { ...match, id: 'm1', matchDate: '2026-01-01' },
        { ...recordedMatch, id: 'm2', matchDate: '2026-01-01' },
      ],
      isLoading: false,
    })
    rerender(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" readOnly />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Reorder match' })).not.toBeInTheDocument()
  })

  it('offers no reorder handle once any same-date match is rated / frozen (#337)', () => {
    // Two same-date matches, but m2 is already rated → the whole group is frozen, no handles.
    useGetApiV1Matches.mockReturnValue({
      data: [
        { ...match, id: 'm1', matchDate: '2026-01-01' },
        { ...recordedMatch, id: 'm2', matchDate: '2026-01-01', ratedAt: '2026-02-01T00:00:00Z' },
      ],
      isLoading: false,
    })
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <RecordedResultsSection eventId="evt-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Reorder match' })).not.toBeInTheDocument()
  })
})
