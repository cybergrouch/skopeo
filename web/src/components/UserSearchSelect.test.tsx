import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserSearchSelect } from './UserSearchSelect'

const { useGetApiV1Users } = vi.hoisted(() => ({ useGetApiV1Users: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))
// Identity debounce so typing reflects immediately in tests.
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: string) => v }))

const alice = { id: 'u1', displayName: 'Alice', capabilities: [] }
const bob = { id: 'u2', displayName: 'Bob', capabilities: [] }

describe('UserSearchSelect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Users.mockReturnValue({ data: [alice, bob], isLoading: false })
  })

  it('shows nothing until the query is long enough', async () => {
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'a')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.queryByText('No matches.')).not.toBeInTheDocument()
  })

  it('lists results and emits the picked user, clearing the input', async () => {
    const onSelect = vi.fn()
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={onSelect} />)
    const input = screen.getByLabelText('Player 1')
    await user.type(input, 'al')

    await user.click(screen.getByRole('button', { name: 'Alice' }))

    expect(onSelect).toHaveBeenCalledWith(alice)
    expect((input as HTMLInputElement).value).toBe('')
  })

  it('hides excluded users', async () => {
    const user = userEvent.setup()
    render(
      <UserSearchSelect label="Player 1" excludeIds={['u2']} onSelect={vi.fn()} />,
    )
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.getByRole('button', { name: 'Alice' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Bob' })).not.toBeInTheDocument()
  })

  it('shows neither a list nor a note while loading with no data yet', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: true })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.queryByText('No matches.')).not.toBeInTheDocument()
  })

  it('falls back to the id when a result has no display name', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [{ id: 'u9', displayName: null, capabilities: [] }],
      isLoading: false,
    })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.getByRole('button', { name: 'u9' })).toBeInTheDocument()
  })

  it('shows a no-matches note when the search is empty', async () => {
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'zz')
    expect(screen.getByText('No matches.')).toBeInTheDocument()
  })
})
