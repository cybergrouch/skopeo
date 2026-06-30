import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EventPage } from './EventPage'

const { useGetApiV1EventsCodeCode, signupMutate, state } = vi.hoisted(() => ({
  useGetApiV1EventsCodeCode: vi.fn(),
  signupMutate: vi.fn(),
  state: { signupFail: false, signupPending: false },
}))
vi.mock('@/api/generated/events/events', () => ({
  useGetApiV1EventsCodeCode,
  getGetApiV1EventsCodeCodeQueryKey: (code: string) => ['event', code],
  usePostApiV1EventsCodeCodeSignup: (opts?: {
    mutation?: { onSuccess?: () => void; onError?: () => void }
  }) => ({
    isPending: state.signupPending,
    mutate: (vars: unknown) => {
      signupMutate(vars)
      if (state.signupFail) opts?.mutation?.onError?.()
      else opts?.mutation?.onSuccess?.()
    },
  }),
}))

const event = {
  publicCode: 'EVT001',
  name: 'Spring Open',
  startDate: '2026-03-01',
  endDate: '2026-03-03',
  participants: [
    { userId: 'u1', displayName: 'Ana', publicCode: 'AAA111' },
    { userId: 'abcdef120000', displayName: null, publicCode: null }, // both null → not a link
  ],
  matches: [
    {
      publicCode: 'MTCH01',
      matchFormat: 'SINGLES',
      matchType: 'OPEN_PLAY',
      matchDate: '2026-03-02',
      status: 'COMPLETED',
      team1: [{ displayName: 'Ana', publicCode: 'AAA111' }],
      team2: [{ displayName: 'Bob', publicCode: 'BBB222' }],
      winner: 'TEAM1',
      sets: [{ setNumber: 1, team1Games: 6, team2Games: 4 }],
    },
    {
      publicCode: 'MTCH02',
      matchFormat: 'SINGLES',
      matchType: 'OPEN_PLAY',
      matchDate: '2026-03-02',
      status: 'COMPLETED',
      team1: [{ displayName: 'Ana', publicCode: 'AAA111' }],
      team2: [{ displayName: 'Bob', publicCode: 'BBB222' }],
      winner: 'TEAM2',
      sets: [{ setNumber: 1, team1Games: 3, team2Games: 6 }],
    },
    {
      // No winner yet and no sets — exercises the "not played" branches.
      publicCode: 'MTCH03',
      matchFormat: 'SINGLES',
      matchType: 'OPEN_PLAY',
      matchDate: '2026-03-03',
      status: 'SCHEDULED',
      team1: [{ displayName: 'Ana', publicCode: 'AAA111' }],
      team2: [{ displayName: 'Bob', publicCode: 'BBB222' }],
      winner: 'NONE',
      sets: [],
    },
  ],
}

function renderAt(code = 'EVT001') {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/events/${code}`]}>
        <Routes>
          <Route path="/events/:code" element={<EventPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EventPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.signupFail = false
    state.signupPending = false
  })

  it('shows a loading state', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: undefined, isLoading: true })
    renderAt()
    expect(screen.getByText('Loading event…')).toBeInTheDocument()
  })

  it('shows an error state', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: undefined, isError: true })
    renderAt()
    expect(screen.getByText(/couldn’t find or load this event/i)).toBeInTheDocument()
  })

  it('renders details, participant + match links, and a share QR', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: event, isLoading: false })
    renderAt()

    expect(screen.getByText('Spring Open')).toBeInTheDocument()
    expect(screen.getByText('EVT001')).toBeInTheDocument()
    // Participant with a code links to their profile; the both-null one is plain text.
    expect(screen.getByRole('link', { name: 'Ana' })).toHaveAttribute('href', '/players/AAA111')
    expect(screen.getByText('abcdef12')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'abcdef12' })).not.toBeInTheDocument()
    // Each match row links to its public match page.
    const matchLinks = screen.getAllByRole('link', { name: /Ana vs Bob/ })
    expect(matchLinks).toHaveLength(3)
    expect(matchLinks[0]).toHaveAttribute('href', '/matches/MTCH01')
    // Share card.
    expect(screen.getByText('Share this event')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('shows empty states for an event with no participants or matches', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({
      data: { ...event, participants: [], matches: [] },
      isLoading: false,
    })
    renderAt()
    expect(screen.getByText('No participants yet.')).toBeInTheDocument()
    expect(screen.getByText('No matches yet.')).toBeInTheDocument()
  })

  it('offers Request to join and signs up when the viewer has no status (#201)', async () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: { ...event, viewerStatus: null }, isLoading: false })
    const user = userEvent.setup()
    renderAt()

    await user.click(screen.getByRole('button', { name: 'Request to join' }))
    expect(signupMutate).toHaveBeenCalledWith({ code: 'EVT001' })
  })

  it('shows the pending state instead of a join button once requested (#201)', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: { ...event, viewerStatus: 'PENDING' }, isLoading: false })
    renderAt()
    expect(screen.getByText(/pending the host’s approval/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request to join' })).not.toBeInTheDocument()
  })

  it('shows the approved and on-hold states (#201)', () => {
    useGetApiV1EventsCodeCode.mockReturnValue({ data: { ...event, viewerStatus: 'APPROVED' }, isLoading: false })
    const { unmount } = renderAt()
    expect(screen.getByText(/confirmed for this event/i)).toBeInTheDocument()
    unmount()

    useGetApiV1EventsCodeCode.mockReturnValue({ data: { ...event, viewerStatus: 'HOLD' }, isLoading: false })
    renderAt()
    expect(screen.getByText(/on hold/i)).toBeInTheDocument()
  })

  it('shows an error when signing up fails, and a busy label while in flight (#201)', () => {
    // In-flight: the button is disabled and reads "Requesting…".
    state.signupPending = true
    useGetApiV1EventsCodeCode.mockReturnValue({ data: { ...event, viewerStatus: null }, isLoading: false })
    const { unmount } = renderAt()
    expect(screen.getByRole('button', { name: 'Requesting…' })).toBeDisabled()
    unmount()

    // Failure: clicking surfaces the error message.
    state.signupPending = false
    state.signupFail = true
    renderAt()
    const user = userEvent.setup()
    return user.click(screen.getByRole('button', { name: 'Request to join' })).then(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Could not sign up/i)
    })
  })
})
