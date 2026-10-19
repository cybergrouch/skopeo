import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ThemeScheduleRow } from '@/api/generated/model'
import { ThemeScheduleEditor } from './ThemeScheduleEditor'

const { usePutApiV1ThemeSchedule, putMutate, toastSuccess, toastError } = vi.hoisted(() => ({
  usePutApiV1ThemeSchedule: vi.fn(),
  putMutate: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))
vi.mock('sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('@/api/generated/settings/settings', () => ({
  usePutApiV1ThemeSchedule,
  getGetApiV1ThemeQueryKey: () => ['theme'],
}))

type MutationOpts = { mutation: { onSuccess: () => void; onError: (e: unknown) => void } }

const row = (name: string, theme: ThemeScheduleRow['theme'], start: [number, number], end: [number, number]) => ({
  name,
  theme,
  start: { month: start[0], day: start[1] },
  end: { month: end[0], day: end[1] },
})

// Two rows leaving Aug 1 – Apr 15 (across New Year) uncovered.
const SAVED: ThemeScheduleRow[] = [row('Clay', 'CLAY', [4, 16], [6, 10]), row('Grass', 'GRASS', [6, 11], [7, 31])]
const MAY_1 = new Date(2026, 4, 1)

// null stands for "not loaded yet" (a default parameter would swallow undefined).
function renderEditor(schedule: ThemeScheduleRow[] | null = SAVED, loading = false) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeScheduleEditor schedule={schedule ?? undefined} loading={loading} now={MAY_1} />
    </QueryClientProvider>,
  )
}

const names = () => screen.getAllByLabelText(/^Row \d+ name$/).map((input) => (input as HTMLInputElement).value)
const saveButton = () => screen.getByRole('button', { name: 'Save schedule' })

