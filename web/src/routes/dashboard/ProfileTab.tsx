import { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/auth/useAuth'
import { MatchHistoryCard } from '@/components/MatchHistoryCard'
import { RatingHistoryCard } from '@/components/RatingHistoryCard'
import type { Capability } from '@/auth/capabilities'
import {
  useGetApiV1UsersUserIdRatingHistory,
  useGetApiV1UsersUserIdRatings,
} from '@/api/generated/ratings/ratings'

interface ProfileTabProps {
  userId: string
  capabilities: readonly Capability[]
  /** Short, shareable player code (e.g. "K7Q2MX") others can search to find this player. */
  publicCode?: string
}

export function ProfileTab({ userId, capabilities, publicCode }: ProfileTabProps) {
  const { user } = useAuth()
  const enabled = Boolean(userId)
  const ratingsQuery = useGetApiV1UsersUserIdRatings(userId, {
    query: { enabled },
  })
  const historyQuery = useGetApiV1UsersUserIdRatingHistory(userId, {
    query: { enabled },
  })

  const [copied, setCopied] = useState(false)
  const shareUrl = publicCode
    ? `${window.location.origin}/players/${publicCode}`
    : ''
  function copyLink() {
    void navigator.clipboard.writeText(shareUrl)
    setCopied(true)
  }

  const ratings = ratingsQuery.data ?? []
  const history = historyQuery.data ?? []
  const hasRating = ratings.length > 0

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-3">
            {user?.photoURL ? (
              <img
                src={user.photoURL}
                alt=""
                referrerPolicy="no-referrer"
                className="h-12 w-12 shrink-0 rounded-full object-cover"
              />
            ) : (
              // No upload support: Google/Facebook supply a photo; everyone else gets initials.
              <div
                aria-hidden="true"
                className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-muted text-lg font-medium text-muted-foreground"
              >
                {(user?.displayName ?? user?.email ?? 'P').charAt(0).toUpperCase()}
              </div>
            )}
            <div className="min-w-0">
              <CardTitle>
                {user?.displayName ?? user?.email ?? 'Player'}
              </CardTitle>
              <CardDescription>{user?.email}</CardDescription>
              {publicCode ? (
                <CardDescription className="mt-1">
                  Player ID:{' '}
                  <code className="select-all font-mono font-medium text-foreground">
                    {publicCode}
                  </code>
                </CardDescription>
              ) : null}
            </div>
          </div>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {capabilities.map((capability) => (
            <Badge key={capability} variant="secondary">
              {capability}
            </Badge>
          ))}
        </CardContent>
      </Card>

      {publicCode ? (
        <Card>
          <CardHeader>
            <CardTitle>Share your profile</CardTitle>
            <CardDescription>
              Anyone signed in can scan this code or open the link to view your
              profile.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col items-center gap-3">
            <QRCodeSVG value={shareUrl} size={144} />
            <Button type="button" variant="outline" size="sm" onClick={copyLink}>
              {copied ? 'Copied!' : 'Copy link'}
            </Button>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Rating</CardTitle>
          <CardDescription>
            {hasRating
              ? 'Your current rating across systems.'
              : 'Awaiting an initial rating.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {ratingsQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : hasRating ? (
            <ul className="space-y-2">
              {ratings.map((rating) => (
                <li
                  key={rating.value}
                  className="flex items-center justify-between rounded-lg border p-3 text-sm"
                >
                  <span className="font-medium">NTRP</span>
                  <span>{rating.level ?? rating.value}</span>
                </li>
              ))}
            </ul>
          ) : (
            <div className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">Pending assessment</p>
              <p className="mt-1">
                An administrator will assign your starting rating. Once that's
                done you'll be eligible to be scheduled in matches.
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      <RatingHistoryCard
        entries={history}
        isLoading={historyQuery.isLoading}
        description="Changes from your rated matches."
      />

      {publicCode ? <MatchHistoryCard code={publicCode} /> : null}
    </div>
  )
}
