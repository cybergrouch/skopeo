import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ReportTab } from './ReportTab'

const { useGetApiV1ReportsBandHops } = vi.hoisted(() => ({
  useGetApiV1ReportsBandHops: vi.fn(),
}))

vi.mock('@/api/generated/reports/reports', () => ({ useGetApiV1ReportsBandHops }))

const report = {
  startDate: '2026-03-01',
  endDate: '2026-03-31',
  totalPlayers: 5,
  stayedCount: 3,
  jumpedCount: 2,
  buckets: [
    {
      hopDistance: 0,
      count: 3,
      users: [
        { publicCode: 'STAY01', displayName: 'Stay Er', fromBand: '3.0', toBand: '3.0' },
        { publicCode: 'STAY02', displayName: 'Round Trip', fromBand: '3.0', toBand: '3.0' },
        { publicCode: 'STAY03', displayName: null, fromBand: '3.5', toBand: '3.5' },
      ],
    },
    {
      hopDistance: 1,
      count: 1,
      users: [{ publicCode: 'DROP01', displayName: 'Drop Per', fromBand: '3.5', toBand: '3.0' }],
    },
    {
      hopDistance: 2,
      count: 1,
      users: [{ publicCode: 'JUMP01', displayName: 'Jump Er', fromBand: '3.0', toBand: '4.0' }],
    },
  ],
}

function renderTab() {
  return render(
    <MemoryRouter>
      <ReportTab />
    </MemoryRouter>,
  )
}

describe('ReportTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1ReportsBandHops.mockReturnValue({ data: report, isLoading: false, isError: false })
  })

  it('leads with how many players stayed within their band', () => {
    renderTab()
    expect(
      screen.getByText('3 of 5 players (60%) stayed within their band'),
    ).toBeInTheDocument()
    expect(screen.getByText(/2 players moved at least one band/)).toBeInTheDocument()
  })

  it('lists each jump bucket with a link to the player and the band move', () => {
    renderTab()
    expect(screen.getByText('Moved 2 bands — 1 player')).toBeInTheDocument()
    expect(screen.getByText('Moved 1 band — 1 player')).toBeInTheDocument()

    const jumper = screen.getByRole('link', { name: 'Jump Er' })
    expect(jumper).toHaveAttribute('href', '/players/JUMP01')
    expect(screen.getByText('3.0 → 4.0')).toBeInTheDocument()
    expect(screen.getByText('3.5 → 3.0')).toBeInTheDocument()
  })

  it('collapses the stayed-in-band players behind a disclosure', () => {
    renderTab()
    expect(screen.getByText('Stayed in band — 3 players')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Round Trip' })).toHaveAttribute(
      'href',
      '/players/STAY02',
    )
    // A player without a display name falls back to their public code as the link label.
    expect(screen.getByRole('link', { name: 'STAY03' })).toHaveAttribute('href', '/players/STAY03')
  })

  it('omits the stayed disclosure when every player jumped', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({
      data: {
        ...report,
        totalPlayers: 1,
        stayedCount: 0,
        jumpedCount: 1,
        buckets: [report.buckets[2]],
      },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.queryByText(/Stayed in band/)).not.toBeInTheDocument()
  })

  it('refetches with the chosen date range', () => {
    renderTab()
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-02-01' } })
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-06-30' } })
    const lastCall = useGetApiV1ReportsBandHops.mock.calls.at(-1)
    expect(lastCall?.[0]).toMatchObject({ startDate: '2026-02-01', endDate: '2026-06-30' })
  })

  it('pluralises a jump bucket with more than one player and a single-player stayed bucket', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({
      data: {
        ...report,
        totalPlayers: 3,
        stayedCount: 1,
        jumpedCount: 2,
        buckets: [
          { hopDistance: 0, count: 1, users: [report.buckets[0].users[0]] },
          {
            hopDistance: 1,
            count: 2,
            users: [
              { publicCode: 'D1', displayName: 'Dee One', fromBand: '3.5', toBand: '3.0' },
              { publicCode: 'D2', displayName: 'Dee Two', fromBand: '4.0', toBand: '3.5' },
            ],
          },
        ],
      },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.getByText('Moved 1 band — 2 players')).toBeInTheDocument()
    expect(screen.getByText('Stayed in band — 1 player')).toBeInTheDocument()
  })

  it('renders nothing extra when the query has no data yet and is not loading', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({ data: undefined, isLoading: false, isError: false })
    renderTab()
    // Header card is always shown; there is no headline/summary without data.
    expect(screen.queryByText(/stayed within their band/)).not.toBeInTheDocument()
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
      data: { ...report, totalPlayers: 0, stayedCount: 0, jumpedCount: 0, buckets: [] },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.getByText(/No rated players to report on/)).toBeInTheDocument()
  })

  it('notes when no one jumped', () => {
    useGetApiV1ReportsBandHops.mockReturnValue({
      data: {
        ...report,
        totalPlayers: 3,
        stayedCount: 3,
        jumpedCount: 0,
        buckets: [report.buckets[0]],
      },
      isLoading: false,
      isError: false,
    })
    renderTab()
    expect(screen.getByText(/everyone stayed within their band/)).toBeInTheDocument()
  })
})
