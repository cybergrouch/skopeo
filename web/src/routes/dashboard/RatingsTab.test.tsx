import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RatingsTab } from './RatingsTab'

vi.mock('./admin/PendingAssessmentSection', () => ({
  PendingAssessmentSection: () => <div>pending assessment section</div>,
}))

describe('RatingsTab', () => {
  it('renders the pending-assessment queue', () => {
    render(<RatingsTab />)
    expect(screen.getByText('pending assessment section')).toBeInTheDocument()
  })
})
