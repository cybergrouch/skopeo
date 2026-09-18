import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom'
import { MatchHistoryCard } from './MatchHistoryCard'
import type { PlayerMatchHistoryEntry } from '@/api/generated/model'

const { useGetApiV1PlayersCodeMatchHistory } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1PlayersCodeMatchHistory,
}))

/** Surfaces the query string the band filter lives in (#1056), and how it got there. */
function UrlProbe() {
  const location = useLocation()
  const navigationType = useNavigationType()
  return (
    <>
      <div data-testid="url">{decodeURIComponent(location.search)}</div>
      <div data-testid="nav-type">{navigationType}</div>
    </>
  )
}

function renderCard(entry = '/players/K7Q2MX') {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <MatchHistoryCard code="K7Q2MX" />
      <UrlProbe />
    </MemoryRouter>,
  )
}

/** A minimal completed-match row; only the fields the card surfaces need to be realistic. */
function match(id: string, opponent: string): PlayerMatchHistoryEntry {
  return {
    matchId: id,
    publicCode: id.toUpperCase(),
    matchDate: '2026-01-01',
    status: 'COMPLETED',
    rated: false,
    result: 'WIN',
    setScores: ['6-4'],
    partners: [],
    opponents: [{ publicCode: `${opponent}1`, displayName: opponent, photoUrl: null, levelAtMatch: null }],
    playerLevelAtMatch: null,
  }
}

describe('MatchHistoryCard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('requests only a bounded preview', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: undefined, isLoading: true })
    renderCard()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    // The preview asks for a small page, not the whole history.
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenCalledWith('K7Q2MX', { limit: 5 }, { query: { enabled: true } })
  })

  it('shows an empty state when there are no matches', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: { items: [], total: 0 }, isLoading: false })
    renderCard()
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument()
  })

  it('renders the preview rows and links to the full page when there are more (#284)', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben'), match('b', 'Cara')], total: 12 },
      isLoading: false,
    })
    renderCard()
    expect(screen.getByText('Ben')).toBeInTheDocument()
    expect(screen.getByText('Cara')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View all 12 matches' })).toHaveAttribute('href', '/players/K7Q2MX/matches')
  })

  it('re-queries the preview filtered by opponent NTRP band (#563)', async () => {
    const user = userEvent.setup()
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben')], total: 1 },
      isLoading: false,
    })
    renderCard()
    await user.selectOptions(screen.getByLabelText('Filter by opponent NTRP band'), '3.5')
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      'K7Q2MX',
      { limit: 5, opponentBand: '3.5' },
      { query: { enabled: true } },
    )
  })

  it('shows a band-specific empty state once a band filter is applied (#563)', async () => {
    const user = userEvent.setup()
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: { items: [], total: 0 }, isLoading: false })
    renderCard()
    // With no filter yet, the empty state is the generic one.
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
    // Selecting a band and still finding nothing switches to the band-specific message.
    await user.selectOptions(screen.getByLabelText('Filter by opponent NTRP band'), '4.0')
    expect(screen.getByText('No matches vs that band.')).toBeInTheDocument()
    expect(screen.queryByText('No matches yet.')).not.toBeInTheDocument()
  })

  describe('band filter in the URL (#1056)', () => {
    it('opens filtered when the link names a band, with the select describing it', () => {
      useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
        data: { items: [match('a', 'Ben')], total: 1 },
        isLoading: false,
      })
      renderCard('/players/K7Q2MX?matches.band=3.5')
      // Reload-proof and shareable: the filtered request goes out on mount.
      expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
        'K7Q2MX',
        { limit: 5, opponentBand: '3.5' },
        { query: { enabled: true } },
      )
      expect(screen.getByLabelText('Filter by opponent NTRP band')).toHaveValue('3.5')
    })

    it('writes the chosen band under its namespace, by replace', async () => {
      const user = userEvent.setup()
      useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
        data: { items: [match('a', 'Ben')], total: 1 },
        isLoading: false,
      })
      renderCard()
      await user.selectOptions(screen.getByLabelText('Filter by opponent NTRP band'), '4.0')
      // Namespaced, so it cannot collide with the rating-history card beside it on the profile.
      expect(screen.getByTestId('url')).toHaveTextContent('?matches.band=4.0')
      expect(screen.getByTestId('nav-type')).toHaveTextContent('REPLACE')
    })

    it('drops the param again when the filter goes back to all bands', async () => {
      const user = userEvent.setup()
      useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
        data: { items: [match('a', 'Ben')], total: 1 },
        isLoading: false,
      })
      renderCard('/players/K7Q2MX?matches.band=4.0')
      await user.selectOptions(screen.getByLabelText('Filter by opponent NTRP band'), '')
      // A default is omitted rather than written blank, so an untouched card has a clean URL.
      expect(screen.getByTestId('url').textContent).toBe('')
    })

    it('says so and shows every band when the link names one that does not exist', () => {
      useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
        data: { items: [match('a', 'Ben')], total: 1 },
        isLoading: false,
      })
      renderCard('/players/K7Q2MX?matches.band=9.9')
      expect(screen.getByRole('alert')).toHaveTextContent(
        'This link had 1 unreadable view setting (matches.band)',
      )
      // The unfiltered preview still renders — a bad param must not empty the card.
      expect(screen.getByText('Ben')).toBeInTheDocument()
      expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
        'K7Q2MX',
        { limit: 5, opponentBand: undefined },
        { query: { enabled: true } },
      )
    })
  })

  it('omits the "View all" link when the preview already shows everything', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben')], total: 1 },
      isLoading: false,
    })
    renderCard()
    expect(screen.getByText('Ben')).toBeInTheDocument()
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument()
  })
})
