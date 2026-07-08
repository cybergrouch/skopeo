import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { PlayerMatchesPage } from './PlayerMatchesPage'
import type { PlayerMatchHistoryEntry } from '@/api/generated/model'

const { useGetApiV1PlayersCodeMatchHistory } = vi.hoisted(() => ({
  useGetApiV1PlayersCodeMatchHistory: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1PlayersCodeMatchHistory }))
// Make the search debounce a pass-through so typing flows straight to the query in tests.
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: string) => v }))
// PublicPageNav reads auth; stub it so Firebase never initializes.
vi.mock('@/components/PublicPageNav', () => ({ PublicPageNav: () => <nav>nav</nav> }))

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

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/players/K7Q2MX/matches']}>
      <Routes>
        <Route path="/players/:code/matches" element={<PlayerMatchesPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PlayerMatchesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({
      data: { items: [match('a', 'Ben'), match('b', 'Cara')], total: 45 },
      isLoading: false,
    })
  })

  it('renders a page of matches with the pager and requests the first page', () => {
    renderPage()
    expect(screen.getByText('vs Ben')).toBeInTheDocument()
    expect(screen.getByText('vs Cara')).toBeInTheDocument()
    expect(screen.getByText('Showing 1–20 of 45')).toBeInTheDocument()
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      'K7Q2MX',
      { limit: 20, offset: 0, search: undefined },
      { query: { enabled: true } },
    )
  })

  it('requests the next page by offset', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      'K7Q2MX',
      { limit: 20, offset: 20, search: undefined },
      { query: { enabled: true } },
    )
  })

  it('searches server-side and resets to the first page', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: 'Next' })) // move off page 0 first
    await user.type(screen.getByPlaceholderText('Search opponent…'), 'ben')
    expect(useGetApiV1PlayersCodeMatchHistory).toHaveBeenLastCalledWith(
      'K7Q2MX',
      { limit: 20, offset: 0, search: 'ben' },
      { query: { enabled: true } },
    )
  })

  it('shows a loading state', () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: undefined, isLoading: true })
    renderPage()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows a search-specific empty state', async () => {
    useGetApiV1PlayersCodeMatchHistory.mockReturnValue({ data: { items: [], total: 0 }, isLoading: false })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('Search opponent…'), 'zzz')
    expect(screen.getByText('No matches for that search.')).toBeInTheDocument()
  })
})
