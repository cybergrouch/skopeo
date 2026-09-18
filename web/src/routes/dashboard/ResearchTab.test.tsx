import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom'
import { ResearchTab } from './ResearchTab'

const { useGetApiV1UsersSearch } = vi.hoisted(() => ({ useGetApiV1UsersSearch: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersSearch }))

/** Wrap a page of items + total in the query-result shape the hook returns. */
function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false, isError: false }
}

/**
 * Surfaces the query string the tab writes its search into (#1054), plus how it got there — REPLACE
 * is what keeps a filter tweak from becoming its own Back step.
 */
function UrlProbe() {
  const location = useLocation()
  const navigationType = useNavigationType()
  return (
    <>
      <div data-testid="search">{location.search}</div>
      <div data-testid="nav-type">{navigationType}</div>
    </>
  )
}

function renderTab(initialEntries: string[] = ['/dashboard?tab=research']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <ResearchTab />
      <UrlProbe />
    </MemoryRouter>,
  )
}

/** The current query string, decoded — interval brackets are percent-encoded on the way in. */
function currentSearch(): string {
  return decodeURIComponent(screen.getByTestId('search').textContent ?? '')
}

/** One minimal row — the table only renders when the page is non-empty. */
const ONE_ROW = [
  { id: 'u1', publicCode: 'AAA111', displayName: 'Alice', photoUrl: null, status: 'ACTIVE', capabilities: [] },
]

/** Run a name search so the results table renders. */
async function search(user: ReturnType<typeof userEvent.setup>, term = 'ali') {
  await user.type(screen.getByLabelText('Name'), term)
  await user.click(screen.getByRole('button', { name: 'Search' }))
}

