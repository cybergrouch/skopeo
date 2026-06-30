import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ManagePlayerSection } from './ManagePlayerSection'

const {
  useGetApiV1UsersId,
  usePatchApiV1UsersId,
  useGetApiV1UsersUserIdRatings,
  usePutApiV1UsersUserIdRatings,
  useGetApiV1UsersUserIdCapabilities,
  usePostApiV1UsersUserIdCapabilities,
  useDeleteApiV1UsersUserIdCapabilitiesCapability,
  patchMutate,
  putMutate,
  grantMutate,
  revokeMutate,
  picked,
} = vi.hoisted(() => ({
  useGetApiV1UsersId: vi.fn(),
  usePatchApiV1UsersId: vi.fn(),
  useGetApiV1UsersUserIdRatings: vi.fn(),
  usePutApiV1UsersUserIdRatings: vi.fn(),
  useGetApiV1UsersUserIdCapabilities: vi.fn(),
  usePostApiV1UsersUserIdCapabilities: vi.fn(),
  useDeleteApiV1UsersUserIdCapabilitiesCapability: vi.fn(),
  patchMutate: vi.fn(),
  putMutate: vi.fn(),
  grantMutate: vi.fn(),
  revokeMutate: vi.fn(),
  // The player the stub picker selects — overridable per test.
  picked: {
    current: {
      id: 'u1',
      publicCode: 'ABC234',
      displayName: 'Alice' as string | null,
      capabilities: [] as string[],
    },
  },
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersId,
  usePatchApiV1UsersId,
  getGetApiV1UsersIdQueryKey: (id: string) => ['users', id],
}))
vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersUserIdRatings,
  usePutApiV1UsersUserIdRatings,
  getGetApiV1UsersUserIdRatingsQueryKey: (id: string) => ['ratings', id],
  getGetApiV1UsersUserIdRatingHistoryQueryKey: (id: string) => ['rating-history', id],
}))
vi.mock('@/api/generated/capabilities/capabilities', () => ({
  useGetApiV1UsersUserIdCapabilities,
  usePostApiV1UsersUserIdCapabilities,
  useDeleteApiV1UsersUserIdCapabilitiesCapability,
  getGetApiV1UsersUserIdCapabilitiesQueryKey: (id: string) => ['capabilities', id],
}))
// Drive selection from a stub so the real picker (axios → firebase) never loads.
vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({ onSelect }: { onSelect: (u: unknown) => void }) => (
    <button type="button" onClick={() => onSelect(picked.current)}>
      pick Alice
    </button>
  ),
}))

type SuccessOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ManagePlayerSection />
    </QueryClientProvider>,
  )
}

async function selectAlice() {
  const user = userEvent.setup()
  renderSection()
  await user.click(screen.getByRole('button', { name: 'pick Alice' }))
  return user
}

