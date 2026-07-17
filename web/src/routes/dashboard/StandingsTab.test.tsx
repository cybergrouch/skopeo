import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { StandingsTab } from './StandingsTab'

/** The tab links to public profiles, so it must render inside a router. */
function renderTab() {
  return render(
    <MemoryRouter>
      <StandingsTab />
    </MemoryRouter>,
  )
}

const { useGetApiV1Standings, getApiV1StandingsMe, useGetApiV1UsersMe } = vi.hoisted(() => ({
  useGetApiV1Standings: vi.fn(),
  getApiV1StandingsMe: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}))

vi.mock('@/api/generated/standings/standings', () => ({
  useGetApiV1Standings,
  getApiV1StandingsMe,
}))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))

const groups = [
  { band: '4.0', label: 'NTRP 4.0 Band Race', sex: 'Male' },
  { band: '4.0', label: 'NTRP 4.0 Band Race', sex: 'Female' },
  { band: '3.5', label: 'NTRP 3.5 Band Race', sex: 'Male' },
]

/** Every NTRP band, strongest-first (as the backend now returns), regardless of which groups have data. */
const bands = [
  { code: '6.0+', label: 'NTRP 6.0+ Band Race' },
  { code: '5.5', label: 'NTRP 5.5 Band Race' },
  { code: '5.0', label: 'NTRP 5.0 Band Race' },
  { code: '4.5', label: 'NTRP 4.5 Band Race' },
  { code: '4.0', label: 'NTRP 4.0 Band Race' },
  { code: '3.5', label: 'NTRP 3.5 Band Race' },
  { code: '3.0', label: 'NTRP 3.0 Band Race' },
  { code: '<3.0', label: 'NTRP Under 3.0 Band Race' },
]

/** The default page the hook returns for the seeded band+sex (Men, 4.0). */
const defaultPage = {
  band: '4.0',
  label: 'NTRP 4.0 Band Race',
  sex: 'Male',
  limit: 25,
  offset: 0,
  total: 2,
  entries: [
    { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40 },
    // No display name → falls back to the public code; no age → meta omitted.
    { rank: 2, userId: 'm2', displayName: null, publicCode: 'CCC333', sex: 'Male', age: null },
  ],
  groups,
  bands,
  source: 'RATING',
}

