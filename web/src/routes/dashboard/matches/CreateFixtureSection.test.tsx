import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CreateFixtureSection } from './CreateFixtureSection'

const { mutateAsync, busy } = vi.hoisted(() => ({
  mutateAsync: vi.fn(),
  busy: { value: false },
}))

vi.mock('@/api/generated/matches/matches', () => ({
  usePostApiV1Matches: (options?: {
    mutation?: { onSuccess?: (r: unknown, v: unknown) => void }
  }) => ({
    isPending: busy.value,
    mutateAsync: async (vars: unknown) => {
      const r = await mutateAsync(vars)
      options?.mutation?.onSuccess?.(r, vars)
      return r
    },
  }),
  getGetApiV1MatchesQueryKey: () => ['matches', 'awaiting-results'],
}))

vi.mock('@/components/UserSearchSelect', () => ({
  UserSearchSelect: ({
    label,
    onSelect,
  }: {
    label: string
    onSelect: (u: { id: string; displayName: string | null; capabilities: string[] }) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onSelect({
          id: label === 'Player 1' ? 'p1' : 'p2',
          // Player 1 has a name (covers displayName), Player 2 has none (covers the id fallback).
          displayName: label === 'Player 1' ? 'Alice' : null,
          capabilities: [],
        })
      }
    >
      pick {label}
    </button>
  ),
}))

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <CreateFixtureSection />
    </QueryClientProvider>,
  )
}

async function pickPlayersAndDate() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'pick Player 1' }))
  await user.click(screen.getByRole('button', { name: 'pick Player 2' }))
  fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-07-01' } })
  return user
}

describe('CreateFixtureSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    busy.value = false
    mutateAsync.mockResolvedValue({})
  })

  it('shows a busy label while scheduling', () => {
    busy.value = true
    renderSection()
    expect(screen.getByRole('button', { name: 'Scheduling…' })).toBeDisabled()
  })

  it('disables Schedule until both players and a date are set', async () => {
    renderSection()
    const button = screen.getByRole('button', { name: /schedule fixture/i })
    expect(button).toBeDisabled()
    await pickPlayersAndDate()
    expect(button).toBeEnabled()
  })

  it('creates a fixture with the chosen players, format, and date', async () => {
    renderSection()
    const user = await pickPlayersAndDate()
    await user.selectOptions(screen.getByLabelText('Format'), 'BEST_OF_FIVE')

    await user.click(screen.getByRole('button', { name: /schedule fixture/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: {
          matchType: 'SINGLES',
          matchFormat: 'BEST_OF_FIVE',
          matchDate: '2026-07-01',
          team1: ['p1'],
          team2: ['p2'],
        },
      }),
    )
    expect(await screen.findByText('Fixture scheduled.')).toBeInTheDocument()
  })

  it('offers the single-set format and submits it', async () => {
    renderSection()
    expect(
      screen.getByRole('option', { name: 'Single set' }),
    ).toBeInTheDocument()
    const user = await pickPlayersAndDate()
    await user.selectOptions(screen.getByLabelText('Format'), 'SINGLE_SET')

    await user.click(screen.getByRole('button', { name: /schedule fixture/i }))

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: {
          matchType: 'SINGLES',
          matchFormat: 'SINGLE_SET',
          matchDate: '2026-07-01',
          team1: ['p1'],
          team2: ['p2'],
        },
      }),
    )
  })

  it('ignores a submit with players missing', () => {
    const { container } = renderSection()
    const form = container.querySelector('form')
    expect(form).toBeTruthy()
    fireEvent.submit(form as HTMLFormElement)
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('shows an error when creation fails', async () => {
    mutateAsync.mockRejectedValue(new Error('unrated'))
    renderSection()
    const user = await pickPlayersAndDate()

    await user.click(screen.getByRole('button', { name: /schedule fixture/i }))

    expect(await screen.findByText(/Could not create the fixture/i)).toBeInTheDocument()
  })

  it('lets either chosen player be changed', async () => {
    renderSection()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'pick Player 1' }))
    expect(screen.queryByRole('button', { name: 'pick Player 1' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('button', { name: 'pick Player 1' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'pick Player 2' }))
    expect(screen.queryByRole('button', { name: 'pick Player 2' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('button', { name: 'pick Player 2' })).toBeInTheDocument()
  })
})
