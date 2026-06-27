import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    <QueryClientProvider client={new QueryClient()}>
      <ActivityLogSection />
    </QueryClientProvider>,
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
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 5, offset: 0 })
    expect(screen.getByText('Admin (ADM123)')).toBeInTheDocument()
    expect(screen.getByText('Bob (BOB456)')).toBeInTheDocument()
    expect(screen.getByText('Set rating to 4.0')).toBeInTheDocument()
    expect(
      screen.getByText(new Date('2026-06-27T10:00:00Z').toLocaleString()),
    ).toBeInTheDocument()
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
      limit: 5,
      offset: 0,
    })
  })

  it('paginates, enabling/disabling Previous and Next at the ends', async () => {
    useGetApiV1Audit.mockReturnValue(page([entry()], 12))
    const user = userEvent.setup()
    renderSection()

    expect(screen.getByText('Page 1 of 3 · 12 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(useGetApiV1Audit).toHaveBeenLastCalledWith({ category: undefined, limit: 5, offset: 5 })
    expect(screen.getByText('Page 2 of 3 · 12 total')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 3 of 3 · 12 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByText('Page 2 of 3 · 12 total')).toBeInTheDocument()
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