describe('StandingsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersMe.mockReturnValue({ data: { id: 'm1' } })
    useGetApiV1Standings.mockReturnValue({ data: defaultPage, isLoading: false })
  })

  it('shows a loading state while the first page resolves', () => {
    useGetApiV1Standings.mockReturnValue({ data: undefined, isLoading: true })
    renderTab()
    expect(screen.getByText('Loading standings…')).toBeInTheDocument()
  })

  it('shows a loading state while a selection-driven refetch is in flight (#433)', () => {
    // A refetch (isFetching) after a band/sex change must show the loader, avoiding a jarring blank.
    useGetApiV1Standings.mockReturnValue({ data: defaultPage, isLoading: false, isFetching: true })
    renderTab()
    expect(screen.getByText('Loading standings…')).toBeInTheDocument()
  })

  it('renders an interim, sex-split description', () => {
    renderTab()
    expect(screen.getByText(/Interim standings/i)).toBeInTheDocument()
    expect(screen.getByText(/Men's and Women's standings/i)).toBeInTheDocument()
  })

  it('has no "View" button — the tab is selection-driven (#433)', () => {
    renderTab()
    expect(screen.queryByRole('button', { name: 'View' })).not.toBeInTheDocument()
  })

  it('renders "Find me" as a distinct secondary button (#433)', () => {
    renderTab()
    // Secondary variant carries the theme's secondary background token, distinct from the segmented toggle.
    expect(screen.getByRole('button', { name: 'Find me' })).toHaveClass('bg-secondary')
  })

  it('lists every NTRP band and offers sex toggles even when the snapshot has no data (#113)', () => {
    // An empty snapshot still advertises every band and the standard sex toggles, so the tab stays queryable.
    useGetApiV1Standings.mockReturnValue({
      data: { band: null, label: null, sex: null, limit: 25, offset: 0, total: 0, entries: [], groups: [], bands, source: 'RATING' },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByRole('option', { name: 'NTRP 6.0+ Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'NTRP Under 3.0 Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Men' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Women' })).toBeInTheDocument()
    expect(screen.getByText('No players in this group.')).toBeInTheDocument()
  })

  it('renders the served band selector, segmented sex toggle, and the ranked, name/code-fallback rows (#212)', () => {
    renderTab()

    // The band dropdown lists the distinct bands; the segmented sex toggle shows Men + Women.
    expect(screen.getByRole('option', { name: 'NTRP 4.0 Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'NTRP 3.5 Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Men' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Women' })).toBeInTheDocument()

    // "NTRP 4.0 Band Race" appears both as an <option> and the served card heading.
    expect(screen.getAllByText('NTRP 4.0 Band Race').length).toBeGreaterThan(0)
    expect(screen.getByText('Bob Cruz')).toBeInTheDocument()
    expect(screen.getByText('40')).toBeInTheDocument() // age-only meta
    expect(screen.getByText('CCC333')).toBeInTheDocument() // name fallback to code
  })

  it('renders the sex toggle as a fused segmented control with a marked active segment (#433)', () => {
    renderTab()
    // Default is Men → the Men segment is the selected tab, Women is not.
    expect(screen.getByRole('tab', { name: 'Men' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Women' })).toHaveAttribute('aria-selected', 'false')
    // Both segments live inside one tablist container (fused), not separate button groups.
    const tablist = screen.getByRole('tablist', { name: 'Standings by sex' })
    expect(within(tablist).getAllByRole('tab')).toHaveLength(2)
  })

  it('lists ALL NTRP bands in the dropdown even though only a subset of groups has data (#113)', () => {
    renderTab()
    // groups only covers 4.0 and 3.5, yet the dropdown offers every band, strongest-first.
    const bandSelect = screen.getByLabelText('Band')
    const options = within(bandSelect).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(bands.map((b) => b.label))
    expect(options[0]).toBe('NTRP 6.0+ Band Race') // strongest first
  })

  it('selecting a band triggers the query immediately for that band + current sex (#433)', async () => {
    const user = userEvent.setup()
    renderTab()

    // 5.0 has no data in `groups`, but it is selectable; picking it fires the query immediately (no View).
    await user.selectOptions(screen.getByLabelText('Band'), '5.0')
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '5.0', sex: 'Male', limit: 25, offset: 0 })
    })
  })

  it('renders player name links with the themed content-link style (#417)', () => {
    renderTab()
    // Guards the regression where names used text-primary and vanished on the Grass theme.
    expect(screen.getByRole('link', { name: 'Bob Cruz' })).toHaveClass('content-link')
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveClass('content-link')
  })

  it('paginates the served group via the pager (offset changes by page size)', async () => {
    useGetApiV1Standings.mockReturnValue({ data: { ...defaultPage, total: 60 }, isLoading: false })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ offset: 25 })
    })
  })

  it('"Find me" loads the caller\'s page and jumps to their band/sex/offset', async () => {
    getApiV1StandingsMe.mockResolvedValue({ band: '3.5', label: 'NTRP 3.5 Band Race', sex: 'Male', rank: 30, limit: 25, offset: 25 })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Find me' }))
    expect(getApiV1StandingsMe).toHaveBeenCalledWith({ limit: 25 })
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '3.5', sex: 'Male', offset: 25 })
    })
  })

  it('"Find me" hits the locate endpoint once and syncs the controls without a duplicate fetch (#433)', async () => {
    // The regression this guards: after locating, the code programmatically sets band+sex+offset. That sync
    // must update the UI only and NOT re-fire the standings query. So exactly one locate call, and every
    // located-group render carries the SAME query args — no extra tuple from a feedback-loop refetch.
    getApiV1StandingsMe.mockResolvedValue({ band: '3.5', label: 'NTRP 3.5 Band Race', sex: 'Female', rank: 30, limit: 25, offset: 25 })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Find me' }))

    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '3.5', sex: 'Female', offset: 25 })
    })
    // The locate endpoint is hit exactly once for the single Find-me action.
    expect(getApiV1StandingsMe).toHaveBeenCalledTimes(1)

    // The located group is the terminal request: exactly ONE distinct query-args tuple targets that band,
    // and the segment/dropdown having been forced to Female/3.5 did not spawn a second (duplicate) fetch.
    const locatedCalls = useGetApiV1Standings.mock.calls
      .map((c) => c[0])
      .filter((args) => args?.band === '3.5')
    const distinctLocated = new Set(locatedCalls.map((args) => JSON.stringify(args)))
    expect(distinctLocated.size).toBe(1)
    expect([...distinctLocated][0]).toBe(
      JSON.stringify({ band: '3.5', sex: 'Female', limit: 25, offset: 25 }),
    )
    // The synced controls reflect the located group.
    expect(screen.getByRole('tab', { name: 'Women' })).toHaveAttribute('aria-selected', 'true')
  })

  it('highlights only the current user’s own row', () => {
    renderTab()
    const myRow = screen.getByLabelText('Your standing')
    expect(within(myRow).getByText('Bob Cruz')).toBeInTheDocument()
    expect(within(myRow).getByText('You')).toBeInTheDocument()
    expect(screen.getAllByLabelText('Your standing')).toHaveLength(1)
  })

  it('highlights no row when the current user is unknown', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    renderTab()
    expect(screen.queryByLabelText('Your standing')).not.toBeInTheDocument()
  })

  it('shows no rating value when the payload omits it (non-privileged viewer)', () => {
    const { container } = renderTab()
    container.querySelectorAll('li').forEach((row) => {
      expect(row.textContent ?? '').not.toMatch(/\d\.\d/)
    })
  })

  it('shows the rating badge per row under the RATING source (#457)', () => {
    useGetApiV1Standings.mockReturnValue({
      data: {
        ...defaultPage,
        source: 'RATING',
        entries: [
          { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40, currentRating: '4.200000' },
        ],
      },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText('4.200000')).toBeInTheDocument()
    // The rating is not mislabeled as points.
    expect(screen.queryByText(/pts/)).not.toBeInTheDocument()
  })

  it('shows the points value per row under the POINTS source, not the rating (#457)', () => {
    useGetApiV1Standings.mockReturnValue({
      data: {
        ...defaultPage,
        source: 'POINTS',
        entries: [
          // A POINTS row carries points (public); no currentRating is served.
          { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40, points: '240' },
        ],
      },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText('240 pts')).toBeInTheDocument()
  })

  it('renders no metric under POINTS when an entry omits its points value (#457)', () => {
    useGetApiV1Standings.mockReturnValue({
      data: {
        ...defaultPage,
        source: 'POINTS',
        entries: [
          // A POINTS row with no points value → no "pts" is shown (and no rating leaks).
          { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40 },
        ],
      },
      isLoading: false,
    })
    renderTab()
    // No "pts" metric is rendered for a POINTS row that carries no points value (the falsy arm).
    expect(screen.queryByText(/pts/)).not.toBeInTheDocument()
  })

  it('links each player card to their public profile (#186)', () => {
    renderTab()
    expect(screen.getByRole('link', { name: 'Bob Cruz' })).toHaveAttribute('href', '/players/BBB222')
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveAttribute('href', '/players/CCC333')
  })

  it('clicking the inactive sex segment triggers a query; clicking the active one does NOT (#433)', async () => {
    const user = userEvent.setup()
    renderTab()

    // The distinct query-arg tuples seen before any segment interaction.
    const distinctBefore = new Set(
      useGetApiV1Standings.mock.calls.map((c) => JSON.stringify(c[0])),
    )

    // Clicking the already-active Men segment is a no-op — no NEW distinct query args appear.
    await user.click(screen.getByRole('tab', { name: 'Men' }))
    const distinctAfterActive = new Set(
      useGetApiV1Standings.mock.calls.map((c) => JSON.stringify(c[0])),
    )
    expect(distinctAfterActive).toEqual(distinctBefore)
    // Still Male — clicking active didn't switch it.
    expect(screen.getByRole('tab', { name: 'Men' })).toHaveAttribute('aria-selected', 'true')

    // Clicking the inactive Women segment switches sex and fires the query for that group, pinned to the
    // band the user is currently looking at (the served 4.0) so switching sex doesn't drop back to default.
    await user.click(screen.getByRole('tab', { name: 'Women' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '4.0', sex: 'Female', offset: 0 })
    })
    expect(screen.getByRole('tab', { name: 'Women' })).toHaveAttribute('aria-selected', 'true')
  })

  it('queries with no sex for the Unspecified group (sex omitted)', async () => {
    useGetApiV1Standings.mockReturnValue({
      data: {
        ...defaultPage,
        groups: [{ band: '4.0', label: 'NTRP 4.0 Band Race', sex: null }],
        bands,
      },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('tab', { name: 'Unspecified' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ sex: undefined, offset: 0 })
    })
  })

  it('renders an empty-group page (band/label/total absent) with a placeholder heading', () => {
    // A requested (band, sex) group with no members: groups still list the band, but this page is empty.
    useGetApiV1Standings.mockReturnValue({
      data: { band: null, label: null, sex: null, limit: 25, offset: 0, entries: [], groups, bands, source: 'RATING' },
      isLoading: false,
    })
    renderTab()

    // The served card heading falls back to "Standings" (no page label) — appearing alongside the tab's
    // own top card title of the same name — and the body reports the empty group.
    expect(screen.getByText('No players in this group.')).toBeInTheDocument()
    expect(screen.getAllByText('Standings').length).toBeGreaterThan(1)
  })

  it('shows an explicit points empty state when source is POINTS with no entries (#428)', () => {
    // POINTS mode with no committed snapshot: the backend returns source=POINTS and no entries, so the
    // tab must explain the source is Points with no data yet — NOT the generic empty-band message and NOT
    // a ratings leaderboard.
    useGetApiV1Standings.mockReturnValue({
      data: { band: null, label: null, sex: null, limit: 25, offset: 0, total: 0, entries: [], groups: [], bands, source: 'POINTS' },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText(/run a points calculation/i)).toBeInTheDocument()
    expect(screen.queryByText('No players in this group.')).not.toBeInTheDocument()
  })

  it('shows the normal leaderboard (not the points empty state) when source is RATING (#428)', () => {
    // Default fixture is source=RATING with entries; the points empty-state message must never appear.
    renderTab()
    expect(screen.getByText('Bob Cruz')).toBeInTheDocument()
    expect(screen.queryByText(/run a points calculation/i)).not.toBeInTheDocument()
  })

  it('shows the precise rating when the payload includes it (raters/admins, #186)', () => {
    useGetApiV1Standings.mockReturnValue({
      data: {
        ...defaultPage,
        entries: [
          { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40, currentRating: '4.230000' },
        ],
      },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText('4.230000')).toBeInTheDocument()
  })
})
