import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MatchPage } from './MatchPage'

const { useGetApiV1MatchesCodeCode } = vi.hoisted(() => ({
  useGetApiV1MatchesCodeCode: vi.fn(),
}))
vi.mock('@/api/generated/matches/matches', () => ({ useGetApiV1MatchesCodeCode }))
// The page renders PublicPageNav (#193), which reads auth; default to anonymous here.
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: null }) }))

const match = {
  publicCode: 'MTCH01',
  matchFormat: 'SINGLES',
  matchType: 'OPEN_PLAY',
  matchDate: '2026-02-03',
  status: 'COMPLETED',
  team1: [{ displayName: 'Ana', publicCode: 'AAA111' }],
  team2: [{ displayName: 'Bob', publicCode: 'BBB222' }],
  winner: 'TEAM1',
  sets: [
    { setNumber: 1, team1Games: 6, team2Games: 4 },
    { setNumber: 2, team1Games: 6, team2Games: 2 },
  ],
  venue: 'Center Court',
}

function renderAt(code = 'MTCH01') {
  return render(
    <MemoryRouter initialEntries={[`/matches/${code}`]}>
      <Routes>
        <Route path="/matches/:code" element={<MatchPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MatchPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: undefined, isLoading: true })
    renderAt()
    expect(screen.getByText('Loading match…')).toBeInTheDocument()
  })

  it('shows an error state when the match cannot be loaded', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: undefined, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this match/i)).toBeInTheDocument()
  })

  it('renders the summary: code, players linking to profiles, winner badge, and score', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: match, isLoading: false })
    renderAt()

    expect(screen.getByText('MTCH01')).toBeInTheDocument()
    const anaLink = screen.getByRole('link', { name: 'Ana' })
    expect(anaLink).toHaveAttribute('href', '/players/AAA111')
    // The player name link wears the themed content-link style (#451).
    expect(anaLink).toHaveClass('content-link')
    expect(screen.getByRole('link', { name: 'Bob' })).toHaveAttribute('href', '/players/BBB222')
    expect(screen.getByText('Winner')).toBeInTheDocument() // exactly one side won
    expect(screen.getByText('6-4 6-2')).toBeInTheDocument()
    expect(screen.getByText(/Center Court/)).toBeInTheDocument()
    // The shareable QR card (#137) appears once the match loads.
    expect(screen.getByText('Share this match')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('flags a soft-deleted match but still renders it (#325)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, isActive: false },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('MTCH01')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/this match has been deleted/i)
  })

  it('handles doubles, name/code fallbacks, and a match with no venue', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        venue: null,
        team1: [
          { displayName: 'Ana', publicCode: 'AAA111' },
          { displayName: null, publicCode: 'CCC333' }, // name falls back to the code
        ],
        team2: [{ displayName: null, publicCode: null }], // both null → "Unknown", not a link
      },
      isLoading: false,
    })
    renderAt()

    // Multi-player side renders both, the second linking by its code.
    expect(screen.getByRole('link', { name: 'Ana' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveAttribute('href', '/players/CCC333')
    // A player with neither name nor code is plain text.
    expect(screen.getByText('Unknown')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Unknown' })).not.toBeInTheDocument()
    // No venue → the date line omits it.
    expect(screen.queryByText(/Center Court/)).not.toBeInTheDocument()
  })

  it('shows NTRP band rating changes for a non-rater viewer (precise rates withheld)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          {
            displayName: 'Ana',
            publicCode: 'AAA111',
            previousLevel: '4.0',
            newLevel: '4.5',
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
          {
            displayName: 'Bob',
            publicCode: 'BBB222',
            previousLevel: '4.0',
            newLevel: '3.5',
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
        ],
      },
      isLoading: false,
    })
    renderAt()

    expect(screen.getByText('Rating changes')).toBeInTheDocument()
    expect(screen.getByText('4.0 → 4.5')).toBeInTheDocument()
    expect(screen.getByText('4.0 → 3.5')).toBeInTheDocument()
    // No precise rates leak to a non-rater.
    expect(screen.queryByText(/→ 4\.\d{6}/)).not.toBeInTheDocument()
  })

  it('shows precise 6-dp rates and a signed delta for a rater/admin viewer', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          {
            displayName: 'Ana',
            publicCode: 'AAA111',
            previousLevel: '4.0',
            newLevel: '4.5',
            previousRating: '4.000000',
            newRating: '4.123456',
            ratingChange: '0.123456',
          },
          {
            displayName: 'Bob',
            publicCode: 'BBB222',
            previousLevel: '4.0',
            newLevel: '3.5',
            previousRating: '4.000000',
            newRating: '3.876544',
            ratingChange: '-0.123456',
          },
        ],
      },
      isLoading: false,
    })
    renderAt()

    expect(screen.getByText('4.000000 → 4.123456')).toBeInTheDocument()
    expect(screen.getByText('(+0.123456)')).toBeInTheDocument() // gain gets a + sign
    expect(screen.getByText('4.000000 → 3.876544')).toBeInTheDocument()
    expect(screen.getByText('(-0.123456)')).toBeInTheDocument() // loss keeps its - sign
    // The NTRP-band-only form is not shown when precise rates are present.
    expect(screen.queryByText('4.0 → 4.5')).not.toBeInTheDocument()
  })

  it('shows each player\'s current rating confidence beside the change, in both the band and rate views (#343)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          // Band-only view (non-rater): confidence appended beside the band move.
          {
            displayName: 'Ana',
            publicCode: 'AAA111',
            previousLevel: '4.0',
            newLevel: '4.5',
            previousRating: null,
            newRating: null,
            ratingChange: null,
            confidence: '0.4',
          },
          // Precise-rate view (rater): confidence appended beside the rate move.
          {
            displayName: 'Bob',
            publicCode: 'BBB222',
            previousLevel: '4.0',
            newLevel: '4.0',
            previousRating: '4.000000',
            newRating: '4.010000',
            ratingChange: '0.010000',
            confidence: '1',
          },
        ],
      },
      isLoading: false,
    })
    renderAt()

    expect(screen.getByText(/· 40%/)).toBeInTheDocument()
    expect(screen.getByText(/· 100%/)).toBeInTheDocument()
  })

  it('falls back to code/"Unknown" names, an em-dash band, and omits an absent delta', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        ratingChanges: [
          // No display name → label falls back to the public code, still a profile link.
          {
            displayName: null,
            publicCode: 'DDD444',
            previousLevel: null,
            newLevel: null,
            previousRating: null,
            newRating: null,
            ratingChange: null,
          },
          // Neither name nor code → "Unknown", plain text; precise rates present but no net change.
          {
            displayName: null,
            publicCode: null,
            previousLevel: '3.5',
            newLevel: '3.0',
            previousRating: '3.500000',
            newRating: '3.000000',
            ratingChange: null,
          },
        ],
      },
      isLoading: false,
    })
    renderAt()

    const codeLink = screen.getByRole('link', { name: 'DDD444' })
    expect(codeLink).toHaveAttribute('href', '/players/DDD444')
    // The rating-change name link wears the themed content-link style (#451).
    expect(codeLink).toHaveClass('content-link')
    expect(screen.getByText('— → —')).toBeInTheDocument() // both band levels missing
    expect(screen.getByText('Unknown')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Unknown' })).not.toBeInTheDocument()
    expect(screen.getByText('3.500000 → 3.000000')).toBeInTheDocument()
    // ratingChange null → no parenthesised delta is rendered.
    expect(screen.queryByText(/\(/)).not.toBeInTheDocument()
  })

  it('omits the rating-changes block entirely for an unrated match', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, ratingChanges: null },
      isLoading: false,
    })
    renderAt()
    expect(screen.queryByText('Rating changes')).not.toBeInTheDocument()
  })

  it('shows "Not yet played" and no winner badge before a result, and a player without a code is not a link', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        status: 'SCHEDULED',
        winner: 'NONE',
        sets: [],
        team2: [{ displayName: 'Guest', publicCode: null }],
      },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('Not yet played')).toBeInTheDocument()
    expect(screen.queryByText('Winner')).not.toBeInTheDocument()
    expect(screen.getByText('Guest')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Guest' })).not.toBeInTheDocument()
  })

  it('renders the head-to-head tally and prior meetings, each linking to its match page (#188)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        headToHead: {
          team1Wins: 1,
          team2Wins: 1,
          meetings: [
            {
              publicCode: 'PREV02',
              matchDate: '2026-02-01',
              status: 'COMPLETED',
              rated: true,
              matchFormat: 'DOUBLES',
              sets: [{ setNumber: 1, team1Games: 4, team2Games: 6 }],
              winnerPublicCode: 'BBB222', // Bob (team2) won
            },
            {
              publicCode: 'PREV01',
              matchDate: '2026-01-01',
              status: 'COMPLETED',
              rated: false,
              matchFormat: 'SINGLES',
              sets: [{ setNumber: 1, team1Games: 6, team2Games: 3 }],
              winnerPublicCode: 'AAA111', // Ana (team1) won
            },
          ],
        },
      },
      isLoading: false,
    })
    renderAt()

    expect(screen.getByText('Head-to-head')).toBeInTheDocument()
    expect(screen.getByText('1 – 1')).toBeInTheDocument()
    // Each meeting shows format · date · score · winner and links to its own public page (#285).
    expect(screen.getByText(/doubles · 2026-02-01 · 4-6 · Bob won/)).toBeInTheDocument()
    expect(screen.getByText(/singles · 2026-01-01 · 6-3 · Ana won/)).toBeInTheDocument()
    const links = screen.getAllByRole('link', { name: 'Public page (QR)' })
    expect(links.map((l) => l.getAttribute('href'))).toEqual([
      '/matches/PREV02',
      '/matches/PREV01',
    ])
  })

  it('hides the head-to-head section when the backend omits it (e.g. non-singles) (#366)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({ data: { ...match, headToHead: null }, isLoading: false })
    renderAt()
    expect(screen.queryByText('Head-to-head')).not.toBeInTheDocument()
  })

  it('shows head-to-head with the tally and a "No prior meetings" note for a first meeting (#366)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, headToHead: { team1Wins: 1, team2Wins: 0, meetings: [] } },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('Head-to-head')).toBeInTheDocument()
    expect(screen.getByText('1 – 0')).toBeInTheDocument()
    expect(screen.getByText('No prior meetings.')).toBeInTheDocument()
  })

  it('head-to-head copes with missing names, scores, and undecided/unknown winners (#188)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: {
        ...match,
        team1: [{ publicCode: 'AAA111' }], // no displayName → falls back to the code
        team2: [{}], // no name or code → "Unknown"
        headToHead: {
          team1Wins: 0,
          team2Wins: 0,
          meetings: [
            // Undecided + no sets: no score, no "won" suffix.
            { publicCode: 'PREVX', matchDate: '2026-02-01', status: 'COMPLETED', rated: false, matchFormat: 'SINGLES', sets: [], winnerPublicCode: null },
            // Winner code matching neither player → no resolvable name.
            {
              publicCode: 'PREVY',
              matchDate: '2026-01-01',
              status: 'COMPLETED',
              rated: true,
              matchFormat: 'SINGLES',
              sets: [{ setNumber: 1, team1Games: 6, team2Games: 0 }],
              winnerPublicCode: 'ZZZ999',
            },
          ],
        },
      },
      isLoading: false,
    })
    renderAt()

    expect(screen.getByText('Head-to-head')).toBeInTheDocument()
    expect(screen.getByText(/Prior meetings between AAA111 and Unknown/)).toBeInTheDocument()
    // Undecided meeting: format · date, no " · ... won".
    expect(screen.getByText('singles · 2026-02-01')).toBeInTheDocument()
    // Unknown winner code resolves to no name → score shown but no "won" suffix.
    expect(screen.getByText('singles · 2026-01-01 · 6-0')).toBeInTheDocument()
    expect(screen.queryByText(/won/)).not.toBeInTheDocument()
  })

  it('links to the owning event when the match belongs to one (#358)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, event: { publicCode: 'EVT001', name: 'Spring Open' } },
      isLoading: false,
    })
    renderAt()

    const link = screen.getByRole('link', { name: 'Spring Open' })
    expect(link).toHaveAttribute('href', '/events/EVT001')
    expect(screen.getByText(/Part of event:/)).toBeInTheDocument()
  })

  it('omits the event link for an eventless (open-play) match (#358)', () => {
    useGetApiV1MatchesCodeCode.mockReturnValue({
      data: { ...match, event: null },
      isLoading: false,
    })
    renderAt()

    expect(screen.queryByText(/Part of event:/)).not.toBeInTheDocument()
  })
})
