import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { MergeAccountsSection } from './MergeAccountsSection'

const { usePostApiV1UsersIdMerge, mergeMutateAsync, toastSuccess, toastError, pickable } = vi.hoisted(() => ({
  usePostApiV1UsersIdMerge: vi.fn(),
  mergeMutateAsync: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
  pickable: {
    current: {} as Record<string, unknown>,
  },
}))

vi.mock('@/api/generated/users/users', () => ({ usePostApiV1UsersIdMerge }))
vi.mock('sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

// Stub the typeahead: render a button per label that selects a preset user for that role.
vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({ label, onSelect }: { label: string; onSelect: (u: unknown) => void }) => (
    <button type="button" onClick={() => onSelect(pickable.current[label])}>
      {`Pick ${label}`}
    </button>
  ),
}))

const linkedGoogle = { id: 'g1', publicCode: 'GGG111', displayName: 'Linked One', capabilities: [], linkStatus: 'GOOGLE' }
const linkedFacebook = { id: 'f1', publicCode: 'FFF111', displayName: 'Linked Two', capabilities: [], linkStatus: 'FACEBOOK' }
const placeholder = { id: 'p1', publicCode: 'PPP111', displayName: 'Ghost', capabilities: [], linkStatus: 'NONE' }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MergeAccountsSection />
    </QueryClientProvider>,
  )
}

describe('MergeAccountsSection (#643)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pickable.current = { 'Survivor account': linkedGoogle, 'Retired account': placeholder }
    usePostApiV1UsersIdMerge.mockReturnValue({ isPending: false, mutateAsync: mergeMutateAsync })
    mergeMutateAsync.mockResolvedValue({})
  })

  it('shows each chosen account’s derived link status', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByText('Google login')).toBeInTheDocument()
    expect(screen.getByText('No login (placeholder)')).toBeInTheDocument()
  })

  it('keeps the merge button disabled until survivor, retired, a note, and the confirmation are all set', async () => {
    const user = userEvent.setup()
    renderSection()
    const button = () => screen.getByRole('button', { name: 'Merge accounts' })

    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))
    expect(button()).toBeDisabled()

    await user.type(screen.getByLabelText('Verification note (required)'), 'same person, verified by phone')
    expect(button()).toBeDisabled()

    await user.click(screen.getByLabelText('I understand this merge is permanent and cannot be undone'))
    expect(button()).toBeEnabled()
  })

  it('merges with the survivor id in the path and the retired id + note in the body', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))
    await user.type(screen.getByLabelText('Verification note (required)'), '  matched ID  ')
    await user.click(screen.getByLabelText('I understand this merge is permanent and cannot be undone'))
    await user.click(screen.getByRole('button', { name: 'Merge accounts' }))

    expect(mergeMutateAsync).toHaveBeenCalledWith({
      id: 'g1',
      data: { retiredAccountId: 'p1', verificationNote: 'matched ID' },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Accounts merged.')
  })

  it('warns (without blocking) when both accounts are linked, since a login is discarded', async () => {
    pickable.current = { 'Survivor account': linkedGoogle, 'Retired account': linkedFacebook }
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByText(/current login will be discarded/i)).toBeInTheDocument()
  })

  it('warns when neither account has a login', async () => {
    pickable.current = { 'Survivor account': placeholder, 'Retired account': { ...placeholder, id: 'p2', publicCode: 'PPP222' } }
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByText(/remain a login-less placeholder/i)).toBeInTheDocument()
  })

  it('surfaces an error toast when the merge fails', async () => {
    mergeMutateAsync.mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))
    await user.type(screen.getByLabelText('Verification note (required)'), 'note')
    await user.click(screen.getByLabelText('I understand this merge is permanent and cannot be undone'))
    await user.click(screen.getByRole('button', { name: 'Merge accounts' }))

    expect(toastError).toHaveBeenCalledWith('Could not merge the accounts.', { duration: 8000 })
  })

  it('labels an email/password account’s login status', async () => {
    pickable.current = {
      'Survivor account': { ...linkedGoogle, id: 'pw1', publicCode: 'PWD111', linkStatus: 'PASSWORD' },
      'Retired account': placeholder,
    }
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))

    expect(screen.getByText('Email/password login')).toBeInTheDocument()
  })

  it('lets the admin change a chosen survivor or retired account', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))
    expect(screen.getAllByRole('button', { name: 'Change' })).toHaveLength(2)

    // Changing the survivor brings its search back (fires the reset handler)…
    await user.click(screen.getAllByRole('button', { name: 'Change' })[0])
    expect(screen.getByText('Pick Survivor account')).toBeInTheDocument()
    // …and changing the retired brings its search back too.
    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByText('Pick Retired account')).toBeInTheDocument()
  })

  it('still offers the survivor search (excluding the retired id) when the retired account is picked first', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByText('Retired (merged away)')).toBeInTheDocument()
    expect(screen.getByText('Pick Survivor account')).toBeInTheDocument()
  })

  it('recommends swapping when the retired account is linked but the survivor is not', async () => {
    pickable.current = { 'Survivor account': placeholder, 'Retired account': linkedGoogle }
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByText(/consider making the linked account the survivor/i)).toBeInTheDocument()
  })

  it('shows a merging label while the merge is pending', async () => {
    usePostApiV1UsersIdMerge.mockReturnValue({ isPending: true, mutateAsync: mergeMutateAsync })
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByText('Pick Survivor account'))
    await user.click(screen.getByText('Pick Retired account'))

    expect(screen.getByRole('button', { name: 'Merging…' })).toBeInTheDocument()
  })
})