describe('ThemeScheduleEditor (#1196)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    usePutApiV1ThemeSchedule.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: (vars: unknown) => {
        putMutate(vars)
        options.mutation.onSuccess()
      },
    }))
  })

  it('shows one block per saved row, with Save disabled until something changes', () => {
    renderEditor()
    expect(names()).toEqual(['Clay', 'Grass'])
    expect((screen.getByLabelText('Row 2 theme') as HTMLSelectElement).value).toBe('GRASS')
    expect((screen.getByLabelText('Row 1 start month') as HTMLSelectElement).value).toBe('4')
    expect((screen.getByLabelText('Row 1 end day') as HTMLSelectElement).value).toBe('10')
    expect(saveButton()).toBeDisabled()
  })

  it('falls back to the built-in schedule while the saved one loads', () => {
    renderEditor(null, true)
    expect(names()).toHaveLength(12)
    expect(names()[0]).toBe('Australian Open')
  })

  it('warns about uncovered dates but still allows a save', async () => {
    const user = userEvent.setup()
    renderEditor()
    expect(screen.getByRole('status')).toHaveTextContent(
      'Not covered: Aug 1 – Apr 15 (these dates will show Skopeo OG)',
    )
    await user.type(screen.getByLabelText('Row 1 name'), ' season')
    expect(saveButton()).toBeEnabled()
  })

  it('previews today and the next change from the schedule as edited', async () => {
    const user = userEvent.setup()
    renderEditor()
    const preview = screen.getByLabelText('Schedule preview')
    expect(preview).toHaveTextContent("Today (May 1): Clay (row 1 'Clay')")
    expect(preview).toHaveTextContent("Next change: Jun 11 → Grass (row 2 'Grass')")

    await user.selectOptions(screen.getByLabelText('Row 1 start month'), 'May')
    expect(preview).toHaveTextContent('Today (May 1): Skopeo OG (uncovered)')
  })

  it('says when nothing changes within a year', () => {
    renderEditor([row('All year', 'CLAY', [1, 1], [12, 31])])
    expect(screen.getByLabelText('Schedule preview')).toHaveTextContent('Next change: none within a year.')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('an overlap is shown on both rows and disables Save', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.selectOptions(screen.getByLabelText('Row 2 start month'), 'Jun')
    await user.selectOptions(screen.getByLabelText('Row 2 start day'), '1')
    const message = "Row 1 'Clay' (Apr 16 – Jun 10) overlaps row 2 'Grass' (Jun 1 – Jul 31) on Jun 1 – Jun 10"
    expect(screen.getAllByText(message)).toHaveLength(2)
    expect(saveButton()).toBeDisabled()
    expect(screen.getByText('Fix the highlighted rows to save.')).toBeInTheDocument()
  })

  it('a duplicate name is shown on every row sharing it and disables Save', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.clear(screen.getByLabelText('Row 2 name'))
    await user.type(screen.getByLabelText('Row 2 name'), ' clay ')
    expect(screen.getAllByText("Rows 1 and 2 are both named 'Clay'")).toHaveLength(2)
    expect(saveButton()).toBeDisabled()
  })

  it('changing a theme and saving sends the whole schedule, trimmed, and resets the draft', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.selectOptions(screen.getByLabelText('Row 2 theme'), 'Skopeo OG')
    await user.type(screen.getByLabelText('Row 1 name'), '  ')
    await user.click(saveButton())
    expect(putMutate).toHaveBeenCalledWith({
      data: { schedule: [row('Clay', 'CLAY', [4, 16], [6, 10]), row('Grass', 'SKOPEO_OG', [6, 11], [7, 31])] },
    })
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Schedule saved'))
    expect(saveButton()).toBeDisabled()
  })

  it('a failed save shows the server message, or a fallback', async () => {
    const errors = [{ response: { data: { message: 'Row 1 has no name' } } }, new Error('network')]
    usePutApiV1ThemeSchedule.mockImplementation((options: MutationOpts) => ({
      isPending: false,
      mutate: () => options.mutation.onError(errors.shift()),
    }))
    const user = userEvent.setup()
    renderEditor()
    await user.type(screen.getByLabelText('Row 1 name'), 'x')
    await user.click(saveButton())
    await user.click(saveButton())
    expect(toastError).toHaveBeenNthCalledWith(1, 'Row 1 has no name', { duration: 8000 })
    expect(toastError).toHaveBeenNthCalledWith(2, 'Could not save the schedule. Try again.', { duration: 8000 })
  })

  it('moves rows up and down, and removes one', async () => {
    const user = userEvent.setup()
    renderEditor()
    expect(screen.getByRole('button', { name: 'Move Row 1 up' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move Row 2 down' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Move Row 1 down' }))
    expect(names()).toEqual(['Grass', 'Clay'])
    await user.click(screen.getByRole('button', { name: 'Move Row 2 up' }))
    expect(names()).toEqual(['Clay', 'Grass'])
    await user.click(screen.getByRole('button', { name: 'Remove Row 1' }))
    expect(names()).toEqual(['Grass'])
  })

  it('adds a row on the first uncovered range, needing a name and a theme', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.click(screen.getByRole('button', { name: 'Add row' }))
    expect(names()).toEqual(['Clay', 'Grass', ''])
    expect((screen.getByLabelText('Row 3 start month') as HTMLSelectElement).value).toBe('8')
    expect((screen.getByLabelText('Row 3 end month') as HTMLSelectElement).value).toBe('4')
    expect((screen.getByLabelText('Row 3 end day') as HTMLSelectElement).value).toBe('15')
    expect(screen.getByText('Row 3 has no name')).toBeInTheDocument()
    expect(screen.getByText('Row 3 has no theme')).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()
  })

  it('adds a Jan 1 row when the year is already covered', async () => {
    const user = userEvent.setup()
    renderEditor([row('All year', 'CLAY', [1, 1], [12, 31])])
    await user.click(screen.getByRole('button', { name: 'Add row' }))
    expect((screen.getByLabelText('Row 2 end day') as HTMLSelectElement).value).toBe('1')
  })

  it('sorts by start date and resets to the default', async () => {
    const user = userEvent.setup()
    renderEditor([row('Late', 'CLAY', [9, 1], [9, 30]), row('Early', 'GRASS', [2, 1], [2, 14])])
    await user.click(screen.getByRole('button', { name: 'Sort by start date' }))
    expect(names()).toEqual(['Early', 'Late'])
    await user.click(screen.getByRole('button', { name: 'Reset to default' }))
    expect(names()).toHaveLength(12)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(saveButton()).toBeEnabled()
  })

  it('an impossible end date is an error on its row', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.selectOptions(screen.getByLabelText('Row 1 end day'), '31')
    await user.selectOptions(screen.getByLabelText('Row 1 end month'), 'Apr')
    expect(screen.getByText("Row 1 'Clay' has an invalid end date (Apr 31)")).toBeInTheDocument()
  })
})