describe('ManagePlayerSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    picked.current = { id: 'u1', publicCode: 'ABC234', displayName: 'Alice', capabilities: [] }
    useGetApiV1UsersId.mockReturnValue({
      data: { id: 'u1', sex: 'Male', dateOfBirth: '2000-01-15' },
      isLoading: false,
    })
    usePatchApiV1UsersId.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        patchMutate(vars)
        options.mutation.onSuccess()
      },
    }))
    useGetApiV1UsersUserIdRatings.mockReturnValue({
      data: [{ value: '4.000000', level: '4.0' }],
      isLoading: false,
    })
    usePutApiV1UsersUserIdRatings.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutateAsync: async (vars: unknown) => {
        putMutate(vars)
        options.mutation.onSuccess()
      },
    }))
    useGetApiV1UsersUserIdCapabilities.mockReturnValue({
      data: [{ capability: 'HOST', isActive: true }],
      isLoading: false,
    })
    usePostApiV1UsersUserIdCapabilities.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        grantMutate(vars)
        options.mutation.onSuccess()
      },
    }))
    useDeleteApiV1UsersUserIdCapabilitiesCapability.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        revokeMutate(vars)
        options.mutation.onSuccess()
      },
    }))
  })

  it('shows the search picker until a player is selected', () => {
    renderSection()
    expect(screen.getByRole('button', { name: 'pick Alice' })).toBeInTheDocument()
    expect(screen.queryByText('Profile')).not.toBeInTheDocument()
  })

  it('selecting a player reveals the manage blocks, and Change resets to search', async () => {
    const user = await selectAlice()
    expect(screen.getByText('Profile')).toBeInTheDocument()
    expect(screen.getByText('Rating')).toBeInTheDocument()
    expect(screen.getByText('Roles')).toBeInTheDocument()
    expect(screen.getByText(/Alice/)).toBeInTheDocument()
    expect(screen.getByText(/ABC234/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('button', { name: 'pick Alice' })).toBeInTheDocument()
  })

  it('prefills and saves the profile (sex + date of birth)', async () => {
    const user = await selectAlice()
    expect((screen.getByLabelText('Sex') as HTMLSelectElement).value).toBe('Male')
    expect((screen.getByLabelText('Date of birth') as HTMLInputElement).value).toBe('2000-01-15')

    await user.selectOptions(screen.getByLabelText('Sex'), 'Female')
    fireEvent.change(screen.getByLabelText('Date of birth'), {
      target: { value: '1999-12-31' },
    })
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(patchMutate).toHaveBeenCalledWith({
      id: 'u1',
      data: { sex: 'Female', dateOfBirth: '1999-12-31' },
    })
    expect(screen.getByText('Saved')).toBeInTheDocument()
  })

  it('clears sex and date of birth to null when blanked', async () => {
    useGetApiV1UsersId.mockReturnValue({
      data: { id: 'u1', sex: null, dateOfBirth: null },
      isLoading: false,
    })
    const user = await selectAlice()
    expect((screen.getByLabelText('Sex') as HTMLSelectElement).value).toBe('')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))
    expect(patchMutate).toHaveBeenCalledWith({
      id: 'u1',
      data: { sex: null, dateOfBirth: null },
    })
  })

  it('shows a loading state while the profile loads', async () => {
    useGetApiV1UsersId.mockReturnValue({ data: undefined, isLoading: true })
    await selectAlice()
    // Both the profile and (data-less) rating editors show a loading line.
    expect(screen.getAllByText('Loading…').length).toBeGreaterThanOrEqual(1)
  })

  it('treats a resolved-but-empty profile as still loading', async () => {
    useGetApiV1UsersId.mockReturnValue({ data: undefined, isLoading: false })
    await selectAlice()
    expect(screen.getByText('Profile').parentElement).toHaveTextContent('Loading…')
  })

  it('overrides the rating and reports success', async () => {
    const user = await selectAlice()
    expect((screen.getByLabelText('NTRP rating') as HTMLInputElement).value).toBe('4.000000')

    await user.clear(screen.getByLabelText('NTRP rating'))
    await user.type(screen.getByLabelText('NTRP rating'), '4.5')
    await user.click(screen.getByRole('button', { name: 'Override rating' }))

    await waitFor(() =>
      expect(putMutate).toHaveBeenCalledWith({ userId: 'u1', data: { value: '4.5' } }),
    )
    expect(screen.getByText('Saved')).toBeInTheDocument()
  })

  it('shows an error when the rating override fails', async () => {
    usePutApiV1UsersUserIdRatings.mockReturnValue({
      isPending: false,
      mutateAsync: vi.fn().mockRejectedValue(new Error('boom')),
    })
    const user = await selectAlice()
    await user.click(screen.getByRole('button', { name: 'Override rating' }))
    expect(await screen.findByText(/Could not set the rating/i)).toBeInTheDocument()
  })

  it('shows a loading state while ratings load', async () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({ data: undefined, isLoading: true })
    await selectAlice()
    expect(screen.getByText('Rating').parentElement).toHaveTextContent('Loading…')
  })

  it('defaults the rating field to empty when the player has no rating', async () => {
    useGetApiV1UsersUserIdRatings.mockReturnValue({ data: [], isLoading: false })
    await selectAlice()
    expect((screen.getByLabelText('NTRP rating') as HTMLInputElement).value).toBe('')
  })

  it('falls back to the id when there is no display name and no capability data', async () => {
    picked.current = { id: 'u1', publicCode: 'ABC234', displayName: null, capabilities: [] }
    useGetApiV1UsersUserIdCapabilities.mockReturnValue({ data: undefined, isLoading: false })
    await selectAlice()
    // Header shows the id (no display name); every grantable role offers Grant (no active capabilities).
    expect(screen.getByText(/u1/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^Grant/ })).toHaveLength(5) // incl. ADMINISTRATOR (#194)
  })

  it('grants and revokes roles', async () => {
    const user = await selectAlice()
    // HOST is active → Revoke; CLUB_OWNER inactive → Grant.
    await user.click(screen.getByRole('button', { name: 'Revoke HOST' }))
    expect(revokeMutate).toHaveBeenCalledWith({ userId: 'u1', capability: 'HOST' })

    await user.click(screen.getByRole('button', { name: 'Grant CLUB_OWNER' }))
    expect(grantMutate).toHaveBeenCalledWith({ userId: 'u1', data: { capability: 'CLUB_OWNER' } })

    // RATER is grantable too (#106).
    await user.click(screen.getByRole('button', { name: 'Grant RATER' }))
    expect(grantMutate).toHaveBeenCalledWith({ userId: 'u1', data: { capability: 'RATER' } })
  })

  it('gates an ADMINISTRATOR grant behind a confirm step (#194)', async () => {
    const user = await selectAlice()
    // First click only asks to confirm — it must not grant yet.
    await user.click(screen.getByRole('button', { name: 'Grant ADMINISTRATOR' }))
    expect(grantMutate).not.toHaveBeenCalled()
    expect(screen.getByText('Grant administrator access?')).toBeInTheDocument()

    // Cancel backs out without granting.
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(grantMutate).not.toHaveBeenCalled()

    // Confirming performs the grant.
    await user.click(screen.getByRole('button', { name: 'Grant ADMINISTRATOR' }))
    await user.click(screen.getByRole('button', { name: 'Confirm grant ADMINISTRATOR' }))
    expect(grantMutate).toHaveBeenCalledWith({ userId: 'u1', data: { capability: 'ADMINISTRATOR' } })
  })

  it('surfaces the backend reason when revoking ADMINISTRATOR is refused (#194)', async () => {
    // ADMINISTRATOR is active → Revoke; the server refuses (e.g. bootstrap admin).
    useGetApiV1UsersUserIdCapabilities.mockReturnValue({
      data: [{ capability: 'ADMINISTRATOR', isActive: true }],
      isLoading: false,
    })
    useDeleteApiV1UsersUserIdCapabilitiesCapability.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: () =>
        options.mutation.onError?.({
          response: { data: { message: 'Cannot revoke a bootstrap administrator' } },
        }),
    }))
    const user = await selectAlice()

    await user.click(screen.getByRole('button', { name: 'Revoke ADMINISTRATOR' }))
    await user.click(screen.getByRole('button', { name: 'Confirm revoke ADMINISTRATOR' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Cannot revoke a bootstrap administrator')
  })
})
