import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ProfileFieldsForm } from './ProfileFieldsForm'

const { useGetApiV1UsersId, usePatchApiV1UsersId, usePostApiV1UsersUserIdNames, patchMutate, nameMutate } =
  vi.hoisted(() => ({
    useGetApiV1UsersId: vi.fn(),
    usePatchApiV1UsersId: vi.fn(),
    usePostApiV1UsersUserIdNames: vi.fn(),
    patchMutate: vi.fn(),
    nameMutate: vi.fn(),
  }))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersId,
  usePatchApiV1UsersId,
  getGetApiV1UsersIdQueryKey: (id: string) => ['users', id],
  getGetApiV1UsersMeQueryKey: () => ['me'],
}))
vi.mock('@/api/generated/names/names', () => ({
  usePostApiV1UsersUserIdNames,
  getGetApiV1UsersUserIdNamesQueryKey: (id: string) => ['names', id],
}))

function renderForm() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ProfileFieldsForm userId="u1" />
    </QueryClientProvider>,
  )
}

describe('ProfileFieldsForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersId.mockReturnValue({
      data: {
        id: 'u1',
        sex: 'Male',
        dateOfBirth: '2000-01-15',
        names: [
          { type: 'DISPLAY', value: 'Alice', isActive: true },
          { type: 'FIRST', value: 'Al', isActive: true },
          { type: 'LAST', value: 'Ice', isActive: true },
        ],
      },
      isLoading: false,
    })
    usePatchApiV1UsersId.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => patchMutate(vars),
    })
    usePostApiV1UsersUserIdNames.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => nameMutate(vars),
    })
  })

  it('prefills every field from the current values', () => {
    renderForm()
    expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Alice')
    expect((screen.getByLabelText('First name') as HTMLInputElement).value).toBe('Al')
    expect((screen.getByLabelText('Last name') as HTMLInputElement).value).toBe('Ice')
    expect((screen.getByLabelText('Sex') as HTMLSelectElement).value).toBe('Male')
    expect((screen.getByLabelText('Date of birth') as HTMLInputElement).value).toBe('2000-01-15')
  })

  it('shows a loading state until the user resolves', () => {
    useGetApiV1UsersId.mockReturnValue({ data: undefined, isLoading: true })
    renderForm()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('posts a new DISPLAY name when it changes, without touching demographics', async () => {
    const user = userEvent.setup()
    renderForm()
    await user.clear(screen.getByLabelText('Display name'))
    await user.type(screen.getByLabelText('Display name'), 'Alice B.')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    await waitFor(() => expect(nameMutate).toHaveBeenCalledWith({ userId: 'u1', data: { type: 'DISPLAY', value: 'Alice B.' } }))
    expect(patchMutate).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
  })

  it('updates first name (private) via the names API on change', async () => {
    const user = userEvent.setup()
    renderForm()
    await user.clear(screen.getByLabelText('First name'))
    await user.type(screen.getByLabelText('First name'), 'Alyce')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    await waitFor(() => expect(nameMutate).toHaveBeenCalledWith({ userId: 'u1', data: { type: 'FIRST', value: 'Alyce' } }))
    expect(patchMutate).not.toHaveBeenCalled()
  })

  it('patches sex and date of birth when they change', async () => {
    const user = userEvent.setup()
    renderForm()
    await user.selectOptions(screen.getByLabelText('Sex'), 'Female')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    await waitFor(() =>
      expect(patchMutate).toHaveBeenCalledWith({ id: 'u1', data: { sex: 'Female', dateOfBirth: '2000-01-15' } }),
    )
    expect(nameMutate).not.toHaveBeenCalled() // names unchanged
  })

  it('requires a non-blank display name', async () => {
    const user = userEvent.setup()
    renderForm()
    await user.clear(screen.getByLabelText('Display name'))
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Display name is required.')
    expect(nameMutate).not.toHaveBeenCalled()
    expect(patchMutate).not.toHaveBeenCalled()
  })

  it('updates last name and clears sex + date of birth to null', async () => {
    const user = userEvent.setup()
    renderForm()
    await user.clear(screen.getByLabelText('Last name'))
    await user.type(screen.getByLabelText('Last name'), 'Brown')
    await user.selectOptions(screen.getByLabelText('Sex'), '') // clear sex
    fireEvent.change(screen.getByLabelText('Date of birth'), { target: { value: '' } }) // clear DOB
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    await waitFor(() => expect(nameMutate).toHaveBeenCalledWith({ userId: 'u1', data: { type: 'LAST', value: 'Brown' } }))
    // Both demographics changed → one PATCH with the cleared values as null.
    expect(patchMutate).toHaveBeenCalledWith({ id: 'u1', data: { sex: null, dateOfBirth: null } })
  })

  it('surfaces an error when a save fails', async () => {
    usePatchApiV1UsersId.mockReturnValue({
      isPending: false,
      mutateAsync: async () => {
        throw new Error('boom')
      },
    })
    const user = userEvent.setup()
    renderForm()
    await user.selectOptions(screen.getByLabelText('Sex'), 'Female') // triggers the PATCH that throws
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Could not save the profile. Check the values and try again.'),
    )
  })

  it('prefills empty fields for a user with no names or demographics', () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: 'u1', sex: null, dateOfBirth: null, names: [] },
      isLoading: false,
    })
    renderForm()
    expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('First name') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Last name') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Sex') as HTMLSelectElement).value).toBe('')
    expect((screen.getByLabelText('Date of birth') as HTMLInputElement).value).toBe('')
  })

  it('disables the button and shows Saving… while a save is in flight', () => {
    usePatchApiV1UsersId.mockReturnValue({ isPending: true, mutateAsync: async (vars: unknown) => patchMutate(vars) })
    renderForm()
    const button = screen.getByRole('button', { name: 'Saving…' })
    expect(button).toBeDisabled()
  })
})