describe('ResearchTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: false })
  })

  it('keeps Search disabled until at least one filter is set', async () => {
    const user = userEvent.setup()
    renderTab()
    const button = screen.getByRole('button', { name: 'Search' })
    expect(button).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'al')
    expect(button).toBeEnabled()
  })

  it('renders the ten columns in the specified order (#1050)', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        {
          id: 'u1',
          publicCode: 'AAA111',
          displayName: 'Alice A',
          firstName: 'Alice',
          lastName: 'Anderson',
          photoUrl: 'https://example.com/a.jpg',
          sex: 'Female',
          age: 34,
          rating: { value: '4.000000', level: '4.0' },
          status: 'ACTIVE',
          inCalibration: false,
          capabilities: ['PLAYER'],
        },
      ]),
    )
    const user = userEvent.setup()
    const { container } = renderTab()
    await search(user)

    // Headers, in order. The photo column's header is visually empty (screen-reader text only).
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent?.trim())
    expect(headers).toEqual([
      'Photo',
      'Code',
      'Display name▲',
      'Last name▲',
      'First name▲',
      'Sex▲',
      'Age▲',
      'Rating▲',
      'Status▲',
      'Calibration',
    ])

    const row = screen.getAllByRole('row')[1]
    expect(within(row).getByRole('link', { name: 'AAA111' })).toHaveAttribute(
      'href',
      '/players/AAA111',
    )
    // Both identifying cells navigate, preserving the card's whole-row link destination.
    expect(within(row).getByRole('link', { name: 'Alice A' })).toHaveAttribute(
      'href',
      '/players/AAA111',
    )
    expect(within(row).getByText('Anderson')).toBeInTheDocument()
    expect(within(row).getByText('Alice')).toBeInTheDocument()
    expect(within(row).getByText('Female')).toBeInTheDocument()
    expect(within(row).getByText('34')).toBeInTheDocument()
    expect(within(row).getByText('NTRP 4.0')).toBeInTheDocument()
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/a.jpg')
    // Capabilities are fetched but never a column.
    expect(screen.queryByText('PLAYER')).not.toBeInTheDocument()
  })

  it('does not nest a button inside the sortable rating header (#697)', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW))
    const user = userEvent.setup()
    renderTab()
    await search(user)
    // The header must be plain text: NtrpLabel renders a button, and a button inside the sort button
    // would be nested interactive content. The disclaimer lives in the card description instead.
    const ratingHeader = screen.getByRole('button', { name: 'Rating' })
    expect(ratingHeader.querySelector('button')).toBeNull()
    // The disclaimer is still reachable — from the card description, outside the table.
    expect(screen.getAllByRole('button', { name: /about this rating framework/i }).length).toBeGreaterThan(0)
  })

  it('sorts server-side, flipping direction on a second click of the same column', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u1', publicCode: 'AAA111', displayName: 'Alice', photoUrl: null, capabilities: [] }]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user)

    // No sort until asked: the params carry neither key, so the server keeps its stable `id` order.
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )

    await user.click(screen.getByRole('button', { name: 'Last name' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true, sort: 'LAST_NAME', direction: 'ASC' },
      { query: { enabled: true } },
    )

    await user.click(screen.getByRole('button', { name: 'Last name' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true, sort: 'LAST_NAME', direction: 'DESC' },
      { query: { enabled: true } },
    )

    // A different column starts ascending again rather than inheriting DESC.
    await user.click(screen.getByRole('button', { name: 'Age' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true, sort: 'AGE', direction: 'ASC' },
      { query: { enabled: true } },
    )
  })

  it('reports the sort state through aria-sort on the active column only', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW))
    const user = userEvent.setup()
    renderTab()
    await search(user)

    const header = (name: string) =>
      screen.getByRole('button', { name }).closest('th') as HTMLElement

    expect(header('Sex')).toHaveAttribute('aria-sort', 'none')
    await user.click(screen.getByRole('button', { name: 'Sex' }))
    expect(header('Sex')).toHaveAttribute('aria-sort', 'ascending')
    expect(header('Age')).toHaveAttribute('aria-sort', 'none')
    await user.click(screen.getByRole('button', { name: 'Sex' }))
    expect(header('Sex')).toHaveAttribute('aria-sort', 'descending')
  })

  it('returns to the first page when the sort changes', async () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`, publicCode: `CODE${i}`, displayName: `P${i}`, photoUrl: null, capabilities: [],
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60))
    const user = userEvent.setup()
    renderTab()
    await search(user, 'p')

    await user.click(screen.getByRole('button', { name: '3' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      expect.objectContaining({ offset: 50 }),
      { query: { enabled: true } },
    )

    // Page 3 of the old order holds different players than page 3 of the new one, so re-sorting has to
    // restart — keeping offset 50 would move the user somewhere they never asked for.
    await user.click(screen.getByRole('button', { name: 'Display name' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      expect.objectContaining({ offset: 0, sort: 'DISPLAY_NAME', direction: 'ASC' }),
      { query: { enabled: true } },
    )
  })

  it('sends the status filter chosen in the Research-only dropdown (#1050)', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.selectOptions(screen.getByLabelText('Status'), 'UNCLAIMED')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { status: 'UNCLAIMED', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )
  })

  it('distinguishes all four lifecycle states, merged included', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        { id: 'a', publicCode: 'AAA111', displayName: 'Active One', photoUrl: null, status: 'ACTIVE', capabilities: [] },
        { id: 'b', publicCode: 'BBB222', displayName: 'Unclaimed One', photoUrl: null, status: 'UNCLAIMED', capabilities: [] },
        { id: 'c', publicCode: 'CCC333', displayName: 'Deleted One', photoUrl: null, status: 'DELETED', capabilities: [] },
        { id: 'd', publicCode: 'DDD444', displayName: 'Merged One', photoUrl: null, status: 'MERGED', capabilities: [] },
      ]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user, 'one')

    // Scoped to the table: the filter dropdown renders an <option> for each of these same words.
    const table = within(screen.getByRole('table'))
    expect(table.getByText('Active')).toBeInTheDocument()
    expect(table.getByText('Unclaimed')).toBeInTheDocument()
    expect(table.getByText('Deleted')).toBeInTheDocument()
    // Merged is the state `isDeleted` could never express: both clear `is_active`.
    expect(table.getByText('Merged')).toBeInTheDocument()
    // The Status column replaces the inline tag, so "Unclaimed" appears once per row, not twice.
    expect(table.getAllByText('Unclaimed')).toHaveLength(1)
  })

  it('shows an unrecognised status verbatim rather than as a blank cell', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'x', publicCode: 'XXX999', displayName: 'Future', photoUrl: null, status: 'SUSPENDED', capabilities: [] }]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user, 'future')
    expect(screen.getByText('SUSPENDED')).toBeInTheDocument()
  })

  it('flags players still calibrating, and leaves the rest blank (#881)', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        { id: 'a', publicCode: 'AAA111', displayName: 'New Player', photoUrl: null, status: 'ACTIVE', inCalibration: true, capabilities: [] },
        { id: 'b', publicCode: 'BBB222', displayName: 'Old Player', photoUrl: null, status: 'ACTIVE', inCalibration: false, capabilities: [] },
        // Null, not false: the field is absent when the endpoint was not asked. Reads the same as "no".
        { id: 'c', publicCode: 'CCC333', displayName: 'Unknown Player', photoUrl: null, status: 'ACTIVE', inCalibration: null, capabilities: [] },
      ]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user, 'player')

    expect(screen.getAllByText('Calibrating')).toHaveLength(1)
    expect(screen.getAllByLabelText('Not calibrating')).toHaveLength(2)
  })

  it('renders an em-dash for every value a player is missing', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        {
          id: 'abc-123', publicCode: 'CCC333', displayName: null, firstName: null, lastName: null,
          photoUrl: null, sex: null, age: null, rating: undefined, status: 'UNCLAIMED', capabilities: [],
        },
      ]),
    )
    const user = userEvent.setup()
    const { container } = renderTab()
    await search(user, 'x')

    // display name, last, first, sex, age, rating — six dashes, plus the calibration cell's own.
    const row = screen.getAllByRole('row')[1]
    expect(within(row).getAllByText('—')).toHaveLength(7)
    expect(within(row).queryByText(/^NTRP /)).not.toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('falls back to a rating value when there is no published level', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u2', publicCode: 'BBB222', displayName: 'Bob', photoUrl: null, sex: 'Male', age: 41, rating: { value: '5.250000', level: null }, status: 'ACTIVE', capabilities: [] }]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user, 'bob')
    expect(screen.getByText('NTRP 5.250000')).toBeInTheDocument()
  })

  it('shows both same-named players, each distinguished by its public code', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([
        { id: 'm1', publicCode: 'CDBZ7N', displayName: 'Maria Garcia', photoUrl: null, sex: 'Female', age: 35, rating: undefined, status: 'ACTIVE', capabilities: [] },
        { id: 'm2', publicCode: 'ERVNVV', displayName: 'Maria Garcia', photoUrl: null, sex: 'Female', age: 32, rating: undefined, status: 'ACTIVE', capabilities: [] },
      ]),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user, 'maria garcia')

    expect(screen.getAllByText('Maria Garcia')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'CDBZ7N' })).toHaveAttribute('href', '/players/CDBZ7N')
    expect(screen.getByRole('link', { name: 'ERVNVV' })).toHaveAttribute('href', '/players/ERVNVV')
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
      { sex: 'Male', age: '[20,30]', rating: '[3.0,)', limit: 25, offset: 0, includeInactive: true },
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
      { age: '(,30]', rating: '(,4.5]', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )
  })

  it('shows an empty state when there are no matches', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page([], 0))
    const user = userEvent.setup()
    renderTab()
    await search(user, 'zzz')
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = userEvent.setup()
    renderTab()
    await search(user)
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('paginates 25/page with numbered page links and a total, navigating by page (#232)', async () => {
    // A full page of 25 rows out of 60 total → 3 pages.
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`, publicCode: `CODE${i}`, displayName: `P${i}`, photoUrl: null, sex: null, age: null, rating: undefined, status: 'ACTIVE', capabilities: [],
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60))
    const user = userEvent.setup()
    renderTab()
    await search(user, 'p')

    expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: '3' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    // Clicking a numbered page link jumps straight to that page (offset 50 for page 3).
    await user.click(screen.getByRole('button', { name: '3' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 50, includeInactive: true },
      { query: { enabled: true } },
    )

    // A fresh search restarts at page 1 (offset 0).
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'p', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )
  })

  it('shows the total even when results fit on one page', async () => {
    useGetApiV1UsersSearch.mockReturnValue(
      page([{ id: 'u1', publicCode: 'AAA111', displayName: 'Alice', photoUrl: null, sex: null, age: null, rating: undefined, status: 'ACTIVE', capabilities: [] }], 1),
    )
    const user = userEvent.setup()
    renderTab()
    await search(user)
    expect(screen.getByText('Showing 1–1 of 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1UsersSearch.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = userEvent.setup()
    renderTab()
    await search(user)
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })

  // The search lives in the URL (#1054) so that leaving the tab and coming back — Back from a public
  // profile, which unmounts this component — restores it, form included.
  it('restores the filters, page, sort and direction from the URL, form included (#1054)', () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW, 60))
    renderTab([
      '/dashboard?tab=research&name=ali&sex=Male&status=ACTIVE&age=[20,30]&rating=[3.0,4.5]&page=3&sort=AGE&direction=DESC',
    ])

    // No interaction at all: a mount with search params requests the results straight away, which is
    // what makes Back land on the results the viewer left and a pasted link show what it describes.
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      {
        name: 'ali',
        sex: 'Male',
        status: 'ACTIVE',
        age: '[20,30]',
        rating: '[3.0,4.5]',
        limit: 25,
        offset: 50,
        includeInactive: true,
        sort: 'AGE',
        direction: 'DESC',
      },
      { query: { enabled: true } },
    )

    // Never results above a blank form: every input shows the filter that produced them.
    expect(screen.getByLabelText('Name')).toHaveValue('ali')
    expect(screen.getByLabelText('Sex')).toHaveValue('Male')
    expect(screen.getByLabelText('Status')).toHaveValue('ACTIVE')
    expect(screen.getByLabelText('Age from')).toHaveValue('20')
    expect(screen.getByLabelText('to', { selector: '#r-age-max' })).toHaveValue('30')
    expect(screen.getByLabelText('Rating from')).toHaveValue('3.0')
    expect(screen.getByLabelText('to', { selector: '#r-rating-max' })).toHaveValue('4.5')

    // The restored page and sort, not page 1 of an unsorted list.
    expect(screen.getByRole('button', { name: '3' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Age' }).closest('th')).toHaveAttribute(
      'aria-sort',
      'descending',
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('edits the restored search rather than a blank one', async () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW))
    const user = userEvent.setup()
    renderTab(['/dashboard?tab=research&name=ali&sex=Male'])

    // The hazard a restored-but-blank form creates: the first field edit would submit a search that
    // has nothing to do with the rows on screen.
    await user.selectOptions(screen.getByLabelText('Status'), 'UNCLAIMED')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      {
        name: 'ali',
        sex: 'Male',
        status: 'UNCLAIMED',
        limit: 25,
        offset: 0,
        includeInactive: true,
      },
      { query: { enabled: true } },
    )
  })

  it('syncs search, page and sort into the URL without adding history steps', async () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`, publicCode: `CODE${i}`, displayName: `P${i}`, photoUrl: null, capabilities: [],
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60))
    const user = userEvent.setup()
    renderTab()
    // An untouched tab keeps a clean URL: only the dashboard's own tab param.
    expect(currentSearch()).toBe('?tab=research')

    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.type(screen.getByLabelText('Age from'), '20')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(currentSearch()).toBe('?tab=research&name=ali&age=[20,)')
    // Replace, not push, so tweaking a filter is not itself a Back step (#323's reasoning).
    expect(screen.getByTestId('nav-type')).toHaveTextContent('REPLACE')

    await user.click(screen.getByRole('button', { name: '3' }))
    expect(currentSearch()).toContain('page=3')

    // Re-sorting restarts at page 1, so the page param goes away again.
    await user.click(screen.getByRole('button', { name: 'Display name' }))
    expect(currentSearch()).toBe('?tab=research&name=ali&age=[20,)&sort=DISPLAY_NAME')
    await user.click(screen.getByRole('button', { name: 'Display name' }))
    expect(currentSearch()).toContain('direction=DESC')
  })

  it('keeps what the user has typed since, across a sort or a page step', async () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({
      id: `u${i}`, publicCode: `CODE${i}`, displayName: `P${i}`, photoUrl: null, capabilities: [],
    }))
    useGetApiV1UsersSearch.mockReturnValue(page(rows, 60))
    const user = userEvent.setup()
    renderTab(['/dashboard?tab=research&name=ali'])

    // Half-typed and not yet submitted. The form is seeded once per mount, so a URL write must not
    // remount it — that would silently discard this.
    await user.type(screen.getByLabelText('Name'), 'ce')
    await user.click(screen.getByRole('button', { name: 'Sex' }))
    expect(screen.getByLabelText('Name')).toHaveValue('alice')
    // …and the results still answer the search that is applied, not the draft.
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true, sort: 'SEX', direction: 'ASC' },
      { query: { enabled: true } },
    )
  })

  it('explains a filter it could not read instead of showing an empty tab', () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW))
    // A hand-edited or stale link: RETIRED is not a lifecycle state, and an exclusive age bound is
    // one the form's two number inputs cannot show.
    renderTab(['/dashboard?tab=research&name=ali&status=RETIRED&age=(20,30]'])

    expect(screen.getByRole('alert')).toHaveTextContent(
      'This link had 2 unreadable filters (status, age), so they were ignored. The form shows the filters that were applied.',
    )
    // The filters it could read still ran, and the form shows exactly those.
    expect(screen.getByLabelText('Name')).toHaveValue('ali')
    expect(screen.getByLabelText('Status')).toHaveValue('')
    expect(screen.getByLabelText('Age from')).toHaveValue('')
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('says "filter" and "it" when exactly one param is unreadable', () => {
    useGetApiV1UsersSearch.mockReturnValue(page(ONE_ROW))
    // The singular arm of the same sentence — a link whose only bad param is the sort column.
    renderTab(['/dashboard?tab=research&name=ali&sort=SHOE_SIZE'])

    expect(screen.getByRole('alert')).toHaveTextContent(
      'This link had 1 unreadable filter (sort), so it was ignored. The form shows the filters that were applied.',
    )
    // Unsorted, not sorted by a column the server has never heard of.
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith(
      { name: 'ali', limit: 25, offset: 0, includeInactive: true },
      { query: { enabled: true } },
    )
  })

  it('leaves the tab untouched when the URL carries no search at all', () => {
    renderTab(['/dashboard?tab=research'])
    expect(useGetApiV1UsersSearch).toHaveBeenLastCalledWith({}, { query: { enabled: false } })
    expect(screen.queryByText('Results')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
