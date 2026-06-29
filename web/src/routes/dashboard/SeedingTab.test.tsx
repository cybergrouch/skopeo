import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SeedingTab } from './SeedingTab'

const {
  useGetApiV1PlayerLists,
  useGetApiV1PlayerListsId,
  useGetApiV1PlayerListsIdSeeding,
  createMutate,
  deleteListMutate,
  addMemberMutate,
  removeMemberMutate,
  generateMutate,
  state,
  useGetApiV1Users,
} = vi.hoisted(() => ({
  useGetApiV1PlayerLists: vi.fn(),
  useGetApiV1PlayerListsId: vi.fn(),
  useGetApiV1PlayerListsIdSeeding: vi.fn(),
  createMutate: vi.fn(),
  deleteListMutate: vi.fn(),
  addMemberMutate: vi.fn(),
  removeMemberMutate: vi.fn(),
  generateMutate: vi.fn(),
  state: { addFail: false },
  useGetApiV1Users: vi.fn(),
}))

vi.mock('@/api/generated/player-lists/player-lists', () => ({
  useGetApiV1PlayerLists,
  useGetApiV1PlayerListsId,
  useGetApiV1PlayerListsIdSeeding,
  getGetApiV1PlayerListsQueryKey: () => ['lists'],
  getGetApiV1PlayerListsIdQueryKey: (id: string) => ['list', id],
  getGetApiV1PlayerListsIdSeedingQueryKey: (id: string) => ['seeding', id],
  usePostApiV1PlayerLists: () => ({
    mutateAsync: async (vars: unknown) => {
      createMutate(vars)
      return { id: 'l-new', name: 'New', createdAt: 'now', memberCount: 0 }
    },
  }),
  useDeleteApiV1PlayerListsId: () => ({
    mutateAsync: async (vars: unknown) => deleteListMutate(vars),
  }),
  usePostApiV1PlayerListsIdMembers: () => ({
    mutateAsync: async (vars: unknown) => {
      addMemberMutate(vars)
      if (state.addFail) throw new Error('conflict')
    },
  }),
  useDeleteApiV1PlayerListsIdMembersUserId: () => ({
    mutateAsync: async (vars: unknown) => removeMemberMutate(vars),
  }),
  usePostApiV1PlayerListsIdSeeding: () => ({
    mutateAsync: async (vars: unknown) => generateMutate(vars),
  }),
}))

vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))

// Two candidates the explicit search returns; the second is already a member of listDetail (u2).
const searchUsers = [
  { id: 'u9', publicCode: 'NEW999', displayName: 'New Player', sex: 'Male', age: 28, capabilities: [] },
  { id: 'u8', publicCode: 'EIGHT8', displayName: 'Eight', capabilities: [] },
  // No display name → the result falls back to the public code.
  { id: 'u7', publicCode: 'SEVEN7', displayName: null, capabilities: [] },
  { id: 'u2', publicCode: 'BBB222', displayName: 'Already In', capabilities: [] },
]

function renderTab() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <SeedingTab />
    </QueryClientProvider>,
  )
}

const lists = [
  { id: 'l1', name: 'Summer Open', createdAt: 'now', memberCount: 2 },
  { id: 'l2', name: 'Winter Cup', createdAt: 'now', memberCount: 0 },
]

const listDetail = {
  id: 'l1',
  name: 'Summer Open',
  createdAt: 'now',
  members: [
    {
      id: 'u1',
      publicCode: 'ABC123',
      displayName: 'Ana',
      sex: 'Female',
      age: 30,
      rating: { value: '4.000000', level: '4.0' },
      capabilities: [],
    },
    { id: 'u2', publicCode: 'BBB222', displayName: null, capabilities: [] },
    {
      id: 'u3',
      publicCode: 'CCC333',
      displayName: 'Bea',
      sex: 'Female',
      age: 25,
      // No NTRP level: the meta falls back to the raw rating value.
      rating: { value: '3.500000', level: null },
      capabilities: [],
    },
  ],
}

const seeding = {
  generatedAt: '2026-06-23T10:00:00',
  entries: [
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
  ],
}

