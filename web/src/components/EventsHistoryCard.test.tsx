import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EventsHistoryCard } from './EventsHistoryCard'

const { useGetApiV1EventsMine } = vi.hoisted(() => ({ useGetApiV1EventsMine: vi.fn() }))
vi.mock('@/api/generated/events/events', () => ({ useGetApiV1EventsMine }))

function renderCard() {
  return render(
    <MemoryRouter>
      <EventsHistoryCard />
    </MemoryRouter>,
  )
}

// A far-future and a clearly-past event so the split is stable regardless of the day the test runs.
const upcoming = {
  publicCode: 'UP0001',
  name: 'Summer Slam',
  startDate: '2099-08-01',
  endDate: '2099-08-03',
  status: 'APPROVED',
}
const pending = {
  publicCode: 'UP0002',
  name: 'Autumn Cup',
  startDate: '2099-09-01',
  endDate: '2099-09-02',
  status: 'PENDING',
}
const past = {
  publicCode: 'PA0001',
  name: 'Winter Open',
  startDate: '2000-01-01',
  endDate: '2000-01-02',
  status: 'HOLD',
}

describe('EventsHistoryCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1EventsMine.mockReturnValue({ data: undefined, isLoading: true })
    renderCard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows the empty state when the player has no events', () => {
    useGetApiV1EventsMine.mockReturnValue({ data: [], isLoading: false })
    renderCard()
    expect(screen.getByText(/haven’t joined any events/i)).toBeInTheDocument()
  })

  it('splits events into upcoming and past, links them, and labels pending/held standings (#202)', () => {
    useGetApiV1EventsMine.mockReturnValue({ data: [upcoming, pending, past], isLoading: false })
    renderCard()

    // Upcoming section: the approved + pending future events, the latter labelled.
    expect(screen.getByRole('link', { name: /Summer Slam/ })).toHaveAttribute('href', '/events/UP0001')
    const autumn = screen.getByRole('link', { name: /Autumn Cup/ })
    expect(autumn).toHaveAttribute('href', '/events/UP0002')
    expect(within(autumn).getByText(/Pending approval/)).toBeInTheDocument()

    // Past section: the old event, labelled on hold.
    const winter = screen.getByRole('link', { name: /Winter Open/ })
    expect(winter).toHaveAttribute('href', '/events/PA0001')
    expect(within(winter).getByText(/On hold/)).toBeInTheDocument()

    expect(screen.queryByText('No upcoming events.')).not.toBeInTheDocument()
    expect(screen.queryByText('No past events.')).not.toBeInTheDocument()
  })

  it('shows per-section empty messages when one side is empty', () => {
    // Upcoming-only → the Past section shows its empty message.
    useGetApiV1EventsMine.mockReturnValue({ data: [upcoming], isLoading: false })
    const { unmount } = renderCard()
    expect(screen.getByText('No past events.')).toBeInTheDocument()
    expect(screen.queryByText('No upcoming events.')).not.toBeInTheDocument()
    unmount()

    // Past-only → the Upcoming section shows its empty message.
    useGetApiV1EventsMine.mockReturnValue({ data: [past], isLoading: false })
    renderCard()
    expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    expect(screen.queryByText('No past events.')).not.toBeInTheDocument()
  })
})
