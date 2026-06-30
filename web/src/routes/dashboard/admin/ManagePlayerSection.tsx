import { useState, type FormEvent } from 'react'
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
import { ProfileFieldsForm } from '@/components/ProfileFieldsForm'
import {
  getGetApiV1UsersUserIdRatingHistoryQueryKey,
  getGetApiV1UsersUserIdRatingsQueryKey,
  useGetApiV1UsersUserIdRatings,
  usePutApiV1UsersUserIdRatings,
} from '@/api/generated/ratings/ratings'
import {
  getGetApiV1UsersUserIdCapabilitiesQueryKey,
  useDeleteApiV1UsersUserIdCapabilitiesCapability,
  useGetApiV1UsersUserIdCapabilities,
  usePostApiV1UsersUserIdCapabilities,
} from '@/api/generated/capabilities/capabilities'
import type { UserSummaryResponse } from '@/api/generated/model'

// Roles an admin can grant/revoke here. ADMINISTRATOR is included (#194) but gated behind a confirm
// step, since it's a high-impact grant; the backend also refuses to revoke a bootstrap admin.
const GRANTABLE = ['HOST', 'CLUB_OWNER', 'RATER', 'RESEARCHER', 'ADMINISTRATOR'] as const
type GrantableRole = (typeof GRANTABLE)[number]
const ADMIN_ROLE: GrantableRole = 'ADMINISTRATOR'

/** The backend's `{ error, message }` body carries a human-readable reason (e.g. last-admin, bootstrap). */
function errorMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message ?? fallback
}

/** Push a new NTRP rating, overriding the current one (recorded in rating history, #96). */
function RatingForm({ userId, initialValue }: { userId: string; initialValue: string }) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState(initialValue)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const setRating = usePutApiV1UsersUserIdRatings({
    mutation: {
      onSuccess: () => {
        setSaved(true)
        queryClient.invalidateQueries({ queryKey: getGetApiV1UsersUserIdRatingsQueryKey(userId) })
        queryClient.invalidateQueries({
          queryKey: getGetApiV1UsersUserIdRatingHistoryQueryKey(userId),
        })
      },
    },
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSaved(false)
    setError(null)
    try {
      await setRating.mutateAsync({ userId, data: { value } })
    } catch {
      setError('Could not set the rating. Check the value and try again.')
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-2">
      <div className="space-y-1">
        <Label htmlFor="manage-rating" className="text-xs">
          NTRP rating
        </Label>
        <Input
          id="manage-rating"
          value={value}
          placeholder="e.g. 4.0"
          onChange={(e) => {
            setValue(e.target.value)
            setSaved(false)
          }}
        />
      </div>
      <Button type="submit" size="sm" disabled={setRating.isPending}>
        Override rating
      </Button>
      {saved ? (
        <span className="text-xs text-muted-foreground" role="status">
          Saved
        </span>
      ) : null}
      {error ? (
        <span className="text-xs text-destructive" role="alert">
          {error}
        </span>
      ) : null}
    </form>
  )
}

function RatingEditor({ userId }: { userId: string }) {
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId)
  if (ratingsQuery.isLoading) {
    return <p className="text-sm text-muted-foreground">Loading…</p>
  }
  const current = ratingsQuery.data?.[0]?.value ?? ''
  return <RatingForm key={userId} userId={userId} initialValue={current} />
}

