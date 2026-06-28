import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DuplicateCandidatesSection } from './DuplicateCandidatesSection'

const { useGetApiV1DuplicateCandidates, flagMutate, confirmMutate, dismissMutate, state } = vi.hoisted(
  () => ({
    useGetApiV1DuplicateCandidates: vi.fn(),
    flagMutate: vi.fn(),
    confirmMutate: vi.fn(),
    dismissMutate: vi.fn(),
    state: { flagFail: false },
  }),
)

vi.mock('@/api/generated/duplicates/duplicates', () => ({
  useGetApiV1DuplicateCandidates,
  getGetApiV1DuplicateCandidatesQueryKey: () => ['candidates'],
  usePostApiV1DuplicateCandidates: () => ({
    mutateAsync: async (vars: unknown) => {
      flagMutate(vars)
      if (state.flagFail) throw new Error('boom')
    },
  }),
  usePostApiV1DuplicateCandidatesIdConfirm: () => ({
    mutateAsync: async (vars: unknown) => confirmMutate(vars),
  }),
  useDeleteApiV1DuplicateCandidatesId: () => ({
    mutateAsync: async (vars: unknown) => dismissMutate(vars),
  }),
}))

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
      <DuplicateCandidatesSection />
    </QueryClientProvider>,
  )
}

const candidate = {
  id: 'c1',
  status: 'OPEN',
  signal: 'DUPLICATE_PHONE',
  detail: '+639170000000',
  flaggedAt: '2026-07-01T00:00:00',
  userA: { id: 'ua', publicCode: 'AAA111', displayName: 'Ana' },
  userB: { id: 'ub', publicCode: 'BBB222', displayName: null },
}

describe('DuplicateCandidatesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.flagFail = false
    useGetApiV1DuplicateCandidates.mockReturnValue({ data: { items: [], total: 0 } })
  })

  it('shows the empty state when there are no open candidates', () => {
    renderSection()
    expect(screen.getByText('No open candidates.')).toBeInTheDocument()
  })

  it('confirms a candidate keeping the chosen account', async () => {
    useGetApiV1DuplicateCandidates.mockReturnValue({ data: { items: [candidate], total: 1 } })
    const user = userEvent.setup()
    renderSection()
    // Signal + detail + the no-display-name fallback (BBB222) all render.
    expect(screen.getByText(/Shared phone \(\+639170000000\)/)).toBeInTheDocument()
    expect(screen.getByText('BBB222')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Keep Ana' }))
    await waitFor(() => expect(confirmMutate).toHaveBeenCalledWith({ id: 'c1', data: { canonicalId: 'ua' } }))
  })

  it('confirms keeping the second account, and renders a candidate without a detail', async () => {
    const noDetail = { ...candidate, detail: null }
    useGetApiV1DuplicateCandidates.mockReturnValue({ data: { items: [noDetail], total: 1 } })
    const user = userEvent.setup()
    renderSection()
    // Signal shows with no "(detail)" suffix.
    expect(screen.getByText(/Shared phone/)).toBeInTheDocument()
    expect(screen.queryByText(/\(\+639170000000\)/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Keep BBB222' }))
    await waitFor(() => expect(confirmMutate).toHaveBeenCalledWith({ id: 'c1', data: { canonicalId: 'ub' } }))
  })

  it('dismisses a candidate', async () => {
    useGetApiV1DuplicateCandidates.mockReturnValue({ data: { items: [candidate], total: 1 } })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(dismissMutate).toHaveBeenCalledWith({ id: 'c1' })
  })

  it('flags a pair manually with a reason', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick:First account' }))
    await user.click(screen.getByRole('button', { name: 'pick:Second account' }))
    await user.type(screen.getByLabelText('Reason (optional)'), 'same person')
    await user.click(screen.getByRole('button', { name: 'Flag as candidate' }))

    await waitFor(() =>
      expect(flagMutate).toHaveBeenCalledWith({
        data: { userAId: 'First account', userBId: 'Second account', reason: 'same person' },
      }),
    )
  })

  it('shows an error when manual flagging fails, and lets you change a pick', async () => {
    state.flagFail = true
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'pick:First account' }))
    await user.click(screen.getByRole('button', { name: 'pick:Second account' }))
    await user.click(screen.getByRole('button', { name: 'Flag as candidate' }))
    expect(await screen.findByText(/could not flag the pair/i)).toBeInTheDocument()

    // Both selections remain (the flag failed); each "Change" clears one.
    await user.click(screen.getAllByRole('button', { name: 'Change' })[0]) // clears the first account
    expect(screen.getByRole('button', { name: 'pick:First account' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change' })) // now only the second's remains
    expect(screen.getByRole('button', { name: 'pick:Second account' })).toBeInTheDocument()
  })

  it('handles an undefined candidates payload', () => {
    useGetApiV1DuplicateCandidates.mockReturnValue({ data: undefined })
    renderSection()
    expect(screen.getByText('No open candidates.')).toBeInTheDocument()
  })
})
