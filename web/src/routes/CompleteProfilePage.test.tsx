import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { CompleteProfilePage } from './CompleteProfilePage'

const {
  useGetApiV1UsersMe,
  usePostApiV1Users,
  mutateAsync,
  invalidateQueries,
  navigateMock,
  authUser,
} = vi.hoisted(() => ({
  useGetApiV1UsersMe: vi.fn(),
  usePostApiV1Users: vi.fn(),
  mutateAsync: vi.fn(),
  invalidateQueries: vi.fn(),
  navigateMock: vi.fn(),
  authUser: { value: null as { displayName: string | null } | null },
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersMe,
  usePostApiV1Users,
  getGetApiV1UsersMeQueryKey: () => ['/api/v1/users/me'],
}))
vi.mock('@/auth/useAuth', () => ({
  useAuth: () => ({ user: authUser.value }),
}))
vi.mock('@tanstack/react-query', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-query')>()
  return { ...actual, useQueryClient: () => ({ invalidateQueries }) }
})
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => navigateMock }
})

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/complete-profile']}>
      <Routes>
        <Route path="/complete-profile" element={<CompleteProfilePage />} />
        <Route path="/dashboard" element={<div>DASHBOARD</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CompleteProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authUser.value = null
    usePostApiV1Users.mockReturnValue({ mutateAsync })
    invalidateQueries.mockResolvedValue(undefined)
  })

  it('shows a loading state while checking the profile', () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: true, isSuccess: false })
    renderPage()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('redirects to the dashboard if a profile already exists', () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isSuccess: true })
    renderPage()
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument()
  })

  it('prefills the display name from the provider and provisions on submit', async () => {
    authUser.value = { displayName: 'Roger F.' }
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isSuccess: false })
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderPage()

    const nameField = screen.getByLabelText('Display name')
    expect(nameField).toHaveValue('Roger F.') // prefilled from the provider
    await user.clear(nameField)
    await user.type(nameField, 'Roger Federer') // editable
    fireEvent.change(screen.getByLabelText('Date of birth'), {
      target: { value: '2000-01-01' },
    })
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    await user.selectOptions(screen.getByLabelText('NTRP self-rating'), '4.0')
    await user.click(screen.getByRole('button', { name: /save and continue/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: { displayName: 'Roger Federer', sex: 'Male', dateOfBirth: '2000-01-01', proposedRating: '4.0' },
      }),
    )
    expect(invalidateQueries).toHaveBeenCalled()
    expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('provisions with a null display name when left blank', async () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isSuccess: false })
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderPage()

    fireEvent.change(screen.getByLabelText('Date of birth'), {
      target: { value: '1995-05-05' },
    })
    await user.selectOptions(screen.getByLabelText('Sex'), 'Female')
    await user.selectOptions(screen.getByLabelText('NTRP self-rating'), '3.0')
    await user.click(screen.getByRole('button', { name: /save and continue/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: { displayName: null, sex: 'Female', dateOfBirth: '1995-05-05', proposedRating: '3.0' },
      }),
    )
  })

  it('shows an error and does not navigate when provisioning fails', async () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isSuccess: false })
    mutateAsync.mockRejectedValue(new Error('provision boom'))
    const user = userEvent.setup()
    renderPage()

    fireEvent.change(screen.getByLabelText('Date of birth'), {
      target: { value: '2000-01-01' },
    })
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    await user.selectOptions(screen.getByLabelText('NTRP self-rating'), '4.0')
    await user.click(screen.getByRole('button', { name: /save and continue/i }))

    expect(await screen.findByText('provision boom')).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('does not provision until a self-rating is chosen (#75)', async () => {
    useGetApiV1UsersMe.mockReturnValue({ isLoading: false, isSuccess: false })
    mutateAsync.mockResolvedValue({})
    const user = userEvent.setup()
    renderPage()

    fireEvent.change(screen.getByLabelText('Date of birth'), { target: { value: '2000-01-01' } })
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    // Self-rating left unset → the required field blocks submission.
    await user.click(screen.getByRole('button', { name: /save and continue/i }))

    expect(mutateAsync).not.toHaveBeenCalled()
  })
})