function CapabilitiesEditor({ userId }: { userId: string }) {
  const queryClient = useQueryClient()
  const capsQuery = useGetApiV1UsersUserIdCapabilities(userId)
  const active = new Set(
    (capsQuery.data ?? []).filter((c) => c.isActive).map((c) => c.capability),
  )
  const [error, setError] = useState<string | null>(null)
  // The role/action awaiting a confirm click — only ADMINISTRATOR is gated this way.
  const [confirming, setConfirming] = useState<string | null>(null)

  const invalidate = () => {
    setError(null)
    setConfirming(null)
    queryClient.invalidateQueries({
      queryKey: getGetApiV1UsersUserIdCapabilitiesQueryKey(userId),
    })
  }
  const grant = usePostApiV1UsersUserIdCapabilities({
    mutation: {
      onSuccess: invalidate,
      onError: (e) => setError(errorMessage(e, 'Could not grant the role.')),
    },
  })
  const revoke = useDeleteApiV1UsersUserIdCapabilitiesCapability({
    mutation: {
      onSuccess: invalidate,
      onError: (e) => setError(errorMessage(e, 'Could not revoke the role.')),
    },
  })
  const busy = grant.isPending || revoke.isPending

  const doGrant = (role: GrantableRole) => grant.mutate({ userId, data: { capability: role } })
  const doRevoke = (role: GrantableRole) => revoke.mutate({ userId, capability: role })
  // ADMINISTRATOR is high-impact, so the first click asks for confirmation; other roles act directly.
  const onGrant = (role: GrantableRole) => (role === ADMIN_ROLE ? setConfirming(`grant:${role}`) : doGrant(role))
  const onRevoke = (role: GrantableRole) => (role === ADMIN_ROLE ? setConfirming(`revoke:${role}`) : doRevoke(role))

  return (
    <>
      <ul className="space-y-2">
        {GRANTABLE.map((role) => {
          const has = active.has(role)
          const isConfirming = confirming === `${has ? 'revoke' : 'grant'}:${role}`
          return (
            <li
              key={role}
              className="flex items-center justify-between gap-2 rounded-md border p-2 text-sm"
            >
              <span className="font-medium">{role}</span>
              {isConfirming ? (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">
                    {has ? 'Revoke administrator access?' : 'Grant administrator access?'}
                  </span>
                  <Button
                    type="button"
                    size="sm"
                    disabled={busy}
                    aria-label={`Confirm ${has ? 'revoke' : 'grant'} ${role}`}
                    onClick={() => (has ? doRevoke(role) : doGrant(role))}
                  >
                    Confirm
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() => setConfirming(null)}
                  >
                    Cancel
                  </Button>
                </div>
              ) : has ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  aria-label={`Revoke ${role}`}
                  onClick={() => onRevoke(role)}
                >
                  Revoke
                </Button>
              ) : (
                <Button
                  type="button"
                  size="sm"
                  disabled={busy}
                  aria-label={`Grant ${role}`}
                  onClick={() => onGrant(role)}
                >
                  Grant
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      {error ? (
        <p className="mt-2 text-xs text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </>
  )
}

function ManageBlock({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2 rounded-lg border p-3">
      <h4 className="text-sm font-semibold">{title}</h4>
      {children}
    </div>
  )
}

/**
 * Admin player management (#96): find a member, then edit their profile (sex, date of birth),
 * override their NTRP rating, and grant/revoke roles — all in one place. Reached via search, so
 * the public profile page stays read-only.
 */
export function ManagePlayerSection() {
  const [user, setUser] = useState<UserSummaryResponse | null>(null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Manage player</CardTitle>
        <CardDescription>
          Find a member to edit their profile, rating, and roles.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {user ? (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">
                {user.displayName ?? user.id}{' '}
                <span className="text-muted-foreground">· {user.publicCode}</span>
              </span>
              <Button type="button" variant="ghost" size="sm" onClick={() => setUser(null)}>
                Change
              </Button>
            </div>
            <ManageBlock title="Profile">
              <ProfileFieldsForm userId={user.id} />
            </ManageBlock>
            <ManageBlock title="Rating">
              <RatingEditor userId={user.id} />
            </ManageBlock>
            <ManageBlock title="Roles">
              <CapabilitiesEditor userId={user.id} />
            </ManageBlock>
          </div>
        ) : (
          <UserSearchSelect label="Member" onSelect={setUser} />
        )}
      </CardContent>
    </Card>
  )
}
