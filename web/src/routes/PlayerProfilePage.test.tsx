import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { PlayerProfilePage } from './PlayerProfilePage'

const { useGetApiV1PlayersCode } = vi.hoisted(() => ({
  useGetApiV1PlayersCode: vi.fn(),
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1PlayersCode }))

function renderAt(code = 'ABC234') {
  return render(
    <MemoryRouter initialEntries={[`/players/${code}`]}>
      <Routes>
        <Route path="/players/:code" element={<PlayerProfilePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PlayerProfilePage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a loading state', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: true, isError: false })
    renderAt()
    expect(screen.getByText('Loading player…')).toBeInTheDocument()
  })

  it('shows an error message when the player cannot be loaded', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: false, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this player/i)).toBeInTheDocument()
  })

  it('renders the player with avatar and rating', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        publicCode: 'ABC234',
        displayName: 'Ana',
        photoUrl: 'https://example.com/a.jpg',
        rating: { value: '4.000000', level: '4.0' },
      },
    })
    const { container } = renderAt()
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('ABC234')).toBeInTheDocument()
    expect(screen.getByText('4.000000 · 4.0')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/a.jpg',
    )
  })

  it('shows a rating without a level', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { publicCode: 'ABC234', displayName: 'Ana', photoUrl: null, rating: { value: '4.000000', level: null } },
    })
    renderAt()
    expect(screen.getByText('4.000000')).toBeInTheDocument()
  })

  it('falls back to a placeholder name/avatar and a no-rating note', () => {
    useGetApiV1PlayersCode.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { publicCode: 'XYZ789', displayName: null, photoUrl: null, rating: undefined },
    })
    const { container } = renderAt()
    expect(screen.getByText('Player')).toBeInTheDocument()
    expect(screen.getByText('No rating yet.')).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('handles a missing code param without crashing', () => {
    useGetApiV1PlayersCode.mockReturnValue({ isLoading: true, isError: false })
    render(
      <MemoryRouter initialEntries={['/players']}>
        <Routes>
          <Route path="/players" element={<PlayerProfilePage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByText('Loading player…')).toBeInTheDocument()
  })
})
