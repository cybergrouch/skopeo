import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RoleGrantsSection } from './RoleGrantsSection'

const { useGetApiV1UsersUserIdCapabilities, grantMutate, revokeMutate, stubUser } =
  vi.hoisted(() => ({
    useGetApiV1UsersUserIdCapabilities: vi.fn(),
    grantMutate: vi.fn(),
    revokeMutate: vi.fn(),
    stubUser: { id: 'u1', displayName: 'Alice' as string | null },
  }))

vi.mock('@/api/generated/capabilities/capabilities', () => ({
  useGetApiV1UsersUserIdCapabilities,
  usePostApiV1UsersUserIdCapabilities: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      grantMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
  useDeleteApiV1UsersUserIdCapabilitiesCapability: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      revokeMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
  getGetApiV1UsersUserIdCapabilitiesQueryKey: () => ['caps'],
}))
vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({
    onSelect,
  }: {
    onSelect: (u: { id: string; displayName: string | null; capabilities: string[] }) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onSelect({
          id: stubUser.id,
          displayName: stubUser.displayName,
          capabilities: [],
        })
      }
    >
      pick user
    </button>
  ),
}))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RoleGrantsSection />
    </QueryClientProvider>,
  )
}

describe('RoleGrantsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stubUser.id = 'u1'
    stubUser.displayName = 'Alice'
    useGetApiV1UsersUserIdCapabilities.mockReturnValue({
      data: [{ capability: 'HOST', isActive: true }],
      isLoading: false,
    })
  })

  it('treats absent capability data as no roles (all grantable)', async () => {
    useGetApiV1UsersUserIdCapabilities.mockReturnValue({ data: undefined, isLoading: false })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))
    expect(screen.getAllByRole('button', { name: 'Grant' })).toHaveLength(2)
  })

  it('falls back to the id when the selected user has no name', async () => {
    stubUser.displayName = null
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))
    expect(screen.getByText('u1')).toBeInTheDocument()
  })

  it('starts with the user search', () => {
    renderSection()
    expect(screen.getByRole('button', { name: 'pick user' })).toBeInTheDocument()
  })

  it('shows a Revoke for an active role and a Grant for an inactive one', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))

    expect(screen.getByText('HOST')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Grant' })).toBeInTheDocument()
  })

  it('grants an inactive role', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))
    await user.click(screen.getByRole('button', { name: 'Grant' }))

    expect(grantMutate).toHaveBeenCalledWith({
      userId: 'u1',
      data: { capability: 'CLUB_OWNER' },
    })
  })

  it('revokes an active role', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))
    await user.click(screen.getByRole('button', { name: 'Revoke' }))

    expect(revokeMutate).toHaveBeenCalledWith({ userId: 'u1', capability: 'HOST' })
  })

  it('can change the selected user', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick user' }))
    expect(screen.getByText('Alice')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('button', { name: 'pick user' })).toBeInTheDocument()
  })
})
