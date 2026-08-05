import type { PlayerMatchHistoryEntry } from '@/api/generated/model'
import { PublicPageLink } from '@/components/PublicPageLink'
import { PlaceholderTag } from '@/components/PlaceholderTag'
import { Badge } from '@/components/ui/badge'
import { formatConfidence } from '@/lib/confidence'

/** An at-the-time band with the player's *current* rating confidence appended (#343), e.g. "3.5 · 40%". */
function bandWithConfidence(level: string | null | undefined, confidence: string | null | undefined): string {
  const band = level ?? '—'
  const pct = formatConfidence(confidence)
  return pct ? `${band} · ${pct}` : band
}

/** How far along the rating pipeline a match is — drives the per-row status badge. */
function statusLabel(match: PlayerMatchHistoryEntry): string {
  if (match.rated) return 'Rated'
  if (match.status === 'SCHEDULED') return 'Scheduled'
  return 'Awaiting rating'
}

/**
 * Comma-separated display names for a side (partners or opponents); "Player" for anyone unnamed. Each
 * placeholder participant (#505) gets an "Unclaimed" tag beside their name, driven off isPlaceholder.
 */
function NameList({ side }: { side: PlayerMatchHistoryEntry['opponents'] }) {
  if (side.length === 0) return <>Player</>
  return (
    <>
      {side.map((p, i) => (
        <span key={p.publicCode ?? i}>
          {p.displayName ?? 'Player'}
          <PlaceholderTag show={p.isPlaceholder} deleted={p.isDeleted} />
          {i < side.length - 1 ? ', ' : ''}
        </span>
      ))}
    </>
  )
}

/**
 * The at-the-time NTRP value to display: the raw rating when the API reveals it (a raw-rating viewer,
 * #583/#654), otherwise the published band. Everyone else only ever receives the band.
 */
function ratingOrBand(
  rating: string | null | undefined,
  level: string | null | undefined,
): string | null | undefined {
  return rating ?? level
}

/**
 * Comma-separated NTRP at match time for a side, each with the participant's *current* rating confidence
 * appended (#343), e.g. "3.5 · 40%, 4.0 · 100%". Shows the raw rating for a raw-rating viewer (#654),
 * else the band (dash for any missing value).
 */
function bands(side: PlayerMatchHistoryEntry['opponents']): string {
  return side
    .map((p) => bandWithConfidence(ratingOrBand(p.ratingAtMatch, p.levelAtMatch), p.confidence))
    .join(', ')
}

/**
 * One match-history row, shared by the profile preview ({@link MatchHistoryCard}) and the full
 * match-history page (#284). Ratings appear only as the published NTRP band at the time of the match,
 * never the precise value, and only for rated matches.
 */
export function MatchHistoryRow({ match }: { match: PlayerMatchHistoryEntry }) {
  const lead = match.opponents[0]
  return (
    <li className="rounded-lg border p-3 text-sm">
      <div className="flex items-center gap-3">
        {lead?.photoUrl ? (
          <img
            src={lead.photoUrl}
            alt=""
            referrerPolicy="no-referrer"
            className="h-9 w-9 shrink-0 rounded-full object-cover"
          />
        ) : (
          <div
            aria-hidden="true"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-sm font-medium text-muted-foreground"
          >
            {(lead?.displayName ?? 'P').charAt(0).toUpperCase()}
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <span className="font-medium">
              {match.partners.length > 0 ? (
                <>
                  with <NameList side={match.partners} /> ·{' '}
                </>
              ) : null}
              vs <NameList side={match.opponents} />
            </span>
            <Badge variant="secondary">{statusLabel(match)}</Badge>
          </div>
          <div className="text-muted-foreground">
            {match.matchDate}
            {match.result ? ` · ${match.result}` : ''}
            {match.setScores.length > 0 ? ` · ${match.setScores.join(' ')}` : ''}
          </div>
          {match.rated ? (
            <div className="mt-1 text-muted-foreground">
              NTRP{' '}
              {bandWithConfidence(
                ratingOrBand(match.playerRatingAtMatch, match.playerLevelAtMatch),
                match.playerConfidence,
              )}{' '}
              vs {bands(match.opponents)} (at the time)
            </div>
          ) : null}
          <PublicPageLink
            to={`/matches/${match.publicCode}`}
            className="mt-1 inline-block text-xs"
          >
            Public page (QR)
          </PublicPageLink>
        </div>
      </div>
    </li>
  )
}
