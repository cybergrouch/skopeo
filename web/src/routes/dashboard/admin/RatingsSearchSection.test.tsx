import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RatingsSearchSection } from './RatingsSearchSection'

const { useGetApiV1Users, putMutate } = vi.hoisted(() => ({
  useGetApiV1Users: vi.fn(),
  putMutate: vi.fn(),
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1Users,
  getGetApiV1UsersQueryKey: () => ['users'],
}))
vi.mock('@/api/generated/ratings/ratings', () => ({
  usePutApiV1UsersUserIdRatings: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => putMutate(vars),
  }),
}))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RatingsSearchSection />
    </QueryClientProvider>,
  )
}

const row = {
  id: 'u1',
  publicCode: 'AAA111',
  displayName: 'Ana',
  sex: 'Female',
  age: 34,
  rating: { value: '4.000000', level: '4.0' },
}

describe('RatingsSearchSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: false })
  })

  it('only searches after a filter is applied (#205)', async () => {
    const user = userEvent.setup()
    renderSection()
    // No results section before a search.
    expect(screen.queryByText('No matching players.')).not.toBeInTheDocument()

    useGetApiV1Users.mockReturnValue({ data: [row], isLoading: false, isError: false })
    await user.type(screen.getByLabelText('Name'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'ana', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('NTRP 4.0')).toBeInTheDocument()
  })

  it('rates a player from the results, preselected with their current band (#205)', async () => {
    useGetApiV1Users.mockReturnValue({ data: [row], isLoading: false, isError: false })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByLabelText('Rating')).toHaveValue('4.0') // preselected current band
    await user.selectOptions(screen.getByLabelText('Rating'), '4.5')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    await waitFor(() => expect(putMutate).toHaveBeenCalledWith({ userId: 'u1', data: { band: '4.5' } }))
  })

  it('paginates results 25 at a time and shows Unrated for players without a rating', async () => {
    const rows = Array.from({ length: 26 }, (_, i) => ({
      id: `u${i}`,
      publicCode: `CODE${i}`,
      displayName: `P${i}`,
      rating: undefined,
    }))
    useGetApiV1Users.mockReturnValue({ data: rows, isLoading: false, isError: false })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // 25 rendered (26th is the look-ahead); Next enabled.
    expect(screen.getAllByText('Unrated')).toHaveLength(25)
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'p', limit: 26, offset: 25 },
      { query: { enabled: true } },
    )

    // Page back to the first page.
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'p', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('falls back to the id and raw value when a name or band level is missing', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [{ id: 'u9', publicCode: 'CODE9', displayName: undefined, rating: { value: '3.500000' } }],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'x')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByText('u9')).toBeInTheDocument()
    expect(screen.getByText('NTRP 3.500000')).toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })

  it('shows an empty state when nothing matches', async () => {
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false, isError: false })
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })
})
