import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeSection } from './ThemeSection'

const { useGetApiV1Theme, usePutApiV1Theme, putMutate } = vi.hoisted(() => ({
  useGetApiV1Theme: vi.fn(),
  usePutApiV1Theme: vi.fn(),
  putMutate: vi.fn(),
}))

vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1Theme,
  usePutApiV1Theme,
  getGetApiV1ThemeQueryKey: () => ['theme'],
}))

type MutationOpts = { mutation: { onSuccess: () => void; onError?: (e: unknown) => void } }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeSection />
    </QueryClientProvider>,
  )
}

describe('ThemeSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'AUTO' }, isLoading: false })
    usePutApiV1Theme.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        putMutate(vars)
        options.mutation.onSuccess()
      },
    }))
  })

  it('renders the AUTO + eleven theme options', () => {
    renderSection()
    const select = screen.getByLabelText('Theme') as HTMLSelectElement
    expect(select.options).toHaveLength(12)
    expect(screen.getByRole('option', { name: 'Auto (by season)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'US Open' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Grass' })).toBeInTheDocument()
  })

  it('renders the five new seasonal theme options', () => {
    renderSection()
    for (const name of ["Valentine's Day", 'Spring', 'Rainy', 'Halloween', 'Autumn']) {
      expect(screen.getByRole('option', { name })).toBeInTheDocument()
    }
  })

  it.each([
    ['VALENTINES', "Valentine's Day"],
    ['SPRING', 'Spring'],
    ['RAINY', 'Rainy'],
    ['HALLOWEEN', 'Halloween'],
    ['AUTUMN', 'Autumn'],
  ])('selecting %s and saving calls the mutation with that value', async (value, label) => {
    const user = userEvent.setup()
    renderSection()
    await user.selectOptions(screen.getByLabelText('Theme'), label)
    await user.click(screen.getByRole('button', { name: 'Save theme' }))
    expect(putMutate).toHaveBeenCalledWith({ data: { theme: value } })
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
  })

  it('initializes the select from the loaded setting', async () => {
    useGetApiV1Theme.mockReturnValue({ data: { theme: 'CLAY' }, isLoading: false })
    renderSection()
    await waitFor(() =>
      expect((screen.getByLabelText('Theme') as HTMLSelectElement).value).toBe('CLAY'),
    )
  })

  it('selecting a theme and saving calls the mutation with the chosen value and shows Saved', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.selectOptions(screen.getByLabelText('Theme'), 'US_OPEN')
    await user.click(screen.getByRole('button', { name: 'Save theme' }))

    expect(putMutate).toHaveBeenCalledWith({ data: { theme: 'US_OPEN' } })
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
  })

  it('defaults to AUTO and disables the select while the setting is loading', () => {
    useGetApiV1Theme.mockReturnValue({ data: undefined, isLoading: true })
    renderSection()
    const select = screen.getByLabelText('Theme') as HTMLSelectElement
    expect(select.value).toBe('AUTO')
    expect(select.disabled).toBe(true)
  })

  it('shows an error when the save fails', async () => {
    usePutApiV1Theme.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError?.(new Error('boom')),
    }))
    const user = userEvent.setup()
    renderSection()
    await user.click(screen.getByRole('button', { name: 'Save theme' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not set the theme')
  })
})
