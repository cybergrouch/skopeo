import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ResearchTab } from './ResearchTab'

const { useGetApiV1UsersSearch } = vi.hoisted(() => ({ useGetApiV1UsersSearch: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersSearch }))

/** Wrap a page of items + total in the query-result shape the hook returns. */
function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false, isError: false }
}

function renderTab() {
  return render(
    <MemoryRouter>
      <ResearchTab />
    </MemoryRouter>,
  )
}

describe('ResearchTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: false })
  })

  it('keeps Search disabled until at least one filter is set', async () => {
    const user = userEvent.setup()
    renderTab()
    const search = screen.getByRole('button', { name: 'Search' })
    expect(search).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'al')
    expect(search).toBeEnabled()
  })

  it('searches by name and lists results with avatar, age/sex, rating band, and profile link', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        {
          id: 'u1',
          publicCode: 'AAA111',
          displayName: 'Alice',
          photoUrl: 'https://example.com/a.jpg',
          sex: 'Female',
          age: 34,
          rating: { value: '4.000000', level: '4.0' },
          capabilities: ['PLAYER'],
        },
      ]),
    )
    const user = userEvent.setup()
    const { container } = renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('· AAA111')).toBeInTheDocument()
    expect(screen.getByText('Female · 34')).toBeInTheDocument()
    expect(screen.getByText('NTRP 4.0')).toBeInTheDocument()
    expect(screen.queryByText('PLAYER')).not.toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/a.jpg')
    expect(screen.getByRole('link', { name: /Alice/ })).toHaveAttribute('href', '/players/AAA111')
    // 25 per page now that the endpoint returns a total (no +1 look-ahead).
    expect(useGetApiV1UsersSearch).toHaveBeenCalledWith(
      { name: 'ali', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('shows the computed rating confidence as a percentage beside the band (#343)', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        {
          id: 'u1',
          publicCode: 'AAA111',
          displayName: 'Alice',
          photoUrl: null,
          sex: 'Female',
          age: 34,
          rating: { value: '4.000000', level: '4.0', confidence: '0.87' },
          capabilities: ['PLAYER'],
        },
      ]),
    )
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/· 87%/)).toBeInTheDocument()
  })

  it('shows both same-named players, each distinguished by its public code', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        { id: 'm1', publicCode: 'CDBZ7N', displayName: 'Maria Garcia', photoUrl: null, sex: 'Female', age: 35, rating: undefined, capabilities: ['PLAYER'] },
        { id: 'm2', publicCode: 'ERVNVV', displayName: 'Maria Garcia', photoUrl: null, sex: 'Female', age: 32, rating: undefined, capabilities: ['PLAYER'] },
      ]),
    )
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'maria garcia')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getAllByText('Maria Garcia')).toHaveLength(2)
    expect(screen.getByText('· CDBZ7N')).toBeInTheDocument()
    expect(screen.getByText('· ERVNVV')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /CDBZ7N/ })).toHaveAttribute('href', '/players/CDBZ7N')
    expect(screen.getByRole('link', { name: /ERVNVV/ })).toHaveAttribute('href', '/players/ERVNVV')
  })

  it('falls back to a rating value when there is no published level', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u2', publicCode: 'BBB222', displayName: 'Bob', photoUrl: null, sex: 'Male', age: 41, rating: { value: '5.250000', level: null }, capabilities: [] }]),
    )
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'bob')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('NTRP 5.250000')).toBeInTheDocument()
  })

  it('builds age and rating intervals from min/max inputs', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    await user.type(screen.getByLabelText('Age from'), '20')
    await user.type(screen.getByLabelText('to', { selector: '#r-age-max' }), '30')
    await user.type(screen.getByLabelText('Rating from'), '3.0')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { sex: 'Male', age: '[20,30]', rating: '[3.0,)', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('builds open-lower intervals from max-only inputs', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('to', { selector: '#r-age-max' }), '30')
    await user.type(screen.getByLabelText('to', { selector: '#r-rating-max' }), '4.5')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { age: '(,30]', rating: '(,4.5]', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('renders a result with no sex/age/rating/photo/name, using the id and initials', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'abc-123', publicCode: 'CCC333', displayName: null, photoUrl: null, sex: null, age: null, rating: undefined, capabilities: [] }]),
    )
    const user = userEvent.setup()
    const { container } = renderTab()
    await user.type(screen.getByLabelText('Name'), 'x')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('abc-123')).toBeInTheDocument()
    expect(screen.queryByText(/^NTRP /)).not.toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('shows an empty state when there are no matches', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page([], 0))
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('paginates 25/page with numbered page links and a total, navigating by page (#232)', async () => {
    // A full page of 25 rows out of 60 total → 3 pages.
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`, publicCode: `CODE${i}`, displayName: `P${i}`, photoUrl: null, sex: null, age: null, rating: undefined, capabilities: [],
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60))
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // Total + range shown, numbered page links present, Previous disabled on page 1.
    expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: '3' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )

    // Clicking a numbered page link jumps straight to that page (offset 50 for page 3).
    await user.click(screen.getByRole('button', { name: '3' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 50 },
      { query: { enabled: true } },
    )

    // A fresh search restarts at page 1 (offset 0).
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('shows the total even when results fit on one page', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u1', publicCode: 'AAA111', displayName: 'Alice', photoUrl: null, sex: null, age: null, rating: undefined, capabilities: [] }], 1),
    )
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Showing 1–1 of 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })
})
