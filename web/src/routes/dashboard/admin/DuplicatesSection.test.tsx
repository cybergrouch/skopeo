import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DuplicatesSection } from './DuplicatesSection'

const { useGetApiV1UsersIdDuplicates, markMutate, deleteMutate, state } = vi.hoisted(() => ({
  useGetApiV1UsersIdDuplicates: vi.fn(),
  markMutate: vi.fn(),
  deleteMutate: vi.fn(),
  state: { markFail: false, pending: false },
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersIdDuplicates,
  getGetApiV1UsersIdDuplicatesQueryKey: () => ['dups'],
  usePostApiV1UsersIdDuplicates: () => ({
    isPending: state.pending,
    mutateAsync: async (vars: unknown) => {
      markMutate(vars)
      if (state.markFail) throw new Error('boom')
    },
  }),
  useDeleteApiV1UsersIdDuplicate: () => ({
    isPending: state.pending,
    mutateAsync: async (vars: unknown) => {
      deleteMutate(vars)
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

async function selectCanonicalAndDuplicate(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))
  await user.click(screen.getByRole('button', { name: `pick:${DUPLICATE}` }))
}

describe('DuplicatesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.markFail = false
    state.pending = false
    useGetApiV1UsersIdDuplicates.mockReturnValue({ data: [] })
  })

  it('only offers the canonical picker until one is chosen', async () => {
    const user = userEvent.setup()
    renderSection()
    expect(screen.getByRole('button', { name: `pick:${CANONICAL}` })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark as duplicates' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))
    // Now the form appears; the mark button is present but disabled (no duplicates yet).
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: `pick:${DUPLICATE}` }))
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeEnabled()
  })

  it('marks the selected duplicates against the canonical', async () => {
    const user = userEvent.setup()
    renderSection()
    await selectCanonicalAndDuplicate(user)
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
    await selectCanonicalAndDuplicate(user)
    await user.click(screen.getByRole('button', { name: 'Mark as duplicates' }))

    expect(await screen.findByText(/could not mark the selected profiles/i)).toBeInTheDocument()
  })

  it('reflects the pending state for marking and restoring', async () => {
    state.pending = true
    useGetApiV1UsersIdDuplicates.mockReturnValue({
      data: [{ id: 'd1', publicCode: 'D1CODE', displayName: 'Dupe' }],
    })
    const user = userEvent.setup()
    renderSection()
    await selectCanonicalAndDuplicate(user)

    expect(screen.getByRole('button', { name: 'Marking…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
  })

  it('changes the canonical and removes a chosen duplicate', async () => {
    const user = userEvent.setup()
    renderSection()
    await selectCanonicalAndDuplicate(user)

    await user.click(screen.getByRole('button', { name: 'Remove' }))
    expect(screen.getByRole('button', { name: 'Mark as duplicates' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('button', { name: `pick:${CANONICAL}` })).toBeInTheDocument()
  })

  it('lists the canonical’s current duplicates and restores one (no display name)', async () => {
    useGetApiV1UsersIdDuplicates.mockReturnValue({
      data: [{ id: 'd1', publicCode: 'D1CODE', displayName: null }],
    })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))

    expect(screen.getByText('D1CODE')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Restore' }))
    expect(deleteMutate).toHaveBeenCalledWith({ id: 'd1' })
  })

  it('shows no duplicates list when the query has no data', async () => {
    useGetApiV1UsersIdDuplicates.mockReturnValue({ data: undefined })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: `pick:${CANONICAL}` }))

    expect(
      screen.queryByText('Current duplicates of this account'),
    ).not.toBeInTheDocument()
  })
})
