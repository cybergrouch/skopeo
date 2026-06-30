import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
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

const { useGetApiV1Standings, useGetApiV1UsersMe } = vi.hoisted(() => ({
  useGetApiV1Standings: vi.fn(),
  useGetApiV1UsersMe: vi.fn(),
}))

vi.mock('@/api/generated/standings/standings', () => ({ useGetApiV1Standings }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1UsersMe }))

// One (band, sex) row each: Men in two bands, Women in one, plus an Unspecified group.
const bands = [
  {
    band: '4.0–4.5',
    sex: 'Male',
    entries: [
      { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40 },
      // No display name → falls back to the public code; no age → meta omitted.
      { rank: 2, userId: 'm2', displayName: null, publicCode: 'CCC333', sex: 'Male', age: null },
    ],
  },
  {
    band: '4.0–4.5',
    sex: 'Female',
    entries: [{ rank: 1, userId: 'f1', displayName: 'Ana Cruz', publicCode: 'AAA111', sex: 'Female', age: 34 }],
  },
  {
    band: '3.5–4.0',
    sex: 'Male',
    entries: [{ rank: 1, userId: 'm3', displayName: 'Cy Young', publicCode: 'DDD444', sex: 'Male', age: 28 }],
  },
  {
    band: '4.0–4.5',
    sex: null,
    entries: [{ rank: 1, userId: 'u1', displayName: 'No Sex', publicCode: 'EEE555', sex: null, age: null }],
  },
]

describe('StandingsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1UsersMe.mockReturnValue({ data: { id: 'm1' } })
  })

  it('shows a loading state while standings resolve', () => {
    useGetApiV1Standings.mockReturnValue({ data: undefined, isLoading: true })
    renderTab()
    expect(screen.getByText('Loading standings…')).toBeInTheDocument()
  })

  it('renders an interim, sex-split description', () => {
    useGetApiV1Standings.mockReturnValue({ data: [], isLoading: false })
    renderTab()
    expect(screen.getByText(/Interim standings/i)).toBeInTheDocument()
    expect(screen.getByText(/Men's and Women's standings/i)).toBeInTheDocument()
  })

  it('shows a "No standings yet." message when there are no players', () => {
    useGetApiV1Standings.mockReturnValue({ data: [], isLoading: false })
    renderTab()
    expect(screen.getByText('No standings yet.')).toBeInTheDocument()
  })

  it('defaults to Men: shows only Male bands, ranks, name/code fallback, and age (#212)', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    renderTab()

    // The toggle offers Men, Women, and Unspecified (since a null-sex player exists).
    expect(screen.getByRole('button', { name: 'Men' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Women' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Unspecified' })).toBeInTheDocument()

    // Both Men's bands show; Ana (Female) does not.
    expect(screen.getByText('4.0–4.5')).toBeInTheDocument()
    expect(screen.getByText('3.5–4.0')).toBeInTheDocument()
    expect(screen.getByText('Bob Cruz')).toBeInTheDocument()
    expect(screen.getByText('40')).toBeInTheDocument() // age-only meta
    expect(screen.getByText('CCC333')).toBeInTheDocument() // name fallback to code
    expect(screen.queryByText('Ana Cruz')).not.toBeInTheDocument()
  })

  it('switches to Women when toggled, showing only Female bands (#212)', async () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Women' }))
    expect(screen.getByText('Ana Cruz')).toBeInTheDocument()
    expect(screen.queryByText('Bob Cruz')).not.toBeInTheDocument()
    expect(screen.queryByText('3.5–4.0')).not.toBeInTheDocument() // no Women in that band
  })

  it('switches to the Unspecified group when toggled (#212)', async () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Unspecified' }))
    expect(screen.getByText('No Sex')).toBeInTheDocument()
    expect(screen.queryByText('Bob Cruz')).not.toBeInTheDocument()
  })

  it('hides the toggle when only one sex group is present', () => {
    useGetApiV1Standings.mockReturnValue({ data: [bands[0]], isLoading: false }) // Men only
    renderTab()
    expect(screen.queryByRole('button', { name: 'Men' })).not.toBeInTheDocument()
    expect(screen.getByText('Bob Cruz')).toBeInTheDocument()
  })

  it('highlights only the current user’s own row', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    renderTab()

    // me.id is m1 → its row carries the marker (Men is the default tab).
    const myRow = screen.getByLabelText('Your standing')
    expect(within(myRow).getByText('Bob Cruz')).toBeInTheDocument()
    expect(within(myRow).getByText('You')).toBeInTheDocument()
    expect(screen.getAllByLabelText('Your standing')).toHaveLength(1)
  })

  it('highlights no row when the current user is unknown', () => {
    useGetApiV1UsersMe.mockReturnValue({ data: undefined })
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    renderTab()
    expect(screen.queryByLabelText('Your standing')).not.toBeInTheDocument()
  })

  it('shows no rating value when the payload omits it (non-privileged viewer)', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    const { container } = renderTab()
    container.querySelectorAll('li').forEach((row) => {
      expect(row.textContent ?? '').not.toMatch(/\d\.\d/)
    })
  })

  it('links each player card to their public profile (#186)', () => {
    useGetApiV1Standings.mockReturnValue({ data: bands, isLoading: false })
    renderTab()
    expect(screen.getByRole('link', { name: 'Bob Cruz' })).toHaveAttribute('href', '/players/BBB222')
    // The name falls back to the code and still links to the profile.
    expect(screen.getByRole('link', { name: 'CCC333' })).toHaveAttribute('href', '/players/CCC333')
  })

  it('shows the precise rating when the payload includes it (raters/admins, #186)', () => {
    useGetApiV1Standings.mockReturnValue({
      data: [
        {
          band: '4.0–4.5',
          sex: 'Male',
          entries: [
            { rank: 1, userId: 'm1', displayName: 'Bob Cruz', publicCode: 'BBB222', sex: 'Male', age: 40, currentRating: '4.230000' },
          ],
        },
      ],
      isLoading: false,
    })
    renderTab()
    expect(screen.getByText('4.230000')).toBeInTheDocument()
  })
})
