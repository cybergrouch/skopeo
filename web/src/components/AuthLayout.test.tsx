import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AuthLayout } from './AuthLayout'

describe('AuthLayout', () => {
  it('renders the title, description, and children', () => {
    render(
      <AuthLayout title="Title" description="Description">
        <p>body</p>
      </AuthLayout>,
    )
    expect(screen.getByText('Title')).toBeInTheDocument()
    expect(screen.getByText('Description')).toBeInTheDocument()
    expect(screen.getByText('body')).toBeInTheDocument()
  })

  it('renders the Skopeo brand, logo, and app description', () => {
    render(
      <AuthLayout title="Welcome back" description="Sign in">
        <p>body</p>
      </AuthLayout>,
    )
    expect(screen.getByText('Skopeo')).toBeInTheDocument()
    const logo = screen.getByAltText('Skopeo')
    expect(logo).toBeInTheDocument()
    expect(logo).toHaveAttribute('src', '/logo-mark.svg')
    expect(
      screen.getByText(
        /Skopeo calculates performance-based NTRP tennis ratings/i,
      ),
    ).toBeInTheDocument()
  })

  it('renders the footer when provided', () => {
    render(
      <AuthLayout title="t" description="d" footer={<span>footer text</span>}>
        <p>body</p>
      </AuthLayout>,
    )
    expect(screen.getByText('footer text')).toBeInTheDocument()
  })

  it('omits the footer region when none is provided', () => {
    render(
      <AuthLayout title="t" description="d">
        <p>body</p>
      </AuthLayout>,
    )
    expect(screen.queryByText('footer text')).not.toBeInTheDocument()
  })
})
