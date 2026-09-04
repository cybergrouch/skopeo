import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NtrpLabel, NTRP_DISCLAIMER } from './NtrpLabel'

describe('NtrpLabel', () => {
  it('states the disclaimer verbatim', () => {
    // Pinned exactly: this is legal copy (#842), so a well-meaning reword should fail a test rather
    // than ship. The string lives in one place precisely so it cannot drift between screens.
    expect(NTRP_DISCLAIMER).toBe(
      'NTRP is a reference framework. This platform is not affiliated with or endorsed by the United States Tennis Association (USTA).',
    )
  })

  it('opens the disclaimer on click and closes on Escape', async () => {
    const user = userEvent.setup()
    render(<NtrpLabel />)
    expect(screen.queryByText(NTRP_DISCLAIMER)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /about this rating framework/i }))
    expect(screen.getByText(NTRP_DISCLAIMER)).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByText(NTRP_DISCLAIMER)).not.toBeInTheDocument()
  })

  it('is reachable by keyboard alone', async () => {
    // The trigger is a real button, so it takes focus in tab order and opens on Enter — the disclaimer
    // must not be mouse-only.
    const user = userEvent.setup()
    render(<NtrpLabel />)
    await user.tab()
    expect(screen.getByRole('button', { name: /about this rating framework/i })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByText(NTRP_DISCLAIMER)).toBeInTheDocument()
  })

  it('renders the term by default and honours an override', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<NtrpLabel />)
    expect(screen.getByRole('button')).toHaveTextContent('NTRP')
    unmount()

    render(<NtrpLabel>NTRP self-rating</NtrpLabel>)
    expect(screen.getByRole('button')).toHaveTextContent('NTRP self-rating')
    await user.click(screen.getByRole('button'))
    expect(screen.getByText(NTRP_DISCLAIMER)).toBeInTheDocument()
  })

  it('marks a calibrating rating and explains it in the popover (#881)', async () => {
    const user = userEvent.setup()
    render(<NtrpLabel value="4.0" calibrating />)

    // The marker joins the SAME text node — a sibling would make "NTRP 4.0" unfindable by getByText and
    // silently break assertions across the suite, which this component's own docs warn about.
    const trigger = screen.getByRole('button', { name: /still being calibrated/ })
    expect(trigger).toHaveTextContent('NTRP 4.0*')

    await user.click(trigger)

    // Both notes: the calibration explanation AND the standing disclaimer. The marker only signals that
    // there is something to read; the sentence is where it is actually said.
    expect(await screen.findByText(/still being calibrated/)).toBeInTheDocument()
    expect(screen.getByText(NTRP_DISCLAIMER)).toBeInTheDocument()
  })

  it('adds no marker and no calibration note when the rating is settled (#881)', async () => {
    const user = userEvent.setup()
    render(<NtrpLabel value="4.0" />)

    // The default must be untouched: NTRP renders many times per screen, and a marker or an extra
    // sentence on every settled rating would be noise on every roster in the app.
    const trigger = screen.getByRole('button', { name: 'NTRP — about this rating framework' })
    expect(trigger).toHaveTextContent('NTRP 4.0')
    expect(trigger.textContent).not.toContain('*')

    await user.click(trigger)

    expect(await screen.findByText(NTRP_DISCLAIMER)).toBeInTheDocument()
    expect(screen.queryByText(/still being calibrated/)).not.toBeInTheDocument()
  })
})
