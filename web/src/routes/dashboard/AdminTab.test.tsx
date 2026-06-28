import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminTab } from './AdminTab'

vi.mock('./admin/InvitesSection', () => ({
  InvitesSection: () => <div>invites section</div>,
}))
vi.mock('./admin/PendingAssessmentSection', () => ({
  PendingAssessmentSection: () => <div>pending assessment section</div>,
}))
vi.mock('./admin/PendingCalculationSection', () => ({
  PendingCalculationSection: () => <div>pending calculation section</div>,
}))
vi.mock('./admin/ManagePlayerSection', () => ({
  ManagePlayerSection: () => <div>manage player section</div>,
}))
vi.mock('./admin/DuplicatesSection', () => ({
  DuplicatesSection: () => <div>duplicates section</div>,
}))
vi.mock('./admin/ActivityLogSection', () => ({
  ActivityLogSection: () => <div>activity log section</div>,
}))

describe('AdminTab', () => {
  it('renders the admin sections', () => {
    render(<AdminTab />)
    expect(screen.getByText('invites section')).toBeInTheDocument()
    expect(screen.getByText('pending assessment section')).toBeInTheDocument()
    expect(screen.getByText('manage player section')).toBeInTheDocument()
    expect(screen.getByText('duplicates section')).toBeInTheDocument()
    expect(screen.getByText('pending calculation section')).toBeInTheDocument()
    expect(screen.getByText('activity log section')).toBeInTheDocument()
  })
})
