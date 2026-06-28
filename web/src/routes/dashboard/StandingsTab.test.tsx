import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { StandingsTab } from './StandingsTab'

const { useGetApiV1Standings, useGetApiV1UsersMe } = vi.hoisted(() => ({
  useGetApiV1Standings: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}))

vi.mock('@/api/generated/standings/standings', () => ({ useGetApiV1Standings }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))

const bands = [
  {
    band: '4.0',
    entries: [
      {
        rank: 1,
        userId: 'u1',
        displayName: 'Ana Cruz',
        publicCode: 'AAA111',
        sex: 'Female',
        age: 34,
      },
      {
        // No display name → falls back to the public code; no age → only sex shows.
        rank: 2,
        userId: 'u2',
        displayName: null,
        publicCode: 'BBB222',
        sex: 'Male',
        age: null,
      },
      {
        // Neither sex nor age → the meta line is omitted entirely.
        rank: 3,
        userId: 'u3',
        displayName: 'No Meta',
        publicCode: 'CCC333',
        sex: null,
        age: null,
      },
    ],
  },
  {
    band: '3.5',
    entries: [],
  },
]

describe('StandingsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersMe.mockReturnValue({ data: { id: 'u2' } })
  })

  it('shows a loading state while standings resolve', () => {
    useGetApiV1Standings.mockReturnValue({ data: undefined, isLoading: true })
    render(<StandingsTab />)
    expect(screen.getByText('Loading standings…')).toBeInTheDocument()
  })

  it('renders an interim description making clear it is rating-derived', () => {
    useGetApiV1Standings.mockReturnValue({ data: [], isLoading: false })
    render(<StandingsTab />)
    expect(screen.getByText(/Interim standings/i)).toBeInTheDocument()
    expect(screen.getByText(/points-based ranking will replace this/i)).toBeInTheDocument()
  })

  it('renders bands with ranks, the name/code fallback, and the sex·age detail', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    render(<StandingsTab />)

    // Both band labels are visible (including the empty one).
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.getByText('3.5')).toBeInTheDocument()

    // Ranks, name, and full sex·age meta for the first entry.
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('Ana Cruz')).toBeInTheDocument()
    expect(screen.getByText('Female · 34')).toBeInTheDocument()

    // Fallback to public code when displayName is null; meta omits the missing age.
    expect(screen.getByText('BBB222')).toBeInTheDocument()
    expect(screen.getByText('Male')).toBeInTheDocument()
    expect(screen.queryByText(/Male ·/)).not.toBeInTheDocument()
  })

  it('shows "No players yet." for an empty band while keeping it visible', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    render(<StandingsTab />)
    expect(screen.getByText('No players yet.')).toBeInTheDocument()
  })

  it('highlights only the current user’s own row', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    render(<StandingsTab />)

    // me.id is u2 → its row carries the marker; u1's does not.
    const myRow = screen.getByLabelText('Your standing')
    expect(within(myRow).getByText('BBB222')).toBeInTheDocument()
    expect(within(myRow).getByText('You')).toBeInTheDocument()

    expect(screen.getAllByLabelText('Your standing')).toHaveLength(1)
    const otherRow = screen.getByText('Ana Cruz').closest('li')
    expect(otherRow).not.toBeNull()
    expect(otherRow).not.toHaveAttribute('aria-label', 'Your standing')
    expect(within(otherRow as HTMLElement).queryByText('You')).not.toBeInTheDocument()
  })

  it('renders nothing when the current user is unknown (no row highlighted)', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    render(<StandingsTab />)
    expect(screen.queryByLabelText('Your standing')).not.toBeInTheDocument()
  })

  it('never shows a rating value', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    const { container } = render(<StandingsTab />)
    // No decimal rating like 4.2 / 4.200000 anywhere in the rendered output.
    // (Band labels like "4.0"/"3.5" are headings, not per-player rating values.)
    const rows = container.querySelectorAll('li')
    rows.forEach((row) => {
      expect(row.textContent ?? '').not.toMatch(/\d\.\d/)
    })
  })
})
