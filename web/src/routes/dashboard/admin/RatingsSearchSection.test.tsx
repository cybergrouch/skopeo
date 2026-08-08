import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { setupUser } from '@/test/user'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RatingsSearchSection } from './RatingsSearchSection'

const { useGetApiV1UsersSearch, setRatingFormProps } = vi.hoisted(() => ({
  useGetApiV1UsersSearch: vi.fn(),
  setRatingFormProps: vi.fn(),
}))

vi.mock('@/api/generated/users/users', () => ({
  useGetApiV1UsersSearch,
  getGetApiV1UsersSearchQueryKey: () => ['users', 'search'],
}))
// Stub the per-row rating form. It renders once per result — 25× in the pagination test — and the real
// form is a heavy 13-option <select> + mutation. Its own behaviour (band preselect + save) is covered by
// SetRatingForm.test.tsx; here we only assert the props this section wires to it, which keeps these tests
// cheap and deterministic under load (they were the render-bound flaky ones).
vi.mock('@/components/SetRatingForm', () => ({
  SetRatingForm: (props: { userId: string; initialValue?: string; onSaved?: () => void }) => {
    setRatingFormProps(props)
    return <span data-testid={`rate-${props.userId}`} />
  },
}))

/** Wrap a page of items + total in the query-result shape the hook returns. */
function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false, isError: false }
}

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
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: false })
  })

  it('only searches after a filter is applied (#205)', async () => {
    const user = setupUser()
    renderSection()
    expect(screen.queryByText('No matching players.')).not.toBeInTheDocument()

    useGetApiV1UsersSearch.mockReturnValue(page([row]))
    await user.type(screen.getByLabelText('Name'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ana', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('NTRP 4.0')).toBeInTheDocument()
  })

  it('appends the computed rating confidence as a percentage (#343)', async () => {
    const user = setupUser()
    renderSection()
    useGetApiV1UsersSearch.mockReturnValue(page([{ ...row, rating: { value: '4.000000', level: '4.0', confidence: '0.87' } }]))
    await user.type(screen.getByLabelText('Name'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(
      screen.getByRole('button', { name: /rating confidence 87%/i }),
    ).toHaveTextContent('87%')
  })

  it('wires each result to a rating form preselected with the current band (#205)', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page([row]))
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // The section hands each result to SetRatingForm with the player's band preselected; the form's own
    // rate-and-save behaviour is covered in SetRatingForm.test.tsx.
    expect(setRatingFormProps).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 'u1', initialValue: '4.0' }),
    )
  })

  it('paginates 25/page with numbered links + total, and shows Unrated (#232)', async () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`,
      publicCode: `CODE${i}`,
      displayName: `P${i}`,
      rating: undefined,
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60)) // 25 shown, 60 total → 3 pages
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getAllByText('Unrated')).toHaveLength(25)
    expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument()

    // A numbered link jumps to that page (offset 25 for page 2)...
    await user.click(screen.getByRole('button', { name: '2' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 25 },
      { query: { enabled: true } },
    )

    // ...and back to the first page.
    await user.click(screen.getByRole('button', { name: '1' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('falls back to the id and raw value when a name or band level is missing', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u9', publicCode: 'CODE9', displayName: undefined, rating: { value: '3.500000' } }]),
    )
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'x')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByText('u9')).toBeInTheDocument()
    expect(screen.getByText('NTRP 3.500000')).toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })

  it('shows an empty state when nothing matches', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page([], 0))
    const user = setupUser()
    renderSection()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })
})
