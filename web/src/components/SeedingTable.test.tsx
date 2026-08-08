import { describe, it, expect, vi } from 'vitest'
import type { ReactNode } from 'react'
import { act, render, screen } from '@testing-library/react'
import { setupUser } from '@/test/user'
import { SeedingTable } from './SeedingTable'
import type { SeedingEntryResponse } from '@/api/generated/model'

// dnd-kit relies on layout measurement that jsdom can't provide; stub it to passthrough components and
// capture the DndContext onDragEnd so a test can simulate a drop (same technique as AwaitingResultsSection).
const { dnd } = vi.hoisted(() => ({
  dnd: { onDragEnd: undefined as undefined | ((e: unknown) => void) },
}))
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children, onDragEnd }: { children: ReactNode; onDragEnd: (e: unknown) => void }) => {
    dnd.onDragEnd = onDragEnd
    return children
  },
  closestCenter: () => undefined,
  KeyboardSensor: function KeyboardSensor() {},
  PointerSensor: function PointerSensor() {},
  useSensor: () => ({}),
  useSensors: () => [],
}))
vi.mock('@dnd-kit/sortable', () => ({
  SortableContext: ({ children }: { children: ReactNode }) => children,
  verticalListSortingStrategy: {},
  sortableKeyboardCoordinates: () => undefined,
  useSortable: () => ({
    attributes: {},
    listeners: {},
    setNodeRef: () => undefined,
    transform: null,
    transition: undefined,
    isDragging: false,
  }),
  arrayMove: <T,>(arr: T[], from: number, to: number): T[] => {
    const copy = [...arr]
    const [moved] = copy.splice(from, 1)
    copy.splice(to, 0, moved)
    return copy
  },
}))
vi.mock('@dnd-kit/utilities', () => ({ CSS: { Transform: { toString: () => undefined } } }))

const entries: SeedingEntryResponse[] = [
  {
    seed: 1,
    position: 1,
    userId: 'u1',
    displayName: 'Ana',
    publicCode: 'ABC123',
    ntrpBand: '4.0',
    rating: '4.000000',
    sex: 'Female',
    age: 30,
  },
  {
    seed: null,
    position: 2,
    userId: 'u2',
    displayName: null,
    publicCode: 'BBB222',
    ntrpBand: null,
    rating: '3.000000',
    sex: null,
    age: null,
  },
]

