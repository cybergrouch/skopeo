import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ContentLink } from '@/components/ContentLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  getGetApiV1AuditQueryKey,
  useGetApiV1Audit,
  usePatchApiV1AuditIdComment,
} from '@/api/generated/audit/audit'
import type {
  AuditEntryResponse,
  AuditPersonResponse,
  GetApiV1AuditCategory,
} from '@/api/generated/model'

// Activity (invites, ratings, matches, duplicate-rectification, …) grows quickly, so 25 rows/page
// (#134) rather than the original 5.
const PAGE_SIZE = 25

// The trace categories the viewer can scope to (#102). "All" clears the filter.
const CATEGORIES = [
  { value: 'ALL', label: 'All activity' },
  { value: 'USER_CREATION', label: 'User creation' },
  { value: 'NAME_CHANGE', label: 'Name changes' },
  { value: 'CONTACT_CHANGE', label: 'Contact changes' },
  { value: 'INVITE', label: 'Invites' },
  { value: 'MATCH_FIXTURE', label: 'Match fixtures' },
  { value: 'MATCH_RESULT', label: 'Match results' },
  { value: 'EVENT_MANAGEMENT', label: 'Events' },
  { value: 'CAPABILITY_CHANGE', label: 'Capability changes' },
  { value: 'RATING_CHANGE', label: 'Rating changes' },
  { value: 'RATING_CALCULATION', label: 'Rating calculations' },
] as const

type CategoryFilter = (typeof CATEGORIES)[number]['value']

/** "Name (CODE)" for a resolved user, or "System" for a system/self-driven action. */
function person(p: AuditPersonResponse | null | undefined): string {
  if (!p) return 'System'
  return `${p.displayName ?? p.userId.slice(0, 8)} (${p.publicCode ?? '—'})`
}

/** A user cell — links to the public profile when a public code is known (#136). */
function PersonCell({ p }: { p: AuditPersonResponse | null | undefined }) {
  if (!p) return <>System</>
  if (!p.publicCode)
    return (
      <>
        {person(p)}
        <PlaceholderTag show={p.isPlaceholder} />
      </>
    )
  return (
    <>
      <ContentLink to={`/players/${p.publicCode}`}>{person(p)}</ContentLink>
      <PlaceholderTag show={p.isPlaceholder} />
    </>
  )
}

/** The target cell: a match link for match entries (#136), a user link for user entries, else "—". */
function TargetCell({ entry }: { entry: AuditEntryResponse }) {
  if (entry.matchTarget) {
    return (
      <ContentLink to={`/matches/${entry.matchTarget.publicCode}`}>
        Match {entry.matchTarget.publicCode}
      </ContentLink>
    )
  }
  if (entry.target) return <PersonCell p={entry.target} />
  return <>—</>
}

/** A free-text admin note on an entry; saving it isn't itself audited (#100). */
function NoteCell({ entryId, comment }: { entryId: string; comment: string | null | undefined }) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState(comment ?? '')
  const patch = usePatchApiV1AuditIdComment({
    mutation: {
      onSuccess: () => queryClient.invalidateQueries({ queryKey: getGetApiV1AuditQueryKey() }),
    },
  })

  return (
    <div className="flex items-center gap-1">
      <Input
        aria-label="Note"
        className="h-8"
        placeholder="Add note"
        value={value}
        onChange={(e) => setValue(e.target.value)}
      />
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={patch.isPending}
        onClick={() => patch.mutate({ id: entryId, data: { comment: value } })}
      >
        Save
      </Button>
    </div>
  )
}

/**
 * Admin trace viewer (#102): an ADMINISTRATOR-only, read-only table of provenance entries, newest
 * first, scoped by category and paginated. Actor/target ids are resolved to name + player code
 * server-side; timestamps render in the viewer's timezone. Admins can attach a free-text note.
 */
export function ActivityLogSection() {
  const [category, setCategory] = useState<CategoryFilter>('ALL')
  const [page, setPage] = useState(0)

  const query = useGetApiV1Audit({
    category: category === 'ALL' ? undefined : (category as GetApiV1AuditCategory),
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  })
  const items = query.data?.items ?? []
  const total = query.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))

  function changeCategory(next: CategoryFilter) {
    setCategory(next)
    setPage(0)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Activity log</CardTitle>
        <CardDescription>
          Who did what, and when — newest first. Times are shown in your timezone.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <select
          aria-label="Category"
          className="h-9 rounded-md border border-input bg-transparent px-3 text-sm"
          value={category}
          onChange={(e) => changeCategory(e.target.value as CategoryFilter)}
        >
          {CATEGORIES.map((c) => (
            <option key={c.value} value={c.value}>
              {c.label}
            </option>
          ))}
        </select>

        {query.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : items.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr>
                    <th className="py-1 pr-3">When</th>
                    <th className="py-1 pr-3">Who</th>
                    <th className="py-1 pr-3">Action</th>
                    <th className="py-1 pr-3">Target</th>
                    <th className="py-1">Note</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((entry) => (
                    <tr key={entry.id} className="border-t align-top">
                      <td className="py-2 pr-3 text-muted-foreground">
                        {new Date(entry.occurredAt).toLocaleString()}
                      </td>
                      <td className="py-2 pr-3">
                        <PersonCell p={entry.actor} />
                      </td>
                      <td className="py-2 pr-3">{entry.summary}</td>
                      <td className="py-2 pr-3">
                        <TargetCell entry={entry} />
                      </td>
                      <td className="py-2">
                        <NoteCell entryId={entry.id} comment={entry.comment} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {total > PAGE_SIZE ? (
              <div className="flex items-center justify-between text-sm">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}
                >
                  Previous
                </Button>
                <span className="text-muted-foreground">
                  Page {page + 1} of {pageCount} · {total} total
                </span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={page >= pageCount - 1}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            ) : null}
          </>
        ) : (
          <p className="text-sm text-muted-foreground">No activity recorded yet.</p>
        )}
      </CardContent>
    </Card>
  )
}
