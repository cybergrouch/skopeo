import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminTab } from './AdminTab'

vi.mock('./admin/PendingCalculationSection', () => ({
  PendingCalculationSection: () => <div>pending calculation section</div>,
}))
vi.mock('./admin/ManagePlayerSection', () => ({
  ManagePlayerSection: () => <div>manage player section</div>,
}))
vi.mock('./admin/DuplicatesSection', () => ({
  DuplicatesSection: () => <div>duplicates section</div>,
}))
vi.mock('./admin/DuplicateCandidatesSection', () => ({
  DuplicateCandidatesSection: () => <div>duplicate candidates section</div>,
}))
vi.mock('./admin/ActivityLogSection', () => ({
  ActivityLogSection: () => <div>activity log section</div>,
}))

describe('AdminTab', () => {
  it('renders the admin sections (invites now live in their own tab, #135)', () => {
    render(<AdminTab />)
    expect(screen.getByText('manage player section')).toBeInTheDocument()
    expect(screen.getByText('duplicates section')).toBeInTheDocument()
    expect(screen.getByText('duplicate candidates section')).toBeInTheDocument()
    expect(screen.getByText('pending calculation section')).toBeInTheDocument()
    expect(screen.getByText('activity log section')).toBeInTheDocument()
    expect(screen.queryByText('invites section')).not.toBeInTheDocument()
  })
})
