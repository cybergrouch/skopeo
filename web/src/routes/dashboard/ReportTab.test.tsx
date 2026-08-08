import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ReportTab } from './ReportTab'

const { useGetApiV1ReportsBandHops } = vi.hoisted(() => ({
  useGetApiV1ReportsBandHops: vi.fn(),
}))

vi.mock('@/api/generated/reports/reports', () => ({ useGetApiV1ReportsBandHops }))

// Stayer: never left the 3.0 band.
const stay01 = {
  publicCode: 'STAY01',
  displayName: 'Stay Er',
  fromBand: '3.0',
  excursionToBand: '3.0',
  excursionDistance: 0,
  netToBand: '3.0',
  netDistance: 0,
}
// Round-tripper: dipped up to 3.5 then back — excursion 1, net 0.
const stay02 = {
  publicCode: 'STAY02',
  displayName: 'Round Trip',
  fromBand: '3.0',
  excursionToBand: '3.5',
  excursionDistance: 1,
  netToBand: '3.0',
  netDistance: 0,
}
// Stayer with no display name (falls back to public code).
const stay03 = {
  publicCode: 'STAY03',
  displayName: null,
  fromBand: '3.5',
  excursionToBand: '3.5',
  excursionDistance: 0,
  netToBand: '3.5',
  netDistance: 0,
}
// Straight one-band drop: excursion 1 == net 1.
const drop01 = {
  publicCode: 'DROP01',
  displayName: 'Drop Per',
  fromBand: '3.5',
  excursionToBand: '3.0',
  excursionDistance: 1,
  netToBand: '3.0',
  netDistance: 1,
}
// Straight two-band jump: excursion 2 == net 2.
const jump01 = {
  publicCode: 'JUMP01',
  displayName: 'Jump Er',
  fromBand: '3.0',
  excursionToBand: '4.0',
  excursionDistance: 2,
  netToBand: '4.0',
  netDistance: 2,
}

const report = {
  startDate: '2026-03-01',
  endDate: '2026-03-31',
  totalPlayers: 5,
  excursionStayedCount: 2,
  excursionJumpedCount: 3,
  excursionBuckets: [
    { hopDistance: 0, count: 2, users: [stay01, stay03] },
    { hopDistance: 1, count: 2, users: [stay02, drop01] },
    { hopDistance: 2, count: 1, users: [jump01] },
  ],
  netStayedCount: 3,
  netJumpedCount: 2,
  netBuckets: [
    { hopDistance: 0, count: 3, users: [stay01, stay02, stay03] },
    { hopDistance: 1, count: 1, users: [drop01] },
    { hopDistance: 2, count: 1, users: [jump01] },
  ],
}

function renderTab() {
  return render(
    <MemoryRouter>
      <ReportTab />
    </MemoryRouter>,
  )
}

const excursionRegion = () =>
  within(screen.getByRole('region', { name: 'By farthest excursion' }))
const netRegion = () => within(screen.getByRole('region', { name: 'By net movement' }))

