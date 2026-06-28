import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { InvitesTab } from './InvitesTab'

vi.mock('./admin/InvitesSection', () => ({
  InvitesSection: () => <div>invites section</div>,
}))

describe('InvitesTab', () => {
  it('renders the invites management section', () => {
    render(<InvitesTab />)
    expect(screen.getByText('invites section')).toBeInTheDocument()
  })
})
