import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { PointsAuditCard } from './PointsAuditCard'

const { useGetApiV1PlayersCodePoints } = vi.hoisted(() => ({
  useGetApiV1PlayersCodePoints: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1PlayersCodePoints }))

function renderCard(enabled: boolean) {
  return render(
    <MemoryRouter>
      <PointsAuditCard code="ABC123" enabled={enabled} />
    </MemoryRouter>,
  )
}

const matchAward = {
  id: 'a1',
  points: '30',
  band: '4.0',
  pointClass: 'SEASONAL_TOURNAMENT_6M',
  validUntil: '2026-12-01T00:00:00',
  matchCode: 'MTCH01',
  eventCode: null,
}
const eventAward = {
  id: 'a2',
  points: '15',
  band: '4.0',
  pointClass: 'ANNUAL_TOURNAMENT',
  validUntil: '2027-01-01T00:00:00',
  matchCode: null,
  eventCode: 'EVNT01',
}

describe('PointsAuditCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders nothing and does not enable the fetch when the viewer cannot see the audit', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: undefined, isLoading: false })
    const { container } = renderCard(false)
    expect(container).toBeEmptyDOMElement()
    // The hook is called with enabled:false so a non-owner never requests the (403) endpoint.
    expect(useGetApiV1PlayersCodePoints).toHaveBeenCalledWith('ABC123', {
      query: { enabled: false },
    })
  })

  it('lists active awards with points, band, expiry and a link to the granting match (#448)', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [matchAward], isLoading: false })
    renderCard(true)
    expect(screen.getByText('30 pts')).toBeInTheDocument()
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.getByText(/Expires 2026-12-01/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /View granting match/ })).toHaveAttribute('href', '/matches/MTCH01')
  })

  it('falls back to the event link when an award has no match (#448)', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [eventAward], isLoading: false })
    renderCard(true)
    expect(screen.getByRole('link', { name: /View granting event/ })).toHaveAttribute('href', '/events/EVNT01')
  })

  it('shows the empty state when there are no active points', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [], isLoading: false })
    renderCard(true)
    expect(screen.getByText('No active ranking points.')).toBeInTheDocument()
  })
})