describe('SeedingTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    state.addFail = false
    useGetApiV1PlayerLists.mockReturnValue({ data: lists })
    useGetApiV1PlayerListsId.mockReturnValue({ data: undefined })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: undefined })
    useGetApiV1Users.mockReturnValue({ data: searchUsers, isLoading: false })
  })

  it('renders the caller lists with member counts and an empty selection', () => {
    renderTab()
    expect(screen.getByText('Summer Open')).toBeInTheDocument()
    expect(screen.getByText('2 players')).toBeInTheDocument()
    expect(screen.getByText('0 players')).toBeInTheDocument()
  })

  it('shows the empty state when there are no lists', () => {
    useGetApiV1PlayerLists.mockReturnValue({ data: [] })
    renderTab()
    expect(screen.getByText('No lists yet.')).toBeInTheDocument()
  })

  it('shows the empty state while the lists query is still loading', () => {
    useGetApiV1PlayerLists.mockReturnValue({ data: undefined })
    renderTab()
    expect(screen.getByText('No lists yet.')).toBeInTheDocument()
  })

  it('creates a list and selects it', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('New list'), 'Spring')
    await user.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(createMutate).toHaveBeenCalledWith({ data: { name: 'Spring' } }))
  })

  it('ignores a create submit when the name is blank', () => {
    renderTab()
    // Submitting the form with a whitespace-only name is a no-op (guards against empty creates).
    fireEvent.change(screen.getByLabelText('New list'), { target: { value: '   ' } })
    fireEvent.submit(screen.getByLabelText('New list').closest('form')!)
    expect(createMutate).not.toHaveBeenCalled()
  })

  it('selects a list and lists its members (with a code fallback name)', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('Female · 30 · NTRP 4.0')).toBeInTheDocument()
    // No-display-name member falls back to the public code.
    expect(screen.getByText('BBB222')).toBeInTheDocument()
    // A rating without an NTRP level falls back to its raw value.
    expect(screen.getByText('Female · 25 · NTRP 3.500000')).toBeInTheDocument()
  })

  it('searches, multi-selects, and adds the checked players to the list', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))

    // No live suggestions; results appear only after Search.
    expect(screen.queryByText('New Player')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Name'), 'pla')
    // Fill the remaining range inputs too, so every filter field is exercised.
    await user.type(screen.getAllByLabelText('to')[0], '40') // age "to"
    await user.type(screen.getByLabelText('Rating from'), '3.0')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    // u2 ("Already In") is already a member, so it's excluded from the results.
    expect(screen.getByText('New Player')).toBeInTheDocument()
    expect(screen.getByText('Eight')).toBeInTheDocument()
    expect(screen.getByText('SEVEN7')).toBeInTheDocument() // null name → code fallback
    expect(screen.queryByText('Already In')).not.toBeInTheDocument()

    // Add to List is disabled until at least one is checked; checking then unchecking re-disables it.
    expect(screen.getByRole('button', { name: 'Add to List' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /New Player/ }))
    expect(screen.getByRole('button', { name: 'Add to List' })).toBeEnabled()
    await user.click(screen.getByRole('checkbox', { name: /New Player/ }))
    expect(screen.getByRole('button', { name: 'Add to List' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: /New Player/ }))
    await user.click(screen.getByRole('checkbox', { name: /Eight/ }))
    await user.click(screen.getByRole('button', { name: 'Add to List' }))

    await waitFor(() => expect(addMemberMutate).toHaveBeenCalledTimes(2))
    expect(addMemberMutate).toHaveBeenCalledWith({ id: 'l1', data: { userId: 'u9' } })
    expect(addMemberMutate).toHaveBeenCalledWith({ id: 'l1', data: { userId: 'u8' } })
  })

  it('shows the empty state, and disables Search until a filter is set', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    // Search is disabled until a filter is set.
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })

  it('shows a searching state while results load', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: true })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.type(screen.getByLabelText('Name'), 'an')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('shows an error when the filters are invalid', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1Users.mockReturnValue({ data: undefined, isError: true })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.type(screen.getByLabelText('Name'), 'an')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/)).toBeInTheDocument()
  })

  it('labels a single-member list in the singular', () => {
    useGetApiV1PlayerLists.mockReturnValue({ data: [{ id: 'l9', name: 'Solo', createdAt: 'now', memberCount: 1 }] })
    useGetApiV1PlayerListsId.mockReturnValue({ data: undefined })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: undefined })
    renderTab()
    expect(screen.getByRole('button', { name: /Solo/ }).textContent).toContain('1 player')
  })

  it('runs the search with the combined name/sex/age/rating filters on Search', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))

    await user.type(screen.getByLabelText('Name'), 'an')
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    // Open-ended on purpose — age lower-only → "[20,)", rating upper-only → "(,4.5]".
    await user.type(screen.getByLabelText('Age from'), '20')
    await user.type(screen.getAllByLabelText('to')[1], '4.5') // rating "to"
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { name: 'an', sex: 'Male', age: '[20,)', rating: '(,4.5]' },
      { query: { enabled: true } },
    )
  })

  it('surfaces an error when adding the selected players fails', async () => {
    state.addFail = true
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.type(screen.getByLabelText('Name'), 'pla')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(screen.getByRole('checkbox', { name: /New Player/ }))
    await user.click(screen.getByRole('button', { name: 'Add to List' }))
    expect(await screen.findByText("Couldn't add the selected players.")).toBeInTheDocument()
  })

  it('removes a member', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.click(screen.getAllByRole('button', { name: 'Remove' })[0])
    await waitFor(() =>
      expect(removeMemberMutate).toHaveBeenCalledWith({ id: 'l1', userId: 'u1' }),
    )
  })

  it('deletes the selected list and clears the selection', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.click(screen.getByRole('button', { name: 'Delete list' }))
    await waitFor(() => expect(deleteListMutate).toHaveBeenCalledWith({ id: 'l1' }))
  })

  it('generates a seeding and renders the table including a blank seed', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: seeding })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))

    // A seeding already exists, so the button reads "Regenerate".
    await user.click(screen.getByRole('button', { name: 'Regenerate' }))
    await waitFor(() => expect(generateMutate).toHaveBeenCalledWith({ id: 'l1' }))

    const rows = screen.getAllByRole('row')
    // header + 2 entries
    expect(rows).toHaveLength(3)
    expect(screen.getByRole('columnheader', { name: 'Seed' })).toBeInTheDocument()
    // The second entry has a null seed, rendered blank.
    const seedCells = screen
      .getAllByRole('cell')
      .filter((c) => (c as HTMLTableCellElement).cellIndex === 0)
    expect(seedCells[0]).toHaveTextContent('1')
    expect(seedCells[1]).toHaveTextContent('')
  })

  it('labels the generate button "Generate seeding" when none exists yet', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: { generatedAt: 'now', entries: [] } })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    expect(screen.getByRole('button', { name: 'Generate seeding' })).toBeInTheDocument()
    expect(screen.getByText('No seeding yet. Generate one from the members above.')).toBeInTheDocument()
  })

  it('downloads a CSV with the expected filename, header, and escaped fields', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: seeding })

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
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    const text = await blob.text()
    expect(text.split('\r\n')[0]).toBe('"Seed","Name","Code","NTRP","Rating","Sex","Age"')
    expect(text).toContain('"1","Ana","ABC123","4.0","4.000000","Female","30"')
    // Null seed/sex/age/band render as empty quoted fields; name falls back to the code.
    expect(text).toContain('"","BBB222","BBB222","","3.000000","",""')

    expect(click).toHaveBeenCalled()
    expect(anchor!.download).toBe('Summer-Open-seeding-2026-06-23T10:00:00.csv')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:url')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('falls back to a default filename when the list name has no usable characters', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: { ...listDetail, name: '***' } })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: seeding })

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
    renderTab()
    // The list-row label still comes from the lists query; the detail (name "***") drives the filename.
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(anchor!.download).toBe('list-seeding-2026-06-23T10:00:00.csv')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('uses a placeholder list name when the detail has not loaded yet', async () => {
    // Seeding is available but the list detail is still loading.
    useGetApiV1PlayerListsId.mockReturnValue({ data: undefined })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: seeding })

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
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(anchor!.download).toBe('List-seeding-2026-06-23T10:00:00.csv')

    createElementSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('does not download when there is no seeding', async () => {
    useGetApiV1PlayerListsId.mockReturnValue({ data: listDetail })
    useGetApiV1PlayerListsIdSeeding.mockReturnValue({ data: { generatedAt: 'now', entries: [] } })
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:url')
    vi.stubGlobal('URL', { ...URL, createObjectURL })
    const user = userEvent.setup()
    renderTab()
    await user.click(screen.getByRole('button', { name: /Summer Open/ }))
    // With no entries the download button is not rendered at all.
    expect(screen.queryByRole('button', { name: 'Download CSV' })).not.toBeInTheDocument()
    expect(createObjectURL).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
