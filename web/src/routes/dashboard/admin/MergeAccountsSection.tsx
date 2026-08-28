import { useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { toastError } from '@/observability/toastError'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { UserSearchSelect } from '@/components/UserSearchSelect'
import { usePostApiV1UsersIdMerge } from '@/api/generated/users/users'
import type { UserSummaryResponse } from '@/api/generated/model'

function label(user: UserSummaryResponse): string {
  return user.displayName ?? user.publicCode
}

/** Whether the account carries a login (any provider) — NONE means a login-less placeholder. */
function isLinked(user: UserSummaryResponse): boolean {
  return (user.linkStatus ?? 'NONE') !== 'NONE'
}

/** Human-readable login/link status for the per-account badge. */
function linkStatusLabel(user: UserSummaryResponse): string {
  switch (user.linkStatus ?? 'NONE') {
    case 'GOOGLE':
      return 'Google login'
    case 'FACEBOOK':
      return 'Facebook login'
    case 'PASSWORD':
      return 'Email/password login'
    default:
      return 'No login (placeholder)'
  }
}

function AccountRow({
  role,
  user,
  onChange,
}: {
  role: string
  user: UserSummaryResponse
  onChange: () => void
}) {
  return (
    <div className="flex items-center justify-between gap-2 rounded-md border p-2 text-sm">
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">{role}</span>
        <span className="font-medium">{label(user)}</span>
      </div>
      <div className="flex items-center gap-2">
        <Badge variant={isLinked(user) ? 'secondary' : 'outline'}>{linkStatusLabel(user)}</Badge>
        <Button type="button" variant="ghost" size="sm" onClick={onChange}>
          Change
        </Button>
      </div>
    </div>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Merge accounts</CardTitle>
        <CardDescription>
          Consolidate a person’s duplicate accounts into one survivor. All participation and membership
          records (matches, events, lists, clubs, seeding) move to the survivor; the survivor keeps its own
          rating and ranking points (no recompute). The survivor keeps the best available login — if the
          retired account has one, it is transferred to the survivor. This is permanent and cannot be undone.
        </CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

/**
 * Generalized admin account-merge (#643): pick a survivor and a retired account, review each account's
 * derived login/link status, and merge. Soft-recommends the linked account as survivor and warns (without
 * blocking) when the choice leaves the survivor unlinked or discards the only login. Requires a non-blank
 * verification note and an explicit irreversible-merge confirmation.
 */
export function MergeAccountsSection() {
  const [survivor, setSurvivor] = useState<UserSummaryResponse | null>(null)
  const [retired, setRetired] = useState<UserSummaryResponse | null>(null)
  const [note, setNote] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const merge = usePostApiV1UsersIdMerge()

  function resetForm() {
    setSurvivor(null)
    setRetired(null)
    setNote('')
    setConfirmed(false)
  }

  async function onMerge() {
    if (!survivor || !retired) return
    try {
      await merge.mutateAsync({
        id: survivor.id,
        data: { retiredAccountId: retired.id, verificationNote: note.trim() },
      })
      toast.success('Accounts merged.')
      resetForm()
    } catch (error) {
      toastError('Could not merge the accounts.', { cause: error, duration: 8000 })
    }
  }

  // Soft guidance (never blocks): recommend the linked account as survivor, and warn when the merge would
  // leave the survivor unlinked or throw away a login.
  const recommendSwap = survivor && retired && !isLinked(survivor) && isLinked(retired)
  const warnUnlinked = survivor && retired && !isLinked(survivor) && !isLinked(retired)
  const warnDiscard = survivor && retired && isLinked(survivor) && isLinked(retired)

  const ready = Boolean(survivor && retired && note.trim().length > 0 && confirmed && !merge.isPending)

  return (
    <Shell>
      <div className="space-y-4">
        {survivor ? (
          <AccountRow role="Survivor (keeps records, rating, and points)" user={survivor} onChange={() => setSurvivor(null)} />
        ) : (
          <UserSearchSelect
            label="Survivor account"
            excludeIds={retired ? [retired.id] : []}
            onSelect={setSurvivor}
          />
        )}

        {retired ? (
          <AccountRow role="Retired (merged away)" user={retired} onChange={() => setRetired(null)} />
        ) : (
          <UserSearchSelect
            label="Retired account"
            excludeIds={survivor ? [survivor.id] : []}
            onSelect={setRetired}
          />
        )}

        {recommendSwap ? (
          <p className="rounded-md border border-dashed p-2 text-xs text-muted-foreground">
            Tip: the retired account has the login and the survivor does not. The login will be moved to the
            survivor, but consider making the linked account the survivor instead.
          </p>
        ) : null}
        {warnUnlinked ? (
          <p className="rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs">
            Warning: neither account has a login, so the survivor will remain a login-less placeholder.
          </p>
        ) : null}
        {warnDiscard ? (
          <p className="rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs">
            Warning: both accounts have a login. The survivor will inherit the retired account’s login and the
            survivor’s current login will be discarded.
          </p>
        ) : null}

        <div className="space-y-1">
          <Label htmlFor="merge-note">Verification note (required)</Label>
          <textarea
            id="merge-note"
            className="min-h-16 w-full rounded-md border bg-background p-2 text-sm"
            placeholder="How did you confirm these are the same person? (kept in the audit log)"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
            aria-label="I understand this merge is permanent and cannot be undone"
          />
          I understand this merge is permanent and cannot be undone.
        </label>

        <Button type="button" disabled={!ready} onClick={onMerge}>
          {merge.isPending ? 'Merging…' : 'Merge accounts'}
        </Button>
      </div>
    </Shell>
  )
}
