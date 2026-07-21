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
})
