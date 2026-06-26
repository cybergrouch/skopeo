import { StrictMode } from 'react'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { InviteAcceptPage } from './InviteAcceptPage'

const {
  isSignInLink,
  completeSignInLink,
  setPassword,
  mutateAsync,
  navigateMock,
} = vi.hoisted(() => ({
  isSignInLink: vi.fn(),
  completeSignInLink: vi.fn(),
  setPassword: vi.fn(),
  mutateAsync: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ isSignInLink, completeSignInLink, setPassword }),
}))
vi.mock('@/api/generated/users/users', () => ({
  usePostApiV1Users: () => ({ mutateAsync }),
  getGetApiV1UsersMeQueryKey: () => ['me'],
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

function renderAt(path = '/invite?email=invitee@example.com') {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/invite" element={<InviteAcceptPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function fillAndFinish(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Display name'), 'Newbie')
  fireEvent.change(screen.getByLabelText('Date of birth'), {
    target: { value: '1995-05-05' },
  })
  await user.selectOptions(screen.getByLabelText('Sex'), 'Female')
  await user.type(screen.getByLabelText('Password'), 'secret123')
  await user.click(screen.getByRole('button', { name: /finish sign-up/i }))
}

describe('InviteAcceptPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the invalid state when the link is not a sign-in link', async () => {
    isSignInLink.mockReturnValue(false)
    renderAt()
    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument()
    expect(completeSignInLink).not.toHaveBeenCalled()
  })

  it('shows the invalid state when the email is missing from the link', async () => {
    isSignInLink.mockReturnValue(true)
    renderAt('/invite')
    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument()
    expect(completeSignInLink).not.toHaveBeenCalled()
  })

  it('completes email-link sign-in, then sets a password and provisions', async () => {
    isSignInLink.mockReturnValue(true)
    completeSignInLink.mockResolvedValue({})
    setPassword.mockResolvedValue(undefined)
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderAt()

    // The email-link is completed once with the email from the URL.
    await waitFor(() =>
      expect(completeSignInLink).toHaveBeenCalledWith(
        'invitee@example.com',
        expect.any(String),
      ),
    )
    expect(await screen.findByText('Finishing onboarding for invitee@example.com')).toBeInTheDocument()

    await fillAndFinish(user)

    await waitFor(() => expect(setPassword).toHaveBeenCalledWith('secret123'))
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: 'Newbie', sex: 'Female', dateOfBirth: '1995-05-05' },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('completes the single-use email-link exactly once under StrictMode', async () => {
    isSignInLink.mockReturnValue(true)
    completeSignInLink.mockResolvedValue({})
    render(
      <QueryClientProvider client={new QueryClient()}>
        <StrictMode>
          <MemoryRouter initialEntries={['/invite?email=invitee@example.com']}>
            <Routes>
              <Route path="/invite" element={<InviteAcceptPage />} />
            </Routes>
          </MemoryRouter>
        </StrictMode>
      </QueryClientProvider>,
    )
    // StrictMode double-invokes the effect; the ref guard keeps the call to once.
    await waitFor(() => expect(completeSignInLink).toHaveBeenCalledTimes(1))
  })

  it('shows the invalid state when completing the link fails', async () => {
    isSignInLink.mockReturnValue(true)
    completeSignInLink.mockRejectedValue(new Error('expired'))
    renderAt()
    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument()
  })

  it('surfaces an error when provisioning fails', async () => {
    isSignInLink.mockReturnValue(true)
    completeSignInLink.mockResolvedValue({})
    setPassword.mockResolvedValue(undefined)
    mutateAsync.mockRejectedValue(new Error('provision boom'))
    const user = userEvent.setup()
    renderAt()

    await screen.findByLabelText('Display name')
    await fillAndFinish(user)

    expect(await screen.findByText('provision boom')).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
