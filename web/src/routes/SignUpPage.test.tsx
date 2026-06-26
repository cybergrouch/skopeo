import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SignUpPage } from './SignUpPage'

const { signInWithGoogle, signInWithFacebook, mutateAsync, navigateMock } =
  vi.hoisted(() => ({
    signInWithGoogle: vi.fn(),
    signInWithFacebook: vi.fn(),
    mutateAsync: vi.fn(),
    navigateMock: vi.fn(),
  }))

vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ signInWithGoogle, signInWithFacebook }),
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

  it('no longer offers a manual email/password sign-up form', () => {
    renderSignUp()
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /create account/i }),
    ).not.toBeInTheDocument()
    // Invited members are pointed at their email link.
    expect(screen.getByText(/use the sign-in link in your email/i)).toBeInTheDocument()
  })

  it('signs up with Google once the profile is filled, including an optional self-rating', async () => {
    signInWithGoogle.mockResolvedValue({})
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderSignUp()

    await user.type(screen.getByLabelText('Display name (optional)'), 'Roger')
    await fillProfile(user)
    await user.selectOptions(
      screen.getByLabelText('NTRP self-rating (optional)'),
      '4.0',
    )
    await user.click(screen.getByRole('button', { name: /continue with google/i }))

    await waitFor(() => expect(signInWithGoogle).toHaveBeenCalled())
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { displayName: 'Roger', sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: '4.0' },
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

  it('requires date of birth and sex before OAuth sign-up', async () => {
    const user = userEvent.setup()
    renderSignUp()
    await user.click(screen.getByRole('button', { name: /continue with google/i }))
    expect(
      screen.getByText(/enter your date of birth and sex/i),
    ).toBeInTheDocument()
    expect(signInWithGoogle).not.toHaveBeenCalled()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('surfaces an error when OAuth sign-up fails', async () => {
    signInWithGoogle.mockRejectedValue(new Error('popup closed'))
    const user = userEvent.setup()
    renderSignUp()
    await fillProfile(user)
    await user.click(screen.getByRole('button', { name: /continue with google/i }))
    expect(await screen.findByText('popup closed')).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
