import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { FirebaseError } from 'firebase/app'
import { LoginPage } from './LoginPage'

const { signInWithEmail, signInWithGoogle, signInWithFacebook, navigateMock } =
  vi.hoisted(() => ({
    signInWithEmail: vi.fn(),
    signInWithGoogle: vi.fn(),
    signInWithFacebook: vi.fn(),
    navigateMock: vi.fn(),
  }))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ signInWithEmail, signInWithGoogle, signInWithFacebook }),
}))

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

function renderLogin() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('signs in and navigates to the default destination', async () => {
    signInWithEmail.mockResolvedValue({})
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /^sign in$/i }))

    await waitFor(() =>
      expect(signInWithEmail).toHaveBeenCalledWith(
        'roger@example.com',
        'secret123',
      ),
    )
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('shows an error and does not navigate on bad credentials', async () => {
    signInWithEmail.mockRejectedValue(
      new FirebaseError('auth/invalid-credential', 'raw'),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: /^sign in$/i }))

    expect(
      await screen.findByText('Incorrect email or password.'),
    ).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('signs in with Google', async () => {
    signInWithGoogle.mockResolvedValue({})
    const user = userEvent.setup()
    renderLogin()

    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    await waitFor(() => expect(signInWithGoogle).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('signs in with Facebook', async () => {
    signInWithFacebook.mockResolvedValue({})
    const user = userEvent.setup()
    renderLogin()

    await user.click(
      screen.getByRole('button', { name: /continue with facebook/i }),
    )

    await waitFor(() => expect(signInWithFacebook).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })
})
