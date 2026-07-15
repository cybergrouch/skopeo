import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ClubPage } from './ClubPage'

const { useGetApiV1ClubsCodeCode, state } = vi.hoisted(() => ({
  useGetApiV1ClubsCodeCode: vi.fn(),
  state: { user: { uid: 'u1' } as { uid: string } | null },
}))
// PublicPageNav reads auth (#193); default to a logged-in user, overridden per test.
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: state.user }) }))
vi.mock('@/api/generated/clubs/clubs', () => ({ useGetApiV1ClubsCodeCode }))

const club = {
  publicCode: 'CLB001',
  name: 'Downtown TC',
  isActive: true,
  upcoming: [
    { publicCode: 'EVT001', name: 'Spring Open', startDate: '2026-05-01', endDate: '2026-05-03' },
  ],
  past: [
    { publicCode: 'EVT000', name: 'Winter Cup', startDate: '2026-01-01', endDate: '2026-01-03' },
  ],
}

function renderAt(code = 'CLB001') {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/clubs/${code}`]}>
        <Routes>
          <Route path="/clubs/:code" element={<ClubPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClubPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.user = { uid: 'u1' }
  })

  it('shows a loading state', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: undefined, isLoading: true })
    renderAt()
    expect(screen.getByText('Loading club…')).toBeInTheDocument()
  })

  it('shows an error state', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: undefined, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this club/i)).toBeInTheDocument()
  })

  it('renders the name, upcoming + past event links, and a share QR', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: club, isLoading: false })
    renderAt()

    expect(screen.getByText('Downtown TC')).toBeInTheDocument()
    expect(screen.getByText('CLB001')).toBeInTheDocument()

    // Upcoming event under its heading, linking to the public event page.
    const upcoming = screen.getByText('Upcoming events').parentElement as HTMLElement
    expect(within(upcoming).getByRole('link', { name: /Spring Open/ })).toHaveAttribute(
      'href',
      '/events/EVT001',
    )
    // Past event under its heading.
    const past = screen.getByText('Past events').parentElement as HTMLElement
    expect(within(past).getByRole('link', { name: /Winter Cup/ })).toHaveAttribute(
      'href',
      '/events/EVT000',
    )

    // Share card.
    expect(screen.getByText('Share this club')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('shows empty states when a club has no events', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({
      data: { ...club, upcoming: [], past: [] },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    expect(screen.getByText('No past events.')).toBeInTheDocument()
  })

  it('flags a soft-deleted club but still renders it (#325)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: { ...club, isActive: false }, isLoading: false })
    renderAt()
    expect(screen.getByText('Downtown TC')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/this club has been deleted/i)
  })

  it('shows no deleted flag for an active club (#325)', () => {
    useGetApiV1ClubsCodeCode.mockReturnValue({ data: { ...club, isActive: true }, isLoading: false })
    renderAt()
    expect(screen.queryByText(/this club has been deleted/i)).not.toBeInTheDocument()
  })
})
