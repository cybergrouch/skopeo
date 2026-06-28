import { useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import {
  getGetApiV1UsersIdDuplicatesQueryKey,
  useDeleteApiV1UsersIdDuplicate,
  useGetApiV1UsersIdDuplicates,
  usePostApiV1UsersIdDuplicates,
} from '@/api/generated/users/users'
import type { UserSummaryResponse } from '@/api/generated/model'

function label(user: UserSummaryResponse): string {
  return user.displayName ?? user.publicCode
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Duplicate profiles</CardTitle>
        <CardDescription>
          Keep one canonical account and mark the others as duplicates. Duplicates are disabled (not
          deleted) and drop out of search; their public profile links here. Ratings are not merged.
          This is reversible.
        </CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

/**
 * Duplicate-profile rectification (#124): pick a canonical ("true") account and mark one or more other
 * profiles as its duplicates (disabling them). The canonical's current duplicates are listed with a
 * Restore action. Ratings are never consolidated — duplicates are only disabled, reversibly.
 */
export function DuplicatesSection() {
  const [canonical, setCanonical] = useState<UserSummaryResponse | null>(null)
  if (!canonical) {
    return (
      <Shell>
        <UserSearchSelect label="Canonical (true) account" onSelect={setCanonical} />
      </Shell>
    )
  }
  return (
    <Shell>
      <ForCanonical canonical={canonical} onChangeCanonical={() => setCanonical(null)} />
    </Shell>
  )
}

function ForCanonical({
  canonical,
  onChangeCanonical,
}: {
  canonical: UserSummaryResponse
  onChangeCanonical: () => void
}) {
  const queryClient = useQueryClient()
  const [duplicates, setDuplicates] = useState<UserSummaryResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const existing = useGetApiV1UsersIdDuplicates(canonical.id)
  const mark = usePostApiV1UsersIdDuplicates()
  const restore = useDeleteApiV1UsersIdDuplicate()

  function invalidate() {
    queryClient.invalidateQueries({
      queryKey: getGetApiV1UsersIdDuplicatesQueryKey(canonical.id),
    })
  }

  async function onMark() {
    setDone(false)
    try {
      await mark.mutateAsync({ id: canonical.id, data: { duplicateIds: duplicates.map((d) => d.id) } })
      setError(null)
      setDone(true)
      setDuplicates([])
      invalidate()
    } catch {
      setError('Could not mark the selected profiles as duplicates.')
    }
  }

  async function onRestore(id: string) {
    await restore.mutateAsync({ id })
    invalidate()
  }

  const current = existing.data ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between rounded-md border p-2 text-sm">
        <span className="font-medium">{label(canonical)}</span>
        <Button type="button" variant="ghost" size="sm" onClick={onChangeCanonical}>
          Change
        </Button>
      </div>

      <UserSearchSelect
        label="Add a duplicate to mark"
        excludeIds={[canonical.id, ...duplicates.map((d) => d.id)]}
        onSelect={(u) => setDuplicates((prev) => [...prev, u])}
      />
      {duplicates.length > 0 ? (
        <ul className="space-y-2">
          {duplicates.map((d) => (
            <li key={d.id} className="flex items-center justify-between rounded-md border p-2 text-sm">
              <span>{label(d)}</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => setDuplicates((prev) => prev.filter((x) => x.id !== d.id))}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {error ? (
        <p className="text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
      {done ? (
        <p className="text-sm text-foreground" role="status">
          Marked as duplicates.
        </p>
      ) : null}

      <Button type="button" disabled={duplicates.length === 0 || mark.isPending} onClick={onMark}>
        {mark.isPending ? 'Marking…' : 'Mark as duplicates'}
      </Button>

      {current.length > 0 ? (
        <div className="space-y-2">
          <p className="text-sm font-medium">Current duplicates of this account</p>
          <ul className="space-y-2">
            {current.map((d) => (
              <li key={d.id} className="flex items-center justify-between rounded-md border p-2 text-sm">
                <span>{label(d)}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={restore.isPending}
                  onClick={() => onRestore(d.id)}
                >
                  Restore
                </Button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
