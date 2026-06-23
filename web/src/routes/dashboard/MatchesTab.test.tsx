import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MatchesTab } from './MatchesTab'

vi.mock('./matches/CreateFixtureSection', () => ({
  CreateFixtureSection: () => <div>create fixture section</div>,
}))
vi.mock('./matches/AwaitingResultsSection', () => ({
  AwaitingResultsSection: () => <div>awaiting results section</div>,
}))

describe('MatchesTab', () => {
  it('renders the create-fixture and awaiting-results sections', () => {
    render(<MatchesTab />)
    expect(screen.getByText('create fixture section')).toBeInTheDocument()
    expect(screen.getByText('awaiting results section')).toBeInTheDocument()
  })
})
