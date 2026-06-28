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
import { Badge } from '@/components/ui/badge'
import {
  getGetApiV1InvitesQueryKey,
  useDeleteApiV1InvitesId,
  useGetApiV1Invites,
  usePostApiV1Invites,
} from '@/api/generated/invites/invites'
import type { InviteResponse } from '@/api/generated/model'
import { useAuth } from '@/auth/useAuth'

const PAGE_SIZE = 20

// ACCEPTED & REVOKED invites persist indefinitely, so the list defaults to actionable invites
// (stored PENDING, which also covers derived-EXPIRED) and lets admins switch to the rest (#85).
const FILTERS = [
  { value: 'PENDING', label: 'Actionable' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REVOKED', label: 'Revoked' },
  { value: 'ALL', label: 'All' },
] as const

type InviteFilter = (typeof FILTERS)[number]['value']

// The API rejects an email already attached to an active account with 409 (#132). Surface that
// specific case so the admin knows why the invite was blocked, rather than the generic failure.
function existingAccountMessage(err: unknown): string | null {
  const status = (err as { response?: { status?: number } })?.response?.status
  return status === 409 ? 'An account already exists with this email.' : null
}

const BADGE_VARIANT: Record<string, 'default' | 'secondary' | 'outline'> = {
  PENDING: 'default',
  ACCEPTED: 'secondary',
  REVOKED: 'outline',
  EXPIRED: 'outline',
}

function InviteRow({
  invite,
  onResend,
  resending,
}: {
  invite: InviteResponse
  onResend: (email: string) => void
  resending: boolean
}) {
  const queryClient = useQueryClient()
  const revoke = useDeleteApiV1InvitesId({
    mutation: {
      onSuccess: () =>
        queryClient.invalidateQueries({ queryKey: getGetApiV1InvitesQueryKey() }),
    },
  })

  return (
    <li className="flex items-center justify-between gap-2 rounded-lg border p-3 text-sm">
      <div className="min-w-0">
        <div className="truncate font-medium">{invite.email}</div>
        <div className="text-xs text-muted-foreground">
          expires {invite.expiresAt.slice(0, 10)}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Badge variant={BADGE_VARIANT[invite.status]}>{invite.status}</Badge>
        {invite.status === 'PENDING' || invite.status === 'EXPIRED' ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={resending}
            onClick={() => onResend(invite.email)}
          >
            Resend
          </Button>
        ) : null}
        {invite.status === 'PENDING' ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={revoke.isPending}
            onClick={() => revoke.mutate({ id: invite.id })}
          >
            Revoke
          </Button>
        ) : null}
      </div>
    </li>
  )
}

export function InvitesSection() {
  const queryClient = useQueryClient()
  const { sendSignInLink } = useAuth()
  const [email, setEmail] = useState('')
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState<InviteFilter>('PENDING')
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  const create = usePostApiV1Invites()
  const invitesQuery = useGetApiV1Invites({
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
    status: filter === 'ALL' ? undefined : filter,
  })
  const items = invitesQuery.data?.items ?? []
  const total = invitesQuery.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))

  function changeFilter(next: InviteFilter) {
    setFilter(next)
    setPage(0)
  }

  async function invite(address: string) {
    // Record the invite, then send the Firebase email-link. The record is what the provisioning
    // gate checks; the link is how the invitee actually onboards.
    await create.mutateAsync({ data: { email: address } })
    await sendSignInLink(address)
    await queryClient.invalidateQueries({ queryKey: getGetApiV1InvitesQueryKey() })
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSending(true)
    try {
      await invite(email.trim())
      setEmail('')
    } catch (err) {
      setError(existingAccountMessage(err) ?? 'Could not send the invite. Check the email and try again.')
    } finally {
      setSending(false)
    }
  }

  async function onResend(address: string) {
    setError(null)
    setSending(true)
    try {
      await invite(address)
    } catch (err) {
      setError(existingAccountMessage(err) ?? 'Could not resend the invite. Try again.')
    } finally {
      setSending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Invites</CardTitle>
        <CardDescription>
          Invite a new member by email. They receive a one-time sign-in link to
          finish onboarding; manual sign-ups are invite-only.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-2">
          <div className="flex-1 space-y-1">
            <Label htmlFor="invite-email" className="text-xs">
              Email
            </Label>
            <Input
              id="invite-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="player@example.com"
            />
          </div>
          <Button type="submit" size="sm" disabled={sending}>
            {sending ? 'Sending…' : 'Send invite'}
          </Button>
        </form>
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}

        <div className="flex flex-wrap gap-1" role="group" aria-label="Filter invites by status">
          {FILTERS.map((f) => (
            <Button
              key={f.value}
              type="button"
              size="sm"
              variant={filter === f.value ? 'default' : 'outline'}
              aria-pressed={filter === f.value}
              onClick={() => changeFilter(f.value)}
            >
              {f.label}
            </Button>
          ))}
        </div>

        {invitesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : items.length > 0 ? (
          <>
            <ul className="space-y-2">
              {items.map((invite) => (
                <InviteRow
                  key={invite.id}
                  invite={invite}
                  onResend={onResend}
                  resending={sending}
                />
              ))}
            </ul>
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
          <p className="text-sm text-muted-foreground">
            {filter === 'ALL' ? 'No invites yet.' : 'No invites match this filter.'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
