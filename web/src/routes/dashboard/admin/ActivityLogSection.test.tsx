import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ActivityLogSection } from './ActivityLogSection'

const { useGetApiV1Audit, usePatchApiV1AuditIdComment, patchMutate } = vi.hoisted(() => ({
  useGetApiV1Audit: vi.fn(),
  usePatchApiV1AuditIdComment: vi.fn(),
  patchMutate: vi.fn(),
}))

vi.mock('@/api/generated/audit/audit', () => ({
  useGetApiV1Audit,
  usePatchApiV1AuditIdComment,
  getGetApiV1AuditQueryKey: () => ['audit'],
}))

function entry(overrides: Record<string, unknown> = {}) {
  return {
    id: 'a1',
    occurredAt: '2026-06-27T10:00:00Z',
    category: 'RATING_CHANGE',
    action: 'RATING_SET',
    entityType: 'RATING',
    entityId: 'u2',
    summary: 'Set rating to 4.0',
    details: {},
    comment: null,
    actor: { userId: 'admin1', displayName: 'Admin', publicCode: 'ADM123' },
    target: { userId: 'u2', displayName: 'Bob', publicCode: 'BOB456' },
    ...overrides,
  }
}

function page(items: unknown[], total = items.length) {
  return { data: { items, total }, isLoading: false }
}

function renderSection() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ActivityLogSection />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('ActivityLogSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Audit.mockReturnValue(page([entry()]))
    usePatchApiV1AuditIdComment.mockImplementation(
      (options: { mutation: { onSuccess: () => void } }) => ({
        isPending: false,
        mutate: (vars: unknown) => {
          patchMutate(vars)
          options.mutation.onSuccess()
        },
      }),
    )
  })

  it('shows a loading state', () => {
    useGetApiV1Audit.mockReturnValue({ data: undefined, isLoading: true })
    renderSection()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows an empty state', () => {
    useGetApiV1Audit.mockReturnValue(page([]))
    renderSection()
    expect(screen.getByText('No activity recorded yet.')).toBeInTheDocument()
  })

  it('renders an entry with resolved actor/target and a localized time, defaulting to all categories', () => {
    renderSection()
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 25, offset: 0 })
    expect(screen.getByText('Admin (ADM123)')).toBeInTheDocument()
    expect(screen.getByText('Bob (BOB456)')).toBeInTheDocument()
    expect(screen.getByText('Set rating to 4.0')).toBeInTheDocument()
    expect(
      screen.getByText(new Date('2026-06-27T10:00:00Z').toLocaleString()),
    ).toBeInTheDocument()
  })

  it('links the actor and user target to their public profiles wearing the themed content-link style (#136, #417)', () => {
    renderSection()
    const actor = screen.getByRole('link', { name: 'Admin (ADM123)' })
    const target = screen.getByRole('link', { name: 'Bob (BOB456)' })
    expect(actor).toHaveAttribute('href', '/players/ADM123')
    expect(target).toHaveAttribute('href', '/players/BOB456')
    // Who/Target links use the theme-aware --link token (not the primary purple), so they stay
    // WCAG-AA legible on the grass card #1b3b2b (#417).
    expect(actor).toHaveClass('content-link')
    expect(target).toHaveClass('content-link')
  })

  it('links a match-targeted entry to its public match page (#136)', () => {
    useGetApiV1Audit.mockReturnValue(
      page([
        entry({
          id: 'm1',
          category: 'MATCH_FIXTURE',
          action: 'MATCH_FIXTURE_CREATED',
          entityType: 'MATCH',
          entityId: 'match-uuid',
          summary: 'Created a SINGLES fixture',
          target: null,
          matchTarget: { matchId: 'match-uuid', publicCode: 'MTCH01', matchDate: '2026-02-03' },
        }),
      ]),
    )
    renderSection()
    const link = screen.getByRole('link', { name: 'Match MTCH01' })
    expect(link).toHaveAttribute('href', '/matches/MTCH01')
    expect(link).toHaveClass('content-link')
  })

  it('shows System for a null actor, an id fallback without a name, and a dash for no target', () => {
    useGetApiV1Audit.mockReturnValue(
      page([
        entry({ id: 'a1', actor: null, target: null }),
        entry({
          id: 'a2',
          actor: { userId: 'abcdef123456', displayName: null, publicCode: null },
          target: null,
        }),
      ]),
    )
    renderSection()
    expect(screen.getByText('System')).toBeInTheDocument()
    expect(screen.getByText('abcdef12 (—)')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2) // both rows have no target
  })

  it('filters by category and resets to the first page', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.selectOptions(screen.getByLabelText('Category'), 'CAPABILITY_CHANGE')
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({
      category: 'CAPABILITY_CHANGE',
      limit: 25,
      offset: 0,
    })
  })

  it('offers the Events and Rating-calculation category filters (#333/#334)', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.selectOptions(screen.getByLabelText('Category'), 'EVENT_MANAGEMENT')
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: 'EVENT_MANAGEMENT', limit: 25, offset: 0 })
    await user.selectOptions(screen.getByLabelText('Category'), 'RATING_CALCULATION')
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: 'RATING_CALCULATION', limit: 25, offset: 0 })
  })

  it('paginates 25 rows per page, enabling/disabling Previous and Next at the ends', async () => {
    useGetApiV1Audit.mockReturnValue(page([entry()], 60)) // 60 / 25 = 3 pages
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('Page 1 of 3 · 60 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 25, offset: 25 })
    expect(screen.getByText('Page 2 of 3 · 60 total')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 3 of 3 · 60 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByText('Page 2 of 3 · 60 total')).toBeInTheDocument()
  })

  it('jumps to the first, last, and a specific page (#529)', async () => {
    useGetApiV1Audit.mockReturnValue(page([entry()], 60)) // 60 / 25 = 3 pages
    const user = userEvent.setup()
    renderSection()

    // First is disabled on page 1; Last jumps straight to the final page.
    expect(screen.getByRole('button', { name: 'First page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Last page' }))
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 25, offset: 50 })
    expect(screen.getByText('Page 3 of 3 · 60 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last page' })).toBeDisabled()

    // First jumps back to page 1.
    await user.click(screen.getByRole('button', { name: 'First page' }))
    expect(screen.getByText('Page 1 of 3 · 60 total')).toBeInTheDocument()

    // Jump-to-page takes a 1-based number and clears the box afterwards.
    await user.type(screen.getByLabelText('Go to page'), '2')
    await user.click(screen.getByRole('button', { name: 'Go' }))
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 25, offset: 25 })
    expect(screen.getByText('Page 2 of 3 · 60 total')).toBeInTheDocument()
    expect((screen.getByLabelText('Go to page') as HTMLInputElement).value).toBe('')
  })

  it('clamps an out-of-range jump to the last page (#529)', async () => {
    useGetApiV1Audit.mockReturnValue(page([entry()], 60))
    const user = userEvent.setup()
    renderSection()
    await user.type(screen.getByLabelText('Go to page'), '99')
    await user.click(screen.getByRole('button', { name: 'Go' }))
    expect(screen.getByText('Page 3 of 3 · 60 total')).toBeInTheDocument()
  })

  it('prefills an existing note and saves an edit', async () => {
    useGetApiV1Audit.mockReturnValue(page([entry({ comment: 'existing note' })]))
    const user = userEvent.setup()
    renderSection()

    const input = screen.getByLabelText('Note')
    expect((input as HTMLInputElement).value).toBe('existing note')

    await user.clear(input)
    await user.type(input, 'updated note')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(patchMutate).toHaveBeenCalledWith({ id: 'a1', data: { comment: 'updated note' } })
  })
})
