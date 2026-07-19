import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EventsHistoryCard } from './EventsHistoryCard'
import type { MyEventResponse } from '@/api/generated/model'

const { useGetApiV1EventsMine } = vi.hoisted(() => ({ useGetApiV1EventsMine: vi.fn() }))
vi.mock('@/api/generated/events/events', () => ({ useGetApiV1EventsMine }))

function renderCard() {
  return render(
    <MemoryRouter>
      <EventsHistoryCard />
    </MemoryRouter>,
  )
}

// Far-future and clearly-past dates so bucketing is stable regardless of the day the test runs.
const upcoming: MyEventResponse = {
  publicCode: 'UP0001',
  name: 'Summer Slam',
  startDate: '2099-08-01',
  endDate: '2099-08-03',
  status: 'APPROVED',
  isFinalized: false,
  completedMatchCount: 0,
}
const pending: MyEventResponse = {
  publicCode: 'UP0002',
  name: 'Autumn Cup',
  startDate: '2099-09-01',
  endDate: '2099-09-02',
  status: 'PENDING',
  isFinalized: false,
  completedMatchCount: 0,
}
const pastUnfinalized: MyEventResponse = {
  publicCode: 'PA0001',
  name: 'Winter Open',
  startDate: '2000-01-01',
  endDate: '2000-01-02',
  status: 'HOLD',
  isFinalized: false,
  completedMatchCount: 0,
}
// Finalized with a FUTURE end date + no results — must land under Finalized, never Upcoming.
const finalizedFuture: MyEventResponse = {
  publicCode: 'FI0001',
  name: 'Early Bird Final',
  startDate: '2099-10-01',
  endDate: '2099-10-05',
  status: 'APPROVED',
  isFinalized: true,
  completedMatchCount: 0,
}
// Finalized in the past — Finalized, not the old "Past" (now Unfinalized) bucket.
const finalizedPast: MyEventResponse = {
  publicCode: 'FI0002',
  name: 'Old Champs',
  startDate: '2001-01-01',
  endDate: '2001-01-02',
  status: 'APPROVED',
  isFinalized: true,
  completedMatchCount: 3,
}
// Future end date but already has recorded results — Unfinalized, not Upcoming.
const futureWithResults: MyEventResponse = {
  publicCode: 'UN0002',
  name: 'In-Flight Meet',
  startDate: '2099-07-01',
  endDate: '2099-07-30',
  status: 'APPROVED',
  isFinalized: false,
  completedMatchCount: 2,
}

/** The links (by name) rendered under a section heading, in DOM order. */
function linksUnder(heading: string): string[] {
  const headingEl = screen.getByText(heading)
  const section = headingEl.parentElement as HTMLElement
  return within(section)
    .queryAllByRole('link')
    .map((el) => el.textContent ?? '')
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

  it('buckets by finalized status first, then activity/date (#483)', () => {
    useGetApiV1EventsMine.mockReturnValue({
      data: [upcoming, pending, pastUnfinalized, finalizedFuture, finalizedPast, futureWithResults],
      isLoading: false,
    })
    renderCard()

    // Upcoming: future + not finalized + no results only.
    expect(linksUnder('Upcoming').join('|')).toMatch(/Summer Slam/)
    expect(linksUnder('Upcoming').join('|')).toMatch(/Autumn Cup/)
    expect(linksUnder('Upcoming').join('|')).not.toMatch(/Early Bird Final/)
    expect(linksUnder('Upcoming').join('|')).not.toMatch(/In-Flight Meet/)

    // Unfinalized: past-unfinalized + future-with-results (not finalized).
    expect(linksUnder('Unfinalized').join('|')).toMatch(/Winter Open/)
    expect(linksUnder('Unfinalized').join('|')).toMatch(/In-Flight Meet/)
    expect(linksUnder('Unfinalized').join('|')).not.toMatch(/Old Champs/)

    // Finalized: both finalized events, regardless of date/results.
    expect(linksUnder('Finalized').join('|')).toMatch(/Early Bird Final/)
    expect(linksUnder('Finalized').join('|')).toMatch(/Old Champs/)

    // Pending/held standings are still labelled.
    const autumn = screen.getByRole('link', { name: /Autumn Cup/ })
    expect(autumn).toHaveAttribute('href', '/events/UP0002')
    expect(within(autumn).getByText(/Pending approval/)).toBeInTheDocument()
    const winter = screen.getByRole('link', { name: /Winter Open/ })
    expect(within(winter).getByText(/On hold/)).toBeInTheDocument()
  })

  it('shows per-section empty messages for empty buckets', () => {
    // Upcoming-only → Unfinalized + Finalized show their empty messages.
    useGetApiV1EventsMine.mockReturnValue({ data: [upcoming], isLoading: false })
    const { unmount } = renderCard()
    expect(screen.getByText('No unfinalized events.')).toBeInTheDocument()
    expect(screen.getByText('No finalized events.')).toBeInTheDocument()
    expect(screen.queryByText('No upcoming events.')).not.toBeInTheDocument()
    unmount()

    // Finalized-only → Upcoming + Unfinalized show their empty messages.
    useGetApiV1EventsMine.mockReturnValue({ data: [finalizedPast], isLoading: false })
    renderCard()
    expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    expect(screen.getByText('No unfinalized events.')).toBeInTheDocument()
    expect(screen.queryByText('No finalized events.')).not.toBeInTheDocument()
  })
})
