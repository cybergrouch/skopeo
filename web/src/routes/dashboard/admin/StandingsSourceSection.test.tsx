import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StandingsSourceSection } from './StandingsSourceSection'

const { useGet, usePut, putMutate } = vi.hoisted(() => ({
  useGet: vi.fn(),
  usePut: vi.fn(),
  putMutate: vi.fn(),
}))

vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1SettingsStandingsSource: useGet,
  usePutApiV1SettingsStandingsSource: usePut,
  getGetApiV1SettingsStandingsSourceQueryKey: () => ['standings-source'],
}))
vi.mock('@/api/generated/standings/standings', () => ({
  getGetApiV1StandingsQueryKey: () => ['standings'],
}))

type MutationOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <StandingsSourceSection />
    </QueryClientProvider>,
  )
}

describe('StandingsSourceSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGet.mockReturnValue({ data: { source: 'RATING' }, isLoading: false })
    usePut.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        putMutate(vars)
        options.mutation.onSuccess()
      },
    }))
  })

  it('renders the Rating + Points options', () => {
    renderSection()
    const select = screen.getByLabelText('Source') as HTMLSelectElement
    expect(select.options).toHaveLength(2)
    expect(screen.getByRole('option', { name: 'Rating' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Points' })).toBeInTheDocument()
  })

  it('notes that Points needs a points snapshot to take effect', () => {
    renderSection()
    expect(screen.getByText(/Points takes effect only once a\s+points snapshot/i)).toBeInTheDocument()
  })

  it('initializes the select from the loaded setting', async () => {
    useGet.mockReturnValue({ data: { source: 'POINTS' }, isLoading: false })
    renderSection()
    await waitFor(() =>
      expect((screen.getByLabelText('Source') as HTMLSelectElement).value).toBe('POINTS'),
    )
  })

  it('selecting a source and saving calls the mutation with the chosen value and shows Saved', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.selectOptions(screen.getByLabelText('Source'), 'POINTS')
    await user.click(screen.getByRole('button', { name: 'Save source' }))

    expect(putMutate).toHaveBeenCalledWith({ data: { source: 'POINTS' } })
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
  })

  it('defaults to RATING and disables the select while the setting is loading', () => {
    useGet.mockReturnValue({ data: undefined, isLoading: true })
    renderSection()
    const select = screen.getByLabelText('Source') as HTMLSelectElement
    expect(select.value).toBe('RATING')
    expect(select.disabled).toBe(true)
  })

  it('shows an error when the save fails', async () => {
    usePut.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error('boom')),
    }))
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Save source' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not set the standings source')
  })
})
