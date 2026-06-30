import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MatchPage } from './MatchPage'

const { useGetApiV1MatchesCodeCode } = vi.hoisted(() => ({
  useGetApiV1MatchesCodeCode: vi.fn(),
}))
vi.mock('@/api/generated/matches/matches', () => ({ useGetApiV1MatchesCodeCode }))

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
    expect(screen.getByRole('link', { name: 'Ana' })).toHaveAttribute('href', '/players/AAA111')
    expect(screen.getByRole('link', { name: 'Bob' })).toHaveAttribute('href', '/players/BBB222')
    expect(screen.getByText('Winner')).toBeInTheDocument() // exactly one side won
    expect(screen.getByText('6-4 6-2')).toBeInTheDocument()
    expect(screen.getByText(/Center Court/)).toBeInTheDocument()
    // The shareable QR card (#137) appears once the match loads.
    expect(screen.getByText('Share this match')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
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
})
