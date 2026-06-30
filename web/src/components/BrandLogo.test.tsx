import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BrandLogo } from './BrandLogo'

describe('BrandLogo', () => {
  it('renders the wordmark and the icon', () => {
    const { container } = render(<BrandLogo />)
    expect(screen.getByText('Skopeo')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', '/logo-mark.svg')
  })

  it('applies a custom class', () => {
    const { container } = render(<BrandLogo className="text-lg" />)
    expect(container.firstChild).toHaveClass('text-lg')
  })
})
