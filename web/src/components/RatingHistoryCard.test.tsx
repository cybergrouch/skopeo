import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RatingHistoryCard } from './RatingHistoryCard'

const entry = (overrides = {}) => ({
  id: 'h1',
  previousRating: '4.000000',
  newRating: '4.100000',
  ratingChange: '0.100000',
  previousLevel: '4.0',
  newLevel: '4.0',
  levelChanged: false,
  smoothingApplied: false,
  calculatedAt: '2026-06-01T12:00:00',
  ...overrides,
})

describe('RatingHistoryCard', () => {
  it('shows a loading state', () => {
    render(<RatingHistoryCard entries={[]} isLoading />)
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when there are no entries', () => {
    render(<RatingHistoryCard entries={[]} />)
    expect(screen.getByText('No rating changes yet.')).toBeInTheDocument()
  })

  it('shows the full value and the band, and highlights a band change', () => {
    const { container } = render(
      <RatingHistoryCard
        entries={[
          entry({ id: 'h1', newRating: '4.300000', newLevel: '4.5', levelChanged: true }),
        ]}
      />,
    )
    expect(screen.getByText('4.000000 → 4.300000')).toBeInTheDocument()
    expect(screen.getByText('NTRP 4.0 → 4.5')).toBeInTheDocument()
    // Band-change badge + accent row.
    expect(screen.getByText('Band 4.0 → 4.5')).toBeInTheDocument()
    expect(container.querySelector('.border-primary')).not.toBeNull()
  })

  it('does not highlight or badge a row without a band change', () => {
    const { container } = render(<RatingHistoryCard entries={[entry()]} />)
    expect(screen.getByText('NTRP 4.0 → 4.0')).toBeInTheDocument()
    expect(screen.queryByText(/^Band /)).not.toBeInTheDocument()
    expect(container.querySelector('.border-primary')).toBeNull()
  })

  it('falls back to a dash when a band is missing', () => {
    render(
      <RatingHistoryCard
        entries={[entry({ previousLevel: null, newLevel: null })]}
      />,
    )
    expect(screen.getByText('NTRP — → —')).toBeInTheDocument()
  })

  it('uses the provided description', () => {
    render(<RatingHistoryCard entries={[]} description="Full rating history (admin view)." />)
    expect(
      screen.getByText('Full rating history (admin view).'),
    ).toBeInTheDocument()
  })
})
