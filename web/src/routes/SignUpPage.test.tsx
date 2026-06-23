import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { FirebaseError } from 'firebase/app'
import { SignUpPage } from './SignUpPage'

// Hoisted spies so the vi.mock factories (hoisted above imports) can close over them.
const { signUpWithEmail, signInWithGoogle, mutateAsync, navigateMock } =
  vi.hoisted(() => ({
    signUpWithEmail: vi.fn(),
    signInWithGoogle: vi.fn(),
    mutateAsync: vi.fn(),
    navigateMock: vi.fn(),
  }))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ signUpWithEmail, signInWithGoogle }),
}))

vi.mock('@/api/generated/users/users', () => ({
  usePostApiV1Users: () => ({ mutateAsync }),
}))

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

function renderSignUp() {
  return render(
    <MemoryRouter>
      <SignUpPage />
    </MemoryRouter>,
  )
}

describe('SignUpPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('signs up, provisions the profile, and navigates to /pending', async () => {
    signUpWithEmail.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() =>
      expect(signUpWithEmail).toHaveBeenCalledWith(
        'roger@example.com',
        'secret123',
      ),
    )
    expect(mutateAsync).toHaveBeenCalledWith({ data: { displayName: 'Roger F.' } })
    expect(navigateMock).toHaveBeenCalledWith('/pending', { replace: true })
  })

  it('shows a friendly error and does not navigate when sign-up fails', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/email-already-in-use', 'raw'),
    )
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Email'), 'taken@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText('An account with this email already exists.'),
    ).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('provisions with a null display name when signing up with Google', async () => {
    signInWithGoogle.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    await waitFor(() => expect(signInWithGoogle).toHaveBeenCalled())
    expect(mutateAsync).toHaveBeenCalledWith({ data: { displayName: null } })
    expect(navigateMock).toHaveBeenCalledWith('/pending', { replace: true })
  })
})
