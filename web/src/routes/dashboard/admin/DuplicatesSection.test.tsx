import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DuplicatesSection } from './DuplicatesSection'

const { useGetApiV1UsersIdDuplicates, markMutate, deleteMutate, state } = vi.hoisted(() => ({
  useGetApiV1UsersIdDuplicates: vi.fn(),
  markMutate: vi.fn(),
  deleteMutate: vi.fn(),
  state: { markFail: false },
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersIdDuplicates,
  getGetApiV1UsersIdDuplicatesQueryKey: () => ['dups'],
  usePostApiV1UsersIdDuplicates: (options?: {
    mutation?: { onSuccess?: () => void; onError?: () => void }
  }) => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      markMutate(vars)
      if (state.markFail) options?.mutation?.onError?.()
      else options?.mutation?.onSuccess?.()
    },
  }),
  useDeleteApiV1UsersIdDuplicate: (options?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => {
      deleteMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
}))

// Reduce the player picker to a button per label that selects a fixed user derived from the label.
vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({
    label,
    onSelect,
  }: {
    label: string
    onSelect: (u: { id: string; publicCode: string; displayName: string }) => void
  }) => (
    <button type="button" onClick={() => onSelect({ id: label, publicCode: label, displayName: label })}>
      pick:{label}
    </button>
  ),
}))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <DuplicatesSection />
    </QueryClientProvider>,
  )
}

const CANONICAL = 'Canonical (true) account'
const DUPLICATE = 'Add a duplicate to mark'

describe('DuplicatesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.markFail = false
    useGetApiV1UsersIdDuplicates.mockReturnValue({ data: [] })
  })

  it('marks the selected duplicates against the canonical', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))
    await user.click(screen.getByRole('button', { name: `pick:${DUPLICATE}` }))
    await user.click(screen.getByRole('button', { name: 'Mark as duplicates' }))

    await waitFor(() =>
      expect(markMutate).toHaveBeenCalledWith({
        id: CANONICAL,
        data: { duplicateIds: [DUPLICATE] },
      }),
    )
    expect(screen.getByText('Marked as duplicates.')).toBeInTheDocument()
  })

  it('shows an error when marking fails', async () => {
    state.markFail = true
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))
    await user.click(screen.getByRole('button', { name: `pick:${DUPLICATE}` }))
    await user.click(screen.getByRole('button', { name: 'Mark as duplicates' }))

    expect(
      await screen.findByText(/could not mark the selected profiles/i),
    ).toBeInTheDocument()
  })

  it('lists the canonical’s current duplicates and restores one', async () => {
    useGetApiV1UsersIdDuplicates.mockReturnValue({
      data: [{ id: 'd1', publicCode: 'D1CODE', displayName: 'Dupe One' }],
    })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))

    expect(screen.getByText('Dupe One')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Restore' }))
    expect(deleteMutate).toHaveBeenCalledWith({ id: 'd1' })
  })

  it('disables the mark button until a canonical and a duplicate are chosen', async () => {
    const user = userEvent.setup()
    renderSection()
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: `pick:${DUPLICATE}` }))
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeEnabled()
  })
})
