import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { ReactNode } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PendingCalculationSection } from './PendingCalculationSection'

const {
  useGetApiV1Matches,
  usePostApiV1RatingsCalculations,
  useGetApiV1Users,
  useGetApiV1Events,
  setPriorityMutate,
  calculateMutate,
  dnd,
} = vi.hoisted(() => ({
  useGetApiV1Matches: vi.fn(),
  usePostApiV1RatingsCalculations: vi.fn(),
  useGetApiV1Users: vi.fn(),
  useGetApiV1Events: vi.fn(),
  setPriorityMutate: vi.fn(),
  calculateMutate: vi.fn(),
  dnd: { onDragEnd: undefined as undefined | ((e: unknown) => void) },
}))

vi.mock('@/api/generated/matches/matches', () => ({
  useGetApiV1Matches,
  getGetApiV1MatchesQueryKey: () => ['matches', 'pending-calculation'],
}))
vi.mock('@/api/generated/ratings/ratings', () => ({
  usePostApiV1RatingsCalculations,
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))
vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1Events,
  getGetApiV1EventsQueryKey: () => ['events'],
  usePutApiV1EventsIdCalculationPriority: (options?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      setPriorityMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
}))
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
  }),
  arrayMove: <T,>(arr: T[], from: number, to: number): T[] => {
    const copy = [...arr]
    const [moved] = copy.splice(from, 1)
    copy.splice(to, 0, moved)
    return copy
  },
}))
vi.mock('@dnd-kit/utilities', () => ({ CSS: { Transform: { toString: () => undefined } } }))

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
          breakdown: {
            dominance: '0.200000',
            scale: '1.000000',
            ratingGap: '0.000000',
            normalizedGap: '0.000000',
            competitiveThresholdPct: '0.083000',
            isUpset: false,
            upsetMultiplier: '2.000000',
            kFactor: '0.160000',
          },
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
    useGetApiV1Events.mockReturnValue({ data: [], isLoading: false })
    // mutate(vars) drives the component's onSuccess with a crafted response; calculateMutate records vars.
    usePostApiV1RatingsCalculations.mockImplementation(
      (options: {
        mutation: { onSuccess: (data: typeof PREVIEW | typeof COMMITTED) => void }
      }) => ({
        isPending: false,
        isError: false,
        error: null,
        mutate: (vars: { data: { dryRun: boolean; eventIds?: string[] } }) => {
          calculateMutate(vars)
          options.mutation.onSuccess(vars.data.dryRun ? PREVIEW : COMMITTED)
        },
      }),
    )
  })

  it('shows the pending count', () => {
    renderSection()
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('shows a loading state', () => {
    useGetApiV1Matches.mockReturnValue({ data: undefined, isLoading: true })
    useGetApiV1Events.mockReturnValue({ data: undefined, isLoading: true })
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

  it('numbers the matches in processing order, first to last (#331)', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [match({ id: 'm1' }), match({ id: 'm2' }), match({ id: 'm3' })],
      isLoading: false,
    })
    renderSection()

    // Each card is prefixed with its 1-based processing position.
    expect(screen.getByText('1.')).toBeInTheDocument()
    expect(screen.getByText('2.')).toBeInTheDocument()
    expect(screen.getByText('3.')).toBeInTheDocument()
  })

  it('groups matches by event and reorders event priority on drag (#335)', () => {
    const epochDay = (iso: string) => Math.round(new Date(`${iso}T00:00:00Z`).getTime() / 86_400_000)
    const priorityOf = () =>
      (setPriorityMutate.mock.calls[0][0] as { id: string; data: { priority: number } }).data.priority
    // Alpha (ends 1/10, two matches → one group), an eventless match (played 1/15), Beta (ends 1/20).
    useGetApiV1Matches.mockReturnValue({
      data: [
        match({ id: 'a1', eventId: 'evA' }),
        match({ id: 'a2', eventId: 'evA' }),
        match({ id: 'mid', matchDate: '2026-01-15' }),
        match({ id: 'b1', eventId: 'evB' }),
      ],
      isLoading: false,
    })
    useGetApiV1Events.mockReturnValue({
      data: [
        // Alpha carries an explicit priority (equal to its end-date key) — exercises the override path.
        { id: 'evA', name: 'Alpha Cup', endDate: '2026-01-10', calcPriority: epochDay('2026-01-10') },
        { id: 'evB', name: 'Beta Cup', endDate: '2026-01-20', calcPriority: null },
      ],
      isLoading: false,
    })
    renderSection()

    expect(screen.getByText('Alpha Cup')).toBeInTheDocument()
    expect(screen.getByText('Beta Cup')).toBeInTheDocument()
    expect(screen.getByText('Open (no event)')).toBeInTheDocument()
    // Only the two events are draggable (the Open entry is pinned by date).
    expect(screen.getAllByRole('button', { name: /Reorder event/ })).toHaveLength(2)

    // Drop onto the first entry (no "before" neighbour) → just below Alpha's key.
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evB' }, over: { id: 'event:evA' } }))
    expect(priorityOf()).toBeLessThan(epochDay('2026-01-10'))

    // Drop between neighbours (Beta over the Open entry) → midpoint of Alpha's and the Open key.
    setPriorityMutate.mockClear()
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evB' }, over: { id: 'open:mid' } }))
    const mid = priorityOf()
    expect(mid).toBeGreaterThan(epochDay('2026-01-10'))
    expect(mid).toBeLessThan(epochDay('2026-01-15'))

    // Drop onto the last entry (no "after" neighbour) → just above Beta's key.
    setPriorityMutate.mockClear()
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evA' }, over: { id: 'event:evB' } }))
    expect(priorityOf()).toBeGreaterThan(epochDay('2026-01-20'))
  })

  it('ignores no-op / invalid drops and dragging an eventless entry (#335)', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [
        match({ id: 'a1', eventId: 'evA' }),
        match({ id: 'open1' }),
        match({ id: 'x1', eventId: 'evX' }), // event missing from the events list → "Event" fallback
      ],
      isLoading: false,
    })
    useGetApiV1Events.mockReturnValue({
      data: [{ id: 'evA', name: 'Alpha Cup', endDate: '2026-01-10', calcPriority: 5 }],
      isLoading: false,
    })
    renderSection()

    // Dropped on nothing, onto itself, with an unknown active id, or an unknown target → no update.
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evA' }, over: null }))
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evA' }, over: { id: 'event:evA' } }))
    act(() => dnd.onDragEnd?.({ active: { id: 'event:gone' }, over: { id: 'event:evA' } }))
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evA' }, over: { id: 'event:nope' } }))
    // The Open (eventless) entry isn't an event, so dragging it changes no priority.
    act(() => dnd.onDragEnd?.({ active: { id: 'open:open1' }, over: { id: 'event:evA' } }))
    expect(setPriorityMutate).not.toHaveBeenCalled()

    expect(screen.getByText('Open (no event)')).toBeInTheDocument()
    // A group whose event isn't loaded still renders with a generic label.
    expect(screen.getByText('Event')).toBeInTheDocument()
  })

  it('computes a priority next to an event that is not loaded (#335)', () => {
    useGetApiV1Matches.mockReturnValue({
      data: [match({ id: 'a1', eventId: 'evA' }), match({ id: 'g1', eventId: 'evGone' })],
      isLoading: false,
    })
    useGetApiV1Events.mockReturnValue({
      data: [{ id: 'evA', name: 'Alpha Cup', endDate: '2026-01-10', calcPriority: null }],
      isLoading: false,
    })
    renderSection()

    // Drag Alpha after the unloaded 'evGone' group — its key falls back to 0, so priority = 0 + 1.
    act(() => dnd.onDragEnd?.({ active: { id: 'event:evA' }, over: { id: 'event:evGone' } }))
    expect(setPriorityMutate).toHaveBeenCalledWith({ id: 'evA', data: { priority: 1 } })
  })

  it('disables Preview when nothing is pending', () => {
    useGetApiV1Matches.mockReturnValue({ data: [], isLoading: false })
    renderSection()
    expect(screen.getByRole('button', { name: 'Preview' })).toBeDisabled()
  })

  it('previews a dry run, shows the per-match projection + breakdown on expand, then commits', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(screen.getByRole('button', { name: 'Preview' }))
    expect(screen.getByTestId('calculation-preview')).toBeInTheDocument()

    // Expand the match card to reveal its projection and the calculation breakdown.
    await user.click(screen.getByRole('button', { name: /Alice vs Bob/ }))
    expect(screen.getByText(/4\.000000 → 4\.100000 \(0\.100000\)/)).toBeInTheDocument()
    expect(screen.getByText(/dominance 0\.200000 · scale 1\.000000/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Commit' }))
    await waitFor(() =>
      expect(
        screen.getByText('Committed ratings for 1 match.'),
      ).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('calculation-preview')).not.toBeInTheDocument()
  })

  it('labels an upset in the breakdown', async () => {
    const change = { ...PREVIEW.matches[0].changes[0], breakdown: { ...PREVIEW.matches[0].changes[0].breakdown, isUpset: true } }
    const upset = { ...PREVIEW, matches: [{ ...PREVIEW.matches[0], changes: [change] }] }
    usePostApiV1RatingsCalculations.mockImplementation(
      (options: { mutation: { onSuccess: (data: typeof upset) => void } }) => ({
        isPending: false,
        mutate: () => options.mutation.onSuccess(upset),
      }),
    )
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Preview' }))
    await user.click(screen.getByRole('button', { name: /Alice vs Bob/ }))
    expect(screen.getByText(/· upset · K/)).toBeInTheDocument()
  })

  it('expands a card to prompt for a preview, and collapses it again', async () => {
    const user = userEvent.setup()
    renderSection()
    const card = screen.getByRole('button', { name: /Alice vs Bob/ })

    await user.click(card)
    expect(screen.getByText(/Run Preview to see the projected ratings/i)).toBeInTheDocument()

    await user.click(card) // collapse
    expect(screen.queryByText(/Run Preview to see the projected ratings/i)).not.toBeInTheDocument()
  })

  it('discards a preview without committing', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(screen.getByRole('button', { name: 'Preview' }))
    expect(screen.getByTestId('calculation-preview')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.queryByTestId('calculation-preview')).not.toBeInTheDocument()
  })

  it('previews all pending events when nothing is selected (#479)', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Preview' }))
    // No selection → eventIds is omitted (undefined), the unchanged all-pending behaviour.
    expect(calculateMutate).toHaveBeenCalledWith({ data: { dryRun: true, eventIds: undefined } })
  })

  it('scopes the run to a selected event and passes its id (#479)', async () => {
    const user = userEvent.setup()
    useGetApiV1Matches.mockReturnValue({
      data: [match({ id: 'a1', eventId: 'evA' }), match({ id: 'b1', eventId: 'evB' })],
      isLoading: false,
    })
    useGetApiV1Events.mockReturnValue({
      data: [
        { id: 'evA', name: 'Alpha Cup', endDate: '2026-01-10', calcPriority: null },
        { id: 'evB', name: 'Beta Cup', endDate: '2026-01-20', calcPriority: null },
      ],
      isLoading: false,
    })
    renderSection()

    // Tick the earliest event (Alpha) — a valid prefix — then preview.
    await user.click(screen.getByRole('checkbox', { name: 'Include event Alpha Cup' }))
    expect(screen.getByText(/Scoped to/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Preview' }))
    expect(calculateMutate).toHaveBeenCalledWith({ data: { dryRun: true, eventIds: ['evA'] } })
  })

  it('blocks a selection that skips an earlier pending event and shows the guard error (#479)', async () => {
    const user = userEvent.setup()
    useGetApiV1Matches.mockReturnValue({
      data: [match({ id: 'a1', eventId: 'evA' }), match({ id: 'b1', eventId: 'evB' })],
      isLoading: false,
    })
    useGetApiV1Events.mockReturnValue({
      data: [
        { id: 'evA', name: 'Alpha Cup', endDate: '2026-01-10', calcPriority: null },
        { id: 'evB', name: 'Beta Cup', endDate: '2026-01-20', calcPriority: null },
      ],
      isLoading: false,
    })
    renderSection()

    // Tick only the LATER event (Beta), skipping Alpha → prefix broken.
    await user.click(screen.getByRole('checkbox', { name: 'Include event Beta Cup' }))
    expect(screen.getByTestId('calculation-guard-error')).toHaveTextContent(/Alpha Cup/)
    // Preview is blocked while the selection is invalid.
    expect(screen.getByRole('button', { name: 'Preview' })).toBeDisabled()
    expect(calculateMutate).not.toHaveBeenCalled()
  })
})
