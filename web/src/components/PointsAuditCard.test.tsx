import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { PointsAuditCard } from './PointsAuditCard'

const { useGetApiV1PlayersCodePoints, useHideFlag } = vi.hoisted(() => ({
  useGetApiV1PlayersCodePoints: vi.fn(),
  useHideFlag: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1PlayersCodePoints }))
// The hide-ranking-points flag (#865). Off by default here, so every pre-existing assertion below still
// describes the ordinary case.
vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1SettingsHideRankingPoints: useHideFlag,
}))

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
  beforeEach(() => {
    vi.clearAllMocks()
    useHideFlag.mockReturnValue({ data: { hidden: false } })
  })

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
    expect(screen.getByText('+30 pts')).toBeInTheDocument()
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.getByText(/Expires 2026-12-01/)).toBeInTheDocument()
    const matchLink = screen.getByRole('link', { name: /View granting match/ })
    expect(matchLink).toHaveAttribute('href', '/matches/MTCH01')
    // The granting-match link wears the themed content-link style (#453).
    expect(matchLink).toHaveClass('content-link')
  })

  it('falls back to the event link when an award has no match (#448)', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [eventAward], isLoading: false })
    renderCard(true)
    const eventLink = screen.getByRole('link', { name: /View granting event/ })
    expect(eventLink).toHaveAttribute('href', '/events/EVNT01')
    // The granting-event link wears the themed content-link style (#453).
    expect(eventLink).toHaveClass('content-link')
  })

  it('shows the empty state when there are no active points', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [], isLoading: false })
    renderCard(true)
    expect(screen.getByText('No active ranking points.')).toBeInTheDocument()
  })

  it('shows a loading state while the audit is fetching (#448)', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: undefined, isLoading: true })
    renderCard(true)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows "Manual grant" for an award with neither a match nor an event (#448)', () => {
    const manualAward = { ...eventAward, id: 'a3', matchCode: null, eventCode: null }
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [manualAward], isLoading: false })
    renderCard(true)
    expect(screen.getByText('Manual grant')).toBeInTheDocument()
  })

  it('renders nothing rather than claiming zero when points are hidden (#865)', () => {
    // The server returns an empty list to a suppressed viewer, which is indistinguishable from genuinely
    // having none. Saying "No active ranking points" to a player who HAS points is a false statement they
    // could act on — so the card is absent instead (the #857 pattern).
    useHideFlag.mockReturnValue({ data: { hidden: true } })
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [], isLoading: false })

    const { container } = renderCard(true)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('No active ranking points.')).not.toBeInTheDocument()
  })

  it('still lists awards for an exempt viewer while the flag is on (#865)', () => {
    // An exempt viewer (admin, host, club owner, rater, points manager) gets a populated list from the
    // server even with the flag on, so the card must render it rather than suppressing on the flag alone.
    useHideFlag.mockReturnValue({ data: { hidden: true } })
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: [matchAward], isLoading: false })

    renderCard(true)

    expect(screen.getByText(/30/)).toBeInTheDocument()
  })

})