describe('ReportTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1ReportsBandHops.mockReturnValue({ data: report, isLoading: false, isError: false })
  })

  it('leads with how many players ended in their starting band', () => {
    renderTab()
    expect(
      screen.getByText('3 of 5 players (60%) ended in their starting band'),
    ).toBeInTheDocument()
    expect(screen.getByText(/3 players left their band at some point/)).toBeInTheDocument()
    expect(screen.getByText(/2 ended in a different band/)).toBeInTheDocument()
  })

  it('renders singular counts and an empty jump breakdown', () => {
    const singular = {
      startDate: '2026-03-01',
      endDate: '2026-03-31',
      totalPlayers: 2,
      excursionStayedCount: 1,
      excursionJumpedCount: 1,
      excursionBuckets: [
        { hopDistance: 0, count: 1, users: [stay01] },
        { hopDistance: 1, count: 1, users: [drop01] },
      ],
      netStayedCount: 2,
      netJumpedCount: 0,
      netBuckets: [{ hopDistance: 0, count: 2, users: [stay01, drop01] }],
    }
    useGetApiV1ReportsBandHops.mockReturnValue({ data: singular, isLoading: false, isError: false })
    renderTab()

    // excursionJumpedCount === 1 → singular
    expect(screen.getByText(/1 player left their band at some point/)).toBeInTheDocument()
    // the hopDistance-0 bucket has a single player → singular "player"
    expect(excursionRegion().getByText('Stayed in band — 1 player')).toBeInTheDocument()
    // the net metric has no jump buckets → the empty note renders
    expect(
      netRegion().getByText('No net band changes in this range — everyone ended where they started.'),
    ).toBeInTheDocument()
  })

  it('shows both the excursion and net bucket breakdowns', () => {
    renderTab()
    const excursion = excursionRegion()
    expect(excursion.getByText('Moved 2 bands — 1 player')).toBeInTheDocument()
    expect(excursion.getByText('Moved 1 band — 2 players')).toBeInTheDocument()
    expect(excursion.getByText('Stayed in band — 2 players')).toBeInTheDocument()

    const net = netRegion()
    expect(net.getByText('Moved 2 bands — 1 player')).toBeInTheDocument()
    expect(net.getByText('Moved 1 band — 1 player')).toBeInTheDocument()
    expect(net.getByText('Stayed in band — 3 players')).toBeInTheDocument()
  })

  it('links each player to their public profile', () => {
    renderTab()
    const jumper = excursionRegion().getByRole('link', { name: 'Jump Er' })
    expect(jumper).toHaveAttribute('href', '/players/JUMP01')
  })

  it('shows both excursion and net metrics on every player row', () => {
    renderTab()
    const excursion = excursionRegion()
    // A straight jump: excursion and net agree.
    const jumperRow = excursion.getByRole('link', { name: 'Jump Er' }).closest('li')
    expect(jumperRow).toHaveTextContent('excursion 2 (3.0 → 4.0) · net 2 (3.0 → 4.0)')
    // A round-tripper: excursion 1 but net 0 — visible in the same row.
    const roundTripRow = excursion.getByRole('link', { name: 'Round Trip' }).closest('li')
    expect(roundTripRow).toHaveTextContent('excursion 1 (3.0 → 3.5) · net 0 (3.0 → 3.0)')
  })

  it('collapses the stayed-in-band players behind a disclosure per metric', () => {
    renderTab()
    const net = netRegion()
    expect(net.getByText('Stayed in band — 3 players')).toBeInTheDocument()
    // The round-tripper is a net stayer, so it appears under the net disclosure.
    expect(net.getByRole('link', { name: 'Round Trip' })).toHaveAttribute('href', '/players/STAY02')
    // A player without a display name falls back to their public code as the link label.
    expect(net.getByRole('link', { name: 'STAY03' })).toHaveAttribute('href', '/players/STAY03')
  })

  it('refetches with the chosen date range', () => {
    renderTab()
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-02-01' } })
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-06-30' } })
    const lastCall = useGetApiV1ReportsBandHops.mock.calls.at(-1)
    expect(lastCall?.[0]).toMatchObject({ startDate: '2026-02-01', endDate: '2026-06-30' })
  })

  it('notes each metric separately when no one moved', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({
      data: {
        ...report,
        totalPlayers: 2,
        excursionStayedCount: 2,
        excursionJumpedCount: 0,
        excursionBuckets: [{ hopDistance: 0, count: 2, users: [stay01, stay03] }],
        netStayedCount: 2,
        netJumpedCount: 0,
        netBuckets: [{ hopDistance: 0, count: 2, users: [stay01, stay03] }],
      },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.getByText(/no one left their band at any point/)).toBeInTheDocument()
    expect(screen.getByText(/everyone ended where they started/)).toBeInTheDocument()
  })

  it('renders nothing extra when the query has no data yet and is not loading', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({ data: undefined, isLoading: false, isError: false })
    renderTab()
    expect(screen.queryByText(/ended in their starting band/)).not.toBeInTheDocument()
    expect(screen.queryByText('Loading report…')).not.toBeInTheDocument()
  })

  it('shows a loading state', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    renderTab()
    expect(screen.getByText('Loading report…')).toBeInTheDocument()
  })

  it('shows an error state', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    renderTab()
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load the report')
  })

  it('shows an empty state when there are no rated players', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({
      data: {
        ...report,
        totalPlayers: 0,
        excursionStayedCount: 0,
        excursionJumpedCount: 0,
        excursionBuckets: [],
        netStayedCount: 0,
        netJumpedCount: 0,
        netBuckets: [],
      },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.getByText(/No rated players to report on/)).toBeInTheDocument()
  })
})
