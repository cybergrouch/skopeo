import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserSearchSelect } from './UserSearchSelect'

const { useGetApiV1Users } = vi.hoisted(() => ({ useGetApiV1Users: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))
// Identity debounce so typing reflects immediately in tests.
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: string) => v }))

const alice = { id: 'u1', publicCode: 'ABC234', displayName: 'Alice', capabilities: [] }
const bob = { id: 'u2', publicCode: 'XYZ789', displayName: 'Bob', capabilities: [] }

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

    await user.click(screen.getByRole('button', { name: /Alice/ }))

    expect(onSelect).toHaveBeenCalledWith(alice)
    expect((input as HTMLInputElement).value).toBe('')
  })

  it('hides excluded users', async () => {
    const user = userEvent.setup()
    render(
      <UserSearchSelect label="Player 1" excludeIds={['u2']} onSelect={vi.fn()} />,
    )
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.getByRole('button', { name: /Alice/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Bob/ })).not.toBeInTheDocument()
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
      data: [{ id: 'u9', publicCode: 'QRS567', displayName: null, capabilities: [] }],
      isLoading: false,
    })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.getByRole('button', { name: /u9/ })).toBeInTheDocument()
  })

  it('shows a no-matches note when the search is empty', async () => {
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'zz')
    expect(screen.getByText('No matches.')).toBeInTheDocument()
  })

  it('shows sex, age, and NTRP band in suggestions, with the value as a band fallback', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        {
          id: 'u1',
          publicCode: 'ABC234',
          displayName: 'Alice',
          sex: 'Female',
          age: 34,
          rating: { value: '4.000000', level: '4.0' },
          capabilities: [],
        },
        {
          id: 'u2',
          publicCode: 'XYZ789',
          displayName: 'Bob',
          sex: 'Male',
          age: 41,
          rating: { value: '8.500000', level: null },
          capabilities: [],
        },
      ],
      isLoading: false,
    })
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(screen.getByText('Female · 34 · NTRP 4.0')).toBeInTheDocument()
    expect(screen.getByText('Male · 41 · NTRP 8.500000')).toBeInTheDocument()
  })

  it('sends a single unified term so partial codes and names both search incrementally (#86)', async () => {
    const user = userEvent.setup()
    render(<UserSearchSelect label="Player 1" onSelect={vi.fn()} />)

    // A partial code searches immediately, without waiting for the full 6-char code.
    await user.type(screen.getByLabelText('Player 1'), 'abc')
    expect(useGetApiV1Users).toHaveBeenCalledWith({ q: 'abc' }, expect.anything())

    // A name fragment uses the same unified term.
    await user.clear(screen.getByLabelText('Player 1'))
    await user.type(screen.getByLabelText('Player 1'), 'ali')
    expect(useGetApiV1Users).toHaveBeenCalledWith({ q: 'ali' }, expect.anything())
  })

  it('forwards optional sex/age/rating filters into the query params (#111)', async () => {
    const user = userEvent.setup()
    render(
      <UserSearchSelect
        label="Player 1"
        filters={{ sex: 'Female', age: '[18,30]', rating: '[3.0,4.0]' }}
        onSelect={vi.fn()}
      />,
    )
    await user.type(screen.getByLabelText('Player 1'), 'al')
    expect(useGetApiV1Users).toHaveBeenCalledWith(
      { q: 'al', sex: 'Female', age: '[18,30]', rating: '[3.0,4.0]' },
      expect.anything(),
    )
  })
})
