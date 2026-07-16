import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { PublicPageLink } from './PublicPageLink'

function renderLink(props: { to: string; className?: string }) {
  return render(
    <MemoryRouter>
      <PublicPageLink to={props.to} className={props.className}>
        Public page (QR)
      </PublicPageLink>
    </MemoryRouter>,
  )
}

describe('PublicPageLink', () => {
  it('renders an anchor to the target wearing the token-based public-page-link class (#394)', () => {
    renderLink({ to: '/matches/MTCH01' })
    const link = screen.getByRole('link', { name: 'Public page (QR)' })
    expect(link).toHaveAttribute('href', '/matches/MTCH01')
    // The per-theme --link/--link-underline/--link-hover treatment is applied via this class.
    expect(link).toHaveClass('public-page-link')
  })

  it('merges caller layout classes alongside the shared treatment', () => {
    renderLink({ to: '/events/EVT001', className: 'mt-1 inline-block text-xs' })
    const link = screen.getByRole('link', { name: 'Public page (QR)' })
    expect(link).toHaveClass('public-page-link')
    expect(link).toHaveClass('mt-1', 'inline-block', 'text-xs')
  })
})
