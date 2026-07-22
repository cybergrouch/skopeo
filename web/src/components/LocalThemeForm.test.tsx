import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LocalThemeForm } from './LocalThemeForm'

const { useGetApiV1UsersMeTheme, usePutApiV1UsersMeTheme, putMutate } = vi.hoisted(() => ({
  useGetApiV1UsersMeTheme: vi.fn(),
  usePutApiV1UsersMeTheme: vi.fn(),
  putMutate: vi.fn(),
}))

vi.mock('@/api/generated/settings/settings', () => ({
  useGetApiV1UsersMeTheme,
  usePutApiV1UsersMeTheme,
  getGetApiV1UsersMeThemeQueryKey: () => ['local-theme'],
}))

function renderForm() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <LocalThemeForm />
    </QueryClientProvider>,
  )
}

describe('LocalThemeForm (#514)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    putMutate.mockResolvedValue(undefined)
    usePutApiV1UsersMeTheme.mockReturnValue({
      isPending: false,
      mutateAsync: async (vars: unknown) => putMutate(vars),
    })
  })

  it('prefills the selector from the current local theme', () => {
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: 'GRASS', setAt: '2026-01-01T00:00:00' },
      isLoading: false,
    })
    renderForm()
    expect(screen.getByLabelText('Theme')).toHaveValue('GRASS')
  })

  it('defaults to "Use default (global)" when unset', () => {
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: null, setAt: null },
      isLoading: false,
    })
    renderForm()
    expect(screen.getByLabelText('Theme')).toHaveValue('')
  })

  it('PUTs the chosen theme on save', async () => {
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: null, setAt: null },
      isLoading: false,
    })
    renderForm()
    await userEvent.selectOptions(screen.getByLabelText('Theme'), 'CLAY')
    await userEvent.click(screen.getByRole('button', { name: /save theme/i }))
    await waitFor(() => expect(putMutate).toHaveBeenCalledWith({ data: { theme: 'CLAY' } }))
  })

  it('PUTs null when clearing back to the global default', async () => {
    useGetApiV1UsersMeTheme.mockReturnValue({
      data: { theme: 'CLAY', setAt: '2026-01-01T00:00:00' },
      isLoading: false,
    })
    renderForm()
    await userEvent.selectOptions(screen.getByLabelText('Theme'), 'Use default (global)')
    await userEvent.click(screen.getByRole('button', { name: /save theme/i }))
    await waitFor(() => expect(putMutate).toHaveBeenCalledWith({ data: { theme: null } }))
  })
})
