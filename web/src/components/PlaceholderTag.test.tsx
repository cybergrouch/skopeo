import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PlaceholderTag } from './PlaceholderTag'

describe('PlaceholderTag (#505)', () => {
  it('renders the shared "Unclaimed" tag for a placeholder player', () => {
    render(<PlaceholderTag show={true} />)
    expect(screen.getByText('Unclaimed')).toBeInTheDocument()
  })

  it('renders nothing for a normal (non-placeholder) player', () => {
    const { container } = render(<PlaceholderTag show={false} />)
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('Unclaimed')).not.toBeInTheDocument()
  })

  it('renders nothing when the flag is absent (null/undefined default)', () => {
    const { container } = render(<PlaceholderTag show={undefined} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a "Deleted" chip for a soft-deleted account (#518)', () => {
    render(<PlaceholderTag show={false} deleted={true} />)
    expect(screen.getByText('Deleted')).toBeInTheDocument()
  })

  it('shows only "Deleted" when an account is both deleted and a placeholder — Deleted dominates (#518)', () => {
    render(<PlaceholderTag show={true} deleted={true} />)
    expect(screen.getByText('Deleted')).toBeInTheDocument()
    expect(screen.queryByText('Unclaimed')).not.toBeInTheDocument()
  })

  it('renders nothing for an active, non-placeholder account even with deleted=false', () => {
    const { container } = render(<PlaceholderTag show={false} deleted={false} />)
    expect(container).toBeEmptyDOMElement()
  })
})
