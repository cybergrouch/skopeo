import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ActivityTab } from './ActivityTab'

vi.mock('./admin/ActivityLogSection', () => ({
  ActivityLogSection: () => <div>activity log section</div>,
}))

describe('ActivityTab', () => {
  it('renders the activity log section', () => {
    render(<ActivityTab />)
    expect(screen.getByText('activity log section')).toBeInTheDocument()
  })
})
