import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SetRatingForm } from './SetRatingForm'

const { putMutate, onSaved, state } = vi.hoisted(() => ({
  putMutate: vi.fn(),
  onSaved: vi.fn(),
  state: { pending: false, fail: false },
}))

vi.mock('@/api/generated/ratings/ratings', () => ({
  usePutApiV1UsersUserIdRatings: () => ({
    isPending: state.pending,
    mutateAsync: async (vars: unknown) => {
      if (state.fail) throw new Error('boom')
      return putMutate(vars)
    },
  }),
}))

describe('SetRatingForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.pending = false
    state.fail = false
  })

  it('preselects the band and submits it, then calls onSaved', async () => {
    const user = userEvent.setup()
    render(<SetRatingForm userId="u1" initialValue="3.5" onSaved={onSaved} />)

    expect(screen.getByLabelText('Rating')).toHaveValue('3.5')
    await user.selectOptions(screen.getByLabelText('Rating'), '4.5')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    // The band is sent; the backend stores its midpoint.
    await waitFor(() => expect(putMutate).toHaveBeenCalledWith({ userId: 'u1', data: { band: '4.5' } }))
    expect(onSaved).toHaveBeenCalled()
  })

  it('shows a busy label while saving', () => {
    state.pending = true
    render(<SetRatingForm userId="u1" />)
    expect(screen.getByRole('button', { name: 'Setting…' })).toBeDisabled()
  })

  it('surfaces an error and does not call onSaved when the save fails', async () => {
    state.fail = true
    const user = userEvent.setup()
    render(<SetRatingForm userId="u1" initialValue="4.0" onSaved={onSaved} />)

    await user.click(screen.getByRole('button', { name: 'Set rating' }))
    expect(await screen.findByText(/Could not set the rating/i)).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
  })
})
