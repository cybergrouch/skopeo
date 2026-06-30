import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ResearchTab } from './ResearchTab'

const { useGetApiV1Users } = vi.hoisted(() => ({ useGetApiV1Users: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))

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
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: false })
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
    useGetApiV1Users.mockReturnValue({
      data: [
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
      ],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    const { container } = renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByText('Alice')).toBeInTheDocument()
    // The public code is shown as a differentiator for same-named players.
    expect(screen.getByText('· AAA111')).toBeInTheDocument()
    expect(screen.getByText('Female · 34')).toBeInTheDocument()
    // Band only, not the 6-decimal value; capability badge no longer shown.
    expect(screen.getByText('NTRP 4.0')).toBeInTheDocument()
    expect(screen.queryByText('PLAYER')).not.toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/a.jpg')
    expect(screen.getByRole('link', { name: /Alice/ })).toHaveAttribute('href', '/players/AAA111')
    expect(useGetApiV1Users).toHaveBeenCalledWith(
      { name: 'ali', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('shows both same-named players, each distinguished by its public code', async () => {
    // Two different people can share a name (common in PH); the search must surface both,
    // and the public code is what tells them apart in the list.
    useGetApiV1Users.mockReturnValue({
      data: [
        {
          id: 'm1',
          publicCode: 'CDBZ7N',
          displayName: 'Maria Garcia',
          photoUrl: null,
          sex: 'Female',
          age: 35,
          rating: undefined,
          capabilities: ['PLAYER'],
        },
        {
          id: 'm2',
          publicCode: 'ERVNVV',
          displayName: 'Maria Garcia',
          photoUrl: null,
          sex: 'Female',
          age: 32,
          rating: undefined,
          capabilities: ['PLAYER'],
        },
      ],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'maria garcia')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // Both rows render (no dedup on the identical display name)...
    expect(screen.getAllByText('Maria Garcia')).toHaveLength(2)
    // ...and each carries its own distinguishing public code + profile link.
    expect(screen.getByText('· CDBZ7N')).toBeInTheDocument()
    expect(screen.getByText('· ERVNVV')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /CDBZ7N/ })).toHaveAttribute('href', '/players/CDBZ7N')
    expect(screen.getByRole('link', { name: /ERVNVV/ })).toHaveAttribute('href', '/players/ERVNVV')
  })

  it('falls back to a rating value when there is no published level', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        {
          id: 'u2',
          publicCode: 'BBB222',
          displayName: 'Bob',
          photoUrl: null,
          sex: 'Male',
          age: 41,
          rating: { value: '5.250000', level: null },
          capabilities: [],
        },
      ],
      isLoading: false,
      isError: false,
    })
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

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { sex: 'Male', age: '[20,30]', rating: '[3.0,)', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('builds open-lower intervals from max-only inputs', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('to', { selector: '#r-age-max' }), '30')
    await user.type(screen.getByLabelText('to', { selector: '#r-rating-max' }), '4.5')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { age: '(,30]', rating: '(,4.5]', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('renders a result with no sex/age/rating/photo/name, using the id and initials', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        {
          id: 'abc-123',
          publicCode: 'CCC333',
          displayName: null,
          photoUrl: null,
          sex: null,
          age: null,
          rating: undefined,
          capabilities: [],
        },
      ],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    const { container } = renderTab()
    await user.type(screen.getByLabelText('Name'), 'x')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('abc-123')).toBeInTheDocument()
    expect(screen.queryByText(/^NTRP /)).not.toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull() // initials placeholder, not a photo
  })

  it('shows an empty state when there are no matches', async () => {
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('paginates results 25 at a time, advancing the offset on Next (#197)', async () => {
    // 26 rows: a full page (25) plus the look-ahead row that signals a next page exists.
    const rows = Array.from({ length: 26 }, (_, i) => ({
      id: `u${i}`,
      publicCode: `CODE${i}`,
      displayName: `P${i}`,
      photoUrl: null,
      sex: null,
      age: null,
      rating: undefined,
      capabilities: [],
    }))
    useGetApiV1Users.mockReturnValue({ data: rows, isLoading: false, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'p')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // Only 25 rows render (the 26th is trimmed); Previous disabled, Next enabled.
    expect(screen.getAllByRole('link')).toHaveLength(25)
    expect(screen.getByText('Page 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'p', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )

    // Next advances by the page size (offset 25).
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2')).toBeInTheDocument()
    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'p', limit: 26, offset: 25 },
      { query: { enabled: true } },
    )

    // A fresh search restarts at page 1 (offset 0).
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Page 1')).toBeInTheDocument()
    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'p', limit: 26, offset: 0 },
      { query: { enabled: true } },
    )
  })

  it('hides pagination controls when the results fit on one page', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        {
          id: 'u1',
          publicCode: 'AAA111',
          displayName: 'Alice',
          photoUrl: null,
          sex: null,
          age: null,
          rating: undefined,
          capabilities: [],
        },
      ],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
    expect(screen.queryByText('Page 1')).not.toBeInTheDocument()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })
})
