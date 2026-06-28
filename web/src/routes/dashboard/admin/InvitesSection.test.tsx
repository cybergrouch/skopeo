import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { InvitesSection } from './InvitesSection'

const {
  useGetApiV1Invites,
  createMutate,
  deleteMutate,
  sendSignInLink,
} = vi.hoisted(() => ({
  useGetApiV1Invites: vi.fn(),
  createMutate: vi.fn(),
  deleteMutate: vi.fn(),
  sendSignInLink: vi.fn(),
}))

vi.mock('@/api/generated/invites/invites', () => ({
  useGetApiV1Invites,
  getGetApiV1InvitesQueryKey: () => ['invites'],
  usePostApiV1Invites: () => ({ mutateAsync: createMutate }),
  useDeleteApiV1InvitesId: (options?: { mutation?: { onSuccess?: () => void } }) => ({
    isPending: false,
    mutate: (vars: unknown) => {
      deleteMutate(vars)
      options?.mutation?.onSuccess?.()
    },
  }),
}))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ sendSignInLink }) }))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <InvitesSection />
    </QueryClientProvider>,
  )
}

function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false }
}

const pending = {
  id: 'i1',
  email: 'a@x.dev',
  status: 'PENDING',
  expiresAt: '2026-07-01T00:00:00',
  createdAt: '2026-06-01T00:00:00',
}

describe('InvitesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Invites.mockReturnValue(page([]))
    createMutate.mockResolvedValue({})
    sendSignInLink.mockResolvedValue(undefined)
  })

  it('records an invite and sends the email-link', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Email'), 'new@x.dev')
    await user.click(screen.getByRole('button', { name: 'Send invite' }))

    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({ data: { email: 'new@x.dev' } }),
    )
    expect(sendSignInLink).toHaveBeenCalledWith('new@x.dev')
  })

  it('shows a friendly error when sending fails', async () => {
    createMutate.mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Email'), 'new@x.dev')
    await user.click(screen.getByRole('button', { name: 'Send invite' }))
    expect(await screen.findByText(/could not send the invite/i)).toBeInTheDocument()
  })

  it('shows the existing-account message and does not send when the API returns 409 (#132)', async () => {
    createMutate.mockRejectedValue({ response: { status: 409 } })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Email'), 'taken@x.dev')
    await user.click(screen.getByRole('button', { name: 'Send invite' }))
    expect(
      await screen.findByText(/an account already exists with this email/i),
    ).toBeInTheDocument()
    // The invite link is never sent when the address is already taken.
    expect(sendSignInLink).not.toHaveBeenCalled()
  })

  it('lists invites with their status, and revokes a pending one', async () => {
    useGetApiV1Invites.mockReturnValue(page([pending]))
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('a@x.dev')).toBeInTheDocument()
    expect(screen.getByText('PENDING')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Revoke' }))
    expect(deleteMutate).toHaveBeenCalledWith({ id: 'i1' })
  })

  it('resends an expired invite (no revoke offered)', async () => {
    useGetApiV1Invites.mockReturnValue(page([{ ...pending, status: 'EXPIRED' }]))
    const user = userEvent.setup()
    renderSection()

    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Resend' }))
    await waitFor(() =>
      expect(createMutate).toHaveBeenCalledWith({ data: { email: 'a@x.dev' } }),
    )
    expect(sendSignInLink).toHaveBeenCalledWith('a@x.dev')
  })

  it('shows an error when resending fails', async () => {
    useGetApiV1Invites.mockReturnValue(page([{ ...pending, status: 'EXPIRED' }]))
    createMutate.mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Resend' }))
    expect(await screen.findByText(/could not resend the invite/i)).toBeInTheDocument()
  })

  it('does not offer resend/revoke on an accepted invite', () => {
    useGetApiV1Invites.mockReturnValue(page([{ ...pending, status: 'ACCEPTED' }]))
    renderSection()
    expect(screen.getByText('ACCEPTED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resend' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })

  it('paginates when invites exceed a page', async () => {
    useGetApiV1Invites.mockReturnValue(page([pending], 45))
    const user = userEvent.setup()
    renderSection()
    expect(screen.getByText('Page 1 of 3 · 45 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2 of 3 · 45 total')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByText('Page 1 of 3 · 45 total')).toBeInTheDocument()
  })

  it('shows loading and empty states', async () => {
    useGetApiV1Invites.mockReturnValue({ data: undefined, isLoading: true })
    const { rerender } = renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    useGetApiV1Invites.mockReturnValue(page([]))
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <InvitesSection />
      </QueryClientProvider>,
    )
    // The default (actionable) filter shows a filter-scoped empty message…
    expect(screen.getByText('No invites match this filter.')).toBeInTheDocument()

    // …and only the unfiltered "All" view says there are none at all.
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'All' }))
    expect(screen.getByText('No invites yet.')).toBeInTheDocument()
  })

  it('defaults to the actionable filter and switches the status query', async () => {
    useGetApiV1Invites.mockReturnValue(page([pending]))
    const user = userEvent.setup()
    renderSection()

    expect(useGetApiV1Invites).toHaveBeenLastCalledWith({
      limit: 20,
      offset: 0,
      status: 'PENDING',
    })
    expect(screen.getByRole('button', { name: 'Actionable' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await user.click(screen.getByRole('button', { name: 'Accepted' }))
    expect(useGetApiV1Invites).toHaveBeenLastCalledWith({
      limit: 20,
      offset: 0,
      status: 'ACCEPTED',
    })

    await user.click(screen.getByRole('button', { name: 'All' }))
    expect(useGetApiV1Invites).toHaveBeenLastCalledWith({
      limit: 20,
      offset: 0,
      status: undefined,
    })
  })

  it('resets to the first page when the filter changes', async () => {
    useGetApiV1Invites.mockReturnValue(page([pending], 45))
    const user = userEvent.setup()
    renderSection()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2 of 3 · 45 total')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Revoked' }))
    expect(useGetApiV1Invites).toHaveBeenLastCalledWith({
      limit: 20,
      offset: 0,
      status: 'REVOKED',
    })
  })
})
