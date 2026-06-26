import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { FirebaseError } from 'firebase/app'
import { SignUpPage } from './SignUpPage'

const {
  signUpWithEmail,
  signInWithEmail,
  signInWithGoogle,
  signInWithFacebook,
  mutateAsync,
  navigateMock,
} = vi.hoisted(() => ({
  signUpWithEmail: vi.fn(),
  signInWithEmail: vi.fn(),
  signInWithGoogle: vi.fn(),
  signInWithFacebook: vi.fn(),
  mutateAsync: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({
    signUpWithEmail,
    signInWithEmail,
    signInWithGoogle,
    signInWithFacebook,
  }),
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
      data: { displayName: 'Roger F.', sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: null },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('includes an optional NTRP self-rating when one is chosen', async () => {
    signUpWithEmail.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.selectOptions(
      screen.getByLabelText('NTRP self-rating (optional)'),
      '4.0',
    )
    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: {
          displayName: 'Roger F.',
          sex: 'Male',
          dateOfBirth: '2000-01-01',
          proposedRating: '4.0',
        },
      }),
    )
  })

  it('requires a display name on the manual form', () => {
    renderSignUp()
    // Manual sign-up must capture a display name (OAuth derives one from the provider).
    expect(screen.getByLabelText('Display name')).toBeRequired()
  })

  it('shows a friendly error and does not navigate on a generic sign-up failure', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/weak-password', 'raw'),
    )
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'roger@example.com')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText('Password should be at least 6 characters.'),
    ).toBeInTheDocument()
    expect(signInWithEmail).not.toHaveBeenCalled()
    expect(mutateAsync).not.toHaveBeenCalled()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('recovers an orphaned account: on email-already-in-use, signs in and provisions', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/email-already-in-use', 'raw'),
    )
    signInWithEmail.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'orphan@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() =>
      expect(signInWithEmail).toHaveBeenCalledWith('orphan@example.com', 'secret123'),
    )
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: 'Roger F.', sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: null },
    })
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('points an existing account at sign-in when the password is wrong', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/email-already-in-use', 'raw'),
    )
    signInWithEmail.mockRejectedValue(
      new FirebaseError('auth/wrong-password', 'raw'),
    )
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'taken@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrongpass')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText('This email is already registered. Try signing in instead.'),
    ).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('surfaces a provisioning failure during orphan recovery', async () => {
    signUpWithEmail.mockRejectedValue(
      new FirebaseError('auth/email-already-in-use', 'raw'),
    )
    signInWithEmail.mockResolvedValue({})
    mutateAsync.mockRejectedValue(new Error('provision boom'))
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name'), 'Roger F.')
    await fillProfile(user)
    await user.type(screen.getByLabelText('Email'), 'orphan@example.com')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('provision boom')).toBeInTheDocument()
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
      data: { displayName: null, sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: null },
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
      data: { displayName: null, sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: null },
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
