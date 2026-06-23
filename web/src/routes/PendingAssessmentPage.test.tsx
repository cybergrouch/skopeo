import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { PendingAssessmentPage } from './PendingAssessmentPage'

const {
  useGetApiV1UsersMe,
  useGetApiV1UsersUserIdRatings,
  signOut,
  navigateMock,
} = vi.hoisted(() => ({
  useGetApiV1UsersMe: vi.fn(),
  useGetApiV1UsersUserIdRatings: vi.fn(),
  signOut: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))
vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersUserIdRatings,
}))
vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({
    user: { displayName: 'Roger F.', email: 'roger@example.com' },
    signOut,
  }),
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

function renderPending() {
  return render(
    <MemoryRouter>
      <PendingAssessmentPage />
    </MemoryRouter>,
  )
}

describe('PendingAssessmentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersMe.mockReturnValue({ data: { id: 'u1' }, isLoading: false })
    useGetApiV1UsersUserIdRatings.mockReturnValue({ data: [], isLoading: false })
  })

  it('greets the user by display name', () => {
    renderPending()
    expect(screen.getByText('Welcome, Roger F.')).toBeInTheDocument()
  })

  it('shows the pending-assessment notice when there is no rating', () => {
    renderPending()
    expect(screen.getByText('Pending assessment')).toBeInTheDocument()
    expect(
      screen.getByText(/awaiting an initial rating/i),
    ).toBeInTheDocument()
  })

  it('lists ratings and hides the pending notice once rated', () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ system: 'NTRP', value: '4.000000', level: '4.0' }],
      isLoading: false,
    })
    renderPending()
    expect(screen.getByText('NTRP')).toBeInTheDocument()
    expect(screen.getByText('4.000000 · 4.0')).toBeInTheDocument()
    expect(screen.queryByText('Pending assessment')).not.toBeInTheDocument()
  })

  it('shows a loading state while the profile resolves', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined, isLoading: true })
    renderPending()
    expect(screen.getByText('Loading your profile…')).toBeInTheDocument()
    expect(screen.queryByText('Pending assessment')).not.toBeInTheDocument()
  })

  it('signs out and returns to /login', async () => {
    signOut.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderPending()

    await user.click(screen.getByRole('button', { name: /sign out/i }))

    await waitFor(() => expect(signOut).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith('/login', { replace: true })
  })

  it('only queries ratings once the user id is known', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined, isLoading: false })
    renderPending()
    expect(useGetApiV1UsersUserIdRatings).toHaveBeenCalledWith('', {
      query: { enabled: false },
    })
  })
})
