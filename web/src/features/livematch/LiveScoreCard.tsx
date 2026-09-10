import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { MatchClock } from './MatchClock'
import { useLiveScore } from './useLiveScore'

/**
 * How an abnormal ending reads **next to the player it happened to** (#951).
 *
 * On the player rather than in the badge, because it is a property of a person: a badge saying
 * "Final — retired" tells a spectator that somebody retired and leaves them to work out who by
 * reasoning backwards from the winner. Matches the `1-3 (ret)` convention used on the match page.
 */
const CONCEDED_LABEL: Record<string, string> = {
  RETIRED: '(Retired)',
  DEFAULTED: '(Default)',
}

function SideScore({
  name,
  points,
  games,
  isWinner,
  conceded,
}: {
  name: string
  points: string
  games: number
  isWinner: boolean
  /** `(Retired)` / `(Default)` when this is the side that conceded; absent otherwise. */
  conceded?: string
}) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className={isWinner ? 'font-semibold' : undefined}>
        {name}
        {conceded ? (
          <span className="ml-1 font-normal text-muted-foreground">{conceded}</span>
        ) : null}
      </span>
      <span className="flex items-baseline gap-3 tabular-nums">
        <span className="text-muted-foreground">{games}</span>
        <span className="w-10 text-right text-2xl font-bold">{points}</span>
      </span>
    </div>
  )
}

/**
 * The public spectator scoreboard (#911).
 *
 * **Renders nothing unless a live document exists**, which is the normal state for almost every match —
 * an empty scoreboard on a fixture nobody is scoring would read as a fault. Same reasoning as
 * `MatchPointsCard`.
 *
 * Read-only and push-driven: the score arrives from Firestore, written by the server. Nothing here can
 * write, and the security rules enforce that rather than relying on the client to behave.
 *
 * The scoreline is **rendered by the server** (`40`, `AD`, tiebreak ordinals), so this component never
 * implements deuce — the umpire view and this view cannot disagree about the same match.
 */
export function LiveScoreCard({
  publicCode,
  team1Name,
  team2Name,
}: {
  publicCode: string
  team1Name: string
  team2Name: string
}) {
  const score = useLiveScore(publicCode)
  if (!score) return null

  const finished = score.outcomeKind != null
  // Which side conceded is DERIVED, not sent: a player who retires always loses, so it is whichever
  // side is not the winner. #933 deliberately does not store it, because a stored copy could disagree
  // with the winner and nothing could adjudicate between them.
  const concededLabel = score.outcomeKind
    ? CONCEDED_LABEL[score.outcomeKind]
    : undefined
  const concededSide = concededLabel
    ? score.outcomeWinner === 'TEAM1'
      ? 'TEAM2'
      : 'TEAM1'
    : undefined
  const status = finished
    ? 'Final'
    : score.isPaused
      ? 'Suspended'
      : score.hasStarted
        ? 'Live'
        : 'About to start'

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle>Live score</CardTitle>
          <div className="flex items-center gap-2">
            <MatchClock
              elapsedSeconds={score.elapsedSeconds ?? 0}
              isRunning={score.isRunning ?? false}
              className="text-sm tabular-nums text-muted-foreground"
            />
            {score.isTiebreak && !finished && <Badge variant="secondary">Tiebreak</Badge>}
            <Badge variant={finished ? 'secondary' : 'default'}>{status}</Badge>
          </div>
        </div>
        <CardDescription>
          {finished
            ? 'This match has finished.'
            : 'Updating automatically as the umpire scores.'}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        {score.serverName && !finished ? (
          <p className="text-sm text-muted-foreground">
            <span aria-hidden="true">🎾 </span>
            {score.serverName} to serve
          </p>
        ) : null}
        {score.sets.length > 0 && (
          <p className="text-sm text-muted-foreground tabular-nums">
            {score.sets
              .map((set) => `${set.gamesTeam1}-${set.gamesTeam2}`)
              .join(', ')}
          </p>
        )}
        <SideScore
          name={team1Name}
          points={score.pointsTeam1}
          games={score.gamesTeam1}
          isWinner={score.outcomeWinner === 'TEAM1'}
          conceded={concededSide === 'TEAM1' ? concededLabel : undefined}
        />
        <SideScore
          name={team2Name}
          points={score.pointsTeam2}
          games={score.gamesTeam2}
          isWinner={score.outcomeWinner === 'TEAM2'}
          conceded={concededSide === 'TEAM2' ? concededLabel : undefined}
        />
      </CardContent>
    </Card>
  )
}
