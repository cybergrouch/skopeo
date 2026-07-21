import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MatchHistoryRow } from './MatchHistoryRow'
import type { PlayerMatchHistoryEntry } from '@/api/generated/model'

function renderRow(match: PlayerMatchHistoryEntry) {
  return render(
    <MemoryRouter>
      <ul>
        <MatchHistoryRow match={match} />
      </ul>
    </MemoryRouter>,
  )
}

const base: PlayerMatchHistoryEntry = {
  matchId: 'm1',
  publicCode: 'MATCH1',
  matchDate: '2026-01-01',
  status: 'COMPLETED',
  rated: true,
  result: 'WIN',
  setScores: ['6-4', '6-3'],
  partners: [],
  opponents: [{ publicCode: 'BEN123', displayName: 'Ben', photoUrl: 'https://example.com/ben.jpg', levelAtMatch: '3.5' }],
  playerLevelAtMatch: '4.0',
}

describe('MatchHistoryRow', () => {
  it('renders a rated match with opponent photo, result, scores and at-the-time bands', () => {
    const { container } = renderRow(base)
    expect(container.textContent).toContain('vs Ben')
    expect(screen.getByText('Rated')).toBeInTheDocument()
    expect(screen.getByText(/2026-01-01 · WIN · 6-4 6-3/)).toBeInTheDocument()
    expect(screen.getByText(/NTRP 4.0 vs 3.5 \(at the time\)/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Public page (QR)' })).toHaveAttribute('href', '/matches/MATCH1')
    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/ben.jpg')
  })

  it("appends each player's current rating confidence beside their at-the-time band (#343)", () => {
    renderRow({
      ...base,
      playerConfidence: '1',
      opponents: [
        { publicCode: 'BEN123', displayName: 'Ben', photoUrl: null, levelAtMatch: '3.5', confidence: '0.2' },
      ],
    })
    expect(screen.getByText(/NTRP 4.0 · 100% vs 3.5 · 20% \(at the time\)/)).toBeInTheDocument()
  })

  it('renders a scheduled match with initials, no result and no bands', () => {
    const { container } = renderRow({
      ...base,
      matchId: 'm2',
      status: 'SCHEDULED',
      rated: false,
      result: null,
      setScores: [],
      opponents: [{ publicCode: 'BEN123', displayName: 'Ben', photoUrl: null, levelAtMatch: null }],
      playerLevelAtMatch: null,
    })
    expect(screen.getByText('Scheduled')).toBeInTheDocument()
    expect(screen.getByText('2026-01-01')).toBeInTheDocument()
    expect(screen.queryByText(/\(at the time\)/)).not.toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('B')).toBeInTheDocument()
  })

  it('labels a completed-but-unrated match as awaiting rating, with a placeholder opponent', () => {
    const { container } = renderRow({
      ...base,
      rated: false,
      result: 'LOSS',
      setScores: ['4-6'],
      opponents: [],
      playerLevelAtMatch: null,
    })
    expect(screen.getByText('Awaiting rating')).toBeInTheDocument()
    expect(container.textContent).toContain('vs Player')
    expect(screen.getByText(/2026-01-01 · LOSS · 4-6/)).toBeInTheDocument()
  })

  it('renders a dash for a missing band and "Player" for a nameless opponent', () => {
    const { container } = renderRow({
      ...base,
      setScores: ['6-0', '6-0'],
      opponents: [{ publicCode: 'BEN123', displayName: null, photoUrl: null, levelAtMatch: null }],
      playerLevelAtMatch: null,
    })
    expect(screen.getByText(/NTRP — vs — \(at the time\)/)).toBeInTheDocument()
    expect(container.textContent).toContain('vs Player')
  })

  it('renders a doubles match with the partner and both opponents and their bands', () => {
    const { container } = renderRow({
      ...base,
      setScores: ['6-3'],
      partners: [{ publicCode: 'BEA123', displayName: 'Bea', photoUrl: null, levelAtMatch: '3.5' }],
      opponents: [
        { publicCode: 'CYX123', displayName: 'Cy', photoUrl: null, levelAtMatch: '3.0' },
        { publicCode: 'DEB123', displayName: 'Deb', photoUrl: null, levelAtMatch: '3.5' },
      ],
    })
    expect(container.textContent).toContain('with Bea · vs Cy, Deb')
    expect(screen.getByText(/NTRP 4.0 vs 3.0, 3.5 \(at the time\)/)).toBeInTheDocument()
  })

  it('tags a placeholder opponent as Unclaimed and leaves a normal one untagged (#505)', () => {
    renderRow({
      ...base,
      opponents: [
        { publicCode: 'DUMMY1', displayName: 'Dummy', photoUrl: null, levelAtMatch: '3.5', isPlaceholder: true },
        { publicCode: 'REAL01', displayName: 'Real', photoUrl: null, levelAtMatch: '3.5', isPlaceholder: false },
      ],
    })
    // Exactly one "Unclaimed" tag — beside the placeholder opponent only, not the normal one.
    expect(screen.getAllByText('Unclaimed')).toHaveLength(1)
  })
})
