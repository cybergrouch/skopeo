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

  it('shows a "No standings yet." message when the snapshot is empty', () => {
    useGetApiV1Standings.mockReturnValue({
      data: { band: null, label: null, sex: null, limit: 25, offset: 0, total: 0, entries: [], groups: [] },
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText('No standings yet.')).toBeInTheDocument()
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
