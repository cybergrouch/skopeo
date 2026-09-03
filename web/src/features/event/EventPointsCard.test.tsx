import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EventPointsCard } from './EventPointsCard'

const { useGetApiV1EventsCodeCodePoints } = vi.hoisted(() => ({
  useGetApiV1EventsCodeCodePoints: vi.fn(),
}))
vi.mock('@/api/generated/events/events', () => ({ useGetApiV1EventsCodeCodePoints }))

function renderCard(code = 'EVT9KD') {
  return render(
    <MemoryRouter>
      <EventPointsCard code={code} />
    </MemoryRouter>,
  )
}

const row = (overrides: Record<string, unknown> = {}) => ({
  userId: 'u1',
  publicCode: 'ADA123',
  displayName: 'Ada Lovelace',
  points: '12.0000',
  isPlaceholder: false,
  isDeleted: false,
  ...overrides,
})

describe('EventPointsCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists each player with their total, highest first as the server ordered them', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: {
        rows: [row(), row({ userId: 'u2', publicCode: 'BOB456', displayName: 'Bob', points: '2.0000' })],
        totalPoints: '14.0000',
      },
      isLoading: false,
    })
    renderCard()

    // The card presents the server's order and figures — it neither sorts nor sums.
    const items = screen.getAllByRole('listitem').map((li) => li.textContent ?? '')
    expect(items[0]).toContain('Ada Lovelace')
    expect(items[0]).toContain('+12')
    expect(items[1]).toContain('Bob')
    expect(items[1]).toContain('+2')
    expect(screen.getByText('Total awarded:').parentElement).toHaveTextContent('+14')
  })

  it('links a player to their public profile', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: { rows: [row()], totalPoints: '12.0000' },
      isLoading: false,
    })
    renderCard()
    expect(screen.getByRole('link', { name: 'Ada Lovelace' })).toHaveAttribute('href', '/players/ADA123')
  })

  it('renders a player without a public code as plain text', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: { rows: [row({ publicCode: null, displayName: 'Unclaimed' })], totalPoints: '12.0000' },
      isLoading: false,
    })
    renderCard()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('Unclaimed')).toBeInTheDocument()
  })

  it('renders nothing at all when the event has awarded no points', () => {
    // An event legitimately has none — unfinalized, or "Award ranking points" off (#831). An empty card
    // would read as a fault rather than as an absence, so the whole card is absent.
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: { rows: [], totalPoints: '0.0000' },
      isLoading: false,
    })
    const { container } = renderCard()
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing while loading, rather than an empty shell', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({ data: undefined, isLoading: true })
    const { container } = renderCard()
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when the request fails', () => {
    // A public page must not sprout an error card because an optional extra could not load.
    useGetApiV1EventsCodeCodePoints.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const { container } = renderCard()
    expect(container).toBeEmptyDOMElement()
  })

  it('states both filter rules, since they are surprising in opposite directions', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: { rows: [row()], totalPoints: '12.0000' },
      isLoading: false,
    })
    renderCard()
    // Revoked awards are excluded but expired ones are not — a reader who assumes either the wrong way
    // reads the card as inconsistent with the standings.
    const description = screen.getByText(/Revoked awards are excluded/)
    expect(description).toHaveTextContent('points remain listed here after they expire')
  })

  it('falls back through public code to id when a player has no display name', () => {
    // A placeholder player (#496) may carry no name at all. Naming a row by its code, or failing that its
    // id, keeps the row identifiable — an unnamed row in a points list is useless to a reader.
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: {
        rows: [
          row({ userId: 'u1', displayName: null, publicCode: 'ADA123' }),
          row({ userId: 'u2', displayName: null, publicCode: null }),
        ],
        totalPoints: '24.0000',
      },
      isLoading: false,
    })
    renderCard()

    expect(screen.getByRole('link', { name: 'ADA123' })).toBeInTheDocument()
    expect(screen.getByText('u2')).toBeInTheDocument()
  })

  it('falls back to the raw value when a figure will not format', () => {
    // formatPoints returns null for anything unparseable. Showing the raw string beats showing nothing:
    // a blank where a number belongs reads as "zero" or as a broken card, and both are worse than odd
    // text. Same idiom as the Points Management ledger, so the two surfaces degrade alike.
    useGetApiV1EventsCodeCodePoints.mockReturnValue({
      data: { rows: [row({ points: 'not-a-number' })], totalPoints: 'also-not-a-number' },
      isLoading: false,
    })
    renderCard()

    expect(screen.getByRole('listitem')).toHaveTextContent('not-a-number')
    expect(screen.getByText('Total awarded:').parentElement).toHaveTextContent('also-not-a-number')
  })

  it('does not query without a code', () => {
    useGetApiV1EventsCodeCodePoints.mockReturnValue({ data: undefined, isLoading: false })
    renderCard('')
    expect(useGetApiV1EventsCodeCodePoints).toHaveBeenCalledWith('', {
      query: { enabled: false, retry: false },
    })
  })
})