describe('SeedingTable', () => {
  it('renders the default empty message when there are no entries', () => {
    render(<SeedingTable entries={[]} generatedAt="now" name="Club Open" />)
    expect(screen.getByText('No seeding yet.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download CSV' })).not.toBeInTheDocument()
  })

  it('renders a custom empty message', () => {
    render(<SeedingTable entries={[]} generatedAt="now" name="Club Open" emptyMessage="Nothing here." />)
    expect(screen.getByText('Nothing here.')).toBeInTheDocument()
  })

  it('renders the rows including a blank seed and a code fallback name', () => {
    render(<SeedingTable entries={entries} generatedAt="now" name="Club Open" />)
    const rows = screen.getAllByRole('row')
    // header + 2 entries
    expect(rows).toHaveLength(3)
    const seedCells = screen
      .getAllByRole('cell')
      .filter((c) => (c as HTMLTableCellElement).cellIndex === 0)
    expect(seedCells[0]).toHaveTextContent('1')
    expect(seedCells[1]).toHaveTextContent('')
    // The unnamed second entry falls back to its public code in the Name column.
    const nameCells = screen
      .getAllByRole('cell')
      .filter((c) => (c as HTMLTableCellElement).cellIndex === 1)
    expect(nameCells[1]).toHaveTextContent('BBB222')
  })

  it('downloads a CSV with a filename built from the source name and generatedAt', async () => {
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })

    const click = vi.fn()
    const realCreate = document.createElement.bind(document)
    let anchor: HTMLAnchorElement | null = null
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tag: string) => {
        const el = realCreate(tag)
        if (tag === 'a') {
          anchor = el as HTMLAnchorElement
          ;(el as HTMLAnchorElement).click = click
        }
        return el
      })

    const user = setupUser()
    render(<SeedingTable entries={entries} generatedAt="2026-06-23T10:00:00" name="Club Open" />)
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    const text = await blob.text()
    expect(text.split('\r\n')[0]).toBe('"Seed","Name","Code","NTRP","Rating","Sex","Age"')
    expect(text).toContain('"1","Ana","ABC123","4.0","4.000000","Female","30"')
    expect(click).toHaveBeenCalled()
    expect(anchor!.download).toBe('Club-Open-seeding-2026-06-23T10:00:00.csv')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('falls back to a default filename when the name has no usable characters', async () => {
    const click = vi.fn()
    const realCreate = document.createElement.bind(document)
    let anchor: HTMLAnchorElement | null = null
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tag: string) => {
        const el = realCreate(tag)
        if (tag === 'a') {
          anchor = el as HTMLAnchorElement
          ;(el as HTMLAnchorElement).click = click
        }
        return el
      })
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:url', revokeObjectURL: vi.fn() })

    const user = setupUser()
    render(<SeedingTable entries={entries} generatedAt="2026-06-23T10:00:00" name="***" />)
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(anchor!.download).toBe('list-seeding-2026-06-23T10:00:00.csv')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('shows no Save/Reset/Regenerate controls when it is read-only', () => {
    render(<SeedingTable entries={entries} generatedAt="now" name="Club Open" />)
    expect(screen.queryByRole('button', { name: 'Save order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reset' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reorder/ })).not.toBeInTheDocument()
  })

  it('reorders a draft and Saves the new order to the mutation (#718)', async () => {
    const onSaveOrder = vi.fn<(userIds: string[]) => Promise<void>>(() => Promise.resolve())
    const user = setupUser()
    render(
      <SeedingTable entries={entries} generatedAt="now" name="Club Open" onSaveOrder={onSaveOrder} />,
    )

    // Save/Reset start disabled: no unsaved changes yet.
    expect(screen.getByRole('button', { name: 'Save order' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled()

    // Drag u2 above u1 via the captured DndContext onDragEnd.
    act(() => dnd.onDragEnd?.({ active: { id: 'u2' }, over: { id: 'u1' } }))

    // The draft is now dirty → Save/Reset enabled.
    expect(screen.getByRole('button', { name: 'Save order' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Save order' }))
    expect(onSaveOrder).toHaveBeenCalledWith(['u2', 'u1'])
  })

  it('Reset reverts the draft and is disabled again once unchanged (#718)', async () => {
    const user = setupUser()
    render(
      <SeedingTable entries={entries} generatedAt="now" name="Club Open" onSaveOrder={vi.fn()} />,
    )

    act(() => dnd.onDragEnd?.({ active: { id: 'u2' }, over: { id: 'u1' } }))
    const reset = screen.getByRole('button', { name: 'Reset' })
    expect(reset).toBeEnabled()

    await user.click(reset)
    // Back to the original order → Reset (and Save) disabled again.
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Save order' })).toBeDisabled()
  })

  it('warns before regenerating over a manually-edited order, then confirms (#718)', async () => {
    const onRegenerate = vi.fn()
    const user = setupUser()
    render(
      <SeedingTable
        entries={entries}
        generatedAt="now"
        name="Club Open"
        onRegenerate={onRegenerate}
        manuallyEdited
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Regenerate seeding' }))
    // First click warns instead of regenerating.
    expect(onRegenerate).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('discard the manual order')

    await user.click(screen.getByRole('button', { name: 'Discard manual order and regenerate' }))
    expect(onRegenerate).toHaveBeenCalledTimes(1)
  })

  it('re-syncs the draft to fresh entries when the underlying order changes (#718)', async () => {
    const user = setupUser()
    const { rerender } = render(
      <SeedingTable entries={entries} generatedAt="now" name="Club Open" onSaveOrder={vi.fn()} />,
    )

    // Dirty the draft so a re-sync is observable.
    act(() => dnd.onDragEnd?.({ active: { id: 'u2' }, over: { id: 'u1' } }))
    expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled()

    // A regenerate elsewhere hands down new entries + a new generatedAt → the key changes and the draft
    // re-syncs, dropping the stale manual order (Save/Reset disabled again).
    const regenerated: SeedingEntryResponse[] = [entries[1], entries[0]]
    rerender(
      <SeedingTable entries={regenerated} generatedAt="later" name="Club Open" onSaveOrder={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Save order' })).toBeDisabled()
    void user
  })

  it('cancels the regenerate confirmation without regenerating (#718)', async () => {
    const onRegenerate = vi.fn()
    const user = setupUser()
    render(
      <SeedingTable
        entries={entries}
        generatedAt="now"
        name="Club Open"
        onRegenerate={onRegenerate}
        manuallyEdited
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Regenerate seeding' }))
    expect(screen.getByRole('alert')).toHaveTextContent('discard the manual order')

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onRegenerate).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Regenerate seeding' })).toBeInTheDocument()
  })

  it('regenerates immediately when the order was not manually edited (#718)', async () => {
    const onRegenerate = vi.fn()
    const user = setupUser()
    render(
      <SeedingTable entries={entries} generatedAt="now" name="Club Open" onRegenerate={onRegenerate} />,
    )

    await user.click(screen.getByRole('button', { name: 'Regenerate seeding' }))
    expect(onRegenerate).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
