import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HandicapField } from './HandicapField'

describe('HandicapField', () => {
  it('reveals both side inputs when enabled and reports each side change (#486)', async () => {
    const user = userEvent.setup()
    const onTeam1Change = vi.fn()
    const onTeam2Change = vi.fn()
    render(
      <HandicapField
        enabled
        onToggle={() => {}}
        team1Handicap=""
        team2Handicap=""
        onTeam1Change={onTeam1Change}
        onTeam2Change={onTeam2Change}
      />,
    )

    // Typing in the Side 1 input fires onTeam1Change with the entered value.
    await user.type(screen.getByLabelText('Side 1 handicap'), '0.4')
    expect(onTeam1Change).toHaveBeenLastCalledWith('0.4')

    // Side 2 behaves symmetrically.
    await user.type(screen.getByLabelText('Side 2 handicap'), '0.2')
    expect(onTeam2Change).toHaveBeenLastCalledWith('0.2')
  })

  it('hides the inputs and reports toggling when disabled/unticked (#486)', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    render(
      <HandicapField
        enabled={false}
        onToggle={onToggle}
        team1Handicap=""
        team2Handicap=""
        onTeam1Change={() => {}}
        onTeam2Change={() => {}}
      />,
    )

    // Un-ticked → the two inputs are not rendered.
    expect(screen.queryByLabelText('Side 1 handicap')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Side 2 handicap')).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('Apply handicap'))
    expect(onToggle).toHaveBeenCalledWith(true)
  })

  it('labels each side from the supplied labels (#486)', () => {
    render(
      <HandicapField
        enabled
        onToggle={() => {}}
        team1Handicap=""
        team2Handicap=""
        onTeam1Change={() => {}}
        onTeam2Change={() => {}}
        team1Label="Ana"
        team2Label="Bob"
      />,
    )
    expect(screen.getByLabelText('Ana handicap')).toBeInTheDocument()
    expect(screen.getByLabelText('Bob handicap')).toBeInTheDocument()
  })
})
