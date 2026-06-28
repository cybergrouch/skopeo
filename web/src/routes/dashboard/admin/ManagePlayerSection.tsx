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
import {
  getGetApiV1UsersIdQueryKey,
  useGetApiV1UsersId,
  usePatchApiV1UsersId,
} from '@/api/generated/users/users'
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
import type { ProfileRequestSex, UserSummaryResponse } from '@/api/generated/model'

// Roles an admin grants here. ADMINISTRATOR is deliberately not toggleable from the UI.
const GRANTABLE = ['HOST', 'CLUB_OWNER', 'RATER'] as const

/** Edit a player's sex and date of birth (PATCH leaves untouched fields unchanged). */
function ProfileForm({
  userId,
  initialSex,
  initialDateOfBirth,
}: {
  userId: string
  initialSex: string
  initialDateOfBirth: string
}) {
  const queryClient = useQueryClient()
  const [sex, setSex] = useState(initialSex)
  const [dateOfBirth, setDateOfBirth] = useState(initialDateOfBirth)
  const [saved, setSaved] = useState(false)

  const patch = usePatchApiV1UsersId({
    mutation: {
      onSuccess: () => {
        setSaved(true)
        queryClient.invalidateQueries({ queryKey: getGetApiV1UsersIdQueryKey(userId) })
      },
    },
  })

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSaved(false)
    const sexValue: ProfileRequestSex = sex === 'Male' || sex === 'Female' ? sex : null
    patch.mutate({
      id: userId,
      data: { sex: sexValue, dateOfBirth: dateOfBirth ? dateOfBirth : null },
    })
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="manage-sex" className="text-xs">
          Sex
        </Label>
        <select
          id="manage-sex"
          value={sex}
          onChange={(e) => {
            setSex(e.target.value)
            setSaved(false)
          }}
          className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
        >
          <option value="">—</option>
          <option value="Male">Male</option>
          <option value="Female">Female</option>
        </select>
      </div>
      <div className="space-y-1">
        <Label htmlFor="manage-dob" className="text-xs">
          Date of birth
        </Label>
        <Input
          id="manage-dob"
          type="date"
          value={dateOfBirth}
          onChange={(e) => {
            setDateOfBirth(e.target.value)
            setSaved(false)
          }}
        />
      </div>
      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" disabled={patch.isPending}>
          Save profile
        </Button>
        {saved ? (
          <span className="text-xs text-muted-foreground" role="status">
            Saved
          </span>
        ) : null}
      </div>
    </form>
  )
}

function ProfileEditor({ userId }: { userId: string }) {
  const userQuery = useGetApiV1UsersId(userId)
  if (userQuery.isLoading || !userQuery.data) {
    return <p className="text-sm text-muted-foreground">Loading…</p>
  }
  return (
    <ProfileForm
      key={userId}
      userId={userId}
      initialSex={userQuery.data.sex ?? ''}
      initialDateOfBirth={userQuery.data.dateOfBirth ?? ''}
    />
  )
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
  const invalidate = () =>
    queryClient.invalidateQueries({
      queryKey: getGetApiV1UsersUserIdCapabilitiesQueryKey(userId),
    })
  const grant = usePostApiV1UsersUserIdCapabilities({ mutation: { onSuccess: invalidate } })
  const revoke = useDeleteApiV1UsersUserIdCapabilitiesCapability({
    mutation: { onSuccess: invalidate },
  })
  const busy = grant.isPending || revoke.isPending

  return (
    <ul className="space-y-2">
      {GRANTABLE.map((role) => {
        const has = active.has(role)
        return (
          <li
            key={role}
            className="flex items-center justify-between rounded-md border p-2 text-sm"
          >
            <span className="font-medium">{role}</span>
            {has ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                aria-label={`Revoke ${role}`}
                onClick={() => revoke.mutate({ userId, capability: role })}
              >
                Revoke
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                disabled={busy}
                aria-label={`Grant ${role}`}
                onClick={() => grant.mutate({ userId, data: { capability: role } })}
              >
                Grant
              </Button>
            )}
          </li>
        )
      })}
    </ul>
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
              <ProfileEditor userId={user.id} />
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
