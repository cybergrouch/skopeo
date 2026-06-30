import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NtrpSelfRatingSelect } from './NtrpSelfRatingSelect'

describe('NtrpSelfRatingSelect', () => {
  it('renders a required select spanning 1.0–7.0 and reports a choice', async () => {
    const onChange = vi.fn()
    render(<NtrpSelfRatingSelect value="" onChange={onChange} />)

    const select = screen.getByLabelText('NTRP self-rating')
    expect(select).toBeRequired()
    // The full NTRP band range is offered (1.0 … 7.0).
    expect(screen.getByRole('option', { name: '1.0' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '7.0' })).toBeInTheDocument()
    // The empty placeholder is disabled, so the required select can't submit unset.
    expect(screen.getByRole('option', { name: 'Select your level…' })).toBeDisabled()

    await userEvent.setup().selectOptions(select, '4.5')
    expect(onChange).toHaveBeenCalledWith('4.5')
  })

  it('links to the NTRP self-rating guide (#203)', () => {
    render(<NtrpSelfRatingSelect value="" onChange={() => {}} />)
    const link = screen.getByRole('link', { name: /self-rating guide/i })
    expect(link).toHaveAttribute('href', 'https://www.teamtopspin.com/tennis-self-rating')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })
})
