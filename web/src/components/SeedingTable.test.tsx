import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SeedingTable } from './SeedingTable'
import type { SeedingEntryResponse } from '@/api/generated/model'

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

    const user = userEvent.setup()
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

    const user = userEvent.setup()
    render(<SeedingTable entries={entries} generatedAt="2026-06-23T10:00:00" name="***" />)
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(anchor!.download).toBe('list-seeding-2026-06-23T10:00:00.csv')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })
})
