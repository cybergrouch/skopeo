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
  getGetApiV1UsersUserIdCapabilitiesQueryKey,
  useDeleteApiV1UsersUserIdCapabilitiesCapability,
  useGetApiV1UsersUserIdCapabilities,
  usePostApiV1UsersUserIdCapabilities,
} from '@/api/generated/capabilities/capabilities'
import type { UserSummaryResponse } from '@/api/generated/model'

// Roles an admin grants here. ADMINISTRATOR is deliberately not toggleable from the UI.
const GRANTABLE = ['HOST', 'CLUB_OWNER'] as const

function UserRoles({ user }: { user: UserSummaryResponse }) {
  const queryClient = useQueryClient()
  const capsQuery = useGetApiV1UsersUserIdCapabilities(user.id)
  const active = new Set(
    (capsQuery.data ?? []).filter((c) => c.isActive).map((c) => c.capability),
  )

  const invalidate = () =>
    queryClient.invalidateQueries({
      queryKey: getGetApiV1UsersUserIdCapabilitiesQueryKey(user.id),
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
                onClick={() => revoke.mutate({ userId: user.id, capability: role })}
              >
                Revoke
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                disabled={busy}
                onClick={() =>
                  grant.mutate({ userId: user.id, data: { capability: role } })
                }
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

export function RoleGrantsSection() {
  const [user, setUser] = useState<UserSummaryResponse | null>(null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Role grants</CardTitle>
        <CardDescription>
          Find a member and grant or revoke the HOST or CLUB_OWNER role.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {user ? (
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">{user.displayName ?? user.id}</span>
              <Button type="button" variant="ghost" size="sm" onClick={() => setUser(null)}>
                Change
              </Button>
            </div>
            <UserRoles user={user} />
          </div>
        ) : (
          <UserSearchSelect label="Member" onSelect={setUser} />
        )}
      </CardContent>
    </Card>
  )
}
