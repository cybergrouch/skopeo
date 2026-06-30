import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BuildInfoSection } from './BuildInfoSection'

const { useApiHealth } = vi.hoisted(() => ({ useApiHealth: vi.fn() }))
vi.mock('@/api/health', () => ({ useApiHealth }))

function health(version?: string, isLoading = false) {
  return { data: version ? { status: 'UP', service: 'Skopeo API', version } : undefined, isLoading }
}

describe('BuildInfoSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubEnv('VITE_APP_VERSION', 'v0.0.2')
    vi.stubEnv('VITE_APP_COMMIT', '')
  })
  afterEach(() => vi.unstubAllEnvs())

  it('shows the web and API versions and an in-sync note when they match (#229)', () => {
    useApiHealth.mockReturnValue(health('0.0.2'))
    render(<BuildInfoSection />)
    expect(screen.getByText('v0.0.2')).toBeInTheDocument() // web (tag)
    expect(screen.getByText('0.0.2')).toBeInTheDocument() // api
    expect(screen.getByText('Web and API are in sync.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('warns when the web and API versions differ (#229)', () => {
    useApiHealth.mockReturnValue(health('0.0.1'))
    render(<BuildInfoSection />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(/Web \(v0\.0\.2\) and API \(0\.0\.1\) versions differ/i)
  })

  it('shows the commit short hash when present', () => {
    vi.stubEnv('VITE_APP_COMMIT', 'abcdef1234567')
    useApiHealth.mockReturnValue(health('0.0.2'))
    render(<BuildInfoSection />)
    expect(screen.getByText('v0.0.2 (abcdef1)')).toBeInTheDocument()
  })

  it('shows the API as unavailable (and does not compare) when /health fails', () => {
    useApiHealth.mockReturnValue(health(undefined))
    render(<BuildInfoSection />)
    expect(screen.getByText('unavailable')).toBeInTheDocument()
    expect(screen.queryByText('Web and API are in sync.')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows a checking state while /health loads', () => {
    useApiHealth.mockReturnValue(health(undefined, true))
    render(<BuildInfoSection />)
    expect(screen.getByText('checking…')).toBeInTheDocument()
  })

  it('does not compare when the web build is local dev', () => {
    vi.stubEnv('VITE_APP_VERSION', '') // → webVersion() falls back to "dev"
    useApiHealth.mockReturnValue(health('0.0.2'))
    render(<BuildInfoSection />)
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.queryByText('Web and API are in sync.')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
