import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ContentLink } from './ContentLink'

function renderLink(props: { to: string; className?: string }) {
  return render(
    <MemoryRouter>
      <ContentLink to={props.to} className={props.className}>
        Jane Doe (PLYR01)
      </ContentLink>
    </MemoryRouter>,
  )
}

describe('ContentLink', () => {
  it('renders an anchor to the target wearing the token-based content-link class (#417)', () => {
    renderLink({ to: '/players/PLYR01' })
    const link = screen.getByRole('link', { name: 'Jane Doe (PLYR01)' })
    expect(link).toHaveAttribute('href', '/players/PLYR01')
    // The per-theme --link/--link-underline/--link-hover treatment is applied via this class,
    // keeping inline links WCAG-AA readable in every seasonal theme (grass = Optic Tennis Yellow).
    expect(link).toHaveClass('content-link')
  })

  it('merges caller layout classes alongside the shared treatment', () => {
    renderLink({ to: '/players/PLYR01/matches', className: 'inline-block text-sm' })
    const link = screen.getByRole('link', { name: 'Jane Doe (PLYR01)' })
    expect(link).toHaveClass('content-link')
    expect(link).toHaveClass('inline-block', 'text-sm')
  })
})
