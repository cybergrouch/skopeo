import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PendingAssessmentSection } from './PendingAssessmentSection'

const { useGetApiV1UsersPendingAssessment, mutateAsync, setRatingState } =
  vi.hoisted(() => ({
    useGetApiV1UsersPendingAssessment: vi.fn(),
    mutateAsync: vi.fn(),
    setRatingState: { isPending: false },
  }))

vi.mock('@/api/generated/ratings/ratings', () => ({
  useGetApiV1UsersPendingAssessment,
  // Invoke the caller's onSuccess on a successful assignment (drives invalidation).
  usePutApiV1UsersUserIdRatings: (options?: {
    mutation?: { onSuccess?: () => void }
  }) => ({
    isPending: setRatingState.isPending,
    mutateAsync: async (vars: unknown) => {
      const result = await mutateAsync(vars)
      options?.mutation?.onSuccess?.()
      return result
    },
  }),
  getGetApiV1UsersPendingAssessmentQueryKey: () => ['pending-assessment'],
}))

function renderSection() {
  const client = new QueryClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <PendingAssessmentSection />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false }
}

describe('PendingAssessmentSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setRatingState.isPending = false
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([{ userId: 'u1', publicCode: 'AAA111', displayName: 'Roger F.' }]),
    )
    mutateAsync.mockResolvedValue({})
  })

  it('shows a loading state', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue({
      data: undefined,
      isLoading: true,
    })
    renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state when no one is pending', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(page([]))
    renderSection()
    expect(
      screen.getByText('No players are pending assessment.'),
    ).toBeInTheDocument()
  })

  it('links raters to the NTRP self-rating guide (#203)', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(page([]))
    renderSection()
    const link = screen.getByRole('link', { name: /NTRP self-rating guide/i })
    expect(link).toHaveAttribute('href', 'https://www.teamtopspin.com/tennis-self-rating')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('falls back to the user id when there is no display name', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([{ userId: 'u-no-name', publicCode: 'BBB222', displayName: null }]),
    )
    renderSection()
    expect(screen.getByText('u-no-name')).toBeInTheDocument()
  })

  it('shows an avatar, sex+age(+dob), and links the name to the public profile', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([
        {
          userId: 'u2',
          publicCode: 'K7Q2MX',
          displayName: 'Ana',
          photoUrl: 'https://example.com/ana.jpg',
          sex: 'Female',
          dateOfBirth: '1990-03-15',
          age: 34,
        },
      ]),
    )
    const { container } = renderSection()
    expect(screen.getByText('Female · 34 (1990-03-15)')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/ana.jpg',
    )
    expect(screen.getByRole('link', { name: 'Ana' })).toHaveAttribute(
      'href',
      '/players/K7Q2MX',
    )
  })

  it('omits the meta line when there is no sex/age/dob, using initials for the avatar', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([{ userId: 'u3', publicCode: 'CCC333', displayName: 'Bea' }]),
    )
    const { container } = renderSection()
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('B')).toBeInTheDocument()
  })

  it('shows the age alone when the date of birth is absent', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([
        { userId: 'u5', publicCode: 'EEE555', displayName: 'Dee', sex: 'Male', age: 41 },
      ]),
    )
    renderSection()
    expect(screen.getByText('Male · 41')).toBeInTheDocument()
  })

  it('shows the date of birth alone when age is absent', () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([
        {
          userId: 'u4',
          publicCode: 'DDD444',
          displayName: 'Cy',
          dateOfBirth: '1985-01-01',
        },
      ]),
    )
    renderSection()
    expect(screen.getByText('1985-01-01')).toBeInTheDocument()
  })

  it('prefills the rating input with a self-reported value and shows it', async () => {
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([
        {
          userId: 'u6',
          publicCode: 'FFF666',
          displayName: 'Eve',
          proposedRating: '3.5',
        },
      ]),
    )
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('Self-rated:')).toBeInTheDocument()
    expect(screen.getByLabelText('Rating')).toHaveValue('3.5')

    // Approving as-is submits the preselected band (the backend stores its midpoint).
    await user.click(screen.getByRole('button', { name: 'Set rating' }))
    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        userId: 'u6',
        data: { band: '3.5' },
      }),
    )
  })

  it('paginates: shows controls and steps pages when total exceeds a page', async () => {
    // 45 total with a page of items → 3 pages; controls appear.
    useGetApiV1UsersPendingAssessment.mockReturnValue(
      page([{ userId: 'u1', publicCode: 'AAA111', displayName: 'Roger F.' }], 45),
    )
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('Page 1 of 3 · 45 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2 of 3 · 45 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByText('Page 1 of 3 · 45 total')).toBeInTheDocument()
  })

  it('hides pagination controls when everyone fits on one page', () => {
    renderSection()
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
  })

  it('assigns a rating from the selected band', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.selectOptions(screen.getByLabelText('Rating'), '4.5')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        userId: 'u1',
        data: { band: '4.5' },
      }),
    )
  })

  it('shows a busy label while the assignment is in flight', () => {
    setRatingState.isPending = true
    renderSection()
    expect(screen.getByRole('button', { name: 'Setting…' })).toBeDisabled()
  })

  it('shows an error when the assignment fails', async () => {
    mutateAsync.mockRejectedValue(new Error('bad'))
    const user = userEvent.setup()
    renderSection()

    await user.selectOptions(screen.getByLabelText('Rating'), '4.0')
    await user.click(screen.getByRole('button', { name: 'Set rating' }))

    expect(
      await screen.findByText(/Could not set the rating/i),
    ).toBeInTheDocument()
  })
})
