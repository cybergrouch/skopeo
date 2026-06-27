import { useState } from 'react'
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

function PickedUser({
  user,
  onClear,
}: {
  user: UserSummaryResponse
  onClear: () => void
}) {
  return (
    <div className="flex items-center justify-between rounded-md border p-2 text-sm">
      <span className="font-medium">{user.displayName ?? user.publicCode}</span>
      <Button type="button" variant="ghost" size="sm" onClick={onClear}>
        Change
      </Button>
    </div>
  )
}

/**
 * Duplicate-profile rectification (#124): pick a canonical ("true") account and mark one or more other
 * profiles as its duplicates (disabling them). The canonical's current duplicates are listed below with
 * a Restore action. Ratings are never consolidated — duplicates are only disabled, reversibly.
 */
export function DuplicatesSection() {
  const queryClient = useQueryClient()
  const [canonical, setCanonical] = useState<UserSummaryResponse | null>(null)
  const [duplicates, setDuplicates] = useState<UserSummaryResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const existing = useGetApiV1UsersIdDuplicates(canonical?.id ?? '', {
    query: { enabled: !!canonical },
  })

  function refresh() {
    if (canonical) {
      queryClient.invalidateQueries({
        queryKey: getGetApiV1UsersIdDuplicatesQueryKey(canonical.id),
      })
    }
  }

  const mark = usePostApiV1UsersIdDuplicates({
    mutation: {
      onSuccess: () => {
        setDone(true)
        setError(null)
        setDuplicates([])
        refresh()
      },
      onError: () => setError('Could not mark the selected profiles as duplicates.'),
    },
  })

  const restore = useDeleteApiV1UsersIdDuplicate({
    mutation: { onSuccess: refresh },
  })

  async function onMark() {
    if (!canonical || duplicates.length === 0) return
    setDone(false)
    await mark.mutateAsync({
      id: canonical.id,
      data: { duplicateIds: duplicates.map((d) => d.id) },
    })
  }

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
      <CardContent>
        <div className="space-y-4">
          {canonical ? (
            <PickedUser user={canonical} onClear={() => setCanonical(null)} />
          ) : (
            <UserSearchSelect
              label="Canonical (true) account"
              excludeIds={duplicates.map((d) => d.id)}
              onSelect={setCanonical}
            />
          )}

          <UserSearchSelect
            label="Add a duplicate to mark"
            excludeIds={[
              ...(canonical ? [canonical.id] : []),
              ...duplicates.map((d) => d.id),
            ]}
            onSelect={(u) => setDuplicates((prev) => [...prev, u])}
          />
          {duplicates.length > 0 ? (
            <ul className="space-y-2">
              {duplicates.map((d) => (
                <PickedUser
                  key={d.id}
                  user={d}
                  onClear={() => setDuplicates((prev) => prev.filter((x) => x.id !== d.id))}
                />
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

          <Button
            type="button"
            disabled={!canonical || duplicates.length === 0 || mark.isPending}
            onClick={onMark}
          >
            {mark.isPending ? 'Marking…' : 'Mark as duplicates'}
          </Button>

          {canonical && (existing.data?.length ?? 0) > 0 ? (
            <div className="space-y-2">
              <p className="text-sm font-medium">Current duplicates of this account</p>
              <ul className="space-y-2">
                {existing.data?.map((d) => (
                  <li
                    key={d.id}
                    className="flex items-center justify-between rounded-md border p-2 text-sm"
                  >
                    <span>{d.displayName ?? d.publicCode}</span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={restore.isPending}
                      onClick={() => restore.mutateAsync({ id: d.id })}
                    >
                      Restore
                    </Button>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </CardContent>
    </Card>
  )
}
