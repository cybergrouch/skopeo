import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminTab } from './AdminTab'

vi.mock('./admin/PendingAssessmentSection', () => ({
  PendingAssessmentSection: () => <div>pending assessment section</div>,
}))
vi.mock('./admin/PendingCalculationSection', () => ({
  PendingCalculationSection: () => <div>pending calculation section</div>,
}))

describe('AdminTab', () => {
  it('renders both admin sections', () => {
    render(<AdminTab />)
    expect(screen.getByText('pending assessment section')).toBeInTheDocument()
    expect(screen.getByText('pending calculation section')).toBeInTheDocument()
  })
})
