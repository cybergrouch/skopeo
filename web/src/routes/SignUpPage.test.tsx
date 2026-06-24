import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { FirebaseError } from 'firebase/app'
import { SignUpPage } from './SignUpPage'

const {
  signUpWithEmail,
  signInWithGoogle,
  signInWithFacebook,
  mutateAsync,
  navigateMock,
} = vi.hoisted(() => ({
  signUpWithEmail: vi.fn(),
  signInWithGoogle: vi.fn(),
  signInWithFacebook: vi.fn(),
  mutateAsync: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ signUpWithEmail, signInWithGoogle, signInWithFacebook }),
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

async function fillProfile(user: ReturnType<typeof userEvent.setup>) {
  fireEvent.change(screen.getByLabelText('Date of birth'), {
    target: { value: '2000-01-01' },
  })
  await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
}

describe('SignUpPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('signs up, provisions the profile, and navigates to /dashboard', async () => {
    signUpWithEmail.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() =>
      expect(signUpWithEmail).toHaveBeenCalledWith('roger@example.com', 'secret123'),
    )
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: 'Roger F.', sex: 'Male', dateOfBirth: '2000-01-01' },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('provisions with a null display name when the name is left blank', async () => {
    signUpWithEmail.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: { displayName: null, sex: 'Male', dateOfBirth: '2000-01-01' },
      }),
    )
  })

  it('shows a friendly error and does not navigate when sign-up fails', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/email-already-in-use', 'raw'),
    )
    const user = userEvent.setup()
    renderSignUp()

    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'taken@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText('An account with this email already exists.'),
    ).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('signs up with Google once the profile is filled', async () => {
    signInWithGoogle.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await fillProfile(user)
    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    await waitFor(() => expect(signInWithGoogle).toHaveBeenCalled())
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: null, sex: 'Male', dateOfBirth: '2000-01-01' },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('signs up with Facebook once the profile is filled', async () => {
    signInWithFacebook.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await fillProfile(user)
    await user.click(
      screen.getByRole('button', { name: /continue with facebook/i }),
    )

    await waitFor(() => expect(signInWithFacebook).toHaveBeenCalled())
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: null, sex: 'Male', dateOfBirth: '2000-01-01' },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('requires date of birth and sex before Facebook sign-up', async () => {
    const user = userEvent.setup()
    renderSignUp()

    await user.click(
      screen.getByRole('button', { name: /continue with facebook/i }),
    )

    expect(
      await screen.findByText(/Please enter your date of birth and sex/i),
    ).toBeInTheDocument()
    expect(signInWithFacebook).not.toHaveBeenCalled()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('requires date of birth and sex before Google sign-up', async () => {
    const user = userEvent.setup()
    renderSignUp()

    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    expect(
      await screen.findByText(/Please enter your date of birth and sex/i),
    ).toBeInTheDocument()
    expect(signInWithGoogle).not.toHaveBeenCalled()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('shows an error when Google sign-up fails', async () => {
    signInWithGoogle.mockRejectedValue(
      new FirebaseError('auth/popup-closed-by-user', 'raw'),
    )
    const user = userEvent.setup()
    renderSignUp()

    await fillProfile(user)
    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    expect(await screen.findByText('Sign-in was cancelled.')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
