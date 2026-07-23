import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DeletedAccountsSection } from './DeletedAccountsSection'

const { useGetApiV1UsersSearch, usePostApiV1UsersIdReactivate, reactivateMutate, items } = vi.hoisted(
  () => ({
    useGetApiV1UsersSearch: vi.fn(),
    usePostApiV1UsersIdReactivate: vi.fn(),
    reactivateMutate: vi.fn(),
    items: {
      current: [] as Array<{
        id: string
        publicCode: string
        displayName: string | null
        isDeleted: boolean
        isPlaceholder: boolean
      }>,
    },
  }),
)

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersSearch,
  usePostApiV1UsersIdReactivate,
  getGetApiV1UsersSearchQueryKey: () => ['users-search'],
}))
// Skip the debounce timer so the search runs synchronously in the test.
vi.mock('@/hooks/useDebouncedValue', () => ({
  useDebouncedValue: (value: string) => value,
}))

type SuccessOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <DeletedAccountsSection />
    </QueryClientProvider>,
  )
}

describe('DeletedAccountsSection (#518)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    items.current = [
      { id: 'd1', publicCode: 'DEL111', displayName: 'Gone', isDeleted: true, isPlaceholder: false },
      // A merged duplicate is inactive but NOT deleted — it must be filtered out of this view.
      { id: 'm1', publicCode: 'MRG222', displayName: 'Merged', isDeleted: false, isPlaceholder: false },
    ]
    useGetApiV1UsersSearch.mockImplementation(() => ({
      data: { items: items.current, total: items.current.length },
      isLoading: false,
    }))
    usePostApiV1UsersIdReactivate.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        reactivateMutate(vars)
        options.mutation.onSuccess()
      },
    }))
  })

  it('lists only genuinely deleted accounts (not merged duplicates) once a search is entered', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Search'), 'go')

    expect(screen.getByText('Gone')).toBeInTheDocument()
    expect(screen.getByText('Deleted')).toBeInTheDocument()
    // The merged duplicate is excluded.
    expect(screen.queryByText('Merged')).not.toBeInTheDocument()
  })

  it('shows an empty state when a search matches no deleted accounts', async () => {
    items.current = []
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Search'), 'zzz')

    expect(await screen.findByText('No deleted accounts match.')).toBeInTheDocument()
  })

  it('searches with includeInactive=true so deleted accounts are discoverable', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Search'), 'go')

    await waitFor(() =>
      expect(useGetApiV1UsersSearch).toHaveBeenCalledWith(
        expect.objectContaining({ includeInactive: true, q: 'go' }),
        expect.anything(),
      ),
    )
  })

  it('re-allows login for a deleted account via the reactivate endpoint', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Search'), 'go')

    await user.click(screen.getByRole('button', { name: 'Allow login for DEL111' }))
    expect(reactivateMutate).toHaveBeenCalledWith({ id: 'd1' })
  })

  it('surfaces an error when reactivation fails', async () => {
    usePostApiV1UsersIdReactivate.mockImplementation((options: SuccessOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.({}),
    }))
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Search'), 'go')

    await user.click(screen.getByRole('button', { name: 'Allow login for DEL111' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not re-allow login.')
  })
})
