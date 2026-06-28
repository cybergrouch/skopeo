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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import {
  getGetApiV1DuplicateCandidatesQueryKey,
  useDeleteApiV1DuplicateCandidatesId,
  useGetApiV1DuplicateCandidates,
  usePostApiV1DuplicateCandidates,
  usePostApiV1DuplicateCandidatesIdConfirm,
} from '@/api/generated/duplicates/duplicates'
import type {
  DuplicateCandidateResponse,
  UserSummaryResponse,
} from '@/api/generated/model'

const SIGNAL_LABELS: Record<string, string> = {
  DUPLICATE_PHONE: 'Shared phone',
  MANUAL: 'Manual',
}

function label(user: UserSummaryResponse): string {
  return user.displayName ?? user.publicCode
}

const OPEN = { status: 'OPEN' as const }

/**
 * Duplicate-candidate queue (#126): review suspected duplicate pairs surfaced by detection (shared phone)
 * or flagged manually. Confirm picks the canonical account and marks the other a duplicate (via #124);
 * Dismiss clears a false positive. An admin can also flag a pair manually below.
 */
export function DuplicateCandidatesSection() {
  const queryClient = useQueryClient()
  const [userA, setUserA] = useState<UserSummaryResponse | null>(null)
  const [userB, setUserB] = useState<UserSummaryResponse | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const candidates = useGetApiV1DuplicateCandidates(OPEN)
  const flag = usePostApiV1DuplicateCandidates()
  const confirm = usePostApiV1DuplicateCandidatesIdConfirm()
  const dismiss = useDeleteApiV1DuplicateCandidatesId()

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: getGetApiV1DuplicateCandidatesQueryKey(OPEN) })
  }

  async function onFlag(a: UserSummaryResponse, b: UserSummaryResponse) {
    try {
      await flag.mutateAsync({ data: { userAId: a.id, userBId: b.id, reason: reason || null } })
      setUserA(null)
      setUserB(null)
      setReason('')
      setError(null)
      invalidate()
    } catch {
      setError('Could not flag the pair (they must be two different users).')
    }
  }

  async function onConfirm(id: string, canonicalId: string) {
    await confirm.mutateAsync({ id, data: { canonicalId } })
    invalidate()
  }

  async function onDismiss(id: string) {
    await dismiss.mutateAsync({ id })
    invalidate()
  }

  const open = candidates.data?.items ?? []

  return (
    <Card>
      <CardHeader>
        <CardTitle>Duplicate candidates</CardTitle>
        <CardDescription>
          Suspected duplicate accounts surfaced automatically (shared phone) or flagged manually. Confirm
          to keep one account and mark the other a duplicate; dismiss a false positive.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          {open.length === 0 ? (
            <p className="text-sm text-muted-foreground">No open candidates.</p>
          ) : (
            <ul className="space-y-3">
              {open.map((c: DuplicateCandidateResponse) => (
                <li key={c.id} className="space-y-2 rounded-md border p-3 text-sm">
                  <p>
                    <span className="font-medium">{label(c.userA)}</span> &amp;{' '}
                    <span className="font-medium">{label(c.userB)}</span>
                    <span className="text-muted-foreground">
                      {' '}— {SIGNAL_LABELS[c.signal]}
                      {c.detail ? ` (${c.detail})` : ''}
                    </span>
                  </p>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" onClick={() => onConfirm(c.id, c.userA.id)}>
                      Keep {label(c.userA)}
                    </Button>
                    <Button type="button" size="sm" onClick={() => onConfirm(c.id, c.userB.id)}>
                      Keep {label(c.userB)}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" onClick={() => onDismiss(c.id)}>
                      Dismiss
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <div className="space-y-2 border-t pt-4">
            <p className="text-sm font-medium">Flag a pair manually</p>
            {userA ? (
              <div className="flex items-center justify-between rounded-md border p-2 text-sm">
                <span>{label(userA)}</span>
                <Button type="button" variant="ghost" size="sm" onClick={() => setUserA(null)}>
                  Change
                </Button>
              </div>
            ) : (
              <UserSearchSelect
                label="First account"
                excludeIds={userB ? [userB.id] : []}
                onSelect={setUserA}
              />
            )}
            {userB ? (
              <div className="flex items-center justify-between rounded-md border p-2 text-sm">
                <span>{label(userB)}</span>
                <Button type="button" variant="ghost" size="sm" onClick={() => setUserB(null)}>
                  Change
                </Button>
              </div>
            ) : (
              <UserSearchSelect
                label="Second account"
                excludeIds={userA ? [userA.id] : []}
                onSelect={setUserB}
              />
            )}
            <div className="space-y-1">
              <Label htmlFor="reason">Reason (optional)</Label>
              <Input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            {error ? (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            ) : null}
            {userA && userB ? (
              <Button type="button" onClick={() => onFlag(userA, userB)}>
                Flag as candidate
              </Button>
            ) : null}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
