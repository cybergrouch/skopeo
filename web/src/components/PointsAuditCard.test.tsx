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

/** The grouped payload (#882) for awards that all count in the player's current band. */
const counting = (awards: Array<Record<string, unknown>>, band = '4.0') => ({
  currentBand: band,
  current: {
    band,
    counting: true,
    totalPoints: String(awards.reduce((n, a) => n + Number(a.points), 0)),
    awards,
  },
  latent: [],
  totalPoints: String(awards.reduce((n, a) => n + Number(a.points), 0)),
})

/** An empty payload — what a player with no awards, or a suppressed viewer (#865), receives. */
const nothing = { currentBand: '4.0', current: { band: '4.0', counting: true, totalPoints: '0', awards: [] }, latent: [], totalPoints: '0' }

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
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: counting([matchAward]), isLoading: false })
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
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: counting([eventAward]), isLoading: false })
    renderCard(true)
    const eventLink = screen.getByRole('link', { name: /View granting event/ })
    expect(eventLink).toHaveAttribute('href', '/events/EVNT01')
    // The granting-event link wears the themed content-link style (#453).
    expect(eventLink).toHaveClass('content-link')
  })

  it('shows the empty state when there are no active points', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: nothing, isLoading: false })
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
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: counting([manualAward]), isLoading: false })
    renderCard(true)
    expect(screen.getByText('Manual grant')).toBeInTheDocument()
  })

  it('renders nothing rather than claiming zero when points are hidden (#865)', () => {
    // The server returns an empty list to a suppressed viewer, which is indistinguishable from genuinely
    // having none. Saying "No active ranking points" to a player who HAS points is a false statement they
    // could act on — so the card is absent instead (the #857 pattern).
    useHideFlag.mockReturnValue({ data: { hidden: true } })
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: nothing, isLoading: false })

    const { container } = renderCard(true)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('No active ranking points.')).not.toBeInTheDocument()
  })

  it('still lists awards for an exempt viewer while the flag is on (#865)', () => {
    // An exempt viewer (admin, host, club owner, rater, points manager) gets a populated list from the
    // server even with the flag on, so the card must render it rather than suppressing on the flag alone.
    useHideFlag.mockReturnValue({ data: { hidden: true } })
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: counting([matchAward]), isLoading: false })

    renderCard(true)

    // The award line specifically — the band total now also renders "+30", so /30/ alone is ambiguous.
    expect(screen.getByText('+30 pts')).toBeInTheDocument()
  })

  it('separates the current band from bands the player has left, and marks which counts (#882)', () => {
    // The production shape that produced the bug report (SG59VN): at 3.5 with nothing counting, holding
    // 81 points earned at 3.0.
    useGetApiV1PlayersCodePoints.mockReturnValue({
      data: {
        currentBand: '3.5',
        current: { band: '3.5', counting: true, totalPoints: '0', awards: [] },
        latent: [
          {
            band: '3.0',
            counting: false,
            totalPoints: '81',
            awards: [{ ...matchAward, id: 'a9', points: '81', band: '3.0' }],
          },
        ],
        totalPoints: '81',
      },
      isLoading: false,
    })

    renderCard(true)

    // The current band is stated even at zero — that zero is what explains being unranked while holding
    // points, and its absence is what made this look broken.
    expect(screen.getByText(/Current band 3\.5/)).toBeInTheDocument()
    expect(screen.getByText(/unranked here/)).toBeInTheDocument()
    // The 81 points are visible, in their own section, labelled as not counting.
    expect(screen.getByText('Earned in other bands')).toBeInTheDocument()
    expect(screen.getByText(/Band 3\.0/)).toBeInTheDocument()
    expect(screen.getByText('not counting')).toBeInTheDocument()
    expect(screen.getByText('+81 pts')).toBeInTheDocument()
    // And they are described as dormant, not forfeited.
    expect(screen.getByText(/count again if you return to that band/)).toBeInTheDocument()
  })

  it('does not imply promotion, since a demoted player is in the same position (#882)', () => {
    // Three of the four players affected in production had moved DOWN a band, so any copy about moving
    // up would be wrong for most of them.
    useGetApiV1PlayersCodePoints.mockReturnValue({
      data: {
        currentBand: '2.5',
        current: { band: '2.5', counting: true, totalPoints: '0', awards: [] },
        latent: [
          {
            band: '3.0',
            counting: false,
            totalPoints: '54',
            awards: [{ ...matchAward, id: 'a8', points: '54', band: '3.0' }],
          },
        ],
        totalPoints: '54',
      },
      isLoading: false,
    })

    renderCard(true)

    expect(screen.getByText(/your band has changed since you earned/)).toBeInTheDocument()
    expect(screen.queryByText(/moved up|promot/i)).not.toBeInTheDocument()
  })

  it('shows only the current band when nothing is latent (#882)', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({ data: counting([matchAward]), isLoading: false })

    renderCard(true)

    expect(screen.getByText(/Current band 4\.0/)).toBeInTheDocument()
    // No empty "other bands" section for a player who has never changed band.
    expect(screen.queryByText('Earned in other bands')).not.toBeInTheDocument()
  })

  it('renders no card for an unrated player with no awards at all', () => {
    useGetApiV1PlayersCodePoints.mockReturnValue({
      data: { currentBand: null, current: null, latent: [], totalPoints: '0' },
      isLoading: false,
    })

    renderCard(true)

    expect(screen.getByText('No active ranking points.')).toBeInTheDocument()
    expect(screen.queryByText(/Current band/)).not.toBeInTheDocument()
  })

  it('survives a payload that omits empty award arrays or sends an unparseable total', () => {
    // The generated types mark `awards` optional, so a server that omits empty collections is a shape
    // this component must tolerate rather than crash on — and a total it cannot format should render raw
    // rather than as "null".
    useGetApiV1PlayersCodePoints.mockReturnValue({
      data: {
        currentBand: '3.5',
        current: { band: '3.5', counting: true, totalPoints: '' },
        latent: [{ band: '3.0', counting: false, totalPoints: '' }],
        totalPoints: '',
      },
      isLoading: false,
    })

    renderCard(true)

    expect(screen.getByText(/Current band 3\.5/)).toBeInTheDocument()
    expect(screen.getByText('Earned in other bands')).toBeInTheDocument()
  })

  it('shows only latent bands for an unrated player who holds points (#882)', () => {
    // No rating means no current band, so nothing can count — but the points still exist and must be
    // shown, or the card would report "no points" to a player who has some.
    useGetApiV1PlayersCodePoints.mockReturnValue({
      data: {
        currentBand: null,
        current: null,
        latent: [
          {
            band: '3.0',
            counting: false,
            totalPoints: '12',
            awards: [{ ...matchAward, id: 'a7', points: '12', band: '3.0' }],
          },
        ],
        totalPoints: '12',
      },
      isLoading: false,
    })

    renderCard(true)

    expect(screen.queryByText(/Current band/)).not.toBeInTheDocument()
    expect(screen.getByText('Earned in other bands')).toBeInTheDocument()
    expect(screen.getByText('+12 pts')).toBeInTheDocument()
  })

})
