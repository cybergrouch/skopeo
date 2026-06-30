import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RatingsTab } from './RatingsTab'

vi.mock('./admin/PendingAssessmentSection', () => ({
  PendingAssessmentSection: () => <div>pending assessment section</div>,
}))
vi.mock('./admin/RatingRequestSection', () => ({
  RatingRequestSection: () => <div>re-rate request section</div>,
}))
vi.mock('./admin/RatingsSearchSection', () => ({
  RatingsSearchSection: () => <div>search and rate section</div>,
}))

describe('RatingsTab', () => {
  it('renders the pending queue, re-rate requests, and search-and-rate (#140, #205)', () => {
    render(<RatingsTab />)
    expect(screen.getByText('pending assessment section')).toBeInTheDocument()
    expect(screen.getByText('re-rate request section')).toBeInTheDocument()
    expect(screen.getByText('search and rate section')).toBeInTheDocument()
  })
})
