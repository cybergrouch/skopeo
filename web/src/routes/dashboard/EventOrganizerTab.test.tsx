import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EventOrganizerTab } from './EventOrganizerTab'

const { useGetApiV1Events, createMutate, state } = vi.hoisted(() => ({
  useGetApiV1Events: vi.fn(),
  createMutate: vi.fn(),
  state: { fail: false },
}))

vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1Events,
  getGetApiV1EventsQueryKey: () => ['events'],
  usePostApiV1Events: (opts?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown, handlers?: { onError?: () => void }) => {
      createMutate(vars)
      if (state.fail) handlers?.onError?.()
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
    <button type="button" onClick={() => onSelect({ id: 'u1', publicCode: 'AAA111', displayName: 'Ana' })}>
      {placeholder}
    </button>
  ),
}))
vi.mock('./events/EventDetail', () => ({
  EventDetail: ({ eventId, onBack }: { eventId: string; onBack: () => void }) => (
    <div>
      detail:{eventId}
      <button type="button" onClick={onBack}>
        back
      </button>
    </div>
  ),
}))

function renderTab() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <EventOrganizerTab />
    </QueryClientProvider>,
  )
}

const event = {
  id: 'e1',
  publicCode: 'EV1',
  name: 'Spring Open',
  startDate: '2026-03-01',
  endDate: '2026-03-03',
  isActive: true,
  participants: [{ userId: 'u1', displayName: 'Ana', publicCode: 'AAA111' }],
  creatorDisplayName: 'Hank',
  creatorPublicCode: 'HHH999',
}

describe('EventOrganizerTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.fail = false
    useGetApiV1Events.mockReturnValue({ data: [], isLoading: false })
  })

  it('shows loading and empty states', () => {
    useGetApiV1Events.mockReturnValue({ data: undefined, isLoading: true })
    const { rerender } = renderTab()
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    useGetApiV1Events.mockReturnValue({ data: [], isLoading: false })
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <EventOrganizerTab />
      </QueryClientProvider>,
    )
    expect(screen.getByText(/No events yet/)).toBeInTheDocument()
  })

  it('lists events and opens the detail when a row is clicked', async () => {
    const twoPlayers = {
      ...event,
      id: 'e2',
      name: 'Doubles Day',
      participants: [
        { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111' },
        { userId: 'u2', displayName: 'Bob', publicCode: 'BBB222' },
      ],
    }
    useGetApiV1Events.mockReturnValue({ data: [event, twoPlayers], isLoading: false })
    const user = userEvent.setup()
    renderTab()
    // Both rows render — a singular (1 player) and a plural (2 players) participant count.
    expect(screen.getByText('Spring Open')).toBeInTheDocument()
    expect(screen.getByText('Doubles Day')).toBeInTheDocument()

    await user.click(screen.getByText('Spring Open'))
    expect(screen.getByText('detail:e1')).toBeInTheDocument()
    // Going back returns to the list.
    await user.click(screen.getByRole('button', { name: 'back' }))
    expect(screen.getByText('Events')).toBeInTheDocument()
  })

  it('shows the filing host on each event card, omitting it when unknown (#270)', () => {
    const orphan = { ...event, id: 'e3', name: 'Orphan Cup', creatorDisplayName: null, creatorPublicCode: null }
    useGetApiV1Events.mockReturnValue({ data: [event, orphan], isLoading: false })
    renderTab()

    expect(screen.getByText('Filed by Hank')).toBeInTheDocument()
    // The creator-less event renders no "Filed by" line — only the one with a known creator does.
    expect(screen.getAllByText(/Filed by/)).toHaveLength(1)
  })

  it('splits events into Upcoming and Past subsections by end date (#271)', () => {
    const upcoming = { ...event, id: 'up', name: 'Future Fest', startDate: '2999-01-01', endDate: '2999-01-02' }
    const past = { ...event, id: 'pa', name: 'Old Open', startDate: '2000-01-01', endDate: '2000-01-02' }
    useGetApiV1Events.mockReturnValue({ data: [past, upcoming], isLoading: false })
    renderTab()

    expect(screen.getByText('Upcoming')).toBeInTheDocument()
    expect(screen.getByText('Past')).toBeInTheDocument()
    expect(screen.getByText('Future Fest')).toBeInTheDocument()
    expect(screen.getByText('Old Open')).toBeInTheDocument()
    // No per-section empty state when both sections have events.
    expect(screen.queryByText('No upcoming events.')).not.toBeInTheDocument()
    expect(screen.queryByText('No past events.')).not.toBeInTheDocument()
  })

  it('shows a per-section empty state when a section has no events (#271)', () => {
    const past = { ...event, id: 'pa', name: 'Old Open', startDate: '2000-01-01', endDate: '2000-01-02' }
    useGetApiV1Events.mockReturnValue({ data: [past], isLoading: false })
    renderTab()

    expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    expect(screen.getByText('Old Open')).toBeInTheDocument()
    // The Past section has the event, so no "No past events." message.
    expect(screen.queryByText('No past events.')).not.toBeInTheDocument()
  })

  it('shows only the start date for upcoming and only the end date for past events (#296)', () => {
    const upcoming = { ...event, id: 'up', name: 'Future Fest', startDate: '2999-01-01', endDate: '2999-01-02' }
    const past = { ...event, id: 'pa', name: 'Old Open', startDate: '2000-01-01', endDate: '2000-01-02' }
    useGetApiV1Events.mockReturnValue({ data: [past, upcoming], isLoading: false })
    renderTab()

    // Upcoming: start date shown, end date hidden.
    expect(screen.getByText(/Starts 2999-01-01/)).toBeInTheDocument()
    expect(screen.queryByText(/2999-01-02/)).not.toBeInTheDocument()
    // Past: end date shown, start date hidden.
    expect(screen.getByText(/Ended 2000-01-02/)).toBeInTheDocument()
    expect(screen.queryByText(/2000-01-01/)).not.toBeInTheDocument()
  })

  it('creates an event with a roster', async () => {
    const user = userEvent.setup()
    renderTab()

    // Disabled until name + both dates are filled.
    expect(screen.getByRole('button', { name: 'Create event' })).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'Summer Open')
    await user.type(screen.getByLabelText('Start date'), '2026-06-01')
    await user.type(screen.getByLabelText('End date'), '2026-06-02')
    await user.click(screen.getByRole('button', { name: 'Search players to add…' }))
    // The chosen player shows as a removable chip.
    expect(screen.getByRole('button', { name: /Ana ✕/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Create event' }))
    expect(createMutate).toHaveBeenCalledWith({
      data: { name: 'Summer Open', startDate: '2026-06-01', endDate: '2026-06-02', participantIds: ['u1'] },
    })
  })

  it('shows an error when event creation fails', async () => {
    state.fail = true
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'Summer Open')
    await user.type(screen.getByLabelText('Start date'), '2026-06-01')
    await user.type(screen.getByLabelText('End date'), '2026-06-02')
    await user.click(screen.getByRole('button', { name: 'Create event' }))
    expect(screen.getByText(/Could not create the event/)).toBeInTheDocument()
  })

  it('de-duplicates and removes a staged participant before creating', async () => {
    const user = userEvent.setup()
    renderTab()
    // Selecting the same player twice adds them once (de-duplicated).
    await user.click(screen.getByRole('button', { name: 'Search players to add…' }))
    await user.click(screen.getByRole('button', { name: 'Search players to add…' }))
    expect(screen.getByRole('button', { name: /Ana ✕/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Ana ✕/ }))
    expect(screen.queryByRole('button', { name: /Ana ✕/ })).not.toBeInTheDocument()
  })
})
