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

/** How a finished match reads on the scoreboard. */
const OUTCOME_LABEL: Record<string, string> = {
  COMPLETED: 'Final',
  RETIRED: 'Final — retired',
  DEFAULTED: 'Final — default',
}

function SideScore({
  name,
  points,
  games,
  isWinner,
}: {
  name: string
  points: string
  games: number
  isWinner: boolean
}) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className={isWinner ? 'font-semibold' : undefined}>{name}</span>
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
  const status = finished
    ? (OUTCOME_LABEL[score.outcomeKind as string] ?? 'Final')
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
        />
        <SideScore
          name={team2Name}
          points={score.pointsTeam2}
          games={score.gamesTeam2}
          isWinner={score.outcomeWinner === 'TEAM2'}
        />
      </CardContent>
    </Card>
  )
}
