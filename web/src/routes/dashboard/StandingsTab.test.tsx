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

  it('renders an interim, sex-split description', () => {
    renderTab()
    expect(screen.getByText(/Interim standings/i)).toBeInTheDocument()
    expect(screen.getByText(/Men's and Women's standings/i)).toBeInTheDocument()
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
    expect(screen.getByRole('button', { name: 'Men' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Women' })).toBeInTheDocument()
    expect(screen.getByText('No players in this group.')).toBeInTheDocument()
  })

  it('renders the served band selector, sex toggle, and the ranked, name/code-fallback rows (#212)', () => {
    renderTab()

    // The band dropdown lists the distinct bands; the served group's sex toggles show.
    expect(screen.getByRole('option', { name: 'NTRP 4.0 Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'NTRP 3.5 Band Race' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Men' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Women' })).toBeInTheDocument()

    // "NTRP 4.0 Band Race" appears both as an <option> and the served card heading.
    expect(screen.getAllByText('NTRP 4.0 Band Race').length).toBeGreaterThan(0)
    expect(screen.getByText('Bob Cruz')).toBeInTheDocument()
    expect(screen.getByText('40')).toBeInTheDocument() // age-only meta
    expect(screen.getByText('CCC333')).toBeInTheDocument() // name fallback to code
  })

  it('lists ALL NTRP bands in the dropdown even though only a subset of groups has data (#113)', () => {
    renderTab()
    // groups only covers 4.0 and 3.5, yet the dropdown offers every band, strongest-first.
    const bandSelect = screen.getByLabelText('Band')
    const options = within(bandSelect).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(bands.map((b) => b.label))
    expect(options[0]).toBe('NTRP 6.0+ Band Race') // strongest first
  })

  it('lets an empty band be selected + viewed, keeping the sex toggles queryable (#113)', async () => {
    const user = userEvent.setup()
    renderTab()

    // 5.0 has no data in `groups`, but it is selectable; picking it + View queries that band.
    await user.selectOptions(screen.getByLabelText('Band'), '5.0')
    // The standard sex toggles remain available so the empty band is still queryable.
    expect(screen.getByRole('button', { name: 'Men' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Women' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'View' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '5.0', sex: 'Male', offset: 0 })
    })
  })

  it('renders player name links with the themed content-link style (#417)', () => {
    renderTab()
    // Guards the regression where names used text-primary and vanished on the Grass theme.
    expect(screen.getByRole('link', { name: 'Bob Cruz' })).toHaveClass('content-link')
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveClass('content-link')
  })

  it('re-queries with the chosen band and sex when "View" is clicked', async () => {
    const user = userEvent.setup()
    renderTab()

    await user.selectOptions(screen.getByLabelText('Band'), '3.5')
    await user.click(screen.getByRole('button', { name: 'View' }))

    // The last query reflects the selected band and reset offset.
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '3.5', sex: 'Male', limit: 25, offset: 0 })
    })
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

  it('links each player card to their public profile (#186)', () => {
    renderTab()
    expect(screen.getByRole('link', { name: 'Bob Cruz' })).toHaveAttribute('href', '/players/BBB222')
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveAttribute('href', '/players/CCC333')
  })

  it('switches the served sex when a sex toggle is clicked then "View"ed', async () => {
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Women' }))
    // The pressed state follows the pending selection before "View".
    expect(screen.getByRole('button', { name: 'Women' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Men' })).toHaveAttribute('aria-pressed', 'false')

    await user.click(screen.getByRole('button', { name: 'View' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '4.0', sex: 'Female', offset: 0 })
    })
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

    await user.click(screen.getByRole('button', { name: 'Unspecified' }))
    await user.click(screen.getByRole('button', { name: 'View' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: '4.0', sex: undefined, offset: 0 })
    })
  })

  it('renders an empty-group page (band/label/total absent) with a placeholder heading', async () => {
    // A requested (band, sex) group with no members: groups still list the band, but this page is empty.
    useGetApiV1Standings.mockReturnValue({
      data: { band: null, label: null, sex: null, limit: 25, offset: 0, entries: [], groups, bands, source: 'RATING' },
      isLoading: false,
    })
    const user = userEvent.setup()
    renderTab()

    // The served card heading falls back to "Standings" (no page label) — appearing alongside the tab's
    // own top card title of the same name — and the body reports the empty group.
    expect(screen.getByText('No players in this group.')).toBeInTheDocument()
    expect(screen.getAllByText('Standings').length).toBeGreaterThan(1)

    // With no served band, "View" queries with band undefined (the select shows the first band but the
    // controlled value stays empty until the user picks one).
    await user.click(screen.getByRole('button', { name: 'View' }))
    await waitFor(() => {
      const lastCall = useGetApiV1Standings.mock.calls.at(-1)?.[0]
      expect(lastCall).toMatchObject({ band: undefined, offset: 0 })
    })
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
