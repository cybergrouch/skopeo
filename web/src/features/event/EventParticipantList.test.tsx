import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { EventParticipantResponse } from '@/api/generated/model'
import { EventParticipantList } from './EventParticipantList'

const { putMutate } = vi.hoisted(() => ({ putMutate: vi.fn() }))

vi.mock('@/api/generated/ratings/ratings', () => ({
  usePutApiV1UsersUserIdRatings: () => ({
    isPending: false,
    mutateAsync: async (vars: unknown) => putMutate(vars),
  }),
}))
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

function participant(over: Partial<EventParticipantResponse> = {}): EventParticipantResponse {
  return {
    userId: 'u1',
    displayName: 'Ana',
    publicCode: 'AAA111',
    isPlaceholder: false,
    isDeleted: false,
    ...over,
  } as EventParticipantResponse
}

const rated = participant({
  userId: 'u2',
  displayName: 'Bob',
  publicCode: 'BBB222',
  rating: { level: '4.0', confidence: '0' },
} as Partial<EventParticipantResponse>)

function renderList(props: Parameters<typeof EventParticipantList>[0]) {
  return render(
    <MemoryRouter>
      <EventParticipantList {...props} />
    </MemoryRouter>,
  )
}

describe('EventParticipantList', () => {
  beforeEach(() => vi.clearAllMocks())

  it('says nothing about ratings for a viewer who cannot rate (#907)', () => {
    // The public event page renders the same component. An unrated player is not its business, and the
    // self-rating is a claim about a person that never reaches a non-organizer payload anyway.
    renderList({ participants: [participant(), rated] })

    expect(screen.queryByText('Unrated')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Rating/)).not.toBeInTheDocument()
  })

  it('flags an unrated participant by name and offers the rating inline (#907)', () => {
    renderList({ participants: [participant({ proposedRating: '3.0' }), rated], onRated: vi.fn() })

    // Named, not a uuid — the whole point of #907 is that "User <uuid> has no rating" sent a host hunting.
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('Unrated')).toBeInTheDocument()
    // Scoped: a bare '3.0' also matches the select's own option list.
    expect(screen.getByText(/Self-rated:/)).toBeInTheDocument()

    // Only the unrated one gets a form; a rated participant is left alone.
    expect(screen.getAllByRole('button', { name: 'Set rating' })).toHaveLength(1)
  })

  it("prefills the self-rating so approving it as-is is one interaction (#907)", async () => {
    const user = userEvent.setup()
    const onRated = vi.fn()
    renderList({ participants: [participant({ proposedRating: '3.0' })], onRated })

    // Prefilled with what the player claimed: the host approves by submitting, or picks another value.
    expect(screen.getByRole('combobox')).toHaveValue('3.0')

    await user.click(screen.getByRole('button', { name: 'Set rating' }))
    await waitFor(() =>
      expect(putMutate).toHaveBeenCalledWith({ userId: 'u1', data: { band: '3.0' } }),
    )
    // The roster refetches, so the participant stops being flagged without a manual reload.
    await waitFor(() => expect(onRated).toHaveBeenCalled())
  })

  it('still offers the form when the player never self-rated (#907)', () => {
    // A placeholder, or a pre-#75 account: nothing to approve, but the host can still assess them here.
    renderList({ participants: [participant({ proposedRating: undefined })], onRated: vi.fn() })

    expect(screen.getByText('Unrated')).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveValue('')
  })
})
