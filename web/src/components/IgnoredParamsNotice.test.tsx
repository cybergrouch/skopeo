import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { IgnoredParamsNotice } from './IgnoredParamsNotice'

describe('IgnoredParamsNotice', () => {
  it('renders nothing when every param in the link was usable', () => {
    const { container } = render(<IgnoredParamsNotice ignored={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the one unusable param, in the singular', () => {
    render(<IgnoredParamsNotice ignored={['upcoming.page']} />)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'This link had 1 unreadable view setting (upcoming.page), so it was ignored and the default is shown instead.',
    )
  })

  it('names several unusable params, in the plural', () => {
    render(<IgnoredParamsNotice ignored={['page', 'band']} />)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'This link had 2 unreadable view settings (page, band), so they were ignored and the default is shown instead.',
    )
  })
})
